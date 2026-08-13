package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.core.builder.BuildExecutionContext;
import com.lyw.appgeneration.core.builder.BuildResult;
import com.lyw.appgeneration.core.builder.BuildStage;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.service.AppDeploymentFileService;
import com.lyw.appgeneration.service.AppStoragePathResolver;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.ScreenshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppServiceDeploymentLifecycleTest {

    private static final long APP_ID = 7L;
    private static final long USER_ID = 9L;
    private static final String DEPLOY_KEY = "deploy7";

    @TempDir
    private Path temporaryDirectory;
    private final VueProjectBuilder builder = mock(VueProjectBuilder.class);
    private final AppDeploymentFileService deploymentFileService =
            mock(AppDeploymentFileService.class);
    private final ScreenshotService screenshotService = mock(ScreenshotService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final AiGeneratorServiceFactory aiFactory =
            mock(AiGeneratorServiceFactory.class);
    private AppOperationLeaseManager leaseManager;
    private SimpleMeterRegistry metricsRegistry;
    private AppServiceImpl service;
    private Path sourceDirectory;
    private Path deployDirectory;

    @BeforeEach
    void setUp() throws Exception {
        leaseManager = new AppOperationLeaseManager();
        metricsRegistry = new SimpleMeterRegistry();
        Path sourceRoot = temporaryDirectory.resolve("source");
        Path deployRoot = temporaryDirectory.resolve("deploy");
        sourceDirectory = sourceRoot.resolve("vue_project_" + APP_ID)
                .toAbsolutePath().normalize();
        deployDirectory = deployRoot.resolve(DEPLOY_KEY)
                .toAbsolutePath().normalize();
        Files.createDirectories(sourceDirectory.resolve("dist"));
        AppStoragePathResolver resolver =
                new AppStoragePathResolver(sourceRoot, deployRoot);

        service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", leaseManager);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metricsRegistry));
        ReflectionTestUtils.setField(service, "appStoragePathResolver", resolver);
        ReflectionTestUtils.setField(service, "vueProjectBuilder", builder);
        ReflectionTestUtils.setField(
                service, "appDeploymentFileService", deploymentFileService);
        ReflectionTestUtils.setField(service, "screenshotService", screenshotService);
        ReflectionTestUtils.setField(service, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(service, "aiGeneratorServiceFactory", aiFactory);
        doReturn(true).when(service).updateById(any(App.class));
    }

    @Test
    void unauthorizedDeployFailsBeforeLeaseAcquisition() {
        doReturn(vueApp(USER_ID + 1)).when(service).getById(APP_ID);
        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "existing-generate")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.deployApp(APP_ID, loginUser()));

            assertEquals("无权限部署该应用", exception.getMessage());
            verifyNoInteractions(builder, deploymentFileService, screenshotService);
        }
    }

    @Test
    void missingAppFailsBeforeLeaseAcquisition() {
        doReturn(null).when(service).getById(APP_ID);

        assertPreconditionWinsOverActiveLease("应用不存在", loginUser());
    }

    @Test
    void invalidLoginUserFailsBeforeQueryAndLeaseAcquisition() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deployApp(
                        APP_ID, User.builder().id(0L).build()));

        assertEquals("用户未登录", exception.getMessage());
        verify(service, never()).getById(APP_ID);
        verifyNoInteractions(builder, deploymentFileService, screenshotService);
    }

    @Test
    void invalidCodeGenerationTypeFailsBeforeLeaseAcquisition() {
        App invalidApp = vueApp(USER_ID);
        invalidApp.setCodeGenType("unknown");
        doReturn(invalidApp).when(service).getById(APP_ID);

        assertPreconditionWinsOverActiveLease(
                "代码生成类型无效", loginUser());
    }

    @Test
    void activeGenerateRejectsBeforeSourcePathTouchWithoutLeakingOwner()
            throws Exception {
        doReturn(vueApp(USER_ID)).when(service).getById(APP_ID);
        Files.delete(sourceDirectory.resolve("dist"));
        Files.delete(sourceDirectory);
        Path target = temporaryDirectory.resolve("symbolic-target");
        Files.createDirectories(target);
        Files.createSymbolicLink(sourceDirectory, target);

        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE,
                "secret-owner-token")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.deployApp(APP_ID, loginUser()));

            assertEquals("项目正在生成或修复，请稍后再部署", exception.getMessage());
            verifyNoInteractions(builder, deploymentFileService, screenshotService);
            assertEquals(1.0, metricsRegistry.get("app_operations_total")
                    .tags("operation", "deploy", "result", "rejected",
                            "conflict_with", "generate").counter().count());
        }
    }

    @Test
    void vueBuildFailureHasNoCopyDatabaseScreenshotChatOrAiSideEffects() {
        doReturn(vueApp(USER_ID)).when(service).getById(APP_ID);
        when(builder.buildProjectDetailed(eq(sourceDirectory), any()))
                .thenReturn(new BuildResult(
                        false, BuildStage.NPM_BUILD, 1, false, "编译失败", 10L));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deployApp(APP_ID, loginUser()));

        assertEquals("Vue 项目构建失败，请稍后重试", exception.getMessage());
        verify(deploymentFileService, never()).copyDirectory(any(), any());
        verify(service, never()).updateById(any(App.class));
        verifyNoInteractions(screenshotService, chatHistoryService, aiFactory);
        verify(builder, never()).buildProject(anyString());
        leaseManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DEPLOY,
                "retry-after-build-failure").close();
    }

    @Test
    void vueSuccessUsesTrustedDetailedContextAndKeepsLeaseThroughScreenshot() {
        App app = vueApp(USER_ID);
        doReturn(app).when(service).getById(APP_ID);
        List<String> stages = new ArrayList<>();
        when(builder.buildProjectDetailed(eq(sourceDirectory), any()))
                .thenAnswer(invocation -> {
                    assertDeployLeaseActive("during-build");
                    stages.add("build");
                    return new BuildResult(
                            true, BuildStage.SUCCESS, 0, false, "ok", 10L);
                });
        doAnswer(invocation -> {
            assertDeployLeaseActive("during-copy");
            stages.add("copy");
            return null;
        }).when(deploymentFileService).copyDirectory(
                sourceDirectory.resolve("dist"), deployDirectory);
        doAnswer(invocation -> {
            assertDeployLeaseActive("during-db-update");
            stages.add("database");
            return true;
        }).when(service).updateById(any(App.class));
        when(screenshotService.generateAndUploadScreenshot(anyString()))
                .thenAnswer(invocation -> {
                    assertDeployLeaseActive("during-screenshot");
                    stages.add("screenshot");
                    return "https://example.com/cover.png";
                });

        String url = service.deployApp(APP_ID, loginUser());

        assertEquals("http://lllyw.cn/" + DEPLOY_KEY + "/", url);
        ArgumentCaptor<BuildExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(BuildExecutionContext.class);
        verify(builder).buildProjectDetailed(
                eq(sourceDirectory), contextCaptor.capture());
        BuildExecutionContext context = contextCaptor.getValue();
        assertEquals(APP_ID, context.appId());
        assertTrue(context.turnId().startsWith("deploy-"));
        assertEquals(1, context.attempt());
        assertFalse(context.cancellation().isCancelled());
        verify(builder, never()).buildProject(anyString());
        verify(deploymentFileService).copyDirectory(
                sourceDirectory.resolve("dist"), deployDirectory);
        verify(screenshotService).generateAndUploadScreenshot(url);
        assertEquals(
                List.of("build", "copy", "database", "screenshot", "database"),
                stages);
        assertEquals(1.0, metricsRegistry.get("app_operations_total")
                .tags("operation", "deploy", "result", "acquired",
                        "conflict_with", "none").counter().count());
        verifyNoInteractions(chatHistoryService, aiFactory);
        leaseManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DEPLOY,
                "after-success").close();
    }

    @Test
    void missingDistAfterSuccessfulBuildHasNoCopyOrDatabaseSideEffects()
            throws Exception {
        doReturn(vueApp(USER_ID)).when(service).getById(APP_ID);
        Files.delete(sourceDirectory.resolve("dist"));
        when(builder.buildProjectDetailed(eq(sourceDirectory), any()))
                .thenReturn(new BuildResult(
                        true, BuildStage.SUCCESS, 0, false, "ok", 10L));

        assertThrows(
                BusinessException.class,
                () -> service.deployApp(APP_ID, loginUser()));

        verifyNoInteractions(deploymentFileService, screenshotService);
        verify(service, never()).updateById(any(App.class));
    }

    @Test
    void nonVueKeepsDirectSourceDeploymentWithoutStartingVueOrAi() {
        App htmlApp = vueApp(USER_ID);
        htmlApp.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        Path htmlSource = sourceDirectory.getParent().resolve("html_" + APP_ID);
        try {
            Files.createDirectories(htmlSource);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        doReturn(htmlApp).when(service).getById(APP_ID);
        doAnswer(invocation -> {
            assertDeployLeaseActive("during-html-copy");
            return null;
        }).when(deploymentFileService).copyDirectory(
                htmlSource, deployDirectory);
        when(screenshotService.generateAndUploadScreenshot(anyString()))
                .thenReturn("https://example.com/html-cover.png");

        service.deployApp(APP_ID, loginUser());

        verify(deploymentFileService).copyDirectory(htmlSource, deployDirectory);
        verifyNoInteractions(builder, chatHistoryService, aiFactory);
    }

    @Test
    void copyFailureReleasesDeployLeaseAndSkipsDatabaseAndScreenshot() {
        doReturn(vueApp(USER_ID)).when(service).getById(APP_ID);
        when(builder.buildProjectDetailed(eq(sourceDirectory), any()))
                .thenReturn(new BuildResult(
                        true, BuildStage.SUCCESS, 0, false, "ok", 10L));
        doAnswer(invocation -> {
            throw new BusinessException(
                    com.lyw.appgeneration.exception.ErrorCode.SYSTEM_ERROR,
                    "部署目录拷贝失败");
        }).when(deploymentFileService).copyDirectory(any(), any());

        assertThrows(
                BusinessException.class,
                () -> service.deployApp(APP_ID, loginUser()));

        verify(service, never()).updateById(any(App.class));
        verifyNoInteractions(screenshotService);
        leaseManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.DEPLOY,
                "retry-after-copy-failure").close();
    }

    @Test
    void differentAppsCanDeployInParallel() throws Exception {
        long otherAppId = 8L;
        Path otherSource = sourceDirectory.getParent().resolve(
                "vue_project_" + otherAppId);
        Path otherDeploy = deployDirectory.getParent().resolve("deploy8");
        Files.createDirectories(otherSource.resolve("dist"));
        App first = vueApp(USER_ID);
        App second = App.builder()
                .id(otherAppId).userId(USER_ID)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey("deploy8").build();
        doReturn(first).when(service).getById(APP_ID);
        doReturn(second).when(service).getById(otherAppId);
        when(builder.buildProjectDetailed(any(), any()))
                .thenReturn(new BuildResult(
                        true, BuildStage.SUCCESS, 0, false, "ok", 10L));
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothEntered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(deploymentFileService).copyDirectory(any(), any());
        when(screenshotService.generateAndUploadScreenshot(anyString()))
                .thenReturn("https://example.com/cover.png");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> firstDeploy = executor.submit(
                    () -> service.deployApp(APP_ID, loginUser()));
            Future<String> secondDeploy = executor.submit(
                    () -> service.deployApp(otherAppId, loginUser()));
            assertTrue(bothEntered.await(3, TimeUnit.SECONDS));
            release.countDown();
            firstDeploy.get(3, TimeUnit.SECONDS);
            secondDeploy.get(3, TimeUnit.SECONDS);
        }
        verify(deploymentFileService).copyDirectory(
                sourceDirectory.resolve("dist"), deployDirectory);
        verify(deploymentFileService).copyDirectory(
                otherSource.resolve("dist"), otherDeploy);
    }

    private void assertDeployLeaseActive(String owner) {
        assertThrows(
                AppOperationLeaseManager.ActiveAppOperationException.class,
                () -> leaseManager.acquire(
                        APP_ID,
                        AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                        owner));
    }

    private App vueApp(long ownerId) {
        return App.builder()
                .id(APP_ID)
                .userId(ownerId)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey(DEPLOY_KEY)
                .build();
    }

    private User loginUser() {
        return User.builder().id(USER_ID).build();
    }

    private void assertPreconditionWinsOverActiveLease(
            String expectedMessage, User user) {
        try (AppOperationLeaseManager.AppOperationLease ignored = leaseManager.acquire(
                APP_ID,
                AppOperationLeaseManager.AppOperationType.GENERATE,
                "existing-generate")) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.deployApp(APP_ID, user));

            assertEquals(expectedMessage, exception.getMessage());
            verifyNoInteractions(builder, deploymentFileService, screenshotService);
        }
    }
}
