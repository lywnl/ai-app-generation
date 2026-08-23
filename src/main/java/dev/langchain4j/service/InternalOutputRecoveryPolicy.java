package dev.langchain4j.service;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 内部输出恢复的不可变规则，不保存任何恢复代次或请求状态。 */
public final class InternalOutputRecoveryPolicy {

    public enum Mode {
        RECOVER_ONCE, FAIL_FAST
    }

    private final Mode mode;
    private final String reservedPrefix;
    private final Set<String> exactMarkers;

    public InternalOutputRecoveryPolicy(Mode mode, String reservedPrefix, Set<String> exactMarkers) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.reservedPrefix = validateReservedPrefix(reservedPrefix);
        this.exactMarkers = copyMarkers(exactMarkers);
    }

    public Mode mode() {
        return mode;
    }

    public String reservedPrefix() {
        return reservedPrefix;
    }

    public Set<String> exactMarkers() {
        return exactMarkers;
    }

    public InternalOutputLeakDetector newLeakDetector() {
        return new InternalOutputLeakDetector(reservedPrefix, exactMarkers);
    }

    public ToolArgumentLeakScanner newToolArgumentLeakScanner() {
        return new ToolArgumentLeakScanner(reservedPrefix, exactMarkers);
    }

    static String validateReservedPrefix(String reservedPrefix) {
        if (reservedPrefix == null || reservedPrefix.isBlank()) {
            throw new IllegalArgumentException("reservedPrefix 不能为空白");
        }
        return reservedPrefix;
    }

    static Set<String> copyMarkers(Set<String> exactMarkers) {
        if (exactMarkers == null) {
            throw new IllegalArgumentException("exactMarkers 不能为 null");
        }
        Set<String> copied = new LinkedHashSet<>(exactMarkers);
        if (copied.stream().anyMatch(marker -> marker == null || marker.isBlank())) {
            throw new IllegalArgumentException("exactMarkers 不能包含空白标记");
        }
        return Set.copyOf(copied);
    }
}
