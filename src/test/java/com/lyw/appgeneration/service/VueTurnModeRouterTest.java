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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class VueTurnModeRouterTest {

    @Test
    void 首次回合固定为变更模式且不调用模型() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("创建首页", false));
        verify(factory, never()).create();
    }

    @Test
    void 选中元素信息固定为变更模式() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("查看这个元素\n\n选中元素信息：\n- 标签：button", true));
        verify(factory, never()).create();
    }

    @Test
    void 自由输入使用路由模型返回模式() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(
                VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(service);
        when(service.route("现在布局是什么样子的"))
                .thenReturn(VueTurnMode.READ_ONLY);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        assertEquals(VueTurnMode.READ_ONLY,
                router.route("现在布局是什么样子的", true));
        verify(service).route("现在布局是什么样子的");
    }

    @Test
    void 分类模型异常保守降级为变更模式() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        when(factory.create()).thenThrow(new IllegalStateException("路由失败"));
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("读取首页结构", true));
    }

    @Test
    void 分类日志只记录模式来源耗时和异常类型不记录原始提示词() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(
                VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(service);
        when(service.route("读取首页中的全部组件名称"))
                .thenReturn(VueTurnMode.READ_ONLY);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);
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
                        message.contains("source=ROUTING_MODEL")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        message.matches(".*elapsedMs=\\d+.*")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        message.contains("exceptionType=NONE")),
                () -> org.junit.jupiter.api.Assertions.assertFalse(
                        message.contains("读取首页中的全部组件名称")));
    }
}
