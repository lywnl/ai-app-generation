package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.service.AppStoragePathResolver;
import com.lyw.appgeneration.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppServiceDownloadLifecycleTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;
    private final ProjectDownloadService downloadService = mock(ProjectDownloadService.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    @TempDir
    private Path temporaryDirectory;
    private Path sourceDirectory;
    private AppStoragePathResolver pathResolver;
    private AppOperationLeaseManager leaseManager;
    private AppServiceImpl service;
    private SimpleMeterRegistry metricsRegistry;

    @BeforeEach
    void setUp() {
        leaseManager = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        Path sourceRoot = temporaryDirectory.resolve("source");
        Path deployRoot = temporaryDirectory.resolve("deploy");
        pathResolver = new AppStoragePathResolver(sourceRoot, deployRoot);
        sourceDirectory = sourceRoot.resolve("vue_project_" + APP_ID)
                .toAbsolutePath().normalize();
        service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", leaseManager);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metricsRegistry));
        ReflectionTestUtils.setField(service, "appStoragePathResolver", pathResolver);
        ReflectionTestUtils.setField(service, "projectDownloadService", downloadService);
    }

    @Test
    void unauthorizedRequestDoesNotAcquireLease() {
        doReturn(app(USER_ID + 1)).when(service).getById(APP_ID);
        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "existing-generate")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.downloadApp(APP_ID, loginUser(), response));

            assertEquals("无权限下载该应用代码", exception.getMessage());
            verifyNoInteractions(downloadService);
        }
    }

    @Test
    void missingAppDoesNotAcquireLease() {
        doReturn(null).when(service).getById(APP_ID);

        assertPreconditionWinsOverActiveLease(
                "应用不存在", loginUser(), response);
    }

    @Test
    void nullResponseDoesNotAcquireLease() {
        doReturn(app(USER_ID)).when(service).getById(APP_ID);

        assertPreconditionWinsOverActiveLease(
                "响应对象不能为空", loginUser(), null);
    }

    @Test
    void invalidLoginUserIdDoesNotAcquireLease() {
        doReturn(app(USER_ID)).when(service).getById(APP_ID);

        assertPreconditionWinsOverActiveLease(
                "用户未登录", User.builder().id(0L).build(), response);
    }

    @Test
    void nullLoginUserDoesNotAcquireLease() {
        assertPreconditionWinsOverActiveLease(
                "用户未登录", null, response);
    }

    @Test
    void invalidAppIdFailsBeforeQueryAndLeaseAcquisition() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.downloadApp(0L, loginUser(), response));

        assertEquals("应用 ID 不能为空", exception.getMessage());
        verify(service, never()).getById(0L);
        verifyNoInteractions(downloadService);
    }

    @Test
    void activeGenerateRejectsBeforePathResolutionWithoutLeakingOwner()
            throws Exception {
        doReturn(app(USER_ID)).when(service).getById(APP_ID);
        createSourceSymbolicLink();
        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "secret-owner-token")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.downloadApp(APP_ID, loginUser(), response));

            assertEquals("应用正在生成中，暂时无法下载", exception.getMessage());
            assertEquals(1.0, metricsRegistry.get("app_operations_total")
                    .tags("operation", "download", "result", "rejected",
                            "conflict_with", "generate").counter().count());
            verifyNoInteractions(downloadService);
        }
    }

    @Test
    void pathResolutionFailureReleasesDownloadLeaseBeforeZipStarts()
            throws Exception {
        doReturn(app(USER_ID)).when(service).getById(APP_ID);
        createSourceSymbolicLink();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.downloadApp(APP_ID, loginUser(), response));

        verifyNoInteractions(downloadService);
        leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                "retry-after-path-failure").close();
    }

    @Test
    void invalidCodeGenerationTypeDoesNotAcquireLease() {
        App invalidApp = app(USER_ID);
        invalidApp.setCodeGenType("unknown");
        doReturn(invalidApp).when(service).getById(APP_ID);
        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "existing-generate")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.downloadApp(APP_ID, loginUser(), response));

            assertEquals("代码生成类型无效", exception.getMessage());
            verifyNoInteractions(downloadService);
        }
    }

    @Test
    void downloadLeaseCoversCompleteResponseWrite() {
        App app = app(USER_ID);
        doReturn(app).when(service).getById(APP_ID);
        doAnswer(invocation -> {
            assertThrows(
                    AppOperationLeaseManager.ActiveAppOperationException.class,
                    () -> leaseManager.acquire(
                            APP_ID,
                            AppOperationLeaseManager.AppOperationType.DEPLOY,
                            "probe-during-response"));
            return null;
        }).when(downloadService).downloadProjectAsZip(
                sourceDirectory, String.valueOf(APP_ID), response);

        service.downloadApp(APP_ID, loginUser(), response);

        verify(downloadService).downloadProjectAsZip(
                sourceDirectory, String.valueOf(APP_ID), response);
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "download", "result", "acquired",
                        "conflict_with", "none").counter().count());
        leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                "after-response").close();
    }

    @Test
    void responseFailureAlwaysReleasesDownloadLease() {
        App app = app(USER_ID);
        doReturn(app).when(service).getById(APP_ID);
        RuntimeException responseFailure = new RuntimeException("响应写出失败");
        doAnswer(invocation -> {
            throw responseFailure;
        }).when(downloadService).downloadProjectAsZip(
                sourceDirectory, String.valueOf(APP_ID), response);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.downloadApp(APP_ID, loginUser(), response));

        assertEquals(responseFailure, thrown);
        leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                "retry-after-response-failure").close();
    }

    private App app(long ownerId) {
        return App.builder()
                .id(APP_ID)
                .userId(ownerId)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
    }

    private User loginUser() {
        return User.builder().id(USER_ID).build();
    }

    private void assertPreconditionWinsOverActiveLease(
            String expectedMessage,
            User loginUser,
            HttpServletResponse servletResponse) {
        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "existing-generate")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.downloadApp(
                            APP_ID, loginUser, servletResponse));

            assertEquals(expectedMessage, exception.getMessage());
            verifyNoInteractions(downloadService);
        }
    }

    private void createSourceSymbolicLink() throws Exception {
        Files.createDirectories(sourceDirectory.getParent());
        Path target = temporaryDirectory.resolve("symbolic-target");
        Files.createDirectories(target);
        Files.createSymbolicLink(sourceDirectory, target);
    }
}
