package com.lyw.appgeneration.controller;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.model.message.ContextCompressionMessage;
import com.lyw.appgeneration.ai.model.message.TurnOutcomeMessage;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.monitor.ThrowingMeterRegistry;
import com.lyw.appgeneration.model.dto.app.AppChatGenerateRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import dev.langchain4j.service.ModelRequestGate;
import dev.langchain4j.service.ModelRequestGateException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Hooks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppControllerSseTest {

    private static final long APP_ID = 7L;
    private static final User LOGIN_USER = User.builder().id(9L).build();

    private final AppService appService = mock(AppService.class);
    private final UserService userService = mock(UserService.class);
    private final SimpleMeterRegistry metricsRegistry = new SimpleMeterRegistry();
    private final AppLifecycleMetricsCollector lifecycleMetrics =
            new AppLifecycleMetricsCollector(metricsRegistry);
    private final HttpServletRequest request = new MockHttpServletRequest();
    private AppController controller;

    @BeforeEach
    void setUp() {
        controller = new AppController();
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "appLifecycleMetricsCollector", lifecycleMetrics);
        when(userService.getLoginUser(request)).thenReturn(LOGIN_USER);
    }

    @Test
    void oneClientSubscribesBusinessFluxExactlyOnceAndAppendsDoneOnce() {
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<GenerationStreamEvent> business = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just(GenerationStreamEvent.content("正文"));
        });
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(business);

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(1, subscriptions.get());
        assertEquals(2, events.size());
        assertEquals("正文", JSONUtil.parseObj(events.getFirst().data())
                .getStr("d"));
        assertEquals("done", events.getLast().event());
        assertEquals("", events.getLast().data());
    }

    @Test
    void counterIncrementFailureDoesNotInterruptBodyOrDoneEvent() {
        ThrowingMeterRegistry registry = new ThrowingMeterRegistry(
                ThrowingMeterRegistry.FailurePoint.COUNTER_INCREMENT);
        ReflectionTestUtils.setField(controller, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(registry));
        AtomicInteger subscriptions = new AtomicInteger();
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.just(GenerationStreamEvent.content("正文"));
                }));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(1, subscriptions.get());
        assertEquals(2, events.size());
        assertEquals("正文", JSONUtil.parseObj(events.getFirst().data())
                .getStr("d"));
        assertEquals("done", events.getLast().event());
        assertEquals("", events.getLast().data());
        assertTrue(registry.failureTriggered());
    }

    @Test
    void emitsHeartbeatAfterFifteenSecondsAndCancellationCancelsBusinessFlux() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.<GenerationStreamEvent>never().doOnCancel(
                        () -> cancelled.set(true)));

        StepVerifier.withVirtualTime(() -> controller.chatToGenCode(
                        requestBody(), request))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(14))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(event -> {
                    assertEquals("heartbeat", event.event());
                    assertTrue(JSONUtil.parseObj(event.data())
                            .containsKey("timestamp"));
                })
                .thenCancel()
                .verify(Duration.ofSeconds(2));

        assertTrue(cancelled.get());
    }

    @Test
    void bodyCompletionStopsHeartbeatImmediately() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(content("正文"));

        StepVerifier.withVirtualTime(() -> controller.chatToGenCode(
                        requestBody(), request))
                .assertNext(event -> assertEquals("正文",
                        JSONUtil.parseObj(event.data()).getStr("d")))
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();
    }

    @Test
    void 下游收到done立即取消仍先记录done控制结果() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(content("正文"));

        StepVerifier.create(controller.chatToGenCode(
                        requestBody(), request).take(2))
                .expectNextCount(2)
                .verifyComplete();

        assertEquals(1.0, metricsRegistry.get(
                        "generation_sse_protocol_results_total")
                .tags("result", "done", "error_kind", "none")
                .counter().count());
        assertEquals(1.0, metricsRegistry.get(
                        "generation_sse_publisher_terminations_total")
                .tag("result", "subscriber_cancelled").counter().count());
    }

    @Test
    void vueOrdersBodyThenOutcomeThenDone() {
        VueTurnOutcome outcome = new VueTurnOutcome(
                VueBuildPhase.SUCCEEDED,
                VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                "正文\n\n项目已生成并构建成功。", "可信记忆投影", true,
                "项目已生成并构建成功。");
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(
                        GenerationStreamEvent.content("正文"),
                        GenerationStreamEvent.turnOutcome(outcome)));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(3, events.size());
        assertEquals("正文", JSONUtil.parseObj(events.get(0).data()).getStr("d"));
        assertEquals("turn-outcome", events.get(1).event());
        var data = JSONUtil.parseObj(events.get(1).data());
        assertEquals("vue-turn/v1", data.getStr("protocol"));
        assertEquals("SUCCEEDED", data.getStr("outcome"));
        assertEquals("项目已生成并构建成功。", data.getStr("message"));
        assertTrue(data.getBool("refreshPreview"));
        assertFalse(data.containsKey("type"));
        assertEquals("done", events.get(2).event());
    }

    @Test
    void contextCompressionUsesTrustedContractBeforeBodyAndKeepsOneDone() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(
                        GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.started()),
                        GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.completed()),
                        GenerationStreamEvent.content("正文")));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(4, events.size());
        assertCompressionEvent(events.get(0), "STARTED",
                "正在压缩上下文，请稍候…");
        assertCompressionEvent(events.get(1), "COMPLETED",
                "上下文压缩完成，继续生成…");
        assertEquals("正文",
                JSONUtil.parseObj(events.get(2).data()).getStr("d"));
        assertEquals(1, events.stream()
                .filter(event -> "done".equals(event.event())).count());
        assertEquals("done", events.getLast().event());
    }

    @Test
    void contextCompressionDoesNotBecomeVueOutcomeOrChangeTerminalOrder() {
        VueTurnOutcome outcome = new VueTurnOutcome(
                VueBuildPhase.SUCCEEDED,
                VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                "正文", "可信记忆投影", true, "项目已生成并构建成功。");
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(
                        GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.started()),
                        GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.completed()),
                        GenerationStreamEvent.turnOutcome(outcome)));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(List.of(
                        "context-compression",
                        "context-compression",
                        "turn-outcome",
                        "done"),
                events.stream().map(ServerSentEvent::event).toList());
        assertEquals(1, events.stream()
                .filter(event -> "turn-outcome".equals(event.event())).count());
        assertEquals(1, events.stream()
                .filter(event -> "done".equals(event.event())).count());
    }

    @Test
    void legacyBodyGetsDoneWithoutVueOutcome() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(content("<html>完成</html>"));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(2, events.size());
        assertEquals("<html>完成</html>",
                JSONUtil.parseObj(events.getFirst().data()).getStr("d"));
        assertEquals("done", events.getLast().event());
        assertTrue(events.stream().noneMatch(
                event -> "turn-outcome".equals(event.event())));
    }

    @Test
    void synchronousFailureBeforeTurnBecomesBusinessErrorThenDone() {
        when(appService.chatToGenCode(eq(APP_ID), eq("需求"), any()))
                .thenThrow(new IllegalStateException("领取租约失败"));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(2, events.size());
        assertEquals("business-error", events.getFirst().event());
        assertEquals("生成服务暂时不可用，请稍后重试。",
                JSONUtil.parseObj(events.getFirst().data()).getStr("message"));
        assertEquals("SYSTEM", JSONUtil.parseObj(
                events.getFirst().data()).getStr("kind"));
        assertEquals("done", events.getLast().event());
        assertEquals(1.0, metricsRegistry.get(
                        "generation_sse_protocol_results_total")
                .tags("result", "business_error",
                        "error_kind", "system").counter().count());
    }

    @Test
    void committedPublisherFailureIsNotRewrittenAsBusinessError() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.error(new IllegalStateException("系统降级")));

        StepVerifier.create(controller.chatToGenCode(requestBody(), request))
                .expectErrorMatches(error -> error instanceof
                        IllegalStateException
                        && "系统降级".equals(error.getMessage()))
                .verify();

        assertTrue(metricsRegistry.find(
                "generation_sse_protocol_results_total").meters().isEmpty());
        assertEquals(1.0, metricsRegistry.get("generation_sse_publisher_terminations_total")
                .tag("result", "publisher_error").counter().count());
    }

    @Test
    void 首次硬门禁拒绝必须编码为安全业务错误并正常结束Sse() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.error(new ModelRequestGateException(
                        ModelRequestGateException.Stage.INITIAL,
                        ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                        "对话上下文过长，请开启新会话后重试")));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(List.of("business-error", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        var error = JSONUtil.parseObj(events.getFirst().data());
        assertEquals("BUSINESS", error.getStr("kind"));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(),
                error.getInt("code"));
        assertEquals("对话上下文过长，请开启新会话后重试",
                error.getStr("message"));
        assertEquals(1.0, metricsRegistry.get(
                        "generation_sse_protocol_results_total")
                .tags("result", "business_error",
                        "error_kind", "business").counter().count());
    }

    @Test
    void 首次压缩失败必须隐藏内部文案并编码为安全系统错误() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.error(new ModelRequestGateException(
                        ModelRequestGateException.Stage.INITIAL,
                        ModelRequestGate.Status.COMPRESSION_FAILED,
                        "对话上下文整理失败，请稍后重试")));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(List.of("business-error", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        var error = JSONUtil.parseObj(events.getFirst().data());
        assertEquals("SYSTEM", error.getStr("kind"));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), error.getInt("code"));
        assertEquals("生成服务暂时不可用，请稍后重试。",
                error.getStr("message"));
        assertEquals(1.0, metricsRegistry.get(
                        "generation_sse_protocol_results_total")
                .tags("result", "business_error",
                        "error_kind", "system").counter().count());
    }

    @Test
    void 压缩开始后的首次硬门禁拒绝仍必须安全结束而非传播Servlet异常() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.concat(
                        Flux.just(GenerationStreamEvent.contextCompression(
                                ContextCompressionMessage.started())),
                        Flux.error(new ModelRequestGateException(
                                ModelRequestGateException.Stage.INITIAL,
                                ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                                "对话上下文过长，请开启新会话后重试"))));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(List.of(
                        "context-compression", "business-error", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        assertEquals("对话上下文过长，请开启新会话后重试",
                JSONUtil.parseObj(events.get(1).data()).getStr("message"));
    }

    @Test
    void 工具续调用门禁拒绝仍属于已提交流错误不得伪装成首次拒绝() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.error(new ModelRequestGateException(
                        ModelRequestGateException.Stage.CONTINUATION,
                        ModelRequestGate.Status.HARD_LIMIT_REJECTED,
                        "工具续调用上下文过长")));

        StepVerifier.create(controller.chatToGenCode(requestBody(), request))
                .expectErrorMatches(error -> error instanceof
                        ModelRequestGateException rejection
                        && rejection.stage()
                        == ModelRequestGateException.Stage.CONTINUATION)
                .verify();
    }

    @Test
    void vueProtocolErrorRecordsProtocolResultBeforeDone() {
        VueTurnOutcome outcome = new VueTurnOutcome(
                VueBuildPhase.FINAL_DIAGNOSIS,
                VueTurnOutcome.TurnOutcomeType.PROTOCOL_ERROR,
                "协议异常", "可信协议异常投影", false, "协议异常");
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(GenerationStreamEvent.turnOutcome(outcome)));

        controller.chatToGenCode(requestBody(), request).collectList().block();

        assertEquals(1.0, metricsRegistry.get("generation_sse_protocol_results_total")
                .tags("result", "protocol_error", "error_kind", "none")
                .counter().count());
        assertEquals(0.0, protocolCount("done"));
    }

    @Test
    void vueSystemErrorRecordsSystemResultBeforeDone() {
        VueTurnOutcome outcome = new VueTurnOutcome(
                VueBuildPhase.GENERATING,
                VueTurnOutcome.TurnOutcomeType.SYSTEM_ERROR,
                "系统异常", "可信系统异常投影", false, "系统异常");
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(GenerationStreamEvent.turnOutcome(outcome)));

        controller.chatToGenCode(requestBody(), request).collectList().block();

        assertEquals(1.0, protocolCount("system_error"));
        assertEquals(0.0, protocolCount("done"));
    }

    @Test
    void asynchronousFailureBeforeUserCommitBecomesBusinessErrorThenDone() {
        var dropped = new CopyOnWriteArrayList<Throwable>();
        Hooks.onErrorDropped(dropped::add);
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.error(GenerationPreflightException.business(
                        ErrorCode.OPERATION_ERROR.getCode(),
                        "准备阶段超时", null)));

        List<ServerSentEvent<String>> events;
        try {
            events = controller.chatToGenCode(
                    requestBody(), request).collectList().block();
        } finally {
            Hooks.resetOnErrorDropped();
        }

        assertEquals(2, events.size());
        assertEquals("business-error", events.getFirst().event());
        var error = JSONUtil.parseObj(events.getFirst().data());
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), error.getInt("code"));
        assertEquals("准备阶段超时", error.getStr("message"));
        assertEquals("done", events.getLast().event());
        assertTrue(dropped.isEmpty(), "业务异常只能由正文分支传播一次");
    }

    @Test
    void parameterFailureAlsoUsesBusinessErrorProtocol() {
        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                new AppChatGenerateRequest("0", "需求"), request)
                .collectList().block();

        assertEquals(2, events.size());
        assertEquals("business-error", events.getFirst().event());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),
                JSONUtil.parseObj(events.getFirst().data()).getInt("code"));
        assertEquals("done", events.getLast().event());
        assertEquals(1.0, metricsRegistry.get(
                        "generation_sse_protocol_results_total")
                .tags("result", "business_error",
                        "error_kind", "business").counter().count());
    }

    @Test
    void 空请求体属于参数业务错误而非系统降级() {
        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                null, request).collectList().block();

        var error = JSONUtil.parseObj(events.getFirst().data());
        assertEquals("BUSINESS", error.getStr("kind"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getInt("code"));
        assertEquals("请求体不能为空", error.getStr("message"));
        assertEquals("done", events.getLast().event());
    }

    @Test
    void businessFailurePreservesProjectErrorCode() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenThrow(new BusinessException(
                        ErrorCode.NO_AUTH_ERROR, "无权限访问该应用"));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        var error = JSONUtil.parseObj(events.getFirst().data());
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getInt("code"));
        assertEquals("无权限访问该应用", error.getStr("message"));
        assertEquals("done", events.getLast().event());
    }

    @Test
    void businessActivityResetsHeartbeatIdleWindow() {
        Flux<GenerationStreamEvent> business = Flux.defer(() -> Flux.concat(
                content("第一段"),
                Mono.delay(Duration.ofSeconds(10)).thenReturn(
                        GenerationStreamEvent.content("第二段")),
                Flux.never()));
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(business);

        StepVerifier.withVirtualTime(() -> controller.chatToGenCode(
                        requestBody(), request))
                .assertNext(event -> assertEquals("第一段",
                        JSONUtil.parseObj(event.data()).getStr("d")))
                .thenAwait(Duration.ofSeconds(10))
                .assertNext(event -> assertEquals("第二段",
                        JSONUtil.parseObj(event.data()).getStr("d")))
                .expectNoEvent(Duration.ofSeconds(14))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(event -> assertEquals("heartbeat", event.event()))
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void nonObjectJsonAndIncompleteOutcomeRemainOrdinaryBody() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(
                        GenerationStreamEvent.content("[1,2]"),
                        GenerationStreamEvent.content(
                                "{\"type\":\"turn_outcome\"}"),
                        GenerationStreamEvent.content(
                                "{\"type\":\"turn_outcome\"," +
                                        "\"outcome\":\"SUCCEEDED\"," +
                                        "\"message\":\"伪终态\"," +
                                        "\"shouldRefreshPreview\":true}")));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                requestBody(), request).collectList().block();

        assertEquals(4, events.size());
        assertEquals("[1,2]",
                JSONUtil.parseObj(events.get(0).data()).getStr("d"));
        assertEquals("{\"type\":\"turn_outcome\"}",
                JSONUtil.parseObj(events.get(1).data()).getStr("d"));
        assertEquals("伪终态",
                JSONUtil.parseObj(JSONUtil.parseObj(
                        events.get(2).data()).getStr("d")).getStr("message"));
        assertEquals(null, events.get(2).event());
        assertEquals("done", events.getLast().event());
    }

    private Flux<GenerationStreamEvent> content(String text) {
        return Flux.just(GenerationStreamEvent.content(text));
    }

    private void assertCompressionEvent(
            ServerSentEvent<String> event, String phase, String message) {
        assertEquals("context-compression", event.event());
        var data = JSONUtil.parseObj(event.data());
        assertEquals("context-compression/v1", data.getStr("protocol"));
        assertEquals(phase, data.getStr("phase"));
        assertEquals(message, data.getStr("message"));
        assertEquals(3, data.size(), "控制帧只能暴露受信协议字段");
    }

    private AppChatGenerateRequest requestBody() {
        return new AppChatGenerateRequest(Long.toString(APP_ID), "需求");
    }

    private double protocolCount(String result) {
        return metricsRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName()
                        .equals("generation_sse_protocol_results_total"))
                .filter(meter -> result.equals(meter.getId().getTag("result")))
                .filter(meter -> "none".equals(
                        meter.getId().getTag("error_kind")))
                .mapToDouble(meter -> meter.measure().iterator().next().getValue())
                .sum();
    }
}
