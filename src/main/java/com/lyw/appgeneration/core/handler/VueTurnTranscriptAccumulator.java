package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.memory.SyntheticMemoryMessageProtocol;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按来源维护 Vue 单回合有序转录，并支持只回滚指定代次的 AI 正文。 */
public final class VueTurnTranscriptAccumulator {

    private final FileToolBudgetGuard.Session budgetSession;
    private final int terminalReserveCodePoints;
    private final List<Fragment> fragments = new ArrayList<>();
    private FileToolBudgetGuard.CanonicalAccumulator displayBudget;
    private PendingHighSurrogate pendingHighSurrogate;

    public VueTurnTranscriptAccumulator(
            FileToolBudgetGuard.Session budgetSession,
            int terminalReserveCodePoints) {
        this.budgetSession = Objects.requireNonNull(
                budgetSession, "Vue 转录预算会话不能为空");
        if (terminalReserveCodePoints < 0) {
            throw new IllegalArgumentException("终态预留码点不能为负数");
        }
        this.terminalReserveCodePoints = terminalReserveCodePoints;
        this.displayBudget = newDisplayBudget();
    }

    public synchronized AppendDecision appendAiText(
            long generation, String text) {
        validateGeneration(generation);
        return append(
                FragmentSource.AI_TEXT, generation, null, text);
    }

    public synchronized AppendDecision appendTrustedToolDisplay(
            long generation, String toolRequestId, String text) {
        validateGeneration(generation);
        if (toolRequestId == null || toolRequestId.isBlank()) {
            throw new IllegalArgumentException("可信工具展示必须携带请求 ID");
        }
        return append(
                FragmentSource.TRUSTED_TOOL_DISPLAY,
                generation, toolRequestId, text);
    }

    public synchronized RollbackDecision rollbackAiText(
            long generation, int codePoints) {
        validateGeneration(generation);
        if (codePoints < 0) {
            throw new IllegalArgumentException("回滚码点不能为负数");
        }
        int available = fragments.stream()
                .filter(fragment -> fragment.source() == FragmentSource.AI_TEXT)
                .filter(fragment -> fragment.generation() == generation)
                .mapToInt(fragment -> FileToolBudgetGuard.codePointCount(
                        fragment.text()))
                .sum();
        if (codePoints > available) {
            throw new IllegalArgumentException(
                    "回滚码点超过指定 generation 的已接收正文");
        }
        int remaining = codePoints;
        for (int index = fragments.size() - 1;
                index >= 0 && remaining > 0; index--) {
            Fragment fragment = fragments.get(index);
            if (fragment.source() != FragmentSource.AI_TEXT
                    || fragment.generation() != generation) {
                continue;
            }
            int fragmentCodePoints = FileToolBudgetGuard.codePointCount(
                    fragment.text());
            if (fragmentCodePoints <= remaining) {
                fragments.remove(index);
                remaining -= fragmentCodePoints;
                continue;
            }
            int keptCodePoints = fragmentCodePoints - remaining;
            String kept = FileToolBudgetGuard.prefixByCodePoints(
                    fragment.text(), keptCodePoints);
            fragments.set(index, new Fragment(
                    fragment.source(), fragment.generation(),
                    fragment.toolRequestId(), kept));
            remaining = 0;
        }
        if (pendingHighSurrogate != null
                && pendingHighSurrogate.source() == FragmentSource.AI_TEXT
                && pendingHighSurrogate.generation() == generation) {
            pendingHighSurrogate = null;
        }
        rebuildDisplayBudget();
        return new RollbackDecision(codePoints, snapshot());
    }

    public synchronized String displayText() {
        return joinFragments(false);
    }

    public synchronized String answerMemoryText() {
        return joinFragments(true);
    }

