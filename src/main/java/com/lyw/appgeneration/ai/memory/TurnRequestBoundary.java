package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Objects;

/** 当前模型回合的不可变原始用户请求边界。 */
public record TurnRequestBoundary(
        String rawUserText,
        String canonicalUserName,
        List<ChatMessage> transientMessages) {

    public static final int MAX_RAW_USER_TEXT_LENGTH = 6 * 1024;

    public TurnRequestBoundary {
        if (rawUserText == null || rawUserText.isBlank()) {
            throw new IllegalArgumentException("用户原始需求不能为空");
        }
        if (rawUserText.length() > MAX_RAW_USER_TEXT_LENGTH) {
            throw new IllegalArgumentException("用户原始需求超过 6144 个字符");
        }
        if (containsUnsafeControl(rawUserText)) {
            throw new IllegalArgumentException("用户原始需求包含不安全控制字符");
        }
        canonicalUserName = Objects.requireNonNull(
                canonicalUserName, "用户请求 canonical identity 不能为空");
        if (canonicalUserName.isBlank()) {
            throw new IllegalArgumentException(
                    "用户请求 canonical identity 不能为空");
        }
        transientMessages = List.copyOf(transientMessages == null
                ? List.of() : transientMessages);
    }

    public static TurnRequestBoundary of(
            String rawUserText, List<ChatMessage> transientMessages) {
        validateRawUserText(rawUserText);
        return new TurnRequestBoundary(
                rawUserText,
                TokenAwareChatMemory.canonicalUserName(rawUserText),
                transientMessages);
    }

    public UserMessage userMessage() {
        return UserMessage.from(canonicalUserName, rawUserText);
    }

    private static boolean containsUnsafeControl(String value) {
        return value.codePoints().anyMatch(codePoint ->
                codePoint >= 0 && codePoint <= 0x08
                        || codePoint >= 0x0B && codePoint <= 0x0C
                        || codePoint >= 0x0E && codePoint <= 0x1F
                        || codePoint >= 0x7F && codePoint <= 0x9F
                        || codePoint == 0x2028 || codePoint == 0x2029);
    }

    private static void validateRawUserText(String rawUserText) {
        if (rawUserText == null || rawUserText.isBlank()) {
            throw new IllegalArgumentException("用户原始需求不能为空");
        }
    }
}
