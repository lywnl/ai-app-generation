package com.lyw.appgeneration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.exception.*;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import com.lyw.appgeneration.service.impl.AppPlanQueryServiceImpl;
import com.lyw.appgeneration.web.GenerationSsePreflightWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.nullValue;

class AppControllerPlanTest {
    @TempDir
    Path root;
    private final AppService apps = mock(AppService.class);
    private final UserService users = mock(UserService.class);
    private MockMvc mvc;
    private AppPlanStateManager plans;

    @BeforeEach
    void setup() {
        when(apps.getById(7L)).thenReturn(App.builder().id(7L).userId(9L).codeGenType("vue_project").build());
        when(users.getLoginUser(any())).thenReturn(User.builder().id(9L).build());
        plans = new AppPlanStateManager(new ObjectMapper(), new PlanStoragePathResolver(root));
        AppController controller = new AppController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "appPlanQueryService",
                new AppPlanQueryServiceImpl(apps, new AppDataLifecycleFence(), plans));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new GenerationSsePreflightWriter(
                        new com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))))
                .build();
    }

    @Test
    void 查询实际文件返回受限视图和无缓存头() throws Exception {
        plans.save(7, new AppPlan("plan", "hidden", "hidden", 1, 1, PlanMode.FULL,
                "当前目标", List.of(), List.of(), PlanStatus.PLANNED), 0, "hidden");
        mvc.perform(get("/api/app/7/plan").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary").value("当前目标"))
                .andExpect(jsonPath("$.data.activeTurnId").doesNotExist());
    }

    @Test
    void 缺失计划返回成功空数据() throws Exception {
        mvc.perform(get("/app/7/plan"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(nullValue()));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(root.resolve("vue_project_7")));
    }

    @Test
    void 未登录和其他用户通过真实服务拒绝() throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(get("/app/7/plan")).andExpect(jsonPath("$.code").value(40100))
                .andExpect(header().string("Cache-Control", "no-store"));
        doReturn(User.builder().id(10L).userRole("admin").build()).when(users).getLoginUser(any());
        mvc.perform(get("/app/7/plan")).andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void 错误响应不暴露损坏内容和文件路径() throws Exception {
        Files.createDirectories(root.resolve("vue_project_7"));
        Files.writeString(root.resolve("vue_project_7/.plan.json"), "{invalid");
        mvc.perform(get("/app/7/plan")).andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("计划暂时无法读取，请稍后重试"));
        mvc.perform(get("/app/0/plan")).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/app/8/plan")).andExpect(jsonPath("$.code").value(40400));
    }
}
