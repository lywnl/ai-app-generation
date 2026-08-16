package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.model.entity.ChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPreferenceBatchBuilderTest {

    private MemoryTokenProperties properties;
    private ChatTokenEstimator tokenEstimator;
    private UserPreferenceBatchBuilder batchBuilder;

    @BeforeEach
    void 初始化批次构建器() {
        properties = new MemoryTokenProperties();
        properties.setEstimationSafetyFactor(1D);
        tokenEstimator = new ConservativeChatTokenEstimator(properties);
        batchBuilder = new UserPreferenceBatchBuilder(
                tokenEstimator, properties);
    }

    @Test
    @DisplayName("跨分页相邻 USER 到 AI 仍识别为一个稳定完整回合")
    void 跨分页识别完整回合且Prompt不含Ai正文() {
        UserPreferenceBatchBuilder.Session session =
                batchBuilder.start(0L, "");

        UserPreferenceBatchBuilder.PageResult firstPage =
                session.acceptPage(List.of(
                        消息(11L, "user", "所有应用都使用深色极简")),
                        false);
        UserPreferenceBatchBuilder.PageResult secondPage =
                session.acceptPage(List.of(
                        消息(12L, "ai", "<template>完整代码</template>")),
                        true);

        assertFalse(firstPage.finished());
        assertTrue(secondPage.finished());
        assertEquals(List.of(11L), secondPage.batch().turnIds());
        assertEquals(12L, secondPage.batch().completedThroughId());
        assertTrue(secondPage.batch().prompt().contains("所有应用都使用深色极简"));
        assertFalse(secondPage.batch().prompt().contains("完整代码"));
    }

    @Test
    @DisplayName("连续 USER 属于异常序列且不得进入 Prompt 或推进游标")
    void 连续用户消息不得伪装成稳定完整回合() {
        UserPreferenceBatchBuilder.Session session =
                batchBuilder.start(0L, "");

        UserPreferenceBatchBuilder.PageResult result = session.acceptPage(
                List.of(
                        消息(13L, "user", "第一条未闭合用户消息"),
                        消息(14L, "user", "第二条连续用户消息"),
                        消息(15L, "ai", "异常序列后的回复")),
                true);

        assertTrue(result.finished());
        assertTrue(result.batch().turnIds().isEmpty());
        assertEquals(0L, result.batch().completedThroughId());
        assertFalse(result.batch().prompt().contains("第一条未闭合用户消息"));
        assertFalse(result.batch().prompt().contains("第二条连续用户消息"));
    }

    @Test
    @DisplayName("下一个完整回合越界时保留到下一批且不拆回合")
    void Token越界只选择前一个完整回合() {
        String firstUser = "甲".repeat(120);
        String secondUser = "乙".repeat(120);
        int oneTurnTokens = tokenEstimator.estimateText(
                UserPreferencePromptBuilder.build(
                        "", 用户证据(21L, firstUser), List.of(21L)));
        properties.setAsyncCompressionThreshold(oneTurnTokens);
        UserPreferenceBatchBuilder.Session session =
                batchBuilder.start(0L, "");

        UserPreferenceBatchBuilder.PageResult result = session.acceptPage(
                List.of(
                        消息(21L, "user", firstUser),
                        消息(22L, "ai", "第一轮闭合"),
                        消息(31L, "user", secondUser),
                        消息(32L, "ai", "第二轮闭合")),
                true);

        assertTrue(result.finished());
        assertEquals(List.of(21L), result.batch().turnIds());
        assertEquals(22L, result.batch().completedThroughId());
        assertTrue(result.batch().hasMore());
        assertFalse(result.batch().prompt().contains(secondUser));
    }

    @Test
    @DisplayName("单个完整回合超限时返回安全元数据并推进 AI 边界")
    void 单回合超限跳过并报告TurnId边界() {
        String oversizedUser = "超长用户证据".repeat(2_000);
        int oversizedTokens = tokenEstimator.estimateText(
                UserPreferencePromptBuilder.build(
                        "", 用户证据(41L, oversizedUser), List.of(41L)));
        properties.setAsyncCompressionThreshold(oversizedTokens - 1);
        UserPreferenceBatchBuilder.Session session =
                batchBuilder.start(0L, "");

        UserPreferenceBatchBuilder.PageResult result = session.acceptPage(
                List.of(
                        消息(41L, "user", oversizedUser),
                        消息(42L, "ai", "闭合回复")),
                true);

        assertTrue(result.finished());
        assertTrue(result.batch().turnIds().isEmpty());
        assertEquals(42L, result.batch().completedThroughId());
        assertEquals(List.of(
                        new UserPreferenceBatchBuilder.SkippedTurn(41L, 42L)),
                result.skippedTurns());
    }

    private ChatHistory 消息(long id, String type, String text) {
        return ChatHistory.builder()
                .id(id).appId(100L).userId(7L)
                .messageType(type).message(text).build();
    }

    private String 用户证据(long turnId, String userText) {
        return "turnId=" + turnId + "\n用户:" + userText;
    }
}
