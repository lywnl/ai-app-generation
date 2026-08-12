package com.lyw.appgeneration.controller;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.model.message.TurnOutcomeMessage;
import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    private final HttpServletRequest request = new MockHttpServletRequest();
    private AppController controller;

    @BeforeEach
    void setUp() {
        controller = new AppController();
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userService", userService);
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
                APP_ID, "需求", request).collectList().block();

        assertEquals(1, subscriptions.get());
        assertEquals(2, events.size());
        assertEquals("正文", JSONUtil.parseObj(events.getFirst().data())
                .getStr("d"));
        assertEquals("done", events.getLast().event());
        assertEquals("", events.getLast().data());
    }

    @Test
    void emitsHeartbeatAfterFifteenSecondsAndCancellationCancelsBusinessFlux() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.<GenerationStreamEvent>never().doOnCancel(
                        () -> cancelled.set(true)));

        StepVerifier.withVirtualTime(() -> controller.chatToGenCode(
                        APP_ID, "需求", request))
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
                        APP_ID, "需求", request))
                .assertNext(event -> assertEquals("正文",
                        JSONUtil.parseObj(event.data()).getStr("d")))
                .assertNext(event -> assertEquals("done", event.event()))
                .verifyComplete();
    }

    @Test
    void vueOrdersBodyThenOutcomeThenDone() {
        VueTurnOutcome outcome = new VueTurnOutcome(
                VueBuildPhase.SUCCEEDED,
                VueTurnOutcome.TurnOutcomeType.SUCCEEDED,
                "正文\n\n项目已生成并构建成功。", true,
                "项目已生成并构建成功。");
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.just(
                        GenerationStreamEvent.content("正文"),
                        GenerationStreamEvent.vueOutcome(outcome)));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                APP_ID, "需求", request).collectList().block();

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
    void legacyBodyGetsDoneWithoutVueOutcome() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(content("<html>完成</html>"));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                APP_ID, "需求", request).collectList().block();

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
                APP_ID, "需求", request).collectList().block();

        assertEquals(2, events.size());
        assertEquals("business-error", events.getFirst().event());
        assertEquals("领取租约失败",
                JSONUtil.parseObj(events.getFirst().data()).getStr("message"));
        assertEquals("done", events.getLast().event());
    }

    @Test
    void asynchronousFailureBeforeUserCommitBecomesBusinessErrorThenDone() {
        var dropped = new CopyOnWriteArrayList<Throwable>();
        Hooks.onErrorDropped(dropped::add);
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenReturn(Flux.error(new BusinessException(
                        ErrorCode.OPERATION_ERROR, "准备阶段超时")));

        List<ServerSentEvent<String>> events;
        try {
            events = controller.chatToGenCode(
                    APP_ID, "需求", request).collectList().block();
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
                0L, "需求", request).collectList().block();

        assertEquals(2, events.size());
        assertEquals("business-error", events.getFirst().event());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),
                JSONUtil.parseObj(events.getFirst().data()).getInt("code"));
        assertEquals("done", events.getLast().event());
    }

    @Test
    void businessFailurePreservesProjectErrorCode() {
        when(appService.chatToGenCode(APP_ID, "需求", LOGIN_USER))
                .thenThrow(new BusinessException(
                        ErrorCode.NO_AUTH_ERROR, "无权限访问该应用"));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(
                APP_ID, "需求", request).collectList().block();

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
                        APP_ID, "需求", request))
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
                APP_ID, "需求", request).collectList().block();

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
}
