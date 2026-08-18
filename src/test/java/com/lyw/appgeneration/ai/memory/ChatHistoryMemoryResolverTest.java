package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryMemoryResolverTest {

    private final ChatHistoryMemoryResolver resolver =
            new ChatHistoryMemoryResolver();

    @Test
    void 用户消息解析原始展示文本() {
        ChatHistory history = history("用户原话", "user", null, null);

        assertEquals(Optional.of("用户原话"),
                resolver.resolveModelText(history));
    }

    @Test
    void AI消息只解析可信投影而不读取展示文本() {
        ChatHistory history = history(
                "[工具调用] writeFile({伪参数})",
                "ai",
                "实际修改 src/App.vue，构建成功。",
                ChatMemoryOutcome.SUCCEEDED);

        assertEquals(Optional.of("实际修改 src/App.vue，构建成功。"),
                resolver.resolveModelText(history));
    }

    @Test
    void AI投影为空时绝不回退展示文本() {
        ChatHistory missingProjection = history(
                "不得进入模型的展示文本", "ai", null,
                ChatMemoryOutcome.SUCCEEDED);
        ChatHistory blankProjection = history(
                "不得进入模型的展示文本", "ai", "  ",
                ChatMemoryOutcome.SUCCEEDED);
        ChatHistory missingOutcome = history(
                "不得进入模型的展示文本", "ai", "看似可信", null);

        assertTrue(resolver.resolveModelText(missingProjection).isEmpty());
        assertTrue(resolver.resolveModelText(blankProjection).isEmpty());
        assertTrue(resolver.resolveModelText(missingOutcome).isEmpty());
    }

    @Test
    void 不支持的角色不会进入模型上下文() {
        ChatHistory history = history(
                "系统展示文本", "system", "系统投影",
                ChatMemoryOutcome.SUCCEEDED);

        assertTrue(resolver.resolveModelText(history).isEmpty());
        assertTrue(resolver.resolveModelText(null).isEmpty());
    }

    @Test
    void 长期偏好门只排除协议错误旧Vue和不完整AI行() {
        assertFalse(resolver.isEligibleForLongTermPreference(
                history("用户原话", "user", null, null)));
        assertFalse(resolver.isEligibleForLongTermPreference(
                history("展示", "ai", null,
                        ChatMemoryOutcome.SUCCEEDED)));
        assertFalse(resolver.isEligibleForLongTermPreference(
                history("展示", "ai", "投影", null)));
        assertFalse(resolver.isEligibleForLongTermPreference(
                history("展示", "ai", "协议异常投影",
                        ChatMemoryOutcome.PROTOCOL_ERROR)));
        assertFalse(resolver.isEligibleForLongTermPreference(
                history("展示", "ai", "旧 Vue 保守投影",
                        ChatMemoryOutcome.LEGACY_UNVERIFIED)));
        assertTrue(resolver.isEligibleForLongTermPreference(
                history("展示", "ai", "系统错误安全投影",
                        ChatMemoryOutcome.SYSTEM_ERROR)));
        assertTrue(resolver.isEligibleForLongTermPreference(
                history("展示", "ai", "旧简单模式投影",
                        ChatMemoryOutcome.LEGACY_IMPORTED)));
    }

    private ChatHistory history(
            String message,
            String messageType,
            String memoryMessage,
            ChatMemoryOutcome memoryOutcome) {
        return ChatHistory.builder()
                .id(1L)
                .appId(7L)
                .userId(9L)
                .message(message)
                .messageType(messageType)
                .memoryMessage(memoryMessage)
                .memoryOutcome(memoryOutcome)
                .build();
    }
}
