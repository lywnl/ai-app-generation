package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.lyw.appgeneration.ai.memory.ToolMessageCollapser.CollapseStatus.COLLAPSED;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR;

/** 同一回合唯一允许执行稳定终态持久化与记忆副作用的组件。 */
@Slf4j
@Component
public class VueTurnFinalizer {

    public static final String SYSTEM_ERROR_MESSAGE =
            "生成过程中遇到系统异常，请稍后重试。";

    private final ChatHistoryService chatHistoryService;
    private final ToolMessageCollapser toolMessageCollapser;
    private final MemorySummaryService memorySummaryService;
    private final UserMemoryService userMemoryService;
    private final AiGeneratorServiceFactory serviceFactory;

    public VueTurnFinalizer(ChatHistoryService chatHistoryService,
            ToolMessageCollapser toolMessageCollapser,
            MemorySummaryService memorySummaryService,
            UserMemoryService userMemoryService,
            AiGeneratorServiceFactory serviceFactory) {
        this.chatHistoryService = chatHistoryService;
        this.toolMessageCollapser = toolMessageCollapser;
        this.memorySummaryService = memorySummaryService;
        this.userMemoryService = userMemoryService;
        this.serviceFactory = serviceFactory;
    }

    public FinalizationResult finalizeOnce(
            VueTurnContext context, VueTurnOutcome requestedOutcome) {
        if (!context.tryStartFinalization()) {
            return context.awaitFinalization();
        }
        FinalizationResult result;
        try {
            result = persist(context, requestedOutcome);
        } catch (RuntimeException exception) {
            log.error("Vue 回合终态持久化异常,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
            invalidateService(context);
            result = new FinalizationResult(systemError(context), false);
        }
        try {
            context.closeResources();
        } catch (RuntimeException exception) {
            log.error("Vue 回合终态资源释放异常,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
        context.completeFinalization(result);
        return result;
    }

    private FinalizationResult persist(
            VueTurnContext context, VueTurnOutcome requestedOutcome) {
        boolean saved;
        try {
            saved = chatHistoryService.addChatMessage(
                    context.appId(), requestedOutcome.canonicalAiText(),
                    ChatHistoryMessageTypeEnum.AI.getValue(), context.userId());
        } catch (RuntimeException exception) {
            log.error("Vue 回合 AI 消息保存异常,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
            invalidateService(context);
            return new FinalizationResult(systemError(context), false);
        }
        if (!saved) {
            log.error("Vue 回合 AI 消息保存返回 false,appId={},turnId={}",
                    context.appId(), context.turnId());
            invalidateService(context);
            return new FinalizationResult(systemError(context), false);
        }

        ToolMessageCollapser.CollapseResult collapse =
                toolMessageCollapser.collapseLastTurn(
                        context.appId(), requestedOutcome.canonicalAiText());
        if (collapse.status() != COLLAPSED) {
            log.warn("Vue 回合 L0 未稳定同步,appId={},turnId={},stage={}",
                    context.appId(), context.turnId(), collapse.status());
            invalidateService(context);
        }
        triggerStableMemoryHooks(context);
        return new FinalizationResult(requestedOutcome, true);
    }

    private void triggerStableMemoryHooks(VueTurnContext context) {
        try {
            memorySummaryService.triggerSummarizationAsync(context.appId());
        } catch (RuntimeException exception) {
            log.warn("Vue 回合 L1 摘要触发失败,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
        try {
            userMemoryService.triggerPreferenceExtractionAsync(
                    context.userId(), context.appId());
        } catch (RuntimeException exception) {
            log.warn("Vue 回合 L2 偏好触发失败,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
    }

    private void invalidateService(VueTurnContext context) {
        try {
            serviceFactory.invalidateVueService(context.appId());
        } catch (RuntimeException exception) {
            log.error("Vue 服务缓存失效失败,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
    }

    private VueTurnOutcome systemError(VueTurnContext context) {
        return new VueTurnOutcome(context.phase(), SYSTEM_ERROR,
                SYSTEM_ERROR_MESSAGE, false, SYSTEM_ERROR_MESSAGE);
    }

    public record FinalizationResult(VueTurnOutcome outcome, boolean persisted) {
    }
}
