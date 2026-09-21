package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppPlanQueryServiceTest {
    @TempDir
    Path root;
    private final AppService apps = mock(AppService.class);
    private final AppDataLifecycleFence fence = new AppDataLifecycleFence();
    private final User owner = User.builder().id(9L).build();

    @Test
    void 所有者读取真实计划且空计划不创建目录() throws Exception {
        when(apps.getById(7L)).thenReturn(app(9L, "vue_project"));
        var plans = new AppPlanStateManager(new ObjectMapper(), new PlanStoragePathResolver(root));
        var service = new AppPlanQueryServiceImpl(apps, fence, plans);
        assertNull(service.getCurrentPlan(7L, owner));
        assertFalse(Files.exists(root.resolve("vue_project_7")));
        var plan = new AppPlan("plan", "turn", "turn", 1, 1, PlanMode.FULL,
                "目标", List.of(), List.of(), PlanStatus.PLANNED);
        plans.save(7, plan, 0, "turn");
        byte[] original = Files.readAllBytes(root.resolve("vue_project_7/.plan.json"));
        assertEquals("目标", service.getCurrentPlan(7L, owner).summary());
        assertArrayEquals(original, Files.readAllBytes(root.resolve("vue_project_7/.plan.json")));
    }

    @Test
    void 不合法身份和类型均在读取文件前拒绝() throws Exception {
        var plans = forbiddenReadPlans();
        var service = new AppPlanQueryServiceImpl(apps, fence, plans);
        assertCode(ErrorCode.PARAMS_ERROR, () -> service.getCurrentPlan(0L, owner));
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> service.getCurrentPlan(7L, null));
        assertCode(ErrorCode.NOT_FOUND_ERROR, () -> service.getCurrentPlan(7L, owner));
        when(apps.getById(7L)).thenReturn(app(10L, "vue_project"));
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> service.getCurrentPlan(7L, owner));
        var admin = User.builder().id(9L).userRole("admin").build();
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> service.getCurrentPlan(7L, admin));
        when(apps.getById(7L)).thenReturn(app(9L, "html"));
        assertCode(ErrorCode.PARAMS_ERROR, () -> service.getCurrentPlan(7L, owner));
    }

    @Test
    void 许可内复核失败也释放许可() throws Exception {
        var plans = forbiddenReadPlans();
        when(apps.getById(7L)).thenReturn(app(9L, "vue_project"), app(10L, "vue_project"));
        var service = new AppPlanQueryServiceImpl(apps, fence, plans);
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> service.getCurrentPlan(7L, owner));
        try (var deletion = fence.beginDelete(7, Duration.ZERO)) {
            assertNotNull(deletion);
        }
    }

    @Test
    void 损坏计划返回通用错误且释放许可() throws Exception {
        when(apps.getById(7L)).thenReturn(app(9L, "vue_project"));
        Files.createDirectories(root.resolve("vue_project_7"));
        Files.writeString(root.resolve("vue_project_7/.plan.json"), "{bad");
        var service = new AppPlanQueryServiceImpl(apps, fence,
                new AppPlanStateManager(new ObjectMapper(), new PlanStoragePathResolver(root)));
        BusinessException error = assertThrows(BusinessException.class, () -> service.getCurrentPlan(7L, owner));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), error.getCode());
        assertFalse(error.getMessage().contains(root.toString()));
        try (var deletion = fence.beginDelete(7, Duration.ZERO)) {
            assertNotNull(deletion);
        }
    }

    @Test
    void 删除关门后不读取计划() throws Exception {
        when(apps.getById(7L)).thenReturn(app(9L, "vue_project"));
        var plans = forbiddenReadPlans();
        var service = new AppPlanQueryServiceImpl(apps, fence, plans);
        try (var deletion = fence.beginDelete(7, Duration.ZERO)) {
            assertNotNull(deletion);
            assertCode(ErrorCode.OPERATION_ERROR, () -> service.getCurrentPlan(7L, owner));
        }
    }

    private App app(long ownerId, String type) {
        return App.builder().id(7L).userId(ownerId).codeGenType(type).build();
    }

    private AppPlanStateManager forbiddenReadPlans() throws Exception {
        Files.createDirectories(root.resolve("vue_project_7"));
        Files.writeString(root.resolve("vue_project_7/.plan.json"), "{}");
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public <T> T readValue(java.io.InputStream input, Class<T> type) {
                throw new AssertionError("未授权或关门后不得读取计划内容");
            }
        };
        return new AppPlanStateManager(mapper, new PlanStoragePathResolver(root));
    }

    private void assertCode(ErrorCode expected, Runnable operation) {
        assertEquals(expected.getCode(), assertThrows(BusinessException.class, operation::run).getCode());
    }
}
