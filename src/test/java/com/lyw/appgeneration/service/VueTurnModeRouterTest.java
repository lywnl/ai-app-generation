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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueTurnModeRouterTest {

    @Test
    void 选中元素信息固定为变更模式且不调用分类模型() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("查看这个元素\n\n选中元素信息：\n- 标签：button", true));
        verify(factory, never()).create();
    }

    @Test
    void 首轮和后续回合都交给分类模型() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(service);
        when(service.route("页面都有什么")).thenReturn(VueTurnMode.READ_ONLY);
        when(service.route("把按钮改成红色"))
                .thenReturn(VueTurnMode.MUTATION_REQUIRED);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        assertEquals(VueTurnMode.READ_ONLY,
                router.route("页面都有什么", false));
        assertEquals(VueTurnMode.MUTATION_REQUIRED,
                router.route("把按钮改成红色", true));
        verify(service).route("页面都有什么");
        verify(service).route("把按钮改成红色");
    }

    @Test
    void 分类模型返回空值时抛出系统内部错误() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(service);
        when(service.route("页面都有什么")).thenReturn(null);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        VueTurnModeRoutingException exception = assertThrows(
                VueTurnModeRoutingException.class,
                () -> router.route("页面都有什么", false));
        assertEquals("分类模型返回空结果", exception.getMessage());
    }

    @Test
    void 分类模型调用异常时抛出系统内部错误() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(service);
        when(service.route("页面都有什么"))
                .thenThrow(new IllegalStateException("模型调用失败"));
        VueTurnModeRouter router = new VueTurnModeRouter(factory);

        VueTurnModeRoutingException exception = assertThrows(
                VueTurnModeRoutingException.class,
                () -> router.route("页面都有什么", true));
        assertEquals("分类模型调用失败", exception.getMessage());
    }

    @Test
    void 分类日志不记录用户原始提示词() {
        VueTurnModeRoutingServiceFactory factory = mock(
                VueTurnModeRoutingServiceFactory.class);
        VueTurnModeRoutingService service = mock(VueTurnModeRoutingService.class);
        when(factory.create()).thenReturn(service);
        when(service.route("页面都有什么"))
                .thenReturn(VueTurnMode.READ_ONLY);
        VueTurnModeRouter router = new VueTurnModeRouter(factory);
        Logger logger = (Logger) LoggerFactory.getLogger(
                VueTurnModeRouter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            router.route("页面都有什么", true);
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
                () -> org.junit.jupiter.api.Assertions.assertFalse(
                        message.contains("页面都有什么")));
    }
}
