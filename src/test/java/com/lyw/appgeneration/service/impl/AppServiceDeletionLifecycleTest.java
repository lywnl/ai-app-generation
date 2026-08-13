package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.lyw.appgeneration.service.AppDeletionFileService;
import com.lyw.appgeneration.service.AppDeletionPersistenceService;
import com.lyw.appgeneration.service.AppStoragePathResolver;
import com.lyw.appgeneration.service.MemoryCacheInvalidationResult;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class AppServiceDeletionLifecycleTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;

    @TempDir
    private Path temporaryDirectory;
    private final AppDeletionFileService fileService = mock(AppDeletionFileService.class);
    private final AppDeletionPersistenceService persistence =
            mock(AppDeletionPersistenceService.class);
    private final AiGeneratorServiceFactory aiFactory =
            mock(AiGeneratorServiceFactory.class);
    private final MemorySummaryService summaries = mock(MemorySummaryService.class);
    private final UserMemoryService userMemory = mock(UserMemoryService.class);
    private AppOperationLeaseManager leases;
    private AppDataLifecycleFence fence;
    private AppServiceImpl service;
    private App app;
    private SimpleMeterRegistry metricsRegistry;

    @BeforeEach
    void setUp() throws Exception {
        leases = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        fence = new AppDataLifecycleFence();
        Path source = temporaryDirectory.resolve("source");
        Path deploy = temporaryDirectory.resolve("deploy");
        Files.createDirectories(source);
        Files.createDirectories(deploy);
        app = App.builder().id(APP_ID).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey("deploy7").build();
        service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", leases);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metricsRegistry));
        ReflectionTestUtils.setField(service, "appDataLifecycleFence", fence);
        ReflectionTestUtils.setField(service, "appStoragePathResolver",
                new AppStoragePathResolver(source, deploy));
        ReflectionTestUtils.setField(service, "appDeletionFileService", fileService);
        ReflectionTestUtils.setField(
                service, "appDeletionPersistenceService", persistence);
        ReflectionTestUtils.setField(service, "aiGeneratorServiceFactory", aiFactory);
        ReflectionTestUtils.setField(service, "memorySummaryService", summaries);
        ReflectionTestUtils.setField(service, "userMemoryService", userMemory);
        ReflectionTestUtils.setField(service, "deleteWaitTimeout", Duration.ZERO);
        doReturn(app, app).when(service).getById(APP_ID);
        when(aiFactory.invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(MemoryCacheInvalidationResult.success());
        when(summaries.invalidateCache(APP_ID))
                .thenReturn(MemoryCacheInvalidationResult.success());
        when(userMemory.invalidateCaches(APP_ID, USER_ID))
                .thenReturn(MemoryCacheInvalidationResult.success());
    }

    @Test
    void unauthorizedDeleteFailsBeforeLeaseFenceFilesDatabaseAndCaches() {
        User stranger = User.builder().id(USER_ID + 1).userRole("user").build();
        try (AppOperationLeaseManager.AppOperationLease ignored = leases.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, "existing")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.deleteApp(APP_ID, stranger));
            assertEquals("无权限删除该应用", exception.getMessage());
            verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
        }
    }

    @Test
    void activeDeployRejectsWithoutCancellationOrTokenLeak() {
        try (AppOperationLeaseManager.AppOperationLease deploy = leases.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DEPLOY,
                "secret-owner-token")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.deleteApp(APP_ID, owner()));
            assertEquals("应用正在部署或下载，暂时无法删除", exception.getMessage());
            assertFalse(deploy.isCancellationRequested());
            verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
            assertEquals(1.0, metricsRegistry.get("app_operations_total")
                    .tags("operation", "delete", "result", "rejected",
                            "conflict_with", "deploy").counter().count());
        }
    }

    @Test
    void activeDownloadRejectsWithoutCancellationOrTokenLeak() {
        try (AppOperationLeaseManager.AppOperationLease download = leases.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                "secret-download-owner")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.deleteApp(APP_ID, owner()));
            assertEquals("应用正在部署或下载，暂时无法删除", exception.getMessage());
            assertFalse(download.isCancellationRequested());
            verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
            assertEquals(1.0, metricsRegistry.get("app_operations_total")
                    .tags("operation", "delete", "result", "rejected",
                            "conflict_with", "download").counter().count());
        }
    }

    @Test
    void administratorCanDeleteApplicationOwnedByAnotherUser() {
        User admin = User.builder().id(USER_ID + 100).userRole("admin").build();

        assertTrue(service.deleteApp(APP_ID, admin));

        verify(persistence).deleteAppData(APP_ID);
    }

    @Test
    void writerTimeoutHasZeroExternalSideEffectsAndReopensFence() {
        AppDataLifecycleFence.WriterPermit writer = fence.tryAcquireWriter(APP_ID);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteApp(APP_ID, owner()));

        assertEquals("应用数据正在写入，暂时无法删除", exception.getMessage());
        verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
        writer.close();
        AppDataLifecycleFence.WriterPermit reopened = fence.tryAcquireWriter(APP_ID);
        assertTrue(reopened != null);
        reopened.close();
        leases.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.DELETE,
                "after-timeout").close();
    }

    @Test
    void successKeepsDeleteLeaseOrdersSideEffectsAndCommitsTombstone() {
        doAnswer(invocation -> {
            assertDeleteLeaseActive("files");
            return null;
        }).when(fileService).delete(any());
        doAnswer(invocation -> {
            assertDeleteLeaseActive("database");
            return null;
        }).when(persistence).deleteAppData(APP_ID);

        assertTrue(service.deleteApp(APP_ID, owner()));

        verify(fileService).delete(any());
        verify(persistence).deleteAppData(APP_ID);
        verify(aiFactory).invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        verify(summaries).invalidateCache(APP_ID);
        verify(userMemory).invalidateCaches(APP_ID, USER_ID);
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "delete", "result", "acquired",
                        "conflict_with", "none").counter().count());
        InOrder order = inOrder(
                fileService, persistence, aiFactory, summaries, userMemory);
        order.verify(fileService).delete(any());
        order.verify(persistence).deleteAppData(APP_ID);
        order.verify(aiFactory).invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        order.verify(summaries).invalidateCache(APP_ID);
        order.verify(userMemory).invalidateCaches(APP_ID, USER_ID);
        assertEquals(null, fence.tryAcquireWriter(APP_ID));
        assertEquals(null, fence.tryAcquireWriter(APP_ID));
        leases.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.DELETE,
                "after-success").close();
    }

    @Test
    void fileFailureSkipsDatabaseAndCachesAndReopensFence() {
        doAnswer(invocation -> { throw new IllegalStateException("file failed"); })
                .when(fileService).delete(any());

        assertThrows(IllegalStateException.class,
                () -> service.deleteApp(APP_ID, owner()));

        verifyNoInteractions(persistence, aiFactory, summaries, userMemory);
        assertWriterCanEnter();
    }

    @Test
    void databaseFailureSkipsCachesAndReopensFence() {
        doAnswer(invocation -> { throw new IllegalStateException("db failed"); })
                .when(persistence).deleteAppData(APP_ID);

        assertThrows(IllegalStateException.class,
                () -> service.deleteApp(APP_ID, owner()));

        verifyNoInteractions(aiFactory, summaries, userMemory);
        assertWriterCanEnter();
    }

    @Test
    void eachCacheRetriesAtMostThreeTimesWithoutBlockingOthers() {
        MemoryCacheInvalidationResult failed =
                MemoryCacheInvalidationResult.failure("cache", new RuntimeException("down"));
        when(aiFactory.invalidateAndClearMemory(
                APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(failed);
        when(summaries.invalidateCache(APP_ID)).thenReturn(failed);
        when(userMemory.invalidateCaches(APP_ID, USER_ID)).thenReturn(failed);

        assertTrue(service.deleteApp(APP_ID, User.builder()
                .id(100L).userRole("admin").build()));

        verify(aiFactory, org.mockito.Mockito.times(3))
                .invalidateAndClearMemory(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        verify(summaries, org.mockito.Mockito.times(3)).invalidateCache(APP_ID);
        verify(userMemory, org.mockito.Mockito.times(3))
                .invalidateCaches(APP_ID, USER_ID);
    }

    @Test
    void publicRemoveByIdCannotBypassControlledDeletion() {
        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.removeById(APP_ID));
        assertEquals("请使用受控应用删除入口", exception.getMessage());
        verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
    }

    @Test
    void generateTakeoverWaitsForEnteredCallbackBeforeFileDeletion()
            throws Exception {
        ReflectionTestUtils.setField(
                service, "deleteWaitTimeout", Duration.ofSeconds(3));
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch fileStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            fileStarted.countDown();
            return null;
        }).when(fileService).delete(any());
        try (AppOperationLeaseManager.AppOperationLease generate = leases.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "active-generation");
             AppOperationLeaseManager.CallbackRegistration callback =
                     generate.enterCallback();
             AppOperationLeaseManager.CancellationRegistration cancellation =
                     generate.registerCancellation(cancellationStarted::countDown);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> deletion = executor.submit(
                    () -> service.deleteApp(APP_ID, owner()));
            assertTrue(cancellationStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1L, fileStarted.getCount());
            callback.close();
            generate.close();
            assertTrue(deletion.get(3, TimeUnit.SECONDS));
            assertTrue(fileStarted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownerChangeAfterFenceClosureFailsClosedAndReleasesGuards() {
        App changedOwner = App.builder().id(APP_ID).userId(USER_ID + 1)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey("deploy7").build();
        doReturn(app, changedOwner).when(service).getById(APP_ID);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteApp(APP_ID, owner()));

        assertEquals("应用信息已变化，请重试删除", exception.getMessage());
        verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
        assertGuardsReopened();
    }

    @Test
    void codeGenTypeChangeAfterFenceClosureFailsClosedAndReleasesGuards() {
        App changedType = App.builder().id(APP_ID).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                .deployKey("deploy7").build();
        doReturn(app, changedType).when(service).getById(APP_ID);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteApp(APP_ID, owner()));

        assertEquals("应用信息已变化，请重试删除", exception.getMessage());
        verifyNoInteractions(fileService, persistence, aiFactory, summaries, userMemory);
        assertGuardsReopened();
    }

    @Test
    void emptyDeployKeyFreezesNoDeployDirectory() {
        App withoutDeploy = App.builder().id(APP_ID).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey(" ").build();
        doReturn(withoutDeploy, withoutDeploy).when(service).getById(APP_ID);

        assertTrue(service.deleteApp(APP_ID, owner()));

        verify(fileService).delete(org.mockito.ArgumentMatchers.argThat(
                paths -> paths.deployDirectory().isEmpty()));
    }

    private void assertDeleteLeaseActive(String token) {
        assertThrows(AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> leases.acquire(APP_ID,
                        AppOperationLeaseManager.AppOperationType.DOWNLOAD, token));
    }

    private void assertWriterCanEnter() {
        AppDataLifecycleFence.WriterPermit writer = fence.tryAcquireWriter(APP_ID);
        assertTrue(writer != null);
        writer.close();
    }

    private void assertGuardsReopened() {
        assertWriterCanEnter();
        leases.acquire(APP_ID, AppOperationLeaseManager.AppOperationType.DELETE,
                "after-fail-closed").close();
    }

    private User owner() {
        return User.builder().id(USER_ID).userRole("user").build();
    }
}
