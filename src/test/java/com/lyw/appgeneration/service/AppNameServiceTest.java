package com.lyw.appgeneration.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.ai.AiAppNameService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppNameServiceTest {

    private static final String PROMPT = "创建一个支持文章分类搜索的个人博客";

    @ParameterizedTest
    @ValueSource(strings = {"个人博客", "品牌作品展示", "Acme 商城", "春山笔记"})
    void 合法名称原样返回且只请求一次(String name) {
        AiAppNameService ai = mock(AiAppNameService.class);
        when(ai.generateAppName(PROMPT)).thenReturn(name);
        assertEquals(name, new AppNameService(ai).generateName(PROMPT));
        verify(ai).generateAppName(PROMPT);
        verifyNoMoreInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  个人博客  ", "\"个人博客\"", "'个人博客'", "“个人博客”", "‘个人博客’"})
    void 清理外围空白和一对引号(String name) {
        assertEquals("个人博客", new AppNameService(prompt -> name).generateName(PROMPT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "单", "一二三四五六七八九十一二三四五六七八九十一", "博客\n说明",
            "博客\r说明", "博客\t名称", "博客\u0000", "博客\u2028说明", "博客\u2029说明",
            "```博客```", "应用名称：个人博客", "名称:个人博客", "App Name: Blog", "<name>博客</name>"})
    void 不合法输出回退且不重试(String output) {
        AiAppNameService ai = mock(AiAppNameService.class);
        when(ai.generateAppName(PROMPT)).thenReturn(output);
        assertEquals(PROMPT.substring(0, 12), new AppNameService(ai).generateName(PROMPT));
        verify(ai).generateAppName(PROMPT);
        verifyNoMoreInteractions(ai);
    }

    @Test
    void Unicode长度和兜底不会截断代理对() {
        String symbol = "\uD840\uDC00";
        assertEquals(symbol.repeat(20), new AppNameService(prompt -> symbol.repeat(20)).generateName(PROMPT));
        assertEquals(symbol.repeat(12), new AppNameService(prompt -> null).generateName(symbol.repeat(15)));
        assertEquals("博客", new AppNameService(prompt -> null).generateName("博客"));
    }

    @Test
    void 超时及普通异常都回退并且日志不含输入或模型异常正文() {
        Logger logger = (Logger) LoggerFactory.getLogger(AppNameService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            for (RuntimeException failure : new RuntimeException[]{
                    new IllegalStateException("敏感异常正文"),
                    new RuntimeException(new SocketTimeoutException("敏感异常正文"))}) {
                assertEquals(PROMPT.substring(0, 12), new AppNameService(prompt -> {
                    throw failure;
                }).generateName(PROMPT));
            }
            new AppNameService(prompt -> "私有应用名称").generateName(PROMPT);
            String logs = events.list.toString();
            assertTrue(logs.contains("source=AI"));
            assertTrue(logs.contains("source=FALLBACK"));
            assertTrue(logs.contains("TIMEOUT"));
            assertFalse(logs.contains(PROMPT));
            assertFalse(logs.contains("私有应用名称"));
            assertFalse(logs.contains("敏感异常正文"));
            assertTrue(events.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void 包装的中断异常恢复线程标记且不重试() {
        AiAppNameService ai = mock(AiAppNameService.class);
        when(ai.generateAppName(PROMPT)).thenThrow(new RuntimeException(new InterruptedException()));
        try {
            assertEquals(PROMPT.substring(0, 12), new AppNameService(ai).generateName(PROMPT));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(ai).generateAppName(PROMPT);
            verifyNoMoreInteractions(ai);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 已中断线程不再请求模型() {
        AiAppNameService ai = mock(AiAppNameService.class);
        try {
            Thread.currentThread().interrupt();
            assertEquals(PROMPT.substring(0, 12), new AppNameService(ai).generateName(PROMPT));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(ai);
        } finally {
            Thread.interrupted();
        }
    }
}
