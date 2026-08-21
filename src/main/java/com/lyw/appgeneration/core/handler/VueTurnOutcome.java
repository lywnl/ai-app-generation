package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.core.builder.VueBuildPhase;

import java.util.Objects;

/** 一次 Vue 在线生成回合的规范终态。 */
public record VueTurnOutcome(
        VueBuildPhase phase,
        TurnOutcomeType outcome,
        String displayAiText,
        String memoryAiText,
        boolean shouldRefreshPreview,
        String clientMessage) {

    public VueTurnOutcome {
        Objects.requireNonNull(phase, "phase 不能为空");
        Objects.requireNonNull(outcome, "outcome 不能为空");
        displayAiText = requireText(displayAiText, "displayAiText");
        memoryAiText = requireText(memoryAiText, "memoryAiText");
        clientMessage = requireText(clientMessage, "clientMessage");
        if (shouldRefreshPreview && outcome != TurnOutcomeType.SUCCEEDED) {
            throw new IllegalArgumentException("只有成功终态可以刷新预览");
        }
    }

    private static String requireText(String text, String field) {
        Objects.requireNonNull(text, field + " 不能为空");
        if (text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return text;
    }

    public enum TurnOutcomeType {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        SYSTEM_ERROR,
        PROTOCOL_ERROR,
        INCOMPLETE_TOOL_CHAIN
    }
}
