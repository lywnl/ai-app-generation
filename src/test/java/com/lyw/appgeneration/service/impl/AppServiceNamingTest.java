package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingServiceFactory;
import com.lyw.appgeneration.config.AppCreationExecutorConfig;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.dto.app.AppAddRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.AppNameService;
import com.lyw.appgeneration.controller.AppController;
import com.lyw.appgeneration.model.dto.app.AppUpdateRequest;
import com.lyw.appgeneration.service.UserService;
import com.lyw.appgeneration.service.GoodAppCacheService;
import com.lyw.appgeneration.monitor.AppCreationTiming;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppServiceNamingTest {
    private final AppServiceImpl service = spy(new AppServiceImpl());
    private final AiCodeGenTypeRoutingService route = mock(AiCodeGenTypeRoutingService.class);
    private final AppNameService naming = mock(AppNameService.class);
    private final AppAddRequest request = new AppAddRequest();
    private final User user = new User();
    private SimpleAsyncTaskExecutor namingExecutor;

    @BeforeEach
    void setup() {
        var factory = mock(AiCodeGenTypeRoutingServiceFactory.class);
        when(factory.createAiCodeGenTypeRoutingService()).thenReturn(route);
        ReflectionTestUtils.setField(service, "aiCodeGenTypeRoutingServiceFactory", factory);
        ReflectionTestUtils.setField(service, "appNameService", naming);
        namingExecutor = new AppCreationExecutorConfig().appCreationNamingExecutor(4);
        ReflectionTestUtils.setField(service, "appCreationNamingExecutor", namingExecutor);
        request.setInitPrompt("创建一个个人博客");
        user.setId(7L);
        when(naming.generateName(request.getInitPrompt())).thenReturn("个人博客");
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            assertEquals("个人博客", app.getAppName());
            assertEquals(7L, app.getUserId());
            assertEquals(request.getInitPrompt(), app.getInitPrompt());
            app.setId(99L);
            return true;
        }).when(service).save(any(App.class));
    }

    @AfterEach
    void closeExecutor() {
        namingExecutor.close();
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void 三类应用均在路由和命名完成后只保存一次(CodeGenTypeEnum type) {
        when(route.routeCodeGenType(request.getInitPrompt())).thenReturn(type);
        assertEquals(99L, service.addApp(request, user));
        var routeOrder = inOrder(route, service);
        routeOrder.verify(route).routeCodeGenType(request.getInitPrompt());
        routeOrder.verify(service).save(argThat(app -> type.getValue().equals(app.getCodeGenType())));
        var nameOrder = inOrder(naming, service);
        nameOrder.verify(naming).generateName(request.getInitPrompt());
        nameOrder.verify(service).save(any(App.class));
        verify(service, times(1)).save(any(App.class));
    }

    @Test
    void 命名失败仍保存真实兜底名称() {
        when(route.routeCodeGenType(request.getInitPrompt())).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        ReflectionTestUtils.setField(service, "appNameService", new AppNameService(prompt -> {
            throw new IllegalStateException("模型不可用");
        }));
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            assertEquals(request.getInitPrompt(), app.getAppName());
            app.setId(100L);
            return true;
        }).when(service).save(any(App.class));
        assertEquals(100L, service.addApp(request, user));
    }

    @Test
    void 路由失败取消已启动的命名且不保存() throws Exception {
        CountDownLatch nameStarted = new CountDownLatch(1);
        CountDownLatch nameInterrupted = new CountDownLatch(1);
        CountDownLatch releaseName = new CountDownLatch(1);
        when(naming.generateName(request.getInitPrompt())).thenAnswer(ignored -> {
            nameStarted.countDown();
            try {
                releaseName.await();
            } catch (InterruptedException interrupted) {
                nameInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return "备用名称";
        });
        IllegalStateException failure = new IllegalStateException("路由失败");
        when(route.routeCodeGenType(anyString())).thenAnswer(ignored -> {
            assertTrue(nameStarted.await(3, TimeUnit.SECONDS));
            throw failure;
        });
        try {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.addApp(request, user)));
            assertTrue(nameInterrupted.await(3, TimeUnit.SECONDS));
            verify(service, never()).save(any(App.class));
        } finally {
            releaseName.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 路由与命名重叠执行且任一路未完成都不入库(boolean nameFinishesFirst) throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseRoute = new CountDownLatch(1);
        CountDownLatch releaseName = new CountDownLatch(1);
        CountDownLatch routeFinished = new CountDownLatch(1);
        CountDownLatch nameFinished = new CountDownLatch(1);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        String requestId = "创建并发请求";
        when(route.routeCodeGenType(request.getInitPrompt())).thenAnswer(ignored -> {
            assertSame(callerThread.get(), Thread.currentThread());
            assertEquals(requestId, AppCreationTiming.requestId());
            bothStarted.countDown();
            assertTrue(releaseRoute.await(5, TimeUnit.SECONDS));
            routeFinished.countDown();
            return CodeGenTypeEnum.VUE_PROJECT;
        });
        when(naming.generateName(request.getInitPrompt())).thenAnswer(ignored -> {
            assertTrue(Thread.currentThread().getName().startsWith("app-create-name-"));
            assertEquals(requestId, AppCreationTiming.requestId());
            bothStarted.countDown();
            assertTrue(releaseName.await(5, TimeUnit.SECONDS));
            nameFinished.countDown();
            return "个人博客";
        });
        var caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                try (var ignored = MDC.putCloseable(AppCreationTiming.REQUEST_ID_KEY, requestId)) {
                    return service.addApp(request, user);
                }
            });
            assertTrue(bothStarted.await(3, TimeUnit.SECONDS), "两次模型调用必须能同时开始");
            verify(service, never()).save(any(App.class));
            if (nameFinishesFirst) {
                releaseName.countDown();
                assertTrue(nameFinished.await(3, TimeUnit.SECONDS));
            } else {
                releaseRoute.countDown();
                assertTrue(routeFinished.await(3, TimeUnit.SECONDS));
            }
            assertFalse(result.isDone());
            verify(service, never()).save(any(App.class));
            releaseName.countDown();
            releaseRoute.countDown();
            assertEquals(99L, result.get(3, TimeUnit.SECONDS));
            verify(service, times(1)).save(argThat(app ->
                    "个人博客".equals(app.getAppName())
                            && CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())));
        } finally {
            releaseName.countDown();
            releaseRoute.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void 等待命名时中断会取消任务且不入库() throws Exception {
        CountDownLatch nameStarted = new CountDownLatch(1);
        CountDownLatch nameInterrupted = new CountDownLatch(1);
        CountDownLatch releaseName = new CountDownLatch(1);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        when(route.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.HTML);
        when(naming.generateName(request.getInitPrompt())).thenAnswer(ignored -> {
            nameStarted.countDown();
            try {
                releaseName.await();
            } catch (InterruptedException interrupted) {
                nameInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return "个人博客";
        });
        var caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                assertThrows(CancellationException.class, () -> service.addApp(request, user));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(nameStarted.await(3, TimeUnit.SECONDS));
            callerThread.get().interrupt();
            assertTrue(result.get(3, TimeUnit.SECONDS));
            assertTrue(nameInterrupted.await(3, TimeUnit.SECONDS));
            verify(service, never()).save(any(App.class));
        } finally {
            releaseName.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void 保存失败不冒充成功() {
        when(route.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.HTML);
        doReturn(false).when(service).save(any(App.class));
        assertThrows(BusinessException.class, () -> service.addApp(request, user));
    }

    @Test
    void 空白需求不进入模型或数据库() {
        request.setInitPrompt(" ");
        assertThrows(BusinessException.class, () -> service.addApp(request, user));
        verifyNoInteractions(route, naming);
        verify(service, never()).save(any(App.class));
    }

    @Test
    void 用户手动改名保留输入且不触发自动命名() {
        AppController controller = new AppController();
        ReflectionTestUtils.setField(controller, "goodAppCacheService",
                mock(GoodAppCacheService.class));
        UserService users = mock(UserService.class);
        var http = new MockHttpServletRequest();
        when(users.getLoginUser(http)).thenReturn(user);
        App existing = new App();
        existing.setId(99L);
        existing.setUserId(user.getId());
        existing.setAppName("历史名称");
        doReturn(existing).when(service).getById(99L);
        doReturn(true).when(service).updateById(any(App.class));
        ReflectionTestUtils.setField(controller, "appService", service);
        ReflectionTestUtils.setField(controller, "userService", users);
        var update = new AppUpdateRequest();
        update.setId(99L);
        update.setAppName("用户自己的名称");
        assertTrue(controller.updateApp(update, http).getData());
        verify(service).updateById(argThat(app -> "用户自己的名称".equals(app.getAppName())));
        verifyNoInteractions(naming, route);
        assertEquals("历史名称", existing.getAppName());
    }
}
