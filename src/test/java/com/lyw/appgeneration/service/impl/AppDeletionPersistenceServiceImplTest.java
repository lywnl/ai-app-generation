package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.service.AppDeletionPersistenceService;
import com.lyw.appgeneration.service.GoodAppCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AppDeletionPersistenceServiceImplTest {

    @Test
    void deletesAppScopedRowsInOrderAndKeepsLongTermMemoryBody() {
        Fixtures fixtures = new Fixtures();
        AppDeletionPersistenceService service = fixtures.service();

        service.deleteAppData(7L);

        InOrder order = inOrder(fixtures.chatHistory, fixtures.summary,
                fixtures.cursor, fixtures.memory, fixtures.app);
        order.verify(fixtures.chatHistory).deleteByQuery(any());
        order.verify(fixtures.summary).deleteByQuery(any());
        order.verify(fixtures.cursor).deleteByQuery(any());
        order.verify(fixtures.memory).unlinkAppId(7L);
        order.verify(fixtures.app).deleteById(7L);
        verify(fixtures.memory, never()).deleteByQuery(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void failureAtAnyDatabaseStepStopsAllLaterWrites(int failedStep) {
        Fixtures fixtures = new Fixtures();
        fixtures.failAt(failedStep);

        assertThrows(IllegalStateException.class,
                () -> fixtures.service().deleteAppData(7L));

        fixtures.verifyNoCallsAfter(failedStep);
    }

    @Test
    void springProxyAppliesRequiresNewAndRollsBackOnFailure() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfig.class)) {
            AppDeletionPersistenceService service =
                    context.getBean(AppDeletionPersistenceService.class);
            RecordingTransactionManager transactions =
                    context.getBean(RecordingTransactionManager.class);
            AppMemoryExtractCursorMapper cursor =
                    context.getBean(AppMemoryExtractCursorMapper.class);
            Cache featured = context.getBean(CacheManager.class).getCache(GoodAppCacheService.CACHE_NAME);
            featured.put("page", "old");
            doAnswer(call -> {
                assertNotNull(featured.get("page"));
                assertEquals(0, transactions.commits);
                return 1;
            }).when(context.getBean(AppMapper.class)).deleteById(7L);
            assertTrue(AopUtils.isAopProxy(service));

            service.deleteAppData(7L);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    transactions.propagation);
            assertEquals(1, transactions.commits);
            assertNull(featured.get("page"));

            featured.put("page", "old");
            doThrow(new IllegalStateException("cursor down"))
                    .when(cursor).deleteByQuery(any());
            assertThrows(IllegalStateException.class,
                    () -> service.deleteAppData(8L));
            assertEquals(1, transactions.rollbacks);
            assertNotNull(featured.get("page"));
        }
    }

    @Test
    void databaseCommitFailureDoesNotRunRegisteredEviction() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfig.class)) {
            Cache featured = context.getBean(CacheManager.class).getCache(GoodAppCacheService.CACHE_NAME);
            featured.put("page", "old");
            context.getBean(RecordingTransactionManager.class).failCommit = true;
            assertThrows(IllegalStateException.class,
                    () -> context.getBean(AppDeletionPersistenceService.class).deleteAppData(7L));
            assertNotNull(featured.get("page"));
        }
    }

    @Test
    void rejectsInvalidAppIdBeforeDatabaseWrites() {
        Fixtures fixtures = new Fixtures();

        assertThrows(IllegalArgumentException.class,
                () -> fixtures.service().deleteAppData(null));
        assertThrows(IllegalArgumentException.class,
                () -> fixtures.service().deleteAppData(0L));
        verifyNoInteractions(fixtures.chatHistory, fixtures.summary,
                fixtures.cursor, fixtures.memory, fixtures.app);
    }

    private static final class Fixtures {
        private final ChatHistoryMapper chatHistory = mock(ChatHistoryMapper.class);
        private final AppMemorySummaryMapper summary = mock(AppMemorySummaryMapper.class);
        private final AppMemoryExtractCursorMapper cursor =
                mock(AppMemoryExtractCursorMapper.class);
        private final AppMemoryMapper memory = mock(AppMemoryMapper.class);
        private final AppMapper app = mock(AppMapper.class);

        private AppDeletionPersistenceService service() {
            return new AppDeletionPersistenceServiceImpl(
                    chatHistory, summary, cursor, memory, app, mock(GoodAppCacheService.class));
        }

        private void failAt(int step) {
            switch (step) {
                case 1 -> doThrow(new IllegalStateException("chat down"))
                        .when(chatHistory).deleteByQuery(any());
                case 2 -> doThrow(new IllegalStateException("summary down"))
                        .when(summary).deleteByQuery(any());
                case 3 -> doThrow(new IllegalStateException("cursor down"))
                        .when(cursor).deleteByQuery(any());
                case 4 -> doThrow(new IllegalStateException("memory down"))
                        .when(memory).unlinkAppId(7L);
                case 5 -> doThrow(new IllegalStateException("app down"))
                        .when(app).deleteById(7L);
                default -> throw new IllegalArgumentException("未知步骤");
            }
        }

        private void verifyNoCallsAfter(int failedStep) {
            if (failedStep < 2) verifyNoInteractions(summary);
            if (failedStep < 3) verifyNoInteractions(cursor);
            if (failedStep < 4) verifyNoInteractions(memory);
            if (failedStep < 5) verifyNoInteractions(app);
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
        @Bean GoodAppCacheService goodAppCacheService(CacheManager caches) {
            return new GoodAppCacheService(caches);
        }
        @Bean RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
        @Bean ChatHistoryMapper chatHistoryMapper() { return mock(ChatHistoryMapper.class); }
        @Bean AppMemorySummaryMapper summaryMapper() { return mock(AppMemorySummaryMapper.class); }
        @Bean AppMemoryExtractCursorMapper cursorMapper() {
            return mock(AppMemoryExtractCursorMapper.class);
        }
        @Bean AppMemoryMapper memoryMapper() { return mock(AppMemoryMapper.class); }
        @Bean AppMapper appMapper() { return mock(AppMapper.class); }
        @Bean AppDeletionPersistenceService deletionPersistenceService(
                ChatHistoryMapper chatHistoryMapper,
                AppMemorySummaryMapper summaryMapper,
                AppMemoryExtractCursorMapper cursorMapper,
                AppMemoryMapper memoryMapper,
                AppMapper appMapper,
                GoodAppCacheService goodAppCacheService) {
            return new AppDeletionPersistenceServiceImpl(chatHistoryMapper,
                    summaryMapper, cursorMapper, memoryMapper, appMapper,
                    goodAppCacheService);
        }
    }

    static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {
        private int propagation = Integer.MIN_VALUE;
        private int commits;
        private int rollbacks;
        private boolean failCommit;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,
                                         TransactionDefinition definition) {
            propagation = definition.getPropagationBehavior();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new IllegalStateException("提交失败");
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
