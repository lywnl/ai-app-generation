package com.lyw.appgeneration.core.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/** 把原始字符块组装成完整日志行，并附加后端生成的可信上下文。 */
public final class BuildLogSink implements Consumer<String>, AutoCloseable {

    private static final int MAX_PART_CHARS = 1_024;
    private static final Logger RAW_LOG = LoggerFactory.getLogger("vue-build-raw");
    private static final Logger LOG = LoggerFactory.getLogger(BuildLogSink.class);

    private final long appId;
    private final String turnId;
    private final int attempt;
    private final BuildStage stage;
    private final Consumer<String> eventConsumer;
    private final Consumer<RuntimeException> warningConsumer;
    private final StringBuilder pending = new StringBuilder();
    private boolean failed;
    private boolean closed;
    private boolean lineStarted;
    private boolean pendingCarriageReturn;
    private char pendingHighSurrogate;
    private long lineId = 1;
    private int part = 1;

    public BuildLogSink(long appId, String turnId, int attempt, BuildStage stage) {
        this(appId, turnId, attempt, stage, RAW_LOG::info,
                ignored -> LOG.warn("Vue 原始构建日志写入失败，已忽略该日志异常"));
    }

    BuildLogSink(
            long appId,
            String turnId,
            int attempt,
            BuildStage stage,
            Consumer<String> eventConsumer,
            Consumer<RuntimeException> warningConsumer) {
        if (appId < 0) {
            throw new IllegalArgumentException("appId 不能为负数");
        }
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt 必须大于 0");
        }
        this.appId = appId;
        this.turnId = boundedField(Objects.requireNonNull(turnId, "turnId 不能为空"));
        this.attempt = attempt;
        this.stage = Objects.requireNonNull(stage, "stage 不能为空");
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer 不能为空");
        this.warningConsumer = Objects.requireNonNull(
                warningConsumer, "warningConsumer 不能为空");
    }

    /** 派生共享日志后端、但拥有独立组行状态的构建阶段 sink。 */
    public BuildLogSink forStage(BuildStage nextStage) {
        return new BuildLogSink(
                appId, turnId, attempt, nextStage, eventConsumer, warningConsumer);
    }

    @Override
    public synchronized void accept(String chunk) {
        if (closed || chunk == null || chunk.isEmpty()) {
            return;
        }
        int index = 0;
        if (pendingHighSurrogate != 0) {
            char first = chunk.charAt(0);
            if (Character.isLowSurrogate(first)) {
                appendCodePoint(Character.toCodePoint(pendingHighSurrogate, first));
                index = 1;
            } else {
                appendCodePoint(pendingHighSurrogate);
            }
            pendingHighSurrogate = 0;
        }
        while (index < chunk.length()) {
            char current = chunk.charAt(index++);
            if (Character.isHighSurrogate(current) && index == chunk.length()) {
                pendingHighSurrogate = current;
                break;
            }
            if (Character.isHighSurrogate(current)
                    && Character.isLowSurrogate(chunk.charAt(index))) {
                appendCodePoint(Character.toCodePoint(current, chunk.charAt(index++)));
            } else {
                appendCodePoint(current);
            }
        }
    }

    private void appendCodePoint(int codePoint) {
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            emitLineEnd();
            if (codePoint == '\n') {
                return;
            }
        }
        if (codePoint == '\r') {
            pendingCarriageReturn = true;
            return;
        }
        if (codePoint == '\n') {
            emitLineEnd();
            return;
        }
        pending.appendCodePoint(codePoint);
        lineStarted = true;
        emitFullParts();
    }

    private void emitFullParts() {
        while (pending.length() >= MAX_PART_CHARS) {
            int end = MAX_PART_CHARS;
            if (end < pending.length() && Character.isHighSurrogate(pending.charAt(end - 1))
                    && Character.isLowSurrogate(pending.charAt(end))) {
                end--;
            }
            String value = pending.substring(0, end);
            pending.delete(0, end);
            emit(value, false);
            part++;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            emitLineEnd();
        }
        if (pendingHighSurrogate != 0) {
            pending.append(pendingHighSurrogate);
            pendingHighSurrogate = 0;
            lineStarted = true;
        }
        if (!pending.isEmpty() || lineStarted) {
            emitPending(true);
            lineStarted = false;
        }
    }

    private void emitLineEnd() {
        emitPending(true);
        lineStarted = false;
        nextLine();
    }

    private void emitPending(boolean end) {
        String value = pending.toString();
        pending.setLength(0);
        emit(value, end);
    }

    private void nextLine() {
        lineId++;
        part = 1;
    }

    private void emit(String value, boolean end) {
        if (failed) {
            return;
        }
        try {
            eventConsumer.accept("appId=" + appId + ",turnId=" + turnId
                    + ",attempt=" + attempt + ",stage=" + stage
                    + " | lineId=" + String.format("%06d", lineId)
                    + ",part=" + String.format("%06d", part)
                    + ",end=" + end + ",continued=" + !end
                    + " | " + escape(value));
        } catch (RuntimeException exception) {
            failed = true;
            try {
                warningConsumer.accept(exception);
            } catch (RuntimeException ignored) {
                // 日志后端异常不得影响构建结果。
            }
        }
    }

    private static String boundedField(String value) {
        String escaped = escape(value);
        if (escaped.length() <= 256) {
            return escaped;
        }
        int end = 256;
        if (Character.isHighSurrogate(escaped.charAt(end - 1))
                && Character.isLowSurrogate(escaped.charAt(end))) {
            end--;
        }
        return escaped.substring(0, end);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    int type = Character.getType(codePoint);
                    if (Character.isISOControl(codePoint)
                            || type == Character.FORMAT
                            || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR) {
                        escaped.append(String.format("\\u%04X", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.toString();
    }
}
