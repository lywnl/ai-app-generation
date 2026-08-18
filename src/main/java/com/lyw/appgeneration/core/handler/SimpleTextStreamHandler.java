package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 简单文本流处理器
 * 处理 HTML 和 MULTI_FILE 类型的流式响应
 */
@Slf4j
public class SimpleTextStreamHandler {

    public static final String FAILURE_MESSAGE =
            "生成过程中遇到系统异常，请稍后重试。";

    /**
     * 处理传统流（HTML, MULTI_FILE）
     * 直接收集完整的文本响应
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser,
                               MemorySummaryService memorySummaryService,
                               UserMemoryService userMemoryService,
                               AppDataLifecycleFence lifecycleFence,
                               SimpleGenerationTurnContext context) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        return originFlux
                .doOnNext(aiResponseBuilder::append)
                .concatWith(Flux.defer(() -> persistSuccessfulMessage(
                        aiResponseBuilder.toString(), chatHistoryService,
                        appId, loginUser, memorySummaryService,
                        userMemoryService, lifecycleFence, context)))
                .doOnError(error -> {
                    log.error("普通生成流异常,appId={}", appId, error);
                    persistFailureMessage(chatHistoryService, appId,
                            loginUser, memorySummaryService,
                            userMemoryService, lifecycleFence, context);
                });
    }

    private Flux<String> persistSuccessfulMessage(
            String message, ChatHistoryService chatHistoryService,
            long appId, User loginUser,
            MemorySummaryService memorySummaryService,
            UserMemoryService userMemoryService,
            AppDataLifecycleFence lifecycleFence,
            SimpleGenerationTurnContext context) {
        PersistenceResult result = persistStableMessage(
                message, message, ChatMemoryOutcome.SUCCEEDED,
                true, chatHistoryService, appId, loginUser,
                memorySummaryService, userMemoryService,
                lifecycleFence, context);
        if (result == PersistenceResult.FAILED) {
            return Flux.error(new IllegalStateException("保存 AI 回复失败"));
        }
        return Flux.empty();
    }

    private void persistFailureMessage(
            ChatHistoryService chatHistoryService, long appId,
            User loginUser, MemorySummaryService memorySummaryService,
            UserMemoryService userMemoryService,
            AppDataLifecycleFence lifecycleFence,
            SimpleGenerationTurnContext context) {
        try {
            PersistenceResult result = persistStableMessage(
                    FAILURE_MESSAGE, FAILURE_MESSAGE,
                    ChatMemoryOutcome.SYSTEM_ERROR,
                    false, chatHistoryService, appId,
                    loginUser, memorySummaryService, userMemoryService,
                    lifecycleFence, context);
            if (result == PersistenceResult.FAILED) {
                log.error("普通生成失败消息保存返回 false,appId={}", appId);
            }
        } catch (RuntimeException exception) {
            log.error("普通生成失败消息保存异常,appId={}", appId, exception);
        }
    }

    private PersistenceResult persistStableMessage(
            String displayMessage,
            String memoryMessage,
            ChatMemoryOutcome memoryOutcome,
            boolean triggerMemory,
            ChatHistoryService chatHistoryService, long appId, User loginUser,
            MemorySummaryService memorySummaryService,
            UserMemoryService userMemoryService,
            AppDataLifecycleFence lifecycleFence,
            SimpleGenerationTurnContext context) {
        if (context.isCancelled()) {
            return PersistenceResult.SKIPPED;
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            log.info("普通生成稳定写入被删除门拒绝,appId={}", appId);
            return PersistenceResult.SKIPPED;
        }
        try (writerPermit) {
            if (context.isCancelled()) {
                return PersistenceResult.SKIPPED;
            }
            ChatHistory saved = chatHistoryService.addAiMessageAndReturn(
                    appId, displayMessage, memoryMessage, memoryOutcome,
                    loginUser.getId());
            if (saved == null) {
                return PersistenceResult.FAILED;
            }
            if (!triggerMemory) {
                return PersistenceResult.SAVED;
            }
            context.markStableAiMessagePersisted();
            triggerStableMemoryHooks(
                    appId, loginUser.getId(), saved.getId(),
                    memorySummaryService, userMemoryService);
            return PersistenceResult.SAVED;
        }
    }

    private void triggerStableMemoryHooks(
            long appId,
            Long userId,
            Long stableAiMessageId,
            MemorySummaryService memorySummaryService,
            UserMemoryService userMemoryService) {
        try {
            memorySummaryService.triggerSummarizationAsync(appId);
        } catch (RuntimeException exception) {
            log.warn("普通生成 L1 摘要触发失败 appId={} userId={} "
                            + "stableAiMessageId={} type={}",
                    appId, userId, stableAiMessageId,
                    exception.getClass().getSimpleName());
        }
        if (stableAiMessageId == null || stableAiMessageId <= 0L) {
            log.warn("普通生成稳定 AI 消息缺少有效 ID，跳过 L2 触发 "
                            + "appId={} userId={}",
                    appId, userId);
            return;
        }
        try {
            userMemoryService.triggerPreferenceExtractionAsync(
                    userId, appId, stableAiMessageId);
        } catch (RuntimeException exception) {
            log.warn("普通生成 L2 偏好触发失败 appId={} userId={} "
                            + "stableAiMessageId={} type={}",
                    appId, userId, stableAiMessageId,
                    exception.getClass().getSimpleName());
        }
    }

    private enum PersistenceResult {
        SAVED, SKIPPED, FAILED
    }
}
