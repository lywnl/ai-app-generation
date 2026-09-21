package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.service.AppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppPlanQueryLifecycleTest {
    @TempDir
    Path root;
    private final AppDataLifecycleFence fence = new AppDataLifecycleFence();
    private final User owner = User.builder().id(9L).build();

    @Test
    void 查询先到则删除等待真实文件读取完成() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        ObjectMapper reader = new ObjectMapper() {
            @Override
            public <T> T readValue(InputStream input, Class<T> type) throws IOException {
                reading.countDown();
                try {
                    if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("测试读取等待超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return super.readValue(input, type);
            }
        };
        var paths = seed();
        var service = service(new AppPlanStateManager(reader, paths));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var query = executor.submit(() -> service.getCurrentPlan(7L, owner));
            try {
                assertTrue(reading.await(3, TimeUnit.SECONDS));
                var deletion = executor.submit(() -> {
                    try (var permit = fence.beginDelete(7, Duration.ofSeconds(5))) {
                        assertNotNull(permit);
                        Files.delete(paths.planPath(7));
                        Files.delete(paths.projectRoot(7));
                        permit.commitTombstone();
                    }
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (fence.isOpen(7) && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(fence.isOpen(7));
                assertFalse(deletion.isDone());
                assertTrue(Files.exists(paths.planPath(7)));
                releaseRead.countDown();
                assertEquals("当前计划", query.get(3, TimeUnit.SECONDS).summary());
                deletion.get(3, TimeUnit.SECONDS);
            } finally {
                releaseRead.countDown();
            }
        }
        assertFalse(Files.exists(paths.projectRoot(7)));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.getCurrentPlan(7L, owner)).getCode());
        assertFalse(Files.exists(paths.projectRoot(7)));
    }

    @Test
    void 生成租约不阻止查询但删除关门阻止查询() throws Exception {
        var paths = seed();
        var service = service(new AppPlanStateManager(new ObjectMapper(), paths));
        byte[] original = Files.readAllBytes(paths.planPath(7));
        var operations = new AppOperationLeaseManager();
        try (var generation = operations.acquire(7,
                AppOperationLeaseManager.AppOperationType.GENERATE, "turn")) {
            assertEquals("当前计划", service.getCurrentPlan(7L, owner).summary());
            try (var deletion = fence.beginDelete(7, Duration.ZERO)) {
                assertNotNull(deletion);
                assertEquals(ErrorCode.OPERATION_ERROR.getCode(),
                        assertThrows(BusinessException.class, () -> service.getCurrentPlan(7L, owner)).getCode());
            }
            assertArrayEquals(original, Files.readAllBytes(paths.planPath(7)));
        }
    }

    private PlanStoragePathResolver seed() {
        var paths = new PlanStoragePathResolver(root);
        new AppPlanStateManager(new ObjectMapper(), paths).save(7,
                new AppPlan("plan", "turn", "turn", 1, 1, PlanMode.FULL,
                        "当前计划", List.of(), List.of(), PlanStatus.PLANNED), 0, "turn");
        return paths;
    }

    private AppPlanQueryServiceImpl service(AppPlanStateManager plans) {
        AppService apps = mock(AppService.class);
        when(apps.getById(7L)).thenReturn(App.builder().id(7L).userId(9L).codeGenType("vue_project").build());
        return new AppPlanQueryServiceImpl(apps, fence, plans);
    }
}
