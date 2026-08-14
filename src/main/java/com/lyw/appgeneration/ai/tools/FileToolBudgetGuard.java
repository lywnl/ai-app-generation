package com.lyw.appgeneration.ai.tools;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Vue 文件工具与稳定正文的单回合硬预算配置。 */
@Component
@ConfigurationProperties(prefix = "ai.vue.tool-budget")
public final class FileToolBudgetGuard implements InitializingBean {

    private int maxSingleFileCodePoints = 128_000;
    private int maxCumulativeMutationCodePoints = 256_000;
    private int maxCanonicalAiTextCodePoints = 384_000;
    private int maxReadFileCodePoints = 128_000;
    private int maxReadDirCodePoints = 20_000;

    public Session newSession() {
        afterPropertiesSet();
        return new Session();
    }

    public static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** 按 Unicode 码点裁剪，结果不会以孤立代理项开头或结尾。 */
    public static String prefixByCodePoints(String value, int maximum) {
        if (value == null || value.isEmpty() || maximum <= 0) {
            return "";
        }
        int requested = Math.min(maximum, codePointCount(value));
        int end = value.offsetByCodePoints(0, requested);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && Character.isLowSurrogate(value.charAt(0))) {
            return "";
        }
        return value.substring(0, end);
    }

    @Override
    public void afterPropertiesSet() {
        if (maxSingleFileCodePoints <= 0
                || maxCumulativeMutationCodePoints <= 0
                || maxCanonicalAiTextCodePoints <= 0
                || maxReadFileCodePoints <= 0
                || maxReadDirCodePoints <= 0) {
            throw new IllegalStateException("Vue 文件工具体积预算必须全部大于 0");
        }
        if (maxSingleFileCodePoints > maxCumulativeMutationCodePoints
                || maxCumulativeMutationCodePoints > maxCanonicalAiTextCodePoints) {
            throw new IllegalStateException("Vue 文件工具体积预算层级必须满足 single <= cumulative <= canonical");
        }
    }

    /** 验证稳定正文上限能够容纳固定终态，并至少保留一个正文码点。 */
    public void validateCanonicalReserve(int reservedCodePoints) {
        if (reservedCodePoints < 0
                || maxCanonicalAiTextCodePoints <= reservedCodePoints) {
            throw new IllegalStateException("稳定正文上限必须大于固定终态预留空间");
        }
    }

    public int getMaxSingleFileCodePoints() {
        return maxSingleFileCodePoints;
    }

    public void setMaxSingleFileCodePoints(int value) {
        maxSingleFileCodePoints = value;
    }

    public int getMaxCumulativeMutationCodePoints() {
        return maxCumulativeMutationCodePoints;
    }

    public void setMaxCumulativeMutationCodePoints(int value) {
        maxCumulativeMutationCodePoints = value;
    }

    public int getMaxCanonicalAiTextCodePoints() {
        return maxCanonicalAiTextCodePoints;
    }

    public void setMaxCanonicalAiTextCodePoints(int value) {
        maxCanonicalAiTextCodePoints = value;
    }

    public int getMaxReadFileCodePoints() {
        return maxReadFileCodePoints;
    }

    public void setMaxReadFileCodePoints(int value) {
        maxReadFileCodePoints = value;
    }

    public int getMaxReadDirCodePoints() {
        return maxReadDirCodePoints;
    }

    public void setMaxReadDirCodePoints(int value) {
        maxReadDirCodePoints = value;
    }

    /** 每次生成或评测独占，禁止跨回合复用。 */
    public final class Session {

        private final AtomicInteger acceptedMutationCodePoints = new AtomicInteger();
        private final Map<String, ArgumentBudget> streamedByArgument = new HashMap<>();
        private int streamedMutationCodePoints;
        private final AtomicBoolean resourceLimitClaimed = new AtomicBoolean();

        public MutationReservation reserveMutation(
                String finalFileContent, String requestedMutationContent) {
            int finalSize = codePointCount(finalFileContent);
            int mutationSize = codePointCount(requestedMutationContent);
            if (finalSize > maxSingleFileCodePoints
                    || mutationSize > maxCumulativeMutationCodePoints) {
                return MutationReservation.rejected();
            }
            while (true) {
                int current = acceptedMutationCodePoints.get();
                if (mutationSize > maxCumulativeMutationCodePoints - current) {
                    return MutationReservation.rejected();
                }
                if (acceptedMutationCodePoints.compareAndSet(
                        current, current + mutationSize)) {
                    return new MutationReservation(
                            acceptedMutationCodePoints, mutationSize);
                }
            }
        }

        public ReadDecision validateReadFile(String content) {
            return new ReadDecision(
                    codePointCount(content) <= maxReadFileCodePoints, content);
        }

        public ReadDecision validateReadDir(String content) {
            return new ReadDecision(
                    codePointCount(content) <= maxReadDirCodePoints, content);
        }

        public ReadAccumulator newReadFileAccumulator() {
            return new ReadAccumulator(maxReadFileCodePoints);
        }

        public ReadAccumulator newReadDirAccumulator() {
            return new ReadAccumulator(maxReadDirCodePoints);
        }

        public ReadAccumulator newSingleFileAccumulator() {
            return new ReadAccumulator(maxSingleFileCodePoints);
        }

        public synchronized ArgumentDecision acceptArgumentDelta(
                String toolCallId, String key, String delta) {
            Objects.requireNonNull(toolCallId, "toolCallId 不能为空");
            Objects.requireNonNull(key, "参数名不能为空");
            String value = delta == null ? "" : delta;
            String argumentId = toolCallId + '\u0000' + key;
            ArgumentBudget argument = streamedByArgument.computeIfAbsent(
                    argumentId, ignored -> new ArgumentBudget());
            NormalizedChunk normalized = normalizeChunk(
                    argument.pendingHighSurrogate, value);
            int allowed = Math.min(
                    maxSingleFileCodePoints - argument.codePoints,
                    maxCumulativeMutationCodePoints - streamedMutationCodePoints);
            String acceptedPrefix = prefixByCodePoints(
                    normalized.safeText(), Math.max(0, allowed));
            int accepted = codePointCount(acceptedPrefix);
            if (accepted > 0) {
                argument.codePoints += accepted;
                streamedMutationCodePoints += accepted;
            }
            boolean exceeded = accepted < codePointCount(normalized.safeText());
            argument.pendingHighSurrogate = exceeded
                    ? 0 : normalized.pendingHighSurrogate();
            return new ArgumentDecision(
                    acceptedPrefix, exceeded);
        }

        public CanonicalAccumulator newCanonicalAccumulator() {
            return new CanonicalAccumulator(maxCanonicalAiTextCodePoints);
        }

        public CanonicalAccumulator newCanonicalAccumulator(int reservedCodePoints) {
            validateCanonicalReserve(reservedCodePoints);
            return new CanonicalAccumulator(
                    maxCanonicalAiTextCodePoints - reservedCodePoints);
        }

        public boolean claimResourceLimit() {
            return resourceLimitClaimed.compareAndSet(false, true);
        }
    }

    public static final class MutationReservation implements AutoCloseable {

        private final AtomicInteger counter;
        private final int reserved;
        private final boolean accepted;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean committed = new AtomicBoolean();

        private MutationReservation(AtomicInteger counter, int reserved) {
            this.counter = counter;
            this.reserved = reserved;
            this.accepted = true;
        }

        private MutationReservation() {
            this.counter = null;
            this.reserved = 0;
            this.accepted = false;
            closed.set(true);
        }

        private static MutationReservation rejected() {
            return new MutationReservation();
        }

        public boolean accepted() {
            return accepted;
        }

        public void commit() {
            if (!accepted || closed.get()) {
                if (!accepted) {
                    return;
                }
                throw new IllegalStateException("体积预算预留已经关闭");
            }
            committed.set(true);
        }

        @Override
        public void close() {
            if (accepted && closed.compareAndSet(false, true)
                    && !committed.get()) {
                counter.addAndGet(-reserved);
            }
        }
    }

    public record ReadDecision(boolean accepted, String acceptedText) {
    }

    /** 对分块读取增量计数，避免反复复制已经读取的完整正文。 */
    public static final class ReadAccumulator {

        private final int maximum;
        private int codePoints;
        private char pendingHighSurrogate;
        private boolean finished;

        private ReadAccumulator(int maximum) {
            this.maximum = maximum;
        }

        public synchronized ReadDecision accept(CharSequence value) {
            if (finished) {
                throw new IllegalStateException("读取预算已经结束");
            }
            String text = value == null ? "" : value.toString();
            NormalizedChunk normalized = normalizeChunk(
                    pendingHighSurrogate, text);
            int incoming = codePointCount(normalized.safeText());
            if (incoming > maximum - codePoints) {
                pendingHighSurrogate = 0;
                return new ReadDecision(false, null);
            }
            codePoints += incoming;
            pendingHighSurrogate = normalized.pendingHighSurrogate();
            return new ReadDecision(true, normalized.safeText());
        }

        /** 结算跨块遗留的高代理项，确保不会漏计或返回非法 UTF-16。 */
        public synchronized ReadDecision finish() {
            if (finished) {
                throw new IllegalStateException("读取预算已经结束");
            }
            finished = true;
            if (pendingHighSurrogate == 0) {
                return new ReadDecision(true, "");
            }
            pendingHighSurrogate = 0;
            if (codePoints >= maximum) {
                return new ReadDecision(false, null);
            }
            codePoints++;
            return new ReadDecision(true, "\uFFFD");
        }
    }

    public record ArgumentDecision(
            String acceptedPrefix, boolean resourceLimitExceeded) {
    }

    public static final class CanonicalAccumulator {

        private final int maximum;
        private final StringBuilder content = new StringBuilder();
        private int codePoints;
        private char pendingHighSurrogate;

        private CanonicalAccumulator(int maximum) {
            this.maximum = maximum;
        }

        public synchronized AppendDecision append(String value) {
            String text = value == null ? "" : value;
            NormalizedChunk normalized = normalizeChunk(
                    pendingHighSurrogate, text);
            int remaining = maximum - codePoints;
            String acceptedPrefix = prefixByCodePoints(
                    normalized.safeText(), remaining);
            int accepted = codePointCount(acceptedPrefix);
            if (accepted > 0) {
                content.append(acceptedPrefix);
                codePoints += accepted;
            }
            boolean exceeded = accepted < codePointCount(normalized.safeText());
            pendingHighSurrogate = exceeded
                    ? 0 : normalized.pendingHighSurrogate();
            return new AppendDecision(!exceeded, acceptedPrefix, exceeded);
        }

        public synchronized String content() {
            return content.toString();
        }
    }

    public record AppendDecision(
            boolean accepted, String acceptedPrefix,
            boolean resourceLimitExceeded) {
    }

    private static NormalizedChunk normalizeChunk(
            char pendingHighSurrogate, String value) {
        StringBuilder safe = new StringBuilder(value.length() + 1);
        int index = 0;
        if (pendingHighSurrogate != 0) {
            if (!value.isEmpty() && Character.isLowSurrogate(value.charAt(0))) {
                safe.append(pendingHighSurrogate).append(value.charAt(0));
                index = 1;
            } else {
                safe.append('\uFFFD');
            }
        }
        char trailingHigh = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()) {
                    trailingHigh = current;
                    break;
                }
                char next = value.charAt(index + 1);
                if (Character.isLowSurrogate(next)) {
                    safe.append(current).append(next);
                    index += 2;
                    continue;
                }
                safe.append('\uFFFD');
            } else if (Character.isLowSurrogate(current)) {
                safe.append('\uFFFD');
            } else {
                safe.append(current);
            }
            index++;
        }
        return new NormalizedChunk(safe.toString(), trailingHigh);
    }

    private static final class ArgumentBudget {

        private int codePoints;
        private char pendingHighSurrogate;
    }

    private record NormalizedChunk(String safeText, char pendingHighSurrogate) {
    }
}
