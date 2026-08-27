package com.lyw.appgeneration.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingServiceFactory;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.guardrail.annotation.PromptSafetyCheck;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.core.builder.BuildCancellationSignal;
import com.lyw.appgeneration.core.builder.BuildExecutionContext;
import com.lyw.appgeneration.core.builder.BuildLogSink;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import com.lyw.appgeneration.core.handler.VueTurnCancellationCoordinator;
import com.lyw.appgeneration.core.handler.VueTurnFinalizer;
import com.lyw.appgeneration.core.handler.VueTurnMemoryProjection;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.core.handler.SimpleGenerationTurnContext;
import com.lyw.appgeneration.core.handler.SimpleTextStreamHandler;
import com.lyw.appgeneration.core.handler.GenerationCancellationRegistry;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.core.concurrency.VueTurnAdmissionController;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.model.dto.app.AppAddRequest;
import com.lyw.appgeneration.model.dto.app.AppQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.model.vo.user.UserVO;
import com.lyw.appgeneration.ratelimiter.annotation.RateLimit;
import com.lyw.appgeneration.ratelimiter.enums.RateLimitType;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.AppDeploymentFileService;
import com.lyw.appgeneration.service.AppDeployUrlBuilder;
import com.lyw.appgeneration.service.AppDeletionFileService;
import com.lyw.appgeneration.service.AppDeletionPersistenceService;
import com.lyw.appgeneration.service.AppStoragePathResolver;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.VueTurnModeRouter;
import com.lyw.appgeneration.service.VueTurnModeRoutingException;
import com.lyw.appgeneration.service.ProjectDownloadService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.ScreenshotService;
import com.lyw.appgeneration.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    private static final int DELETE_CACHE_MAX_ATTEMPTS = 3;

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    @Resource
    private AppOperationLeaseManager appOperationLeaseManager;

    @Resource
    private AppLifecycleMetricsCollector appLifecycleMetricsCollector;

    @Resource
    private VueBuildSessionManager vueBuildSessionManager;

    @Resource
    private VueTurnAdmissionController vueTurnAdmissionController;

    @Resource
    private VueTurnCancellationCoordinator vueTurnCancellationCoordinator;

    @Resource
    private VueTurnFinalizer vueTurnFinalizer;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private AppDeploymentFileService appDeploymentFileService;

    @Resource
    private AppDeployUrlBuilder appDeployUrlBuilder;

    @Resource
    private AppDeletionFileService appDeletionFileService;

    @Resource
    private AppDeletionPersistenceService appDeletionPersistenceService;

    @Resource
    private AppDataLifecycleFence appDataLifecycleFence;

    @Resource
    private MemorySummaryService memorySummaryService;

    @Resource
    private UserMemoryService userMemoryService;

    @Resource
    private FileToolBudgetGuard fileToolBudgetGuard;

    private Duration deleteWaitTimeout = Duration.ofSeconds(10);

    @Resource
    private AppStoragePathResolver appStoragePathResolver;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;

    @Resource
    private VueTurnModeRouter vueTurnModeRouter;

    @Resource
    private GenerationCancellationRegistry generationCancellationRegistry;

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        appVO.setDeployUrl(appDeployUrlBuilder.buildUrl(app.getDeployKey()));
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }

        return appVO;
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    @Override
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI请求过于频繁，请稍后再试")
    @PromptSafetyCheck
    public Flux<GenerationStreamEvent> chatToGenCode(
            Long appId, String message, String generationId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(generationId), ErrorCode.PARAMS_ERROR,
                "生成任务 ID 不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            return generateVueTurn(appId, message, generationId, loginUser);
        }
        return generateSimpleTurn(appId, message, generationId, loginUser,
                codeGenTypeEnum);
    }

    @Override
    public boolean cancelGeneration(Long appId, String generationId,
                                    User loginUser) {
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        return generationCancellationRegistry.cancel(
                appId, generationId, loginUser.getId())
                == GenerationCancellationRegistry.CancellationResult.REQUESTED;
    }

    private Flux<GenerationStreamEvent> generateSimpleTurn(
            long appId, String message, String generationId, User loginUser,
            CodeGenTypeEnum codeGenType) {
        return Flux.defer(() -> {
            SimpleGenerationTurnContext context;
            try {
                context = openSimpleTurn(appId);
            } catch (RuntimeException exception) {
                return Flux.error(toPreflightException(exception));
            }
            Runnable cancellation = context::requestCancellation;
            if (!generationCancellationRegistry.register(appId, generationId,
                    loginUser.getId(), cancellation)) {
                context.close();
                return Flux.error(new BusinessException(
                        ErrorCode.OPERATION_ERROR, "生成任务 ID 已被占用"));
            }
                context.registerCancellationFinalizer(() -> {
                    if (context.userMessageCommitted()) {
                        persistCancelledSimpleTurn(context, codeGenType,
                                loginUser);
                    }
                });
            boolean userCommitted = false;
            try {
                PreparedSimpleTurn prepared = prepareSimpleTurn(
                        appId, message, loginUser, codeGenType, context);
                userCommitted = true;
                Flux<String> codeStream = Flux.defer(() ->
                        aiCodeGeneratorFacade.generateAndSaveCodeStream(
                                message, codeGenType, appId,
                                prepared.firstMessage(), context,
                                prepared.generatorService()));
                Flux<GenerationStreamEvent> business = streamHandlerExecutor
                        .doExecute(codeStream, chatHistoryService, appId,
                                loginUser, codeGenType, context)
                        ;
                return Flux.firstWithSignal(context.mergeProgress(business),
                                context.cancellationOutcome())
                        .doOnCancel(context::requestCancellation)
                        .doFinally(signal -> finishSimpleTurn(
                                context, codeGenType, loginUser, generationId,
                                cancellation,
                                signal));
            } catch (RuntimeException exception) {
                generationCancellationRegistry.unregister(
                        appId, generationId, cancellation);
                context.close();
                return Flux.error(userCommitted
                        ? exception : toPreflightException(exception));
            }
        });
    }

    private SimpleGenerationTurnContext openSimpleTurn(long appId) {
        try {
            var lease = appOperationLeaseManager.acquire(
                    appId, AppOperationLeaseManager.AppOperationType.GENERATE,
                    "simple-generate-" + UUID.randomUUID());
            recordOperation(AppOperationLeaseManager.AppOperationType.GENERATE,
                    AppLifecycleMetricsCollector.OperationResult.ACQUIRED, null);
            return new SimpleGenerationTurnContext(lease);
        } catch (AppOperationLeaseManager.ActiveAppOperationException exception) {
            recordOperation(AppOperationLeaseManager.AppOperationType.GENERATE,
                    AppLifecycleMetricsCollector.OperationResult.REJECTED,
                    exception.activeOperation());
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用正在执行其他操作，请稍后再生成");
        }
    }

    private PreparedSimpleTurn prepareSimpleTurn(
            long appId, String message, User loginUser,
            CodeGenTypeEnum codeGenType,
            SimpleGenerationTurnContext context) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                appDataLifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用已进入删除流程，无法继续生成");
        }
        try (writerPermit) {
            if (context.isCancelled()) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR, "本次生成已取消");
            }
            var lastMessage = chatHistoryService.getLastMessage(appId);
            boolean firstMessage = lastMessage == null;
            if (lastMessage != null && ChatHistoryMessageTypeEnum.USER
                    .getValue().equals(lastMessage.getMessageType())) {
                repairIncompleteSimpleTurn(
                        appId, loginUser.getId(), codeGenType);
            }
            AiCodeGeneratorService generatorService =
                    aiCodeGeneratorFacade.prepareSimpleGenerator(
                            appId, codeGenType);
            if (context.isCancelled()) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR, "本次生成已取消");
            }
            boolean saved = chatHistoryService.addChatMessage(
                    appId, message, ChatHistoryMessageTypeEnum.USER.getValue(),
                    loginUser.getId());
            ThrowUtils.throwIf(!saved,
                    ErrorCode.OPERATION_ERROR, "保存用户消息失败");
            context.markUserMessageCommitted();
            return new PreparedSimpleTurn(generatorService, firstMessage);
        }
    }

    private void repairIncompleteSimpleTurn(
            long appId, long userId, CodeGenTypeEnum codeGenType) {
        MemoryCacheInvalidationResult invalidation = aiGeneratorServiceFactory
                .invalidateAndClearMemory(appId, codeGenType);
        if (invalidation == null || !invalidation.failedTargets().isEmpty()) {
            Set<String> failures = invalidation == null
                    ? Set.of("UNKNOWN") : invalidation.failedTargets();
            log.error("普通生成孤立回合清理 L0 失败,appId={},type={},targets={}",
                    appId, codeGenType, failures);
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "修复上一轮未完成对话失败");
        }
        boolean repaired = chatHistoryService.repairOrphanUserTurn(
                appId, userId, SimpleTextStreamHandler.FAILURE_MESSAGE);
        if (!repaired) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "修复上一轮未完成对话失败");
        }
    }

    private record PreparedSimpleTurn(
            AiCodeGeneratorService generatorService, boolean firstMessage) {
    }

    private void finishSimpleTurn(
            SimpleGenerationTurnContext context,
            CodeGenTypeEnum codeGenType,
            User loginUser,
            String generationId,
            Runnable cancellation,
            SignalType signal) {
        try {
            if (!context.hasStableAiMessagePersisted()
                    && context.isCancelled()
                    && context.userMessageCommitted()) {
                persistCancelledSimpleTurn(context, codeGenType, loginUser);
            }
            if (!context.hasStableAiMessagePersisted()
                    && (signal == SignalType.ON_ERROR
                    || signal == SignalType.CANCEL
                    || context.isCancelled())) {
                invalidateUnstableSimpleMemory(context.appId(), codeGenType);
            }
        } finally {
            generationCancellationRegistry.unregister(
                    context.appId(), generationId, cancellation);
            context.close();
        }
    }

    private void persistCancelledSimpleTurn(
            SimpleGenerationTurnContext context,
            CodeGenTypeEnum codeGenType,
            User loginUser) {
        AppDataLifecycleFence.WriterPermit writerPermit =
                appDataLifecycleFence.tryAcquireWriter(context.appId());
        if (writerPermit == null) {
            log.info("普通生成取消终态被删除门拒绝,appId={}", context.appId());
            return;
        }
        try (writerPermit) {
            if (context.hasStableAiMessagePersisted()) {
                return;
            }
            ChatHistory saved = chatHistoryService.addAiMessageAndReturn(
                    context.appId(), VueTurnFinalizer.CANCELLED_MESSAGE,
                    VueTurnMemoryProjection.project(List.of(),
                            VueTurnOutcome.TurnOutcomeType.CANCELLED),
                    ChatMemoryOutcome.CANCELLED, loginUser.getId());
            if (saved != null) {
                context.markStableAiMessagePersisted();
                try {
                    memorySummaryService.triggerSummarizationAsync(
                            context.appId());
                    userMemoryService.triggerPreferenceExtractionAsync(
                            loginUser.getId(), context.appId(), saved.getId());
                } catch (RuntimeException exception) {
                    log.warn("普通生成取消后的记忆钩子触发失败,appId={}",
                            context.appId(), exception);
                }
            }
        } catch (RuntimeException exception) {
            log.error("普通生成取消终态保存失败,appId={}", context.appId(),
                    exception);
        }
    }

    private void invalidateUnstableSimpleMemory(
            long appId, CodeGenTypeEnum codeGenType) {
        try {
            MemoryCacheInvalidationResult result = aiGeneratorServiceFactory
                    .invalidateAndClearMemory(appId, codeGenType);
            if (result != null && !result.failedTargets().isEmpty()) {
                log.error("普通生成异常终态清理 L0 失败,appId={},type={},targets={}",
                        appId, codeGenType, result.failedTargets());
            }
        } catch (RuntimeException exception) {
            log.error("普通生成异常终态清理 L0 失败,appId={},type={}",
                    appId, codeGenType, exception);
        }
    }

    private Flux<GenerationStreamEvent> generateVueTurn(
            long appId, String message, String generationId, User loginUser) {
        return Flux.defer(() -> {
            VueTurnContext context;
            try {
                context = openVueTurn(appId, loginUser.getId());
            } catch (RuntimeException exception) {
                return Flux.error(toPreflightException(exception));
            }
            Runnable cancellation = () -> vueTurnCancellationCoordinator
                    .requestCancellation(context, () -> "");
            if (!generationCancellationRegistry.register(appId, generationId,
                    loginUser.getId(), cancellation)) {
                context.closeResources();
                return Flux.error(new BusinessException(
                        ErrorCode.OPERATION_ERROR, "生成任务 ID 已被占用"));
            }
            Mono<CommittedVueTurn> prepared = Mono.fromCallable(() ->
                            prepareVueTurn(appId, message, loginUser, context))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(error -> handleVuePreparationFailure(
                            context, error))
                    .doOnCancel(() -> cancelVuePreparation(context));
            Flux<GenerationStreamEvent> turnFlow = prepared
                    .flatMapMany(this::runCommittedVueTurn);
            Flux<GenerationStreamEvent> finalizationFlow = context
                    .finalizationSignal()
                    .flatMapMany(result -> context.terminalWinner()
                            .filter(trigger -> trigger
                                    == VueTurnContext.TerminalTrigger.CANCELLED
                                    || trigger
                                    == VueTurnContext.TerminalTrigger.TIMED_OUT)
                            .<Flux<GenerationStreamEvent>>map(ignored ->
                                    Flux.just(GenerationStreamEvent.turnOutcome(
                                            result.outcome())))
                            .orElseGet(Flux::never))
                    .onErrorResume(error -> Flux.never());
            return Flux.firstWithSignal(
                            turnFlow.onErrorResume(error ->
                                    context.isUserCommitted()
                                            && context.terminalWinner().isPresent()
                                            ? Flux.empty() : Flux.error(error)),
                            finalizationFlow)
                    .takeUntil(event -> event
                            instanceof GenerationStreamEvent.TurnOutcome)
                    .doOnCancel(() -> cancelVuePreparation(context))
                    .doFinally(signal -> generationCancellationRegistry.unregister(
                            appId, generationId, cancellation));
        });
    }

    private VueTurnContext openVueTurn(long appId, long userId) {
        String turnId = UUID.randomUUID().toString();
        VueTurnAdmissionController.AdmissionPermit admissionPermit =
                vueTurnAdmissionController.tryAcquire().orElseThrow(() ->
                        new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                                "当前生成任务较多，请稍后再试"));
        AppOperationLeaseManager.AppOperationLease operationLease = null;
        VueBuildSessionManager.VueBuildLease vueLease = null;
        try {
            operationLease = appOperationLeaseManager.acquire(
                    appId, AppOperationLeaseManager.AppOperationType.GENERATE,
                    turnId);
            recordOperation(AppOperationLeaseManager.AppOperationType.GENERATE,
                    AppLifecycleMetricsCollector.OperationResult.ACQUIRED, null);
            vueLease = vueBuildSessionManager.open(
                    operationLease, userId, turnId);
            VueTurnContext context = new VueTurnContext(
                    appId, userId, turnId, operationLease, vueLease,
                    admissionPermit,
                    fileToolBudgetGuard.newSession(), true);
            admissionPermit = null;
            operationLease = null;
            vueLease = null;
            return context;
        } finally {
            VueTurnContext.closeAll(
                    vueLease, operationLease, admissionPermit);
        }
    }

    private CommittedVueTurn prepareVueTurn(
            long appId, String message, User loginUser,
            VueTurnContext context) {
        var lastMessage = context.callPreparation(
                () -> chatHistoryService.getLastMessage(appId));
        boolean hasHistory = lastMessage != null;
        if (hasHistory && ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(lastMessage.getMessageType())) {
            boolean repaired = context.callPreparation(() ->
                    chatHistoryService.repairOrphanUserTurn(
                            appId, loginUser.getId(),
                            "生成过程中遇到系统异常，请稍后重试。"));
            if (!repaired) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "修复上一轮未完成对话失败");
            }
            context.callPreparation(() -> {
                aiGeneratorServiceFactory.prepareVueColdRebuild(appId);
                return null;
            });
        }
        var generatorService = context.callPreparation(
                () -> aiCodeGeneratorFacade.prepareVueGenerator(appId));
        var mode = context.callPreparation(
                () -> vueTurnModeRouter.route(message, hasHistory));
        context.initializeMode(mode);
        VueTurnContext.UserCommitResult commitResult = context.commitUser(() ->
                chatHistoryService.addChatMessage(
                        appId, message,
                        ChatHistoryMessageTypeEnum.USER.getValue(),
                        loginUser.getId()));
        return switch (commitResult) {
            case COMMITTED -> new CommittedVueTurn(
                    context, generatorService, message, !hasHistory, null);
            case TERMINATED_BEFORE_COMMIT -> throw new CancellationException(
                    "Vue 回合在用户消息提交前终止");
            case STORE_FAILED -> throw new IllegalStateException(
                    "保存用户消息失败");
        };
    }

    private Flux<GenerationStreamEvent> runCommittedVueTurn(
            CommittedVueTurn turn) {
        VueTurnContext context = turn.context();
        if (turn.startupFailure() != null) {
            return finalizeCommittedVueFailure(context, turn.startupFailure());
        }
        return Flux.defer(() -> {
            try {
                return context.tryCallHandlerSetup(() -> {
                    Flux<String> codeStream = Flux.defer(() -> context
                            .tryCallCallback(() -> aiCodeGeneratorFacade
                                    .generateVueProjectStream(
                                            turn.message(), context.appId(),
                                            turn.firstMessage(), context,
                                            turn.generatorService()))
                            .orElseGet(Flux::empty));
                    Flux<GenerationStreamEvent> business = streamHandlerExecutor
                            .doExecuteVue(codeStream, context);
                    return context.mergeProgress(business);
                }).orElseGet(() -> finalizeCommittedVueFailure(
                        context, new IllegalStateException(
                                "Vue Handler 装配前回调门已经关闭")));
            } catch (RuntimeException exception) {
                return finalizeCommittedVueFailure(context, exception);
            }
        });
    }

    private Mono<CommittedVueTurn> handleVuePreparationFailure(
            VueTurnContext context, Throwable failure) {
        return switch (context.claimPreCommitTermination()) {
            case PRE_COMMIT_WON -> awaitPreCommitCleanup(
                    context, toPreflightException(failure));
            case ALREADY_TERMINATED -> awaitPreCommitCleanup(
                    context,
                    failure instanceof CancellationException
                            ? failure : new CancellationException(
                            "Vue 回合准备阶段已经终止"));
            case POST_COMMIT_REQUIRED -> Mono.just(
                    new CommittedVueTurn(context, null, null,
                            false, failure));
        };
    }

    private Mono<CommittedVueTurn> awaitPreCommitCleanup(
            VueTurnContext context, Throwable failure) {
        return Mono.fromCompletionStage(
                        vueTurnCancellationCoordinator
                                .requestPreCommitCleanup(context))
                .then(Mono.error(failure));
    }

    private void cancelVuePreparation(VueTurnContext context) {
        switch (context.claimPreCommitTermination()) {
            case PRE_COMMIT_WON -> vueTurnCancellationCoordinator
                    .requestPreCommitCleanup(context);
            case ALREADY_TERMINATED -> {
                // 另一个前置终止分支已经负责清理。
            }
            case POST_COMMIT_REQUIRED -> {
                // SSE 心跳扇出在终态下发后会取消内部订阅，此时回合已经有
                // 终态赢家，不能再把协议内部取消误判为浏览器断连。
                if (context.terminalWinner().isEmpty()) {
                    vueTurnCancellationCoordinator.requestCancellation(
                            context, () -> "",
                            () -> VueTurnMemoryProjection.project(
                                    List.of(),
                                    VueTurnOutcome.TurnOutcomeType.CANCELLED));
                }
            }
        }
    }

    private GenerationPreflightException toPreflightException(
            Throwable failure) {
        if (failure instanceof GenerationPreflightException preflight) {
            return preflight;
        }
        if (failure instanceof AppOperationLeaseManager
                .ActiveAppOperationException active) {
            recordOperation(AppOperationLeaseManager.AppOperationType.GENERATE,
                    AppLifecycleMetricsCollector.OperationResult.REJECTED,
                    active.activeOperation());
            return GenerationPreflightException.business(
                    ErrorCode.OPERATION_ERROR.getCode(),
                    "应用正在执行其他操作，请稍后再生成", active);
        }
        if (failure instanceof BusinessException business) {
            return GenerationPreflightException.business(
                    business.getCode(), business.getMessage(), business);
        }
        if (failure instanceof VueTurnModeRoutingException routingFailure) {
            return GenerationPreflightException.system(
                    "系统内部错误，请稍后尝试。", routingFailure);
        }
        return GenerationPreflightException.system(failure);
    }

    private Flux<GenerationStreamEvent> finalizeCommittedVueFailure(
            VueTurnContext context, Throwable failure) {
        if (!context.tryStartFinalization(
                VueTurnContext.TerminalTrigger.FAILED)) {
            return Flux.empty();
        }
        try {
            context.sealSafeBeforeHandler();
            context.sealRegisteredOutputSafety();
            VueTurnFinalizer.FinalizationResult result = vueTurnFinalizer
                    .finalizeOnce(context, new VueTurnOutcome(
                            context.phase(),
                            VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                            VueTurnFinalizer.SYSTEM_ERROR_MESSAGE,
                            VueTurnMemoryProjection.project(
                                    List.of(),
                                    VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR),
                            false, VueTurnFinalizer.SYSTEM_ERROR_MESSAGE));
            return Flux.just(GenerationStreamEvent.turnOutcome(
                    result.outcome()));
        } catch (RuntimeException finalizationFailure) {
            log.error("Vue 提交后启动失败且终态收尾异常,appId={},turnId={}",
                    context.appId(), context.turnId(), finalizationFailure);
            return Flux.error(finalizationFailure);
        }
    }

    private record CommittedVueTurn(
            VueTurnContext context,
            AiCodeGeneratorService generatorService,
            String message,
            boolean firstMessage,
            Throwable startupFailure) {
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null
                        || loginUser.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App app = getById(appId);
        ThrowUtils.throwIf(app == null,
                ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!loginUser.getId().equals(app.getUserId())) {
            throw new BusinessException(
                    ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(
                app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null,
                ErrorCode.PARAMS_ERROR, "代码生成类型无效");
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }

        String ownerToken = "deploy-" + UUID.randomUUID();
        try (AppOperationLeaseManager.AppOperationLease ignored =
                     recordAcquiredDeployLease(appId, ownerToken)) {
            return deployWithinLease(
                    app, codeGenType, deployKey, ownerToken);
        }
    }

    private String deployWithinLease(
            App app,
            CodeGenTypeEnum codeGenType,
            String deployKey,
            String ownerToken) {
        Path sourceDirectory = appStoragePathResolver.resolveSourceDirectory(app);
        Path deployDirectory = appStoragePathResolver.resolveDeployDirectory(
                app, deployKey);
        requireDirectory(sourceDirectory, "代码生成目录不存在");
        Path contentDirectory = codeGenType == CodeGenTypeEnum.VUE_PROJECT
                ? buildVueDeployment(app.getId(), sourceDirectory, ownerToken)
                : sourceDirectory;
        appDeploymentFileService.copyDirectory(
                contentDirectory, deployDirectory);

        App updateApp = new App();
        updateApp.setDeployKey(deployKey);
        updateApp.setId(app.getId());
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updated = updateById(updateApp);
        ThrowUtils.throwIf(!updated,
                ErrorCode.OPERATION_ERROR, "更新部署失败");
        String formatUrl = appDeployUrlBuilder.buildUrl(deployKey);
        tryGenerateAppScreenshot(app.getId(), formatUrl);
        return formatUrl;
    }

    private void tryGenerateAppScreenshot(Long appId, String appUrl) {
        try {
            generateAppScreenshotAsync(appId, appUrl);
        } catch (RuntimeException exception) {
            log.warn("应用部署成功，但封面截图生成失败，保留部署结果: appId={}, appUrl={}",
                    appId, appUrl, exception);
        }
    }

    private Path buildVueDeployment(
            long appId, Path sourceDirectory, String ownerToken) {
        BuildResult result;
        try (BuildLogSink logSink = new BuildLogSink(
                appId, ownerToken, 1, BuildStage.VALIDATION)) {
            BuildExecutionContext context = new BuildExecutionContext(
                    appId,
                    ownerToken,
                    1,
                    new BuildCancellationSignal(),
                    logSink);
            result = vueProjectBuilder.buildProjectDetailed(
                    sourceDirectory, context);
        }
        ThrowUtils.throwIf(!result.success(),
                ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请稍后重试");
        Path distDirectory = sourceDirectory.resolve("dist");
        requireDirectory(distDirectory, "Vue 项目构建失败，请稍后重试");
        return distDirectory;
    }

    private void requireDirectory(Path directory, String message) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, message);
        }
    }

    private AppOperationLeaseManager.AppOperationLease acquireDeployLease(
            long appId, String ownerToken) {
        try {
            return appOperationLeaseManager.acquire(
                    appId,
                    AppOperationLeaseManager.AppOperationType.DEPLOY,
                    ownerToken);
        } catch (AppOperationLeaseManager.ActiveAppOperationException exception) {
            recordOperation(AppOperationLeaseManager.AppOperationType.DEPLOY,
                    AppLifecycleMetricsCollector.OperationResult.REJECTED,
                    exception.activeOperation());
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "项目正在生成或修复，请稍后再部署");
        }
    }

    private AppOperationLeaseManager.AppOperationLease recordAcquiredDeployLease(
            long appId, String ownerToken) {
        AppOperationLeaseManager.AppOperationLease lease =
                acquireDeployLease(appId, ownerToken);
        recordOperation(AppOperationLeaseManager.AppOperationType.DEPLOY,
                AppLifecycleMetricsCollector.OperationResult.ACQUIRED, null);
        return lease;
    }

    private void recordOperation(
            AppOperationLeaseManager.AppOperationType operation,
            AppLifecycleMetricsCollector.OperationResult result,
            AppOperationLeaseManager.AppOperationType conflictWith) {
        if (appLifecycleMetricsCollector != null) {
            appLifecycleMetricsCollector.recordOperation(operation, result, conflictWith);
        }
    }

    private AppOperationLeaseManager.AppOperationLease acquireDownloadLease(
            long appId, String ownerToken) {
        try {
            AppOperationLeaseManager.AppOperationLease lease = appOperationLeaseManager.acquire(
                    appId,
                    AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                    ownerToken);
            recordOperation(AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                    AppLifecycleMetricsCollector.OperationResult.ACQUIRED, null);
            return lease;
        } catch (AppOperationLeaseManager.ActiveAppOperationException exception) {
            recordOperation(AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                    AppLifecycleMetricsCollector.OperationResult.REJECTED,
                    exception.activeOperation());
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用正在生成中，暂时无法下载");
        }
    }

    @Override
    public void downloadApp(
            Long appId, User loginUser, HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null
                        || loginUser.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        ThrowUtils.throwIf(response == null,
                ErrorCode.PARAMS_ERROR, "响应对象不能为空");
        App app = getById(appId);
        ThrowUtils.throwIf(app == null,
                ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!loginUser.getId().equals(app.getUserId())) {
            throw new BusinessException(
                    ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(
                app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null,
                ErrorCode.PARAMS_ERROR, "代码生成类型无效");

        String ownerToken = "download-" + UUID.randomUUID();
        try (AppOperationLeaseManager.AppOperationLease ignored =
                     acquireDownloadLease(appId, ownerToken)) {
            var sourceDirectory = appStoragePathResolver
                    .resolveSourceDirectory(app);
            projectDownloadService.downloadProjectAsZip(
                    sourceDirectory, String.valueOf(appId), response);
        }
    }


    @Override
    public boolean deleteApp(Long appId, User operator) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(operator == null || operator.getId() == null
                        || operator.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App app = getById(appId);
        ThrowUtils.throwIf(app == null,
                ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean admin = com.lyw.appgeneration.constants.UserConstant.ADMIN_ROLE
                .equals(operator.getUserRole());
        if (!operator.getId().equals(app.getUserId()) && !admin) {
            throw new BusinessException(
                    ErrorCode.NO_AUTH_ERROR, "无权限删除该应用");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(
                app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null,
                ErrorCode.PARAMS_ERROR, "代码生成类型无效");

        String ownerToken = "delete-" + UUID.randomUUID();
        try (AppOperationLeaseManager.AppOperationLease ignored =
                     acquireDeleteLease(appId, ownerToken)) {
            return deleteWithinLease(appId, app.getUserId(), codeGenType);
        }
    }

    private boolean deleteWithinLease(
            long appId, long userId, CodeGenTypeEnum codeGenType) {
        AppDataLifecycleFence.DeletePermit deletePermit =
                appDataLifecycleFence.beginDelete(appId, deleteWaitTimeout);
        if (deletePermit == null) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用数据正在写入，暂时无法删除");
        }
        try (deletePermit) {
            App frozenApp = getById(appId);
            ThrowUtils.throwIf(frozenApp == null,
                    ErrorCode.NOT_FOUND_ERROR, "应用不存在");
            if (!userIdEquals(userId, frozenApp.getUserId())
                    || !codeGenType.getValue().equals(frozenApp.getCodeGenType())) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR, "应用信息已变化，请重试删除");
            }
            AppStoragePathResolver.FrozenAppPaths paths =
                    appStoragePathResolver.resolveForDeletion(frozenApp);
            appDeletionFileService.delete(paths);
            appDeletionPersistenceService.deleteAppData(appId);
            deletePermit.commitTombstone();
            invalidateDeletedAppCaches(appId, userId, codeGenType);
            return true;
        }
    }

    private boolean userIdEquals(long expected, Long actual) {
        return actual != null && expected == actual;
    }

    private AppOperationLeaseManager.AppOperationLease acquireDeleteLease(
            long appId, String ownerToken) {
        try {
            AppOperationLeaseManager.AppOperationLease lease =
                    appOperationLeaseManager.cancelAndAcquireDelete(
                    appId, ownerToken, deleteWaitTimeout);
            recordOperation(AppOperationLeaseManager.AppOperationType.DELETE,
                    AppLifecycleMetricsCollector.OperationResult.ACQUIRED, null);
            return lease;
        } catch (AppOperationLeaseManager.ActiveAppOperationException exception) {
            recordOperation(AppOperationLeaseManager.AppOperationType.DELETE,
                    AppLifecycleMetricsCollector.OperationResult.REJECTED,
                    exception.activeOperation());
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用正在部署或下载，暂时无法删除");
        } catch (AppOperationLeaseManager.OperationQuiescenceTimeoutException exception) {
            recordOperation(AppOperationLeaseManager.AppOperationType.DELETE,
                    AppLifecycleMetricsCollector.OperationResult.REJECTED,
                    AppOperationLeaseManager.AppOperationType.GENERATE);
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用生成正在结束，暂时无法删除");
        }
    }

    private void invalidateDeletedAppCaches(
            long appId, long userId, CodeGenTypeEnum codeGenType) {
        retryCacheInvalidation(
                "L0/Caffeine",
                appId,
                () -> aiGeneratorServiceFactory.invalidateAndClearMemory(
                        appId, codeGenType));
        retryCacheInvalidation(
                "L1",
                appId,
                () -> memorySummaryService.invalidateCache(appId));
        retryCacheInvalidation(
                "L2",
                appId,
                () -> userMemoryService.invalidateCaches(appId, userId));
    }

    private void retryCacheInvalidation(
            String target,
            long appId,
            java.util.function.Supplier<MemoryCacheInvalidationResult> action) {
        MemoryCacheInvalidationResult lastResult = null;
        for (int attempt = 1; attempt <= DELETE_CACHE_MAX_ATTEMPTS; attempt++) {
            try {
                lastResult = action.get();
                if (lastResult != null && lastResult.failedTargets().isEmpty()) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastResult = MemoryCacheInvalidationResult.failure(target, exception);
            }
        }
        log.error("应用删除已提交，但缓存清理最终失败: appId={}, target={}, failures={}",
                appId, target,
                lastResult == null ? Set.of(target) : lastResult.failedTargets());
    }

    @Override
    public boolean removeById(java.io.Serializable id) {
        throw new BusinessException(
                ErrorCode.FORBIDDEN_ERROR, "请使用受控应用删除入口");
    }

    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        //调用截图服务生成截图并且上传
        String screenshotUrl = StrUtil.trim(screenshotService.generateAndUploadScreenshot(appUrl));
        screenshotUrl = StrUtil.removeSuffix(screenshotUrl, "/");
        ThrowUtils.throwIf(StrUtil.isBlank(screenshotUrl), ErrorCode.OPERATION_ERROR, "截图地址为空，更新应用封面失败");
        //更新应用封面字段
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCover(screenshotUrl);
        boolean result = updateById(updateApp);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用封面失败");
    }

    @Override
    public Long addApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 根据 AI 选择代码生成类型
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectGenType = routingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectGenType.getValue());
        // 插入数据库
        boolean result = save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        return app.getId();
    }
}
