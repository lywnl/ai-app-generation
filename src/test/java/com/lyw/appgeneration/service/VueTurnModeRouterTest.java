package com.lyw.appgeneration.service;

import com.lyw.appgeneration.ai.VueTurnModeRoutingService;
import com.lyw.appgeneration.ai.VueTurnModeRoutingServiceFactory;
import com.lyw.appgeneration.core.handler.VueTurnMode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueTurnModeRouterTest {

    @Test
    void 首次回合固定为变更模式且不调用模型() {
        VueReadOnlyIntentPolicy policy = mock(VueReadOnlyIntentPolicy.class);
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRouter router = new VueTurnModeRouter(policy, factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("创建首页", false));
        verify(policy, never()).isExplicitReadOnly("创建首页");
        verify(factory, never()).create();
    }

    @Test
    void 选中元素信息固定为变更模式() {
        VueReadOnlyIntentPolicy policy = mock(VueReadOnlyIntentPolicy.class);
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRouter router = new VueTurnModeRouter(policy, factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("查看这个元素\n\n选中元素信息：\n- 标签：button", true));
        verify(policy, never()).isExplicitReadOnly(
                "查看这个元素\n\n选中元素信息：\n- 标签：button");
        verify(factory, never()).create();
    }

    @Test
    void 本地与模型都确认明确查询时路由为只读模式() {
        VueReadOnlyIntentPolicy policy = mock(VueReadOnlyIntentPolicy.class);
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(policy.isExplicitReadOnly("现在页面有哪些组件？")).thenReturn(true);
        when(factory.create()).thenReturn(service);
        when(service.route("现在页面有哪些组件？"))
                .thenReturn(VueTurnMode.READ_ONLY);
        VueTurnModeRouter router = new VueTurnModeRouter(policy, factory);

        assertEquals(VueTurnMode.READ_ONLY,
                router.route("现在页面有哪些组件？", true));
        verify(service).route("现在页面有哪些组件？");
    }

    @Test
    void 本地拒绝或异常时不调用模型并保守降级为变更模式() {
        VueReadOnlyIntentPolicy rejected = mock(VueReadOnlyIntentPolicy.class);
        VueReadOnlyIntentPolicy failed = mock(VueReadOnlyIntentPolicy.class);
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        when(rejected.isExplicitReadOnly("把按钮改成红色")).thenReturn(false);
        when(failed.isExplicitReadOnly("读取首页结构"))
                .thenThrow(new IllegalStateException("策略失败"));

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                new VueTurnModeRouter(rejected, factory)
                        .route("把按钮改成红色", true));
        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                new VueTurnModeRouter(failed, factory).route("读取首页结构", true));
        verify(factory, never()).create();
    }

    @Test
    void 模型拒绝空值或异常时保守降级为变更模式() {
        VueReadOnlyIntentPolicy policy = mock(VueReadOnlyIntentPolicy.class);
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(policy.isExplicitReadOnly("查询一")).thenReturn(true);
        when(policy.isExplicitReadOnly("查询二")).thenReturn(true);
        when(policy.isExplicitReadOnly("查询三")).thenReturn(true);
        when(factory.create()).thenReturn(service);
        when(service.route("查询一")).thenReturn(VueTurnMode.MUTATION_REQUIRED);
        when(service.route("查询二")).thenReturn(null);
        when(service.route("查询三")).thenThrow(new IllegalStateException("模型失败"));
        VueTurnModeRouter router = new VueTurnModeRouter(policy, factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED, router.route("查询一", true));
        assertEquals(VueTurnMode.MUTATION_REQUIRED, router.route("查询二", true));
        assertEquals(VueTurnMode.MUTATION_REQUIRED, router.route("查询三", true));
    }

    @Test
    void 分类日志只记录模式来源耗时和异常类型不记录原始提示词() {
        VueReadOnlyIntentPolicy policy = mock(VueReadOnlyIntentPolicy.class);
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(policy.isExplicitReadOnly("读取首页中的全部组件名称"))
                .thenReturn(true);
        when(factory.create()).thenReturn(service);
        when(service.route("读取首页中的全部组件名称"))
                .thenReturn(VueTurnMode.READ_ONLY);
        VueTurnModeRouter router = new VueTurnModeRouter(policy, factory);
        Logger logger = (Logger) LoggerFactory.getLogger(
                VueTurnModeRouter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            router.route("读取首页中的全部组件名称", true);
        } finally {
            logger.detachAppender(appender);
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(1, messages.size());
        String message = messages.getFirst();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        message.contains("mode=READ_ONLY")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        message.contains("source=ROUTING_MODEL_AND_POLICY")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        message.matches(".*elapsedMs=\\d+.*")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        message.contains("exceptionType=NONE")),
                () -> org.junit.jupiter.api.Assertions.assertFalse(
                        message.contains("读取首页中的全部组件名称")));
    }

}
