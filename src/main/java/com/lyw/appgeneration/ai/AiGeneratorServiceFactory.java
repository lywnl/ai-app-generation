package com.lyw.appgeneration.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lyw.appgeneration.ai.guardrail.PromptSafetyInputGuardrail;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.LayeredChatMemory;
import com.lyw.appgeneration.ai.memory.TokenAwareChatMemory;
import com.lyw.appgeneration.ai.tools.*;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.manger.ToolManager;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.utils.SpringContextUtil;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * AI 生成服务工厂
 *
 * @author lyw
 */
@Configuration
@Slf4j
public class AiGeneratorServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private MemorySummaryService memorySummaryService;

    @Resource
    private UserMemoryService userMemoryService;

    @Resource
    private ChatTokenEstimator chatTokenEstimator;

    @Resource
    private MemoryTokenProperties memoryTokenProperties;

    @Resource
    private ToolManager toolManager;

    @Resource
    private VueBuildRepairMetricsCollector vueBuildRepairMetricsCollector;

    /**
     * AI 服务实例缓存
     * 缓存策略：
     * - 最大缓存 1000 个实例
     * - 写入后 30 分钟过期
     * - 访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，appId: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
    }

    public boolean isVueServiceCached(long appId) {
        return serviceCache.getIfPresent(
                buildCacheKey(appId, CodeGenTypeEnum.VUE_PROJECT)) != null;
    }

    /** 终态 L0 不稳定时失效 Vue 代理，下一轮强制走 MySQL 冷启动重建。 */
    public void invalidateVueService(long appId) {
        serviceCache.invalidate(buildCacheKey(appId, CodeGenTypeEnum.VUE_PROJECT));
    }

    /** 清空不可信 L0，并让下一次获取严格从 MySQL 冷启动。 */
    public void prepareVueColdRebuild(long appId) {
        invalidateVueService(appId);
        redisChatMemoryStore.deleteMessages(appId);
    }

    public MemoryCacheInvalidationResult invalidateAndClearMemory(
            long appId, CodeGenTypeEnum codeGenType) {
        if (appId <= 0) {
            throw new IllegalArgumentException("应用 ID 必须为正数");
        }
        if (codeGenType == null) {
            throw new IllegalArgumentException("代码生成类型不能为空");
        }
        MemoryCacheInvalidationResult result =
                MemoryCacheInvalidationResult.success();
        try {
            serviceCache.invalidate(buildCacheKey(appId, codeGenType));
        } catch (Exception exception) {
            log.warn("清理 AI 服务进程缓存失败 appId={} codeGenType={}: {}",
                    appId, codeGenType.getValue(), exception.getMessage());
            result = result.merge(MemoryCacheInvalidationResult.failure(
                    "L0_SERVICE_CAFFEINE", exception));
        }
        try {
            redisChatMemoryStore.deleteMessages(appId);
        } catch (Exception exception) {
            log.warn("清理 L0 Redis 记忆失败 appId={} codeGenType={}: {}",
                    appId, codeGenType.getValue(), exception.getMessage());
            result = result.merge(MemoryCacheInvalidationResult.failure(
                    "L0_REDIS", exception));
        }
        return result;
    }

    /**
     * 创建新的 AI 服务实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        LayeredChatMemory chatMemory = createOnlineChatMemory(appId, codeGenType);
        // 根据代码生成类型选择不同的模型配置
        return switch (codeGenType) {
            // Vue 项目生成使用推理模型
            case VUE_PROJECT -> {
                //使用多例模式的StreamingChatModel解决并发问题
                StreamingChatModel reasoningStreamingChatModel = SpringContextUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
                yield AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(reasoningStreamingChatModel)
                    .chatMemoryProvider(memoryId -> chatMemory)
                    // 深度防御第二层：@PromptSafetyCheck 切面是主防线，此处兜底防绕过 AppServiceImpl 直调代理
                    .inputGuardrails(new PromptSafetyInputGuardrail())
                    .tools((Object[]) onlineVueTools())
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                            toolExecutionRequest, "Error: there is no tool called " + toolExecutionRequest.name()
                    ))
                    .build();
            }
            // HTML 和多文件生成使用默认模型
            case HTML, MULTI_FILE -> {
                //使用多例模式的StreamingChatModel解决并发问题
                StreamingChatModel openAiStreamingChatModel = SpringContextUtil.getBean("streamingChatModelPrototype", StreamingChatModel.class);
                yield AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(openAiStreamingChatModel)
                    .chatMemory(chatMemory)
                    // 深度防御第二层：@PromptSafetyCheck 切面是主防线，此处兜底防绕过 AppServiceImpl 直调代理
                    .inputGuardrails(new PromptSafetyInputGuardrail())
                    .build();
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
    }

    /** 按固定装饰顺序创建在线 L0/L1/L2 记忆，并从 MySQL 重建完整回合。 */
    LayeredChatMemory createOnlineChatMemory(
            long appId, CodeGenTypeEnum codeGenType) {
        // MessageWindow 只保留 Redis store 和工具对一致性，不再按消息条数淘汰。
        MessageWindowChatMemory delegate = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(Integer.MAX_VALUE)
                .build();
        TokenAwareChatMemory tokenAwareMemory =
                new TokenAwareChatMemory(delegate);
        // 冷启动按完整回合回填到 30K；L1/L2 由最外层装饰器注入。
        ChatHistoryService.HistoryLoadResult historyLoad =
                chatHistoryService.loadRecentCompleteTurnsToMemory(
                        appId,
                        tokenAwareMemory,
                        memoryTokenProperties.getBlockingCompressionThreshold(),
                        chatTokenEstimator);
        if (historyLoad.status() == ChatHistoryService.HistoryLoadStatus.FAILED) {
            recordVueColdRebuild(
                    VueBuildRepairMetricsCollector.MemoryResult.FAILED, codeGenType);
            throw new IllegalStateException("从 MySQL 重建对话历史失败,appId=" + appId);
        }
        recordVueColdRebuild(historyLoad.status()
                == ChatHistoryService.HistoryLoadStatus.EMPTY
                ? VueBuildRepairMetricsCollector.MemoryResult.EMPTY
                : VueBuildRepairMetricsCollector.MemoryResult.SUCCEEDED, codeGenType);
        return new LayeredChatMemory(
                tokenAwareMemory, memorySummaryService, userMemoryService);
    }

    private void recordVueColdRebuild(
            VueBuildRepairMetricsCollector.MemoryResult result,
            CodeGenTypeEnum codeGenType) {
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT
                && vueBuildRepairMetricsCollector != null) {
            vueBuildRepairMetricsCollector.recordMemoryL0Sync(
                    VueBuildRepairMetricsCollector.MemoryAction.REBUILD, result);
        }
    }

    /**
     * 每次评测创建独立代理、独立模型和纯内存窗口，绝不复用在线缓存或持久记忆。
     */
    public VueEvaluationCodeGeneratorService getVueEvaluationCodeGeneratorService(long appId) {
        StreamingChatModel evaluationModel = evaluationStreamingChatModel();
        MessageWindowChatMemory evaluationMemory = MessageWindowChatMemory.builder()
                .id("vue-evaluation-" + appId + "-" + java.util.UUID.randomUUID())
                .maxMessages(100)
                .build();
        return AiServices.builder(VueEvaluationCodeGeneratorService.class)
                .streamingChatModel(evaluationModel)
                .chatMemoryProvider(memoryId -> evaluationMemory)
                .inputGuardrails(new PromptSafetyInputGuardrail())
                .tools((Object[]) evaluationVueTools())
                .hallucinatedToolNameStrategy(toolExecutionRequest ->
                        ToolExecutionResultMessage.from(
                                toolExecutionRequest,
                                "Error: there is no tool called "
                                        + toolExecutionRequest.name()))
                .build();
    }

    StreamingChatModel evaluationStreamingChatModel() {
        return SpringContextUtil.getBean(
                "evaluationReasoningStreamingChatModelPrototype", StreamingChatModel.class);
    }

    List<String> onlineVueToolNames() {
        return VueToolNames.ONLINE;
    }

    List<String> evaluationVueToolNames() {
        return VueToolNames.EVALUATION;
    }

    BaseTool[] onlineVueTools() {
        return toolManager.requireTools(VueToolNames.ONLINE.toArray(String[]::new));
    }

    BaseTool[] evaluationVueTools() {
        return toolManager.requireTools(VueToolNames.EVALUATION.toArray(String[]::new));
    }

    /**
     * 构建缓存键
     * @param appId
     * @param codeGenType
     * @return
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return appId + "_" + codeGenType.getValue();
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L, CodeGenTypeEnum.HTML);
    }

}
