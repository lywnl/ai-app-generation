package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import com.lyw.appgeneration.ai.plan.AppPlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 当前模型回合的不可变原始用户请求边界。 */
public record TurnRequestBoundary(
        String rawUserText,
        String canonicalUserName,
        List<ChatMessage> transientMessages,
        long userHistoryId,
        String turnId,
        PlanSnapshotReader planReader) {

    public static final int MAX_RAW_USER_TEXT_LENGTH = 6 * 1024;

    /** 非 Vue 及旧调用不具有来源裁剪权限，保留原消息接口。 */
    public TurnRequestBoundary(String rawUserText, String canonicalUserName,
                               List<ChatMessage> transientMessages) {
        this(rawUserText, canonicalUserName, transientMessages, 0L, null, null);
    }

    public TurnRequestBoundary(String rawUserText, String canonicalUserName,
                               List<ChatMessage> transientMessages, long userHistoryId, String turnId) {
        this(rawUserText, canonicalUserName, transientMessages, userHistoryId, turnId, null);
    }

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
        if (userHistoryId < 0L || ((userHistoryId == 0L) != (turnId == null))
                || (turnId != null && (turnId.isBlank() || turnId.length() > 128))) {
            throw new IllegalArgumentException("用户持久身份与运行回合必须同时提供");
        }
    }

    public static TurnRequestBoundary of(
            String rawUserText, List<ChatMessage> transientMessages) {
        validateRawUserText(rawUserText);
        return new TurnRequestBoundary(
                rawUserText,
                TokenAwareChatMemory.canonicalUserName(rawUserText),
                transientMessages);
    }

    /** canonicalUserName 只承担协议命名，不能代替相同原文的不同持久回合。 */
    public static TurnRequestBoundary of(String rawUserText, List<ChatMessage> transientMessages,
                                         long userHistoryId, String turnId) {
        validateRawUserText(rawUserText);
        if (userHistoryId <= 0L || turnId == null) {
            throw new IllegalArgumentException("Vue 请求必须绑定持久用户记录及运行回合");
        }
        return new TurnRequestBoundary(rawUserText, TokenAwareChatMemory.canonicalUserName(rawUserText),
                transientMessages, userHistoryId, turnId);
    }

    public UserMessage userMessage() {
        return UserMessage.from(canonicalUserName, rawUserText);
    }

    /** 仅绑定只读读取能力；每次实际请求准备时再读取，不能将一次结果缓存为本轮固定资料。 */
    public TurnRequestBoundary withPlanReader(PlanSnapshotReader reader) {
        return new TurnRequestBoundary(rawUserText, canonicalUserName, transientMessages, userHistoryId, turnId,
                Objects.requireNonNull(reader));
    }

    public TurnRequestBoundary withTransientMessages(List<ChatMessage> messages) {
        return new TurnRequestBoundary(rawUserText, canonicalUserName, messages, userHistoryId, turnId, planReader);
    }

    public Optional<AppPlan> readLatestPlan() {
        return planReader == null ? Optional.empty() : Objects.requireNonNull(planReader.read(), "只读计划读取未返回明确结果");
    }

    @FunctionalInterface
    public interface PlanSnapshotReader {
        Optional<AppPlan> read();
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
