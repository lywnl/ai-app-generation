package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingServiceFactory;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppServiceNamingTest {
    private final AppServiceImpl service = spy(new AppServiceImpl());
    private final AiCodeGenTypeRoutingService route = mock(AiCodeGenTypeRoutingService.class);
    private final AppNameService naming = mock(AppNameService.class);
    private final AppAddRequest request = new AppAddRequest();
    private final User user = new User();

    @BeforeEach
    void setup() {
        var factory = mock(AiCodeGenTypeRoutingServiceFactory.class);
        when(factory.createAiCodeGenTypeRoutingService()).thenReturn(route);
        ReflectionTestUtils.setField(service, "aiCodeGenTypeRoutingServiceFactory", factory);
        ReflectionTestUtils.setField(service, "appNameService", naming);
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

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void 三类应用路由后命名再保存(CodeGenTypeEnum type) {
        when(route.routeCodeGenType(request.getInitPrompt())).thenReturn(type);
        assertEquals(99L, service.addApp(request, user));
        var order = inOrder(route, naming, service);
        order.verify(route).routeCodeGenType(request.getInitPrompt());
        order.verify(naming).generateName(request.getInitPrompt());
        order.verify(service).save(argThat(app -> type.getValue().equals(app.getCodeGenType())));
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
    void 路由失败不调用命名或保存() {
        when(route.routeCodeGenType(anyString())).thenThrow(new IllegalStateException("路由失败"));
        assertThrows(IllegalStateException.class, () -> service.addApp(request, user));
        verifyNoInteractions(naming);
        verify(service, never()).save(any(App.class));
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