    /** 按 generation 拼接仍保留的 AI 正文，工具展示既不参与也不分隔扫描。 */
    public synchronized boolean containsReservedMarkerInAiText() {
        Map<Long, StringBuilder> aiTextByGeneration = new LinkedHashMap<>();
        for (Fragment fragment : fragments) {
            if (fragment.source() != FragmentSource.AI_TEXT) {
                continue;
            }
            aiTextByGeneration.computeIfAbsent(
                            fragment.generation(), ignored -> new StringBuilder())
                    .append(fragment.text());
        }
        return aiTextByGeneration.values().stream()
                .map(StringBuilder::toString)
                .anyMatch(SyntheticMemoryMessageProtocol
                        ::containsReservedMarker);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                List.copyOf(fragments),
                displayText(), answerMemoryText());
    }

    private AppendDecision append(
            FragmentSource source,
            long generation,
            String toolRequestId,
            String text) {
        List<NormalizedSegment> segments = normalizeSegments(
                source, generation, toolRequestId,
                text == null ? "" : text);
        StringBuilder accepted = new StringBuilder();
        boolean exceeded = false;
        for (NormalizedSegment segment : segments) {
            FileToolBudgetGuard.AppendDecision decision =
                    displayBudget.append(segment.text());
            if (!decision.acceptedPrefix().isEmpty()) {
                fragments.add(new Fragment(
                        segment.source(), segment.generation(),
                        segment.toolRequestId(), decision.acceptedPrefix()));
                accepted.append(decision.acceptedPrefix());
            }
            if (decision.resourceLimitExceeded()) {
                exceeded = true;
                pendingHighSurrogate = null;
                break;
            }
        }
        return new AppendDecision(
                !exceeded,
                accepted.toString(),
                exceeded,
                snapshot());
    }

    private List<NormalizedSegment> normalizeSegments(
            FragmentSource source,
            long generation,
            String toolRequestId,
            String text) {
        List<NormalizedSegment> segments = new ArrayList<>(2);
        int index = 0;
        if (pendingHighSurrogate != null) {
            PendingHighSurrogate pending = pendingHighSurrogate;
            pendingHighSurrogate = null;
            boolean sameSource = pending.source() == source
                    && pending.generation() == generation
                    && Objects.equals(
                    pending.toolRequestId(), toolRequestId);
            if (sameSource && !text.isEmpty()
                    && Character.isLowSurrogate(text.charAt(0))) {
                segments.add(new NormalizedSegment(
                        pending.source(), pending.generation(),
                        pending.toolRequestId(),
                        new String(new char[]{
                                pending.value(), text.charAt(0)})));
                index = 1;
            } else {
                segments.add(new NormalizedSegment(
                        pending.source(), pending.generation(),
                        pending.toolRequestId(), "\uFFFD"));
            }
        }
        StringBuilder safe = new StringBuilder(text.length() - index);
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length()) {
                    pendingHighSurrogate = new PendingHighSurrogate(
                            source, generation, toolRequestId, current);
                    break;
                }
                char next = text.charAt(index + 1);
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
        if (!safe.isEmpty()) {
            segments.add(new NormalizedSegment(
                    source, generation, toolRequestId, safe.toString()));
        }
        return segments;
    }

    private String joinFragments(boolean aiOnly) {
        StringBuilder joined = new StringBuilder();
        for (Fragment fragment : fragments) {
            if (!aiOnly || fragment.source() == FragmentSource.AI_TEXT) {
                joined.append(fragment.text());
            }
        }
        return joined.toString();
    }

    private void rebuildDisplayBudget() {
        FileToolBudgetGuard.CanonicalAccumulator rebuilt =
                newDisplayBudget();
        for (Fragment fragment : fragments) {
            FileToolBudgetGuard.AppendDecision decision =
                    rebuilt.append(fragment.text());
            if (!decision.accepted()
                    || !decision.acceptedPrefix().equals(fragment.text())) {
                throw new IllegalStateException(
                        "回滚后重建 Vue 转录预算失败");
            }
        }
        displayBudget = rebuilt;
    }

    private FileToolBudgetGuard.CanonicalAccumulator newDisplayBudget() {
        return terminalReserveCodePoints == 0
                ? budgetSession.newCanonicalAccumulator()
                : budgetSession.newCanonicalAccumulator(
                        terminalReserveCodePoints);
    }

    private void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation 必须大于 0");
        }
    }

    public enum FragmentSource {
        AI_TEXT,
        TRUSTED_TOOL_DISPLAY
    }

    public record Fragment(
            FragmentSource source,
            long generation,
            String toolRequestId,
            String text) {

        public Fragment {
            Objects.requireNonNull(source, "转录片段来源不能为空");
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation 必须大于 0");
            }
            Objects.requireNonNull(text, "转录片段正文不能为空");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("转录片段正文不能为空字符串");
            }
            if (source == FragmentSource.TRUSTED_TOOL_DISPLAY
                    && (toolRequestId == null || toolRequestId.isBlank())) {
                throw new IllegalArgumentException(
                        "可信工具展示必须携带请求 ID");
            }
            if (source == FragmentSource.AI_TEXT
                    && toolRequestId != null) {
                throw new IllegalArgumentException(
                        "AI 正文不能携带工具请求 ID");
            }
        }
    }

    public record Snapshot(
            List<Fragment> fragments,
            String displayText,
            String answerMemoryText) {

        public Snapshot {
            fragments = List.copyOf(fragments);
            Objects.requireNonNull(displayText, "展示正文不能为空");
            Objects.requireNonNull(answerMemoryText, "回答记忆不能为空");
        }
    }

    public record AppendDecision(
            boolean accepted,
            String acceptedPrefix,
            boolean resourceLimitExceeded,
            Snapshot snapshot) {

        public AppendDecision {
            Objects.requireNonNull(acceptedPrefix, "已接收前缀不能为空");
            Objects.requireNonNull(snapshot, "转录快照不能为空");
        }
    }

    public record RollbackDecision(
            int removedCodePoints,
            Snapshot snapshot) {

        public RollbackDecision {
            if (removedCodePoints < 0) {
                throw new IllegalArgumentException("已回滚码点不能为负数");
            }
            Objects.requireNonNull(snapshot, "转录快照不能为空");
        }
    }

    private record PendingHighSurrogate(
            FragmentSource source,
            long generation,
            String toolRequestId,
            char value) {
    }

    private record NormalizedSegment(
            FragmentSource source,
            long generation,
            String toolRequestId,
            String text) {
    }
}
