package com.lyw.appgeneration.core.handler;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.ai.tools.BuildProjectToolResult;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.lyw.appgeneration.ai.memory.ToolMessageCollapser.CollapseStatus.COLLAPSED;
import static com.lyw.appgeneration.core.handler.VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR;

/** 同一回合唯一允许执行稳定终态持久化与记忆副作用的组件。 */
@Slf4j
@Component
public class VueTurnFinalizer implements InitializingBean {

    public static final String SUCCESS_MESSAGE =
            BuildProjectToolResult.SUCCESS_RESPONSE;
    public static final String BUILD_FAILED_MESSAGE =
            BuildProjectToolResult.FAILURE_RESPONSE;
    public static final String SYSTEM_ERROR_MESSAGE =
            "生成过程中遇到系统异常，请稍后重试。";
    public static final String PROTOCOL_MESSAGE =
            "项目尚未通过真实构建，请重新生成。";
    public static final String SCOPE_PROTOCOL_MESSAGE =
            "生成状态异常，系统已停止本次生成，请重新发起。";
    public static final String LOOP_LIMIT_MESSAGE =
            "生成步骤过多，系统已停止本次生成，请稍后重试。";
    public static final String TIMEOUT_MESSAGE =
            "生成与构建超时，请稍后重试。";
    public static final String RESOURCE_LIMIT_MESSAGE =
            "生成内容过大，系统已停止本次生成，请缩小需求后重试。";
    public static final String CANCELLED_MESSAGE = "本次生成已取消。";

    private static final List<String> FIXED_TERMINAL_MESSAGES = List.of(
            SUCCESS_MESSAGE,
            BUILD_FAILED_MESSAGE,
            SYSTEM_ERROR_MESSAGE,
            PROTOCOL_MESSAGE,
            SCOPE_PROTOCOL_MESSAGE,
            LOOP_LIMIT_MESSAGE,
            TIMEOUT_MESSAGE,
            RESOURCE_LIMIT_MESSAGE,
            CANCELLED_MESSAGE);
    private static final int MAX_TERMINAL_MESSAGE_CODE_POINTS =
            FIXED_TERMINAL_MESSAGES.stream()
                    .mapToInt(FileToolBudgetGuard::codePointCount)
                    .max()
                    .orElseThrow();
    private static final int TERMINAL_RESERVE_CODE_POINTS =
            MAX_TERMINAL_MESSAGE_CODE_POINTS + 2;

    private final ChatHistoryService chatHistoryService;
    private final ToolMessageCollapser toolMessageCollapser;
    private final MemorySummaryService memorySummaryService;
    private final UserMemoryService userMemoryService;
    private final AiGeneratorServiceFactory serviceFactory;
    private final AppDataLifecycleFence lifecycleFence;
    private final VueBuildRepairMetricsCollector metricsCollector;
    private final FileToolBudgetGuard fileToolBudgetGuard;

    public VueTurnFinalizer(ChatHistoryService chatHistoryService,
            ToolMessageCollapser toolMessageCollapser,
            MemorySummaryService memorySummaryService,
            UserMemoryService userMemoryService,
            AiGeneratorServiceFactory serviceFactory,
            AppDataLifecycleFence lifecycleFence,
            VueBuildRepairMetricsCollector metricsCollector,
            FileToolBudgetGuard fileToolBudgetGuard) {
        this.chatHistoryService = chatHistoryService;
        this.toolMessageCollapser = toolMessageCollapser;
        this.memorySummaryService = memorySummaryService;
        this.userMemoryService = userMemoryService;
        this.serviceFactory = serviceFactory;
        this.lifecycleFence = lifecycleFence;
        this.metricsCollector = metricsCollector;
        this.fileToolBudgetGuard = fileToolBudgetGuard;
    }

    @Override
    public void afterPropertiesSet() {
        fileToolBudgetGuard.validateCanonicalReserve(
                terminalReserveCodePoints());
    }

    public static List<String> fixedTerminalMessages() {
        return FIXED_TERMINAL_MESSAGES;
    }

    public static int maxTerminalMessageCodePoints() {
        return MAX_TERMINAL_MESSAGE_CODE_POINTS;
    }

