package com.lyw.appgeneration.core;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.VueEvaluationCodeGeneratorService;
import com.lyw.appgeneration.ai.VueToolNames;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.memory.CanonicalUserMessageScope;
import com.lyw.appgeneration.ai.memory.SyntheticMemoryMessageProtocol;
import com.lyw.appgeneration.ai.skill.SkillCatalog;
import com.lyw.appgeneration.ai.model.HtmlCodeResult;
import com.lyw.appgeneration.ai.model.MultiFileCodeResult;
import com.lyw.appgeneration.ai.model.message.AiResponseMessage;
import com.lyw.appgeneration.ai.model.message.IncompleteToolChainRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRecoveryMessage;
import com.lyw.appgeneration.ai.model.message.InternalOutputRollbackMessage;
import com.lyw.appgeneration.ai.model.message.ToolArgumentDeltaMessage;
import com.lyw.appgeneration.ai.model.message.ToolArgumentMessage;
import com.lyw.appgeneration.ai.model.message.ToolExecutedMessage;
import com.lyw.appgeneration.ai.model.message.ToolRequestMessage;
import com.lyw.appgeneration.ai.parser.ToolRequestStreamParser;
import com.lyw.appgeneration.ai.tools.FileToolExecutionScopeManager;
import com.lyw.appgeneration.ai.tools.FileToolBudgetGuard;
import com.lyw.appgeneration.ai.tools.ToolStreamingSpec;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.core.parser.CodeParserExecutor;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.SimpleGenerationTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.saver.CodeFileSaverExecutor;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.RagPromptAssembler;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import com.lyw.appgeneration.service.rag.model.VueRagContext;
import com.lyw.appgeneration.service.rag.monitor.VueRagLogSanitizer;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.ModelRequestGate;
import dev.langchain4j.service.IncompleteToolChainRecoveryPolicy;
import dev.langchain4j.service.InternalOutputProtocolException;
import dev.langchain4j.service.InternalOutputRecoveryPolicy;
import dev.langchain4j.service.GenerationStreamSignal;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.ToolExecutionGuard;
import dev.langchain4j.service.ToolLoopTerminationProtocol;
import dev.langchain4j.service.ToolProtocolRecoveryPolicy;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * AI 代码生成门面
 *
 * @author lyw
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AppDataLifecycleFence appDataLifecycleFence;

    private static final Duration EVALUATION_DRAIN_TIMEOUT = Duration.ofMillis(100);

    @Resource
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    @Resource
    private ImageCollectionService imageCollectionService;

    @Resource
    private RagRetrievalService ragRetrievalService;

    @Resource
    private RagPromptAssembler ragPromptAssembler;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private FileToolExecutionScopeManager fileToolExecutionScopeManager;

    @Resource
    private ModelRequestGate modelRequestGate;

    private final SkillCatalog skillCatalog = new SkillCatalog();

    /**
     * 生成并保存代码
     * @param userMessage
     * @param bizType
     * @return
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum bizType, long appId) {
        if (bizType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成的类型不能为空");
        }
        // 根据ID 获取AI代码生成服务
        AiCodeGeneratorService aiCodeGeneratorService = aiGeneratorServiceFactory.getAiCodeGeneratorService(appId, bizType);
        // RAG 增强:召回相关模板并前置到用户消息(失败自动降级,不影响主生成)
        String augmentedMessage = ragAugment(userMessage, bizType);
        return switch (bizType) {
           case HTML -> {
               HtmlCodeResult htmlCodeResult = aiCodeGeneratorService.generateHtmlCode(augmentedMessage);
               yield  CodeFileSaverExecutor.executeSaver(htmlCodeResult, CodeGenTypeEnum.HTML, appId);
           }
           case MULTI_FILE -> {
               MultiFileCodeResult multiFileCodeResult = aiCodeGeneratorService.generateMultiFileCode(augmentedMessage);
               yield  CodeFileSaverExecutor.executeSaver(multiFileCodeResult, CodeGenTypeEnum.MULTI_FILE, appId);
           }
            default -> {
                String errorMsg = "不支持的生成类型: " + bizType.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMsg);
            }
        };
    }

    /** 普通在线回合专用入口，文件写入受精确租约和删除栅栏共同保护。 */
    public Flux<String> generateAndSaveCodeStream(
            String userMessage, CodeGenTypeEnum codeGenTypeEnum, long appId,
            boolean isFirstMessage, SimpleGenerationTurnContext context) {
        AiCodeGeneratorService generatorService = prepareSimpleGenerator(
                appId, codeGenTypeEnum);
        return generateAndSaveCodeStream(
                userMessage, codeGenTypeEnum, appId, isFirstMessage,
                context, generatorService);
    }

    /** 只完成普通生成服务的缓存命中或 MySQL 冷重建，不启动本轮模型。 */
    public AiCodeGeneratorService prepareSimpleGenerator(
            long appId, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null
                || codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            throw new IllegalArgumentException(
                    "普通生成入口只支持 HTML 和 MULTI_FILE");
        }
        return aiGeneratorServiceFactory.getAiCodeGeneratorService(
                appId, codeGenTypeEnum);
    }

    /** User 已稳定持久化后，使用预先重建的普通生成服务启动本轮模型。 */
    public Flux<String> generateAndSaveCodeStream(
            String userMessage, CodeGenTypeEnum codeGenTypeEnum, long appId,
            boolean isFirstMessage, SimpleGenerationTurnContext context,
            AiCodeGeneratorService generatorService) {
        Objects.requireNonNull(context, "普通生成回合上下文不能为空");
        Objects.requireNonNull(generatorService, "普通生成服务不能为空");
        if (context.appId() != appId) {
            throw new IllegalArgumentException("普通生成回合上下文与应用不匹配");
        }
        if (codeGenTypeEnum == null || codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            throw new IllegalArgumentException("普通生成入口只支持 HTML 和 MULTI_FILE");
        }
        TokenStream tokenStream = createSimpleCodeStream(
                userMessage, codeGenTypeEnum, isFirstMessage, generatorService);
        tokenStream.internalOutputRecoveryPolicy(
                simpleInternalOutputRecoveryPolicy());
        tokenStream.modelRequestGate(modelRequestGate, context);
        Flux<String> source = processSimpleTokenStream(tokenStream, context);
        return progressCodeStream(source, codeGenTypeEnum, appId, context);
    }

    private TokenStream createSimpleCodeStream(
            String userMessage, CodeGenTypeEnum codeGenTypeEnum,
            boolean isFirstMessage, AiCodeGeneratorService generatorService) {
        boolean shouldRetrieve = shouldRetrieveOnlineRag(isFirstMessage);
        return CanonicalUserMessageScope.call(userMessage, () -> switch (codeGenTypeEnum) {
            case HTML -> {
                String request = shouldRetrieve
                        ? ragAugment(userMessage, CodeGenTypeEnum.HTML)
                        : userMessage;
                yield generatorService.generateHtmlCodeStream(request);
            }
            case MULTI_FILE -> {
                String enhanced = isFirstMessage
                        ? imageCollectionService.enhancePrompt(userMessage)
                        : userMessage;
                String request = shouldRetrieve
                        ? ragAugment(enhanced, CodeGenTypeEnum.MULTI_FILE)
                        : enhanced;
                yield generatorService.generateMultiFileCodeStream(request);
            }
            default -> throw new IllegalArgumentException(
                    "普通生成入口只支持 HTML 和 MULTI_FILE");
        });
    }

    private InternalOutputRecoveryPolicy
            simpleInternalOutputRecoveryPolicy() {
        return new InternalOutputRecoveryPolicy(
                InternalOutputRecoveryPolicy.Mode.FAIL_FAST,
                SyntheticMemoryMessageProtocol.RESERVED_PREFIX,
                Set.of(
                        SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK,
                        SyntheticMemoryMessageProtocol.L1_SUMMARY_ACK,
                        SyntheticMemoryMessageProtocol.L2_PREFERENCE_ACK));
    }

    /**
     * 普通 TokenStream 的可取消 Reactor 适配器。
     *
     * <p>上游 Reactor 适配器不会把订阅取消传播到 {@link TokenStream#cancel()}，
     * 因此在线回合必须在这里显式绑定真实模型取消，并拒绝所有晚到回调。
     */
    private Flux<String> processSimpleTokenStream(
            TokenStream tokenStream, SimpleGenerationTurnContext context) {
        Objects.requireNonNull(tokenStream, "普通生成 TokenStream 不能为空");
        return Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean();
            Runnable cancelModel = () -> {
                if (terminated.compareAndSet(false, true)) {
                    try {
                        tokenStream.cancel();
                    } finally {
                        if (!sink.isCancelled()) {
                            sink.complete();
                        }
                    }
                }
            };
            try {
                sink.onCancel(cancelModel::run);
                tokenStream.onPartialResponse(partial -> {
                            if (!terminated.get() && !sink.isCancelled()) {
                                sink.next(partial);
                            }
                        })
                        .onControlledTermination(ignored -> {
                            finishSimpleControlledTermination(
                                    ignored, terminated, sink);
                        })
                        .onCompleteResponse(ignored -> {
                            if (terminated.compareAndSet(false, true)
                                    && !sink.isCancelled()) {
                                sink.complete();
                            }
                        })
                        .onError(error -> {
                            if (terminated.compareAndSet(false, true)
                                    && !sink.isCancelled()) {
                                sink.error(error);
                            }
                        });
                if (context != null) {
                    context.bindUpstream(cancelModel);
                }
                if (!terminated.get()) {
                    tokenStream.start();
                }
            } catch (RuntimeException exception) {
                if (terminated.compareAndSet(false, true)
                        && !sink.isCancelled()) {
                    sink.error(exception);
                }
            }
        });
    }

    private void finishSimpleControlledTermination(
            ToolLoopTerminationProtocol.ControlledTermination termination,
            AtomicBoolean terminated,
            reactor.core.publisher.FluxSink<String> sink) {
        if (!terminated.compareAndSet(false, true)
                || sink.isCancelled()) {
            return;
        }
        if (termination.reason()
                == ToolLoopTerminationProtocol
                .ControlledTerminationReason.PROTOCOL_ERROR) {
            sink.error(new InternalOutputProtocolException());
        } else {
            sink.complete();
        }
    }

    /**
     * Vue 在线生成专用入口：服务与 TokenStream 在调用时创建，真正模型请求在订阅后启动。
     */
    public Flux<String> generateVueProjectStream(
            String userMessage, long appId, boolean isFirstMessage,
            VueTurnContext turnContext) {
        AiCodeGeneratorService generatorService = prepareVueGenerator(appId);
        return generateVueProjectStream(
                userMessage, appId, isFirstMessage, turnContext, generatorService);
    }

    /** 只完成缓存命中或 MySQL 冷重建，不做图片、RAG、Prompt 或模型调用。 */
    public AiCodeGeneratorService prepareVueGenerator(long appId) {
        return aiGeneratorServiceFactory.getAiCodeGeneratorService(
                appId, CodeGenTypeEnum.VUE_PROJECT);
    }

    /** User 已稳定持久化后，才允许执行增强并创建本轮 TokenStream。 */
    public Flux<String> generateVueProjectStream(
            String userMessage, long appId, boolean isFirstMessage,
            VueTurnContext turnContext, AiCodeGeneratorService generatorService) {
        java.util.Objects.requireNonNull(generatorService, "Vue 生成服务不能为空");
        boolean mutationTurn = turnContext.turnMode()
                == VueTurnMode.MUTATION_REQUIRED;
        String generationRequest = isFirstMessage && mutationTurn
                ? imageCollectionService.enhancePrompt(userMessage) : userMessage;
        if (shouldRetrieveVueOnlineRag(isFirstMessage, mutationTurn)) {
            VueRagContext context = retrieveVueContext(
                    userMessage, ragProperties.getHybrid().isEnabled());
            generationRequest = ragPromptAssembler.assembleVueProject(
                    generationRequest, context);
        }
        String request = generationRequest;
        TokenStream tokenStream = CanonicalUserMessageScope.call(
                userMessage,
                () -> generatorService.generateVueProjectCodeStream(
                        appId, request));
        tokenStream.initialToolChoiceRequired(
                turnContext.requiresInitialToolCall());
        if (mutationTurn) {
            tokenStream.turnTransientMessages(
                    skillCatalog.vueFrontendDesignMessages());
        }
        tokenStream.modelRequestGate(modelRequestGate, turnContext);
        tokenStream.toolProtocolRecoveryPolicy(new ToolProtocolRecoveryPolicy(
                Set.copyOf(VueToolNames.ONLINE),
                phase -> turnContext.tryRunCallback(() ->
                        turnContext.publishToolProtocolRecovery(
                                recoveryMessage(phase)))));
        tokenStream.incompleteToolChainRecoveryPolicy(
                new IncompleteToolChainRecoveryPolicy(
                        turnContext::requiresBuild,
                        () -> incompleteBuildState(turnContext.phase()),
                        phase -> turnContext.tryRunCallback(() ->
                                turnContext.publishIncompleteToolChainRecovery(
                                        incompleteRecoveryMessage(phase)))));
        tokenStream.internalOutputRecoveryPolicy(
                vueInternalOutputRecoveryPolicy());
        return processOnlineTokenStream(tokenStream, turnContext);
    }

    private InternalOutputRecoveryPolicy
            vueInternalOutputRecoveryPolicy() {
        return new InternalOutputRecoveryPolicy(
                InternalOutputRecoveryPolicy.Mode.RECOVER_ONCE,
                SyntheticMemoryMessageProtocol.RESERVED_PREFIX,
                Set.of(
                        SyntheticMemoryMessageProtocol.TRUSTED_TURN_ACK,
                        SyntheticMemoryMessageProtocol.L1_SUMMARY_ACK,
                        SyntheticMemoryMessageProtocol.L2_PREFERENCE_ACK));
    }

    private IncompleteToolChainRecoveryPolicy.BuildState incompleteBuildState(
            VueBuildPhase phase) {
        return switch (phase) {
            case GENERATING -> IncompleteToolChainRecoveryPolicy
                    .BuildState.GENERATING;
            case REPAIRING -> IncompleteToolChainRecoveryPolicy
                    .BuildState.REPAIRING;
            case RETRYING -> IncompleteToolChainRecoveryPolicy
                    .BuildState.RETRYING;
            case FINAL_DIAGNOSIS -> IncompleteToolChainRecoveryPolicy
                    .BuildState.FINAL_DIAGNOSIS;
            case SUCCEEDED -> IncompleteToolChainRecoveryPolicy
                    .BuildState.SUCCEEDED;
            case FAILED -> IncompleteToolChainRecoveryPolicy
                    .BuildState.FAILED;
            case CANCELLED -> IncompleteToolChainRecoveryPolicy
                    .BuildState.CANCELLED;
        };
    }

    private IncompleteToolChainRecoveryMessage incompleteRecoveryMessage(
            IncompleteToolChainRecoveryPolicy.RecoveryPhase phase) {
        return switch (phase) {
            case STARTED -> IncompleteToolChainRecoveryMessage.started();
            case RECOVERED -> IncompleteToolChainRecoveryMessage.recovered();
            case FAILED -> IncompleteToolChainRecoveryMessage.failed();
        };
    }

    private com.lyw.appgeneration.ai.model.message.ToolProtocolRecoveryMessage
            recoveryMessage(ToolProtocolRecoveryPolicy.Phase phase) {
        return switch (phase) {
            case STARTED -> com.lyw.appgeneration.ai.model.message
                    .ToolProtocolRecoveryMessage.started();
            case RECOVERED -> com.lyw.appgeneration.ai.model.message
                    .ToolProtocolRecoveryMessage.recovered();
            case FAILED -> com.lyw.appgeneration.ai.model.message
                    .ToolProtocolRecoveryMessage.failed();
        };
    }

    /**
     * 高成本离线质量门禁的真实 Vue 生成入口。
     *
     * <p>返回的检索上下文与生成流来自同一次准备过程，避免报告通过二次检索猜测选中 ID。
     * 评测固定跳过图片增强，以免引入与生成可构建性无关的额外外部调用。
     *
     * @param userMessage 原始生成需求
     * @param appId 独立评测应用 ID
     * @return 本次真实检索上下文与真实生成流
     */
    public VueProjectGeneration generateVueProjectForEvaluation(String userMessage, long appId) {
        if (!ragProperties.isEnabled() || !ragProperties.getHybrid().isEnabled()) {
            throw new IllegalStateException("真实 Vue 生成评测必须显式开启 RAG 与 hybrid");
        }
        VueRagContext context = retrieveVueContext(userMessage, true);
        String augmentedMessage = ragPromptAssembler.assembleVueProject(userMessage, context);
        Flux<String> stream = Flux.usingWhen(
                Mono.fromSupplier(() -> createEvaluationScopeLease(
                        appId, augmentedMessage)),
                this::processEvaluationTokenStream,
                this::cleanupEvaluationScope,
                (lease, ignored) -> cleanupEvaluationScope(lease),
                this::cleanupEvaluationScope);
        return new VueProjectGeneration(context, stream);
    }

    private EvaluationScopeLease createEvaluationScopeLease(
            long appId, String augmentedMessage) {
        FileToolExecutionScopeManager.FileToolScope scope =
                fileToolExecutionScopeManager.evaluation(
                        appId, UUID.randomUUID().toString(),
                        Set.copyOf(VueToolNames.EVALUATION));
        try {
            VueEvaluationCodeGeneratorService generatorService = aiGeneratorServiceFactory
                    .getVueEvaluationCodeGeneratorService(appId);
            TokenStream tokenStream = generatorService.generate(appId, augmentedMessage);
            ToolExecutionGuard directGuard = ToolExecutionGuard.direct();
            ToolExecutionGuard scopedGuard = (toolName, memoryId, action) -> {
                ToolExecutionGuard.GuardedToolExecution execution = directGuard.execute(
                        toolName, memoryId,
                        () -> fileToolExecutionScopeManager.callInScope(
                                scope, toolName, action));
                if (!"exit".equals(toolName)) {
                    return execution;
                }
                return new ToolExecutionGuard.GuardedToolExecution(
                        execution.toolResult(),
                        evaluationExitTermination(execution.toolResult()));
            };
            tokenStream.toolExecutionGuard(scopedGuard);
            return new EvaluationScopeLease(
                    scope, tokenStream, scope.budgetSession());
        } catch (RuntimeException exception) {
            revokeAndAwaitEvaluation(scope, EVALUATION_DRAIN_TIMEOUT);
            throw exception;
        }
    }

    private Flux<String> processEvaluationTokenStream(EvaluationScopeLease lease) {
        return processTokenStream(
                lease.tokenStream(),
                () -> fileToolExecutionScopeManager.revokeEvaluation(lease.scope()),
                () -> { },
                termination -> termination.reason()
                        == ToolLoopTerminationProtocol.ControlledTerminationReason
                        .EVALUATION_COMPLETED
                        ? null
                        : new EvaluationControlledTerminationException(
                                termination.reason()));
    }

    private Mono<Void> cleanupEvaluationScope(EvaluationScopeLease lease) {
        return Mono.fromRunnable(() -> revokeAndAwaitEvaluation(
                        lease.scope(), EVALUATION_DRAIN_TIMEOUT))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void revokeAndAwaitEvaluation(
            FileToolExecutionScopeManager.FileToolScope scope,
            Duration cleanupBudget) {
        long deadline = System.nanoTime() + cleanupBudget.toNanos();
        try {
            fileToolExecutionScopeManager.revokeEvaluation(scope);
            long remainingNanos = Math.max(0L, deadline - System.nanoTime());
            if (!fileToolExecutionScopeManager.awaitEvaluationQuiescence(
                    scope, Duration.ofNanos(remainingNanos))) {
                log.warn("Vue 评测工具未在撤销期限内静默,appId={},ownerToken={}",
                        scope.appId(), scope.ownerToken());
            }
        } catch (RuntimeException exception) {
            log.warn("Vue 评测作用域清理失败,appId={},ownerToken={}",
                    scope.appId(), scope.ownerToken(), exception);
        }
    }

    private record EvaluationScopeLease(
            FileToolExecutionScopeManager.FileToolScope scope,
            TokenStream tokenStream,
            FileToolBudgetGuard.Session budgetSession) {

        private EvaluationScopeLease {
            Objects.requireNonNull(scope, "评测工具作用域不能为空");
            Objects.requireNonNull(tokenStream, "评测 TokenStream 不能为空");
            Objects.requireNonNull(budgetSession, "评测预算会话不能为空");
        }
    }

    /**
     * RAG 增强:召回相关模板片段并前置到用户消息
     * 失败自动降级为返回原 userMessage(由 RagRetrievalService 保证,不抛异常)
     */
    private String ragAugment(String userMessage, CodeGenTypeEnum type) {
        List<RetrievedSnippet> snippets = ragRetrievalService.retrieve(userMessage, type);
        return ragPromptAssembler.assemble(userMessage, snippets);
    }

    private boolean shouldRetrieveOnlineRag(boolean isFirstMessage) {
        return isFirstMessage && ragProperties.isEnabled();
    }

    private boolean shouldRetrieveVueOnlineRag(
            boolean isFirstMessage, boolean mutationTurn) {
        return isFirstMessage && mutationTurn && ragProperties.isEnabled();
    }

    /**
     * 同一次真实 Vue 生成准备产生的检索上下文与输出流。
     */
    public record VueProjectGeneration(VueRagContext context, Flux<String> stream) {
    }

    private VueRagContext retrieveVueContext(String userMessage, boolean hybridEnabled) {
        try {
            return hybridEnabled
                    ? ragRetrievalService.retrieveVueProject(userMessage)
                    : ragRetrievalService.retrieveVueProjectDenseOnly(userMessage);
        } catch (Exception exception) {
            log.warn("[Vue RAG] 检索异常,使用空上下文继续生成,queryHash={},candidateCount=0",
                    VueRagLogSanitizer.queryHash(userMessage));
            return VueRagContext.unavailable();
        }
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应
     */
    private Flux<String> processOnlineTokenStream(
            TokenStream tokenStream, VueTurnContext context) {
        FileToolExecutionScopeManager.FileToolScope scope =
                fileToolExecutionScopeManager.online(
                        context.lease(), context.turnId(), context.appId(),
                        Set.copyOf(VueToolNames.ONLINE), context.budgetSession());
        ToolExecutionGuard directGuard = ToolExecutionGuard.direct();
        tokenStream.toolExecutionGuard((toolName, memoryId, action) ->
                directGuard.execute(toolName, memoryId,
                        () -> fileToolExecutionScopeManager.callInScope(
                                scope, toolName, action)));
        return Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean();
            SerializedGenerationStreamEmitter emitter =
                    new SerializedGenerationStreamEmitter(
                            new SerializedGenerationStreamEmitter.Target() {
                                @Override
                                public boolean isCancelled() {
                                    return sink.isCancelled();
                                }

                                @Override
                                public void next(String value) {
                                    sink.next(value);
                                }

                                @Override
                                public void complete() {
                                    sink.complete();
                                }

                                @Override
                                public void error(Throwable error) {
                                    sink.error(error);
                                }
                            });
            Runnable cancelModel = () -> {
                if (terminated.compareAndSet(false, true)) {
                    emitter.cancel();
                    tokenStream.cancel();
                }
            };
            context.registerModelCancellation(cancelModel);
            sink.onCancel(cancelModel::run);
            sink.onDispose(() -> {
                if (!sink.isCancelled()) {
                    terminated.compareAndSet(false, true);
                }
            });
            Map<String, ToolRequestStreamParser> parsers = new HashMap<>();
            Set<String> announcedToolIds = new java.util.HashSet<>();
            Set<String> completedToolIds = new java.util.HashSet<>();
            FileToolBudgetGuard.Session budgetSession = scope.budgetSession();
            try {
                tokenStream.onGenerationStreamSignal(signal ->
                                context.tryRunCallback(() -> {
                                    emitter.execute(target ->
                                            handleGenerationSignal(
                                                    signal, target::next,
                                                    tokenStream, budgetSession,
                                                    parsers, announcedToolIds,
                                                    completedToolIds));
                                }))
                        .onControlledTermination(termination ->
                                context.tryRunCallback(() -> {
                                    Throwable error =
                                            onlineControlledTerminationError(
                                                    termination);
                                    Consumer<SerializedGenerationStreamEmitter
                                            .Target> beforeTerminal = ignored -> {
                                                finishParsers(parsers);
                                                context.recordControlledTermination(
                                                        termination);
                                                terminated.set(true);
                                            };
                                    if (error == null) {
                                        emitter.complete(beforeTerminal);
                                    } else {
                                        emitter.error(beforeTerminal, error);
                                    }
                                }))
                        .onCompleteResponse(response -> context.tryRunCallback(() -> {
                            emitter.complete(ignored -> {
                                finishParsers(parsers);
                                terminated.set(true);
                            });
                        }))
                        .onError(error -> context.tryRunCallback(() -> {
                            emitter.error(ignored -> {
                                finishParsers(parsers);
                                terminated.set(true);
                            }, error);
                        }))
                        .start();
            } catch (RuntimeException exception) {
                terminated.set(true);
                emitter.error(exception);
            }
        });
    }

    private void handleGenerationSignal(
            GenerationStreamSignal signal,
            Consumer<String> output,
            TokenStream tokenStream,
            FileToolBudgetGuard.Session budgetSession,
            Map<String, ToolRequestStreamParser> parsers,
            Set<String> announcedToolIds,
            Set<String> completedToolIds) {
        switch (signal) {
            case GenerationStreamSignal.AiText text -> output.accept(
                    JSONUtil.toJsonStr(new AiResponseMessage(
                            text.generation(), text.text())));
            case GenerationStreamSignal.PartialToolRequest partial ->
                    handlePartialToolRequest(
                            partial, output, tokenStream, budgetSession,
                            parsers, announcedToolIds, completedToolIds);
            case GenerationStreamSignal.CompleteToolRequest complete -> {
                var request = complete.request();
                if (announcedToolIds.add(request.id())) {
                    output.accept(JSONUtil.toJsonStr(
                            new ToolRequestMessage(
                                    complete.generation(), request.id(),
                                    request.name(), null)));
                }
            }
            case GenerationStreamSignal.ToolExecuted executed ->
                    handleToolExecuted(
                            executed, output, tokenStream, budgetSession,
                            parsers, completedToolIds);
            case GenerationStreamSignal.Rollback rollback -> {
                rollback.provisionalToolRequestIds().forEach(toolId -> {
                    parsers.remove(toolId);
                    announcedToolIds.remove(toolId);
                });
                output.accept(JSONUtil.toJsonStr(
                        new InternalOutputRollbackMessage(rollback)));
            }
            case GenerationStreamSignal.Recovery recovery -> output.accept(
                    JSONUtil.toJsonStr(
                            new InternalOutputRecoveryMessage(recovery)));
        }
    }

    private void handlePartialToolRequest(
            GenerationStreamSignal.PartialToolRequest partial,
            Consumer<String> output,
            TokenStream tokenStream,
            FileToolBudgetGuard.Session budgetSession,
            Map<String, ToolRequestStreamParser> parsers,
            Set<String> announcedToolIds,
            Set<String> completedToolIds) {
        var request = partial.request();
        ToolRequestStreamParser parser = parsers.get(request.id());
        if (parser == null) {
            if (announcedToolIds.add(request.id())) {
                output.accept(JSONUtil.toJsonStr(new ToolRequestMessage(
                        partial.generation(), request.id(),
                        request.name(), null)));
            }
            parser = new ToolRequestStreamParser(request.name(), event -> {
                switch (event.type) {
                    case DELTA -> emitBudgetedArgumentDelta(
                            output, tokenStream, budgetSession,
                            completedToolIds, partial.generation(),
                            request.id(), request.name(),
                            event.key, event.payload);
                    case VALUE_READY -> {
                        if (!ToolStreamingSpec.isStreaming(
                                request.name(), event.key)
                                && !completedToolIds.contains(request.id())) {
                            output.accept(JSONUtil.toJsonStr(
                                    new ToolArgumentMessage(
                                            partial.generation(), request.id(),
                                            request.name(), event.key,
                                            event.payload)));
                        }
                    }
                    case KEY_READY -> { }
                }
            });
            parsers.put(request.id(), parser);
        }
        parser.feed(request.arguments());
    }

    private void handleToolExecuted(
            GenerationStreamSignal.ToolExecuted executed,
            Consumer<String> output,
            TokenStream tokenStream,
            FileToolBudgetGuard.Session budgetSession,
            Map<String, ToolRequestStreamParser> parsers,
            Set<String> completedToolIds) {
        ToolExecution execution = executed.execution();
        ToolRequestStreamParser parser = parsers.remove(
                execution.request().id());
        if (parser != null) {
            parser.finish();
        }
        ToolLoopTerminationProtocol.ToolLoopTermination parsed =
                ToolLoopTerminationProtocol.parseTrusted(
                        execution.request().name(), execution.result());
        if (parsed.reason() == ToolLoopTerminationProtocol
                .ControlledTerminationReason.RESOURCE_LIMIT_EXCEEDED) {
            emitResourceLimitExecution(
                    output, tokenStream, budgetSession, completedToolIds,
                    executed.generation(), execution);
            return;
        }
        if (completedToolIds.add(execution.request().id())) {
            output.accept(JSONUtil.toJsonStr(new ToolExecutedMessage(
                    executed.generation(), execution)));
        }
    }

    private void finishParsers(
            Map<String, ToolRequestStreamParser> parsers) {
        parsers.values().forEach(ToolRequestStreamParser::finish);
        parsers.clear();
    }

    private void emitResourceLimitExecution(
            Consumer<String> output,
            TokenStream tokenStream,
            FileToolBudgetGuard.Session budgetSession,
            Set<String> completedToolIds,
            long generation,
            ToolExecution execution) {
        String toolId = execution.request().id();
        if (completedToolIds.add(toolId)) {
            var redactedRequest = dev.langchain4j.agent.tool.ToolExecutionRequest
                    .builder().id(toolId).name(execution.request().name())
                    .arguments("{}").build();
            output.accept(JSONUtil.toJsonStr(new ToolExecutedMessage(
                    generation, ToolExecution.builder().request(redactedRequest)
                            .result(execution.result()).build())));
        }
        if (budgetSession.claimResourceLimit()) {
            tokenStream.requestControlledTermination(
                    new ToolLoopTerminationProtocol.ControlledTermination(
                            ToolLoopTerminationProtocol.ControlledTerminationReason
                                    .RESOURCE_LIMIT_EXCEEDED,
                            null));
        }
    }

    private void emitBudgetedArgumentDelta(
            Consumer<String> output,
            TokenStream tokenStream,
            FileToolBudgetGuard.Session budgetSession,
            Set<String> completedToolIds,
            long generation,
            String toolId, String toolName, String key, String delta) {
        if (!ToolStreamingSpec.isStreaming(toolName, key)
                || completedToolIds.contains(toolId)) {
            return;
        }
        FileToolBudgetGuard.ArgumentDecision decision =
                budgetSession.acceptArgumentDelta(toolId, key, delta);
        if (!decision.acceptedPrefix().isEmpty()) {
            output.accept(JSONUtil.toJsonStr(new ToolArgumentDeltaMessage(
                    generation, toolId, toolName, key,
                    decision.acceptedPrefix())));
        }
        if (!decision.resourceLimitExceeded()
                || !budgetSession.claimResourceLimit()) {
            return;
        }
        String rejectedResult = resourceLimitToolResult(toolName);
        if (completedToolIds.add(toolId)) {
            output.accept(JSONUtil.toJsonStr(new ToolExecutedMessage(
                    generation, ToolExecution.builder()
                            .request(dev.langchain4j.agent.tool.ToolExecutionRequest
                                    .builder().id(toolId).name(toolName)
                                    .arguments("{}").build())
                            .result(rejectedResult).build())));
        }
        tokenStream.requestControlledTermination(
                new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .RESOURCE_LIMIT_EXCEEDED,
                        null));
    }

    private String resourceLimitToolResult(String toolName) {
        cn.hutool.json.JSONObject json = new cn.hutool.json.JSONObject(
                cn.hutool.json.JSONConfig.create().setIgnoreNullValue(false));
        json.set("protocol", "file-tool/v1");
        json.set("operation", toolName);
        json.set("status", "REJECTED");
        json.set("relativePath", null);
        json.set("changed", false);
        json.set("message", "工具内容超过本轮资源上限");
        json.set("failureReason", "RESOURCE_LIMIT_EXCEEDED");
        json.set("content", null);
        return JSONUtil.toJsonStr(json);
    }

    private Flux<String> processTokenStream(
            TokenStream tokenStream,
            Runnable revoke,
            Runnable cleanup,
            Function<ToolLoopTerminationProtocol.ControlledTermination, Throwable>
                    controlledTerminationError) {
        return Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean();
            Runnable finish = () -> {
                if (terminated.compareAndSet(false, true)) {
                    cleanup.run();
                }
            };
            Runnable cancelAndFinish = () -> {
                if (terminated.compareAndSet(false, true)) {
                    revoke.run();
                    try {
                        tokenStream.cancel();
                    } finally {
                        cleanup.run();
                    }
                }
            };
            sink.onCancel(cancelAndFinish::run);
            sink.onDispose(cancelAndFinish::run);

            // 每个 tool call id 维护独立的字符级状态机。同一 id 的多次 partial 喂入同一 parser。
            Map<String, ToolRequestStreamParser> parsers = new HashMap<>();
            try {
                tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        String toolId = toolExecutionRequest.id();
                        String toolName = toolExecutionRequest.name();
                        // 首次出现该 id:发一条 TOOL_REQUEST(兼容现有下游去重逻辑),并创建 parser
                        ToolRequestStreamParser parser = parsers.get(toolId);
                        if (parser == null) {
                            sink.next(JSONUtil.toJsonStr(new ToolRequestMessage(toolExecutionRequest)));
                            parser = new ToolRequestStreamParser(toolName, evt -> {
                                switch (evt.type) {
                                    case DELTA -> sink.next(JSONUtil.toJsonStr(
                                            new ToolArgumentDeltaMessage(toolId, toolName, evt.key, evt.payload)));
                                    case VALUE_READY -> sink.next(JSONUtil.toJsonStr(
                                            new ToolArgumentMessage(toolId, toolName, evt.key, evt.payload)));
                                    case KEY_READY -> { /* 不单独下发,KEY 信息会在紧随的 DELTA/VALUE 中携带 */ }
                                }
                            });
                            parsers.put(toolId, parser);
                        }
                        parser.feed(toolExecutionRequest.arguments());
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        // 某工具调用真正执行前,先 finish 对应 parser(确保字面量 value 也能 flush)
                        ToolRequestStreamParser parser = parsers.remove(toolExecution.request().id());
                        if (parser != null) parser.finish();
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onControlledTermination(termination -> {
                        finish.run();
                        Throwable error = controlledTerminationError.apply(termination);
                        if (error == null) {
                            sink.complete();
                        } else {
                            sink.error(error);
                        }
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        parsers.values().forEach(ToolRequestStreamParser::finish);
                        finish.run();
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        finish.run();
                        sink.error(error);
                    })
                    .start();
            } catch (RuntimeException exception) {
                finish.run();
                sink.error(exception);
            }
        });
    }

    private ToolLoopTerminationProtocol.ControlledTermination evaluationExitTermination(
            String toolResult) {
        try {
            cn.hutool.json.JSONObject result = JSONUtil.parseObj(toolResult);
            if ("file-tool/v1".equals(result.getStr("protocol"))
                    && "exit".equals(result.getStr("operation"))
                    && "APPLIED".equals(result.getStr("status"))
                    && !Boolean.TRUE.equals(result.getBool("changed"))) {
                return new ToolLoopTerminationProtocol.ControlledTermination(
                        ToolLoopTerminationProtocol.ControlledTerminationReason
                                .EVALUATION_COMPLETED,
                        null);
            }
        } catch (RuntimeException ignored) {
            // 非法或伪造的 exit 结果按普通工具结果处理，不能结束评测工具循环。
        }
        return null;
    }

    private Throwable onlineControlledTerminationError(
            ToolLoopTerminationProtocol.ControlledTermination termination) {
        return switch (termination.reason()) {
            case BUILD_SUCCEEDED, BUILD_FAILED -> null;
            case CANCELLED, PROTOCOL_ERROR, LOOP_LIMIT_EXCEEDED,
                    REPEATED_READ_LOOP, INCOMPLETE_TOOL_CHAIN,
                    RESOURCE_LIMIT_EXCEEDED,
                    EVALUATION_COMPLETED -> new OnlineControlledTerminationException(
                    termination.reason());
        };
    }

    public static final class OnlineControlledTerminationException
            extends IllegalStateException {

        private final ToolLoopTerminationProtocol.ControlledTerminationReason reason;

        public OnlineControlledTerminationException(
                ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
            super("Vue 在线生成被受控终止: " + reason);
            this.reason = reason;
        }

        public ToolLoopTerminationProtocol.ControlledTerminationReason reason() {
            return reason;
        }
    }

    private static final class EvaluationControlledTerminationException
            extends IllegalStateException {

        private EvaluationControlledTerminationException(
                ToolLoopTerminationProtocol.ControlledTerminationReason reason) {
            super("Vue 评测生成被受控终止: " + reason);
        }
    }


    /**
     * 通用流式代码处理方法
     * @param codeStream
     * @param codeGenTypeEnum
     * @return
     */
    private Flux<String> progressCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum, long appId) {
        // 当流式返回生成代码完成后，再保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            // 流式返回完成后保存代码
            try {
                String completeHtmlCode = codeBuilder.toString();
                //执行器执行解析代码
                Object parseResult = CodeParserExecutor.executeParse(completeHtmlCode, codeGenTypeEnum);
                //执行器保存代码
                File savedDir = CodeFileSaverExecutor.executeSaver(parseResult, codeGenTypeEnum, appId);
                log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());
            }
        });

    }

    private Flux<String> progressCodeStream(
            Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum,
            long appId, SimpleGenerationTurnContext context) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .doOnNext(codeBuilder::append)
                .concatWith(Flux.defer(() -> {
                    saveSimpleCode(codeBuilder.toString(), codeGenTypeEnum,
                            appId, context);
                    return Flux.empty();
                }));
    }

    private void saveSimpleCode(
            String code, CodeGenTypeEnum codeGenTypeEnum, long appId,
            SimpleGenerationTurnContext context) {
        if (context.isCancelled()) {
            return;
        }
        if (SyntheticMemoryMessageProtocol.containsReservedMarker(code)) {
            throw new InternalOutputProtocolException();
        }
        AppDataLifecycleFence.WriterPermit writerPermit =
                appDataLifecycleFence.tryAcquireWriter(appId);
        if (writerPermit == null) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "应用已进入删除流程，无法保存生成文件");
        }
        try (writerPermit) {
            if (context.isCancelled()) {
                return;
            }
            Object parsed = CodeParserExecutor.executeParse(code, codeGenTypeEnum);
            File savedDirectory = CodeFileSaverExecutor.executeSaver(
                    parsed, codeGenTypeEnum, appId);
            log.info("普通生成文件保存成功,appId={},type={},path={}",
                    appId, codeGenTypeEnum, savedDirectory.getAbsolutePath());
        }
    }

}
