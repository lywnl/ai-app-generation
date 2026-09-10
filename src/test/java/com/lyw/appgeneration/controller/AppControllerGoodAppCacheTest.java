package com.lyw.appgeneration.controller;

import cn.hutool.core.bean.BeanUtil;
import com.lyw.appgeneration.aop.AuthInterceptor;
import com.lyw.appgeneration.config.AppCodeDeployProperties;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.model.dto.app.AppAdminUpdateRequest;
import com.lyw.appgeneration.model.dto.app.AppQueryRequest;
import com.lyw.appgeneration.model.dto.app.AppUpdateRequest;
import com.lyw.appgeneration.common.DeleteRequest;
import com.lyw.appgeneration.service.AppDeletionPersistenceService;
import com.lyw.appgeneration.service.impl.AppDeletionPersistenceServiceImpl;
import com.lyw.appgeneration.mapper.*;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.service.AppDeployUrlBuilder;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.GoodAppCacheService;
import com.lyw.appgeneration.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppControllerGoodAppCacheTest {

    private AnnotationConfigApplicationContext context;
    private AppController controller;
    private AppService appService;
    private CacheManager caches;
    private final Map<Long, App> database = new LinkedHashMap<>();
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("cacheManager", CacheManager.class, this::createCacheManager);
        context.register(Config.class);
        context.refresh();
        controller = context.getBean(AppController.class);
        appService = context.getBean(AppService.class);
        caches = context.getBean(CacheManager.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        when(context.getBean(UserService.class).getLoginUser(any()))
                .thenReturn(User.builder().id(9L).userRole("admin").build());
        database.put(1L, App.builder().id(1L).userId(9L).priority(99).appName("精选一").build());
        database.put(2L, App.builder().id(2L).userId(9L).priority(99).appName("精选二").build());
        database.put(3L, App.builder().id(3L).userId(9L).priority(0).appName("普通应用").build());
        when(appService.getById(anyLong())).thenAnswer(call -> database.get(call.getArgument(0)));
        when(appService.getQueryWrapper(any())).thenReturn(QueryWrapper.create());
        when(appService.page(any(Page.class), any(QueryWrapper.class))).thenAnswer(call -> {
            Page<App> request = call.getArgument(0);
            List<App> selected = database.values().stream()
                    .filter(app -> Integer.valueOf(99).equals(app.getPriority())).toList();
            Page<App> result = new Page<>(request.getPageNumber(), request.getPageSize(), selected.size());
            result.setRecords(selected.stream()
                    .skip((request.getPageNumber() - 1) * request.getPageSize())
                    .limit(request.getPageSize()).toList());
            return result;
        });
        when(appService.getAppVOList(anyList())).thenAnswer(call -> {
            List<App> apps = call.getArgument(0);
            return apps.stream().map(app -> BeanUtil.toBean(app, AppVO.class)).toList();
        });
        when(appService.updateById(any(App.class))).thenAnswer(call -> {
            App change = call.getArgument(0);
            App stored = database.get(change.getId());
            if (change.getPriority() != null) stored.setPriority(change.getPriority());
            if (change.getAppName() != null) stored.setAppName(change.getAppName());
            return true;
        });
        when(context.getBean(AppMapper.class).deleteById(anyLong())).thenAnswer(call -> {
            database.remove(call.getArgument(0));
            return 1;
        });
        when(appService.deleteApp(anyLong(), any(User.class))).thenAnswer(call -> {
            context.getBean(AppDeletionPersistenceService.class).deleteAppData(call.getArgument(0));
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (context != null) context.close();
    }

    @Test
    void repeatedQueryUsesCacheAndFeaturedChangesReloadRecordsAndTotal() {
        assertEquals(2, query(1, "desc").getTotalRow());
        assertEquals(2, query(1, "desc").getTotalRow());
        verify(appService, times(1)).page(any(Page.class), any(QueryWrapper.class));

        changePriority(3L, 99);
        assertEquals(3, query(1, "desc").getTotalRow());
        changePriority(1L, 0);
        Page<AppVO> latest = query(1, "desc");
        assertEquals(2, latest.getTotalRow());
        assertEquals(2L, latest.getRecords().getFirst().getId());
        verify(appService, times(3)).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void allPagesAndSortVariantsAreEvictedButOtherCachesSurvive() {
        query(1, "desc");
        query(2, "desc");
        query(1, "ascend");
        caches.getCache("unrelated").put("session", "retained");
        changePriority(1L, 0);

        assertEquals(2L, query(1, "desc").getRecords().getFirst().getId());
        assertTrue(query(2, "desc").getRecords().isEmpty());
        assertEquals(1, query(1, "ascend").getTotalRow());
        verify(appService, times(6)).page(any(Page.class), any(QueryWrapper.class));
        assertEquals("retained", caches.getCache("unrelated").get("session", String.class));
    }

    @Test
    void eleventhPageAlwaysQueriesDatabase() {
        query(11, "desc");
        query(11, "desc");
        verify(appService, times(2)).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void failedUpdateDoesNotEvict() {
        query(1, "desc");
        when(appService.updateById(any(App.class))).thenReturn(false);
        assertThrows(BusinessException.class, () -> changePriority(1L, 0));
        query(1, "desc");
        verify(appService, times(1)).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void nonAdminIsRejectedByActualAuthorizationAspectWithoutEviction() {
        query(1, "desc");
        when(context.getBean(UserService.class).getLoginUser(any()))
                .thenReturn(User.builder().id(9L).userRole("user").build());
        assertThrows(BusinessException.class, () -> changePriority(1L, 0));
        query(1, "desc");
        verify(appService, never()).updateById(any(App.class));
        verify(appService, times(1)).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void deletionCommitReloadsRecordsAndTotal() {
        assertEquals(2, query(1, "desc").getTotalRow());
        DeleteRequest request = new DeleteRequest();
        request.setId(1L);
        assertTrue(controller.deleteAppByAdmin(request, http).getData());
        Page<AppVO> latest = query(1, "desc");
        assertEquals(1, latest.getTotalRow());
        assertEquals(2L, latest.getRecords().getFirst().getId());
    }

    @Test
    void ownerRenameEvictsFeaturedResponse() {
        query(1, "desc");
        AppUpdateRequest request = new AppUpdateRequest();
        request.setId(1L);
        request.setAppName("更新后的名称");
        assertTrue(controller.updateApp(request, http).getData());
        assertEquals("更新后的名称", query(1, "desc").getRecords().getFirst().getAppName());
    }

    @Test
    void nonOwnerCannotRenameOrEvict() {
        query(1, "desc");
        when(context.getBean(UserService.class).getLoginUser(any()))
                .thenReturn(User.builder().id(100L).userRole("user").build());
        AppUpdateRequest request = new AppUpdateRequest();
        request.setId(1L);
        request.setAppName("不允许更新");
        assertThrows(BusinessException.class, () -> controller.updateApp(request, http));
        query(1, "desc");
        verify(appService, never()).updateById(any(App.class));
        verify(appService, times(1)).page(any(Page.class), any(QueryWrapper.class));
    }

    protected CacheManager createCacheManager() {
        return new ConcurrentMapCacheManager();
    }

    private void changePriority(long appId, int priority) {
        AppAdminUpdateRequest request = new AppAdminUpdateRequest();
        request.setId(appId);
        request.setPriority(priority);
        assertTrue(controller.updateAppByAdmin(request).getData());
    }

    private Page<AppVO> query(int page, String order) {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(page);
        request.setPageSize(1);
        request.setSortField("createTime");
        request.setSortOrder(order);
        return controller.listGoodAppVOByPage(request).getData();
    }

    @Configuration
    @EnableCaching(proxyTargetClass = true)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement
    @Import({AppController.class, AuthInterceptor.class, GoodAppCacheService.class,
            AppDeletionPersistenceServiceImpl.class})
    static class Config {
        @Bean ChatHistoryMapper chatHistoryMapper() { return mock(ChatHistoryMapper.class); }
        @Bean AppMemorySummaryMapper summaryMapper() { return mock(AppMemorySummaryMapper.class); }
        @Bean AppMemoryExtractCursorMapper cursorMapper() { return mock(AppMemoryExtractCursorMapper.class); }
        @Bean AppMemoryMapper memoryMapper() { return mock(AppMemoryMapper.class); }
        @Bean AppMapper appMapper() { return mock(AppMapper.class); }
        @Bean AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }
        @Bean
        AppService appService() { return mock(AppService.class); }
        @Bean
        UserService userService() { return mock(UserService.class); }
        @Bean
        AppLifecycleMetricsCollector appLifecycleMetricsCollector() {
            return new AppLifecycleMetricsCollector(new SimpleMeterRegistry());
        }
        @Bean
        AppDeployUrlBuilder appDeployUrlBuilder() {
            AppCodeDeployProperties properties = new AppCodeDeployProperties();
            properties.setBaseUrl("http://cache-test.example");
            return new AppDeployUrlBuilder(properties);
        }
    }
}