    public static int terminalReserveCodePoints() {
        return TERMINAL_RESERVE_CODE_POINTS;
    }

    public FinalizationResult finalizeOnce(
            VueTurnContext context, VueTurnOutcome requestedOutcome) {
        VueTurnContext.TerminalTrigger fallbackTrigger =
                requestedOutcome.outcome()
                        == VueTurnOutcome.TurnOutcomeType.SUCCEEDED
                        ? VueTurnContext.TerminalTrigger.COMPLETED
                        : VueTurnContext.TerminalTrigger.FAILED;
        if (!context.tryClaimFinalizationExecution(fallbackTrigger)) {
            return context.awaitFinalization();
        }
        try {
            requestedOutcome = enforceCanonicalBudget(context, requestedOutcome);
            FinalizationResult result = persistWithinWriterPermit(
                    context, requestedOutcome);
            metricsCollector.recordTurnOutcome(result.outcome());
            context.closeResources();
            context.completeFinalization(result);
            return result;
        } catch (RuntimeException | Error failure) {
            closeResourcesAfterFailure(context, failure);
            failSharedFinalization(context, failure);
            throw failure;
        }
    }

    private void closeResourcesAfterFailure(
            VueTurnContext context, Throwable originalFailure) {
        try {
            context.closeResources();
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != originalFailure) {
                originalFailure.addSuppressed(closeFailure);
            }
            log.error("Vue 回合终态资源释放异常,appId={},turnId={}",
                    context.appId(), context.turnId(), closeFailure);
        }
    }

    @SuppressWarnings("removal")
    private void failSharedFinalization(
            VueTurnContext context, Throwable originalFailure) {
        if (context.turnState().stage()
                != VueTurnContext.TurnStage.FINALIZING) {
            return;
        }
        try {
            context.failFinalization(originalFailure);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (RuntimeException | Error completionFailure) {
            if (completionFailure != originalFailure) {
                originalFailure.addSuppressed(completionFailure);
            }
        }
    }

    private VueTurnOutcome enforceCanonicalBudget(
            VueTurnContext context, VueTurnOutcome requestedOutcome) {
        FileToolBudgetGuard.CanonicalAccumulator check =
                context.budgetSession().newCanonicalAccumulator();
        if (check.append(requestedOutcome.displayAiText()).accepted()) {
            return requestedOutcome;
        }
        return new VueTurnOutcome(
                context.phase(), SYSTEM_ERROR, RESOURCE_LIMIT_MESSAGE,
                VueTurnMemoryProjection.project(
                        List.of(), SYSTEM_ERROR),
                false, RESOURCE_LIMIT_MESSAGE);
    }

    private FinalizationResult persistWithinWriterPermit(
            VueTurnContext context, VueTurnOutcome requestedOutcome) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                lifecycleFence.tryAcquireWriter(context.appId());
        if (writerPermit == null) {
            log.info("Vue 回合终态被应用数据删除门拒绝,appId={},turnId={}",
                    context.appId(), context.turnId());
            return new FinalizationResult(requestedOutcome, false);
        }
        try (writerPermit) {
            try {
                return persist(context, requestedOutcome);
            } catch (RuntimeException exception) {
                log.error("Vue 回合终态持久化异常,appId={},turnId={}",
                        context.appId(), context.turnId(), exception);
                invalidateUnstableMemory(context);
                return new FinalizationResult(systemError(context), false);
            }
        }
    }

    private FinalizationResult persist(
            VueTurnContext context, VueTurnOutcome requestedOutcome) {
        ChatHistory saved;
        try {
            saved = chatHistoryService.addAiMessageAndReturn(
                    context.appId(), requestedOutcome.displayAiText(),
                    requestedOutcome.memoryAiText(),
                    VueTurnMemoryProjection.memoryOutcome(
                            requestedOutcome.outcome()),
                    context.userId());
        } catch (RuntimeException exception) {
            log.error("Vue 回合 AI 消息保存异常,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
            invalidateUnstableMemory(context);
            return new FinalizationResult(systemError(context), false);
        }
        if (saved == null) {
            log.error("Vue 回合 AI 消息保存返回 false,appId={},turnId={}",
                    context.appId(), context.turnId());
            invalidateUnstableMemory(context);
            return new FinalizationResult(systemError(context), false);
        }

        ToolMessageCollapser.CollapseResult collapse =
                toolMessageCollapser.collapseLastTurn(
                        context.appId(), requestedOutcome.memoryAiText());
        recordCollapse(collapse.status());
        if (collapse.status() != COLLAPSED) {
            log.warn("Vue 回合 L0 未稳定同步,appId={},turnId={},stage={}",
                    context.appId(), context.turnId(), collapse.status());
            invalidateUnstableMemory(context);
            return new FinalizationResult(requestedOutcome, true);
        }
        triggerStableMemoryHooks(context, saved.getId());
        return new FinalizationResult(requestedOutcome, true);
    }

    private void triggerStableMemoryHooks(
            VueTurnContext context, Long stableAiMessageId) {
        try {
            memorySummaryService.triggerSummarizationAsync(context.appId());
        } catch (RuntimeException exception) {
            log.warn("Vue 回合 L1 摘要触发失败,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
        if (stableAiMessageId == null || stableAiMessageId <= 0L) {
            log.warn("Vue 稳定 AI 消息缺少有效 ID，跳过 L2 触发 appId={},turnId={}",
                    context.appId(), context.turnId());
            return;
        }
        try {
            userMemoryService.triggerPreferenceExtractionAsync(
                    context.userId(), context.appId(), stableAiMessageId);
        } catch (RuntimeException exception) {
            log.warn("Vue 回合 L2 偏好触发失败,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
    }

    private void invalidateUnstableMemory(VueTurnContext context) {
        try {
            MemoryCacheInvalidationResult result = serviceFactory
                    .invalidateAndClearMemory(
                            context.appId(), CodeGenTypeEnum.VUE_PROJECT);
            if (result != null && !result.failedTargets().isEmpty()) {
                metricsCollector.recordMemoryL0Sync(
                        VueBuildRepairMetricsCollector.MemoryAction.INVALIDATE,
                        VueBuildRepairMetricsCollector.MemoryResult.FAILED);
                log.error("Vue 不可信 L0 清理不完整,appId={},turnId={},targets={}",
                        context.appId(), context.turnId(), result.failedTargets());
            } else {
                metricsCollector.recordMemoryL0Sync(
                        VueBuildRepairMetricsCollector.MemoryAction.INVALIDATE,
                        VueBuildRepairMetricsCollector.MemoryResult.SUCCEEDED);
            }
        } catch (RuntimeException exception) {
            metricsCollector.recordMemoryL0Sync(
                    VueBuildRepairMetricsCollector.MemoryAction.INVALIDATE,
                    VueBuildRepairMetricsCollector.MemoryResult.FAILED);
            log.error("Vue 不可信 L0 清理失败,appId={},turnId={}",
                    context.appId(), context.turnId(), exception);
        }
    }

    private void recordCollapse(ToolMessageCollapser.CollapseStatus status) {
        VueBuildRepairMetricsCollector.MemoryResult result = switch (status) {
            case COLLAPSED -> VueBuildRepairMetricsCollector.MemoryResult.SUCCEEDED;
            case NO_MESSAGES, NO_USER_BOUNDARY, INVALID_TEXT ->
                    VueBuildRepairMetricsCollector.MemoryResult.EMPTY;
            case STORE_FAILED -> VueBuildRepairMetricsCollector.MemoryResult.FAILED;
        };
        metricsCollector.recordMemoryL0Sync(
                VueBuildRepairMetricsCollector.MemoryAction.COLLAPSE, result);
    }

    private VueTurnOutcome systemError(VueTurnContext context) {
        return new VueTurnOutcome(context.phase(), SYSTEM_ERROR,
                SYSTEM_ERROR_MESSAGE,
                VueTurnMemoryProjection.project(List.of(), SYSTEM_ERROR),
                false, SYSTEM_ERROR_MESSAGE);
    }

    public record FinalizationResult(VueTurnOutcome outcome, boolean persisted) {
    }
}
