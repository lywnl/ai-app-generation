package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.config.AppCodeDeployProperties;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.service.AppDeployUrlBuilder;
import com.lyw.appgeneration.service.AppDeploymentFileService;
import com.lyw.appgeneration.service.AppStoragePathResolver;
import com.lyw.appgeneration.service.GoodAppCacheService;
import com.lyw.appgeneration.service.ScreenshotService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppServiceGoodAppCacheTest {

    @TempDir
    Path directory;
    private AppServiceImpl service;
    private final ScreenshotService screenshots = mock(ScreenshotService.class);
    private final ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager();
    private final Cache featured = caches.getCache(GoodAppCacheService.CACHE_NAME);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private AppOperationLeaseManager leases;

    @BeforeEach
    void setUp() throws Exception {
        service = spy(new AppServiceImpl());
        leases = new AppOperationLeaseManager();
        ReflectionTestUtils.setField(service, "goodAppCacheService", new GoodAppCacheService(caches));
        ReflectionTestUtils.setField(service, "screenshotService", screenshots);
        ReflectionTestUtils.setField(service, "appOperationLeaseManager", leases);
        ReflectionTestUtils.setField(service, "appLifecycleMetricsCollector",
                new AppLifecycleMetricsCollector(metrics));
        Path source = directory.resolve("source");
        Path deploy = directory.resolve("deploy");
        Files.createDirectories(source.resolve("html_7"));
        Files.createDirectories(deploy);
        ReflectionTestUtils.setField(service, "appStoragePathResolver", new AppStoragePathResolver(source, deploy));
        ReflectionTestUtils.setField(service, "appDeploymentFileService", mock(AppDeploymentFileService.class));
        AppCodeDeployProperties properties = new AppCodeDeployProperties();
        properties.setBaseUrl("http://cache-test.example");
        ReflectionTestUtils.setField(service, "appDeployUrlBuilder", new AppDeployUrlBuilder(properties));
        doReturn(App.builder().id(7L).userId(9L).codeGenType("html").deployKey("test7").build())
                .when(service).getById(7L);
        doReturn(true).when(service).updateById(any(App.class));
        featured.put("page", "old");
    }

    @AfterEach
    void tearDown() {
        metrics.close();
    }

    @Test
    void screenshotUpdateClearsOnlyAfterDatabaseSuccess() {
        when(screenshots.generateAndUploadScreenshot(anyString())).thenReturn("http://images.example/cover.png");
        doAnswer(call -> {
            assertNotNull(featured.get("page"));
            return true;
        }).when(service).updateById(any(App.class));
        service.generateAppScreenshotAsync(7L, "http://cache-test.example/test7/");
        assertNull(featured.get("page"));
    }

    @Test
    void failedScreenshotDatabaseWritePreservesCache() {
        when(screenshots.generateAndUploadScreenshot(anyString())).thenReturn("http://images.example/cover.png");
        doReturn(false).when(service).updateById(any(App.class));
        assertThrows(BusinessException.class, () -> service.generateAppScreenshotAsync(7L, "http://test.example"));
        assertNotNull(featured.get("page"));
    }

    @Test
    void screenshotGenerationFailurePreservesCache() {
        when(screenshots.generateAndUploadScreenshot(anyString())).thenThrow(new IllegalStateException("截图失败"));
        assertThrows(IllegalStateException.class,
                () -> service.generateAppScreenshotAsync(7L, "http://test.example"));
        verify(service, never()).updateById(any(App.class));
        assertNotNull(featured.get("page"));
    }

    @Test
    void deploymentEvictsBeforeScreenshotAndStillSucceedsIfScreenshotFails() {
        doAnswer(call -> {
            assertNotNull(featured.get("page"));
            return true;
        }).when(service).updateById(any(App.class));
        when(screenshots.generateAndUploadScreenshot(anyString())).thenAnswer(call -> {
            assertNull(featured.get("page"));
            throw new IllegalStateException("截图失败");
        });
        assertEquals("http://cache-test.example/test7/",
                service.deployApp(7L, User.builder().id(9L).build()));
        assertNull(featured.get("page"));
    }

    @Test
    void failedDeploymentDatabaseWritePreservesCacheAndSkipsScreenshot() {
        doReturn(false).when(service).updateById(any(App.class));
        assertThrows(BusinessException.class,
                () -> service.deployApp(7L, User.builder().id(9L).build()));
        verifyNoInteractions(screenshots);
        assertNotNull(featured.get("page"));
    }
}
