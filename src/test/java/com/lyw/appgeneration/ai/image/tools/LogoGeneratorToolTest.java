package com.lyw.appgeneration.ai.image.tools;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.utils.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogoGeneratorToolTest {
    private LogoImageClient client;
    private AtomicLong now;
    private List<Long> sleeps;
    private LogoGeneratorTool tool;

    @BeforeEach
    void setUp() {
        client = mock(LogoImageClient.class);
        now = new AtomicLong();
        sleeps = new ArrayList<>();
        tool = configured(new LogoGeneratorTool(1000, 10, () -> client, now::get, nanos -> {
            sleeps.add(TimeUnit.NANOSECONDS.toMillis(nanos));
            now.addAndGet(nanos);
        }));
    }

    @AfterEach
    void clearThreadState() {
        Thread.interrupted();
        MDC.remove("imageCollectionRequestId");
    }

    @Test
    void 只提交一次并按固定间隔查询同一个任务() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch("task-1", "test-key-do-not-log"))
                .thenReturn(result("PENDING"), result("RUNNING"), result("SUCCEEDED"));

        var images = tool.generateLogos("蓝色科技品牌");

        assertEquals(1, images.size());
        assertEquals("https://example.com/logo.png", images.getFirst().getUrl());
        assertEquals(List.of(1000L, 1000L), sleeps);
        ArgumentCaptor<ImageSynthesisParam> param = ArgumentCaptor.forClass(ImageSynthesisParam.class);
        verify(client, times(1)).asyncCall(param.capture());
        verify(client, times(3)).fetch("task-1", "test-key-do-not-log");
        assertEquals("wan2.2-t2i-flash", param.getValue().getModel());
        assertEquals("512*512", param.getValue().getSize());
        assertEquals(1, param.getValue().getN());
        assertFalse(param.getValue().getParameters().containsKey("prompt_extend"));
        assertEquals("生成 Logo，Logo 中禁止包含任何文字！Logo 介绍：蓝色科技品牌", param.getValue().getPrompt());
    }

    @Test
    void 提交已成功则直接交付无需查询() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("SUCCEEDED"));
        assertEquals(1, tool.generateLogos("品牌").size());
        verify(client, never()).fetch(anyString(), anyString());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void 固定间隔包含查询HTTP耗时() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenAnswer(ignored -> {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
            return result("RUNNING");
        }).thenReturn(result("SUCCEEDED"));
        assertEquals(1, tool.generateLogos("品牌").size());
        assertEquals(List.of(750L), sleeps, "查询已花 250 毫秒，只需补足剩余 750 毫秒");
        assertEquals(TimeUnit.SECONDS.toNanos(1), now.get());
    }

    @Test
    void 慢查询先返回再继续且不补发错过的查询() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        AtomicBoolean firstQueryReturned = new AtomicBoolean();
        when(client.fetch(anyString(), anyString())).thenAnswer(ignored -> {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(2400));
            firstQueryReturned.set(true);
            return result("RUNNING");
        }).thenAnswer(ignored -> {
            assertTrue(firstQueryReturned.get());
            return result("SUCCEEDED");
        });
        assertEquals(1, tool.generateLogos("品牌").size());
        assertTrue(sleeps.isEmpty());
        verify(client, times(2)).fetch(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELED", "UNKNOWN", "未定义状态"})
    void 终止状态和异常状态不再查询(String state) throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenReturn(result(state));
        assertTrue(tool.generateLogos("品牌").isEmpty());
        verify(client, times(1)).fetch(anyString(), anyString());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void 缺少任务标识不发出查询() throws Exception {
        var task = result("PENDING");
        task.getOutput().setTaskId(null);
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(task);
        assertTrue(tool.generateLogos("品牌").isEmpty());
        verify(client, never()).fetch(anyString(), anyString());
    }

    @Test
    void 空提交和空查询响应能够结束() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(null, result("PENDING"));
        assertTrue(tool.generateLogos("品牌一").isEmpty());
        when(client.fetch(anyString(), anyString())).thenReturn(null);
        assertTrue(tool.generateLogos("品牌二").isEmpty());
        verify(client, times(2)).asyncCall(any(ImageSynthesisParam.class));
        verify(client, times(1)).fetch(anyString(), anyString());
    }

    @Test
    void 成功但没有图片时返回空结果() throws Exception {
        var completed = result("SUCCEEDED");
        completed.getOutput().setResults(List.of(Map.of("url", " ")));
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(completed);
        assertTrue(tool.generateLogos("品牌").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503, 504})
    void 查询遇到暂时错误时退避并复用任务(int code) throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenThrow(apiError(code)).thenReturn(result("SUCCEEDED"));
        assertEquals(1, tool.generateLogos("品牌").size());
        assertEquals(List.of(2000L), sleeps);
        verify(client, times(1)).asyncCall(any(ImageSynthesisParam.class));
        verify(client, times(2)).fetch("task-1", "test-key-do-not-log");
    }

    @Test
    void 连续错误退避最多五秒且恢复后重置() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString()))
                .thenThrow(apiError(429), apiError(429), apiError(503), apiError(504))
                .thenReturn(result("RUNNING"))
                .thenThrow(apiError(429))
                .thenReturn(result("SUCCEEDED"));
        assertEquals(1, tool.generateLogos("品牌").size());
        assertEquals(List.of(2000L, 4000L, 5000L, 5000L, 1000L, 2000L), sleeps);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500})
    void 不可重试的查询错误直接回退(int code) throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenThrow(apiError(code));
        assertTrue(tool.generateLogos("品牌").isEmpty());
        verify(client, times(1)).fetch(anyString(), anyString());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void 提交错误不自动重试生成() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenThrow(apiError(503));
        assertTrue(tool.generateLogos("品牌").isEmpty());
        verify(client, times(1)).asyncCall(any(ImageSynthesisParam.class));
        verify(client, never()).fetch(anyString(), anyString());
    }

    @Test
    void 已取消或中断的任务不提交() {
        assertTrue(tool.generateLogos("品牌", () -> true).isEmpty());
        Thread.currentThread().interrupt();
        assertTrue(tool.generateLogos("品牌").isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
        verifyNoInteractions(client);
    }

    @Test
    void 即使客户端清除了中断停止信号仍能阻止后续查询() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenAnswer(ignored -> {
            cancelled.set(true);
            Thread.currentThread().interrupt();
            Thread.interrupted();
            return result("RUNNING");
        });
        assertTrue(tool.generateLogos("品牌", cancelled::get).isEmpty());
        verify(client, times(1)).fetch(anyString(), anyString());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void 查询间隔等待被中断后保留中断状态() throws Exception {
        tool = configured(new LogoGeneratorTool(1000, 10, () -> client, now::get,
                nanos -> { throw new InterruptedException("主动取消"); }));
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenReturn(result("RUNNING"));
        assertTrue(tool.generateLogos("品牌").isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
        verify(client, times(1)).fetch(anyString(), anyString());
    }

    @Test
    void 同一实例的不同生成请求共享查询额度() throws Exception {
        when(client.asyncCall(any(ImageSynthesisParam.class))).thenReturn(result("PENDING"));
        when(client.fetch(anyString(), anyString())).thenReturn(result("SUCCEEDED"));
        assertEquals(1, tool.generateLogos("品牌一").size());
        assertEquals(1, tool.generateLogos("品牌二").size());
        assertEquals(List.of(100L), sleeps);
        verify(client, times(2)).asyncCall(any(ImageSynthesisParam.class));
    }

    @Test
    void Spring使用配置的轮询间隔和共享频率() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "dashscope.api-key", "test-key-do-not-log",
                    "image.logo.poll-interval-ms", "1250",
                    "image.logo.max-poll-qps", "4")));
            context.register(LogoGeneratorTool.class);
            context.refresh();
            var bean = context.getBean(LogoGeneratorTool.class);
            assertEquals(1250L, ReflectionTestUtils.getField(bean, "pollIntervalMs"));
            Object limiter = ReflectionTestUtils.getField(bean, "rateLimiter");
            assertNotNull(limiter);
            assertEquals(250_000_000L, ReflectionTestUtils.getField(limiter, "spacingNanos"));
        }
    }

    @Test
    void 日志关联图片请求并记录阶段耗时但不记录秘密或正文() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(LogoGeneratorTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("imageCollectionRequestId", "image-request-1");
        try {
            when(client.asyncCall(any(ImageSynthesisParam.class))).thenAnswer(ignored -> {
                now.addAndGet(TimeUnit.MILLISECONDS.toNanos(120));
                return result("PENDING");
            });
            when(client.fetch(anyString(), anyString()))
                    .thenThrow(new ApiException(Status.builder().statusCode(429)
                            .message("test-key-do-not-log 私密Logo描述 https://example.com/private.png")
                            .code("Throttling").build()))
                    .thenAnswer(ignored -> {
                        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(80));
                        return result("SUCCEEDED");
                    });
            assertEquals(1, tool.generateLogos("私密Logo描述").size());
            String logs = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertTrue(logs.contains("requestId=image-request-1"));
            assertTrue(logs.contains("submitMs=120,pollCount=2,retryCount=1,queryMs=80"));
            assertTrue(logs.contains("totalMs=2200"));
            assertFalse(logs.contains("test-key-do-not-log"));
            assertFalse(logs.contains("私密Logo描述"));
            assertFalse(logs.contains("https://"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    static LogoGeneratorTool configured(LogoGeneratorTool tool) {
        ReflectionTestUtils.setField(tool, "dashScopeApiKey", "test-key-do-not-log");
        ReflectionTestUtils.setField(tool, "imageModel", "wan2.2-t2i-flash");
        return tool;
    }

    static ImageSynthesisResult result(String status) {
        ImageSynthesisResult result = JsonUtils.fromJson("{}", ImageSynthesisResult.class);
        ImageSynthesisOutput output = new ImageSynthesisOutput();
        output.setTaskId("task-1");
        output.setTaskStatus(status);
        if ("SUCCEEDED".equals(status)) {
            output.setResults(List.of(Map.of("url", "https://example.com/logo.png")));
        }
        result.setOutput(output);
        return result;
    }

    private ApiException apiError(int code) {
        return new ApiException(Status.builder().statusCode(code).code("TestError").message("测试错误").build());
    }
}
