package com.lyw.appgeneration.monitor;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class AiModelMonitorListener implements ChatModelListener {

    // 用于存储请求开始时间的键
    private static final String REQUEST_START_TIME_KEY = "request_start_time";
    // 用于监控上下文传递（因为请求和响应事件的触发不是同一个线程）
    private static final String MONITOR_CONTEXT_KEY = "monitor_context";

    @Resource
    private AiModelMetricsCollector aiModelMetricsCollector;

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // 记录请求开始时间
        requestContext.attributes().put(REQUEST_START_TIME_KEY, Instant.now());
        // 从监控上下文中获取信息（ThreadLocal 可能为空）
        MonitorContext context = safeContext(MonitorContextHolder.getContext());
        requestContext.attributes().put(MONITOR_CONTEXT_KEY, context);
        // 获取模型名称
        String modelName = safeModelName(requestContext.chatRequest().modelName());
        // 记录请求指标（监控失败不影响主流程）
        safeRecord(() -> aiModelMetricsCollector.recordRequest(context.getUserId(), context.getAppId(), modelName, "started"));
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        // 从属性中获取监控信息（由 onRequest 方法存储）
        Map<Object, Object> attributes = responseContext.attributes();
        // 优先从 attributes 获取，避免跨线程 ThreadLocal 丢失
        MonitorContext context = safeContext((MonitorContext) attributes.get(MONITOR_CONTEXT_KEY));
        String userId = context.getUserId();
        String appId = context.getAppId();
        // 获取模型名称
        String modelName = safeModelName(responseContext.chatResponse().modelName());
        // 记录成功请求
        safeRecord(() -> aiModelMetricsCollector.recordRequest(userId, appId, modelName, "success"));
        // 记录响应时间
        recordResponseTime(attributes, userId, appId, modelName);
        // 记录 Token 使用情况
        recordTokenUsage(responseContext, userId, appId, modelName);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        // 错误回调线程可能与请求线程不同，必须从 attributes 取上下文
        Map<Object, Object> attributes = errorContext.attributes();
        MonitorContext context = safeContext((MonitorContext) attributes.get(MONITOR_CONTEXT_KEY));
        String userId = context.getUserId();
        String appId = context.getAppId();
        // 获取模型名称和错误类型
        String modelName = safeModelName(errorContext.chatRequest().modelName());
        String errorMessage = errorContext.error() == null ? "unknown" : String.valueOf(errorContext.error().getMessage());
        // 记录失败请求
        safeRecord(() -> aiModelMetricsCollector.recordRequest(userId, appId, modelName, "error"));
        safeRecord(() -> aiModelMetricsCollector.recordError(userId, appId, modelName, errorMessage));
        // 记录响应时间（即使是错误响应）
        recordResponseTime(attributes, userId, appId, modelName);
    }


    /**
     * 记录响应时间
     */
    private void recordResponseTime(Map<Object, Object> attributes, String userId, String appId, String modelName) {
        Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);
        if (startTime == null) {
            return;
        }
        Duration responseTime = Duration.between(startTime, Instant.now());
        safeRecord(() -> aiModelMetricsCollector.recordResponseTime(userId, appId, modelName, responseTime));
    }

    /**
     * 记录Token使用情况
     */
    private void recordTokenUsage(ChatModelResponseContext responseContext, String userId, String appId, String modelName) {
        TokenUsage tokenUsage = responseContext.chatResponse().metadata().tokenUsage();
        if (tokenUsage != null) {
            safeRecord(() -> aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "input", tokenUsage.inputTokenCount()));
            safeRecord(() -> aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "output", tokenUsage.outputTokenCount()));
            safeRecord(() -> aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "total", tokenUsage.totalTokenCount()));
        }
    }

    private MonitorContext safeContext(MonitorContext context) {
        if (context == null) {
            return MonitorContext.builder().userId("unknown").appId("unknown").build();
        }
        String userId = Objects.toString(context.getUserId(), "unknown");
        String appId = Objects.toString(context.getAppId(), "unknown");
        return MonitorContext.builder().userId(userId).appId(appId).build();
    }

    private String safeModelName(String modelName) {
        return (modelName == null || modelName.isBlank()) ? "unknown" : modelName;
    }

    private void safeRecord(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("monitor metric record failed: {}", e.getMessage());
        }
    }
}

