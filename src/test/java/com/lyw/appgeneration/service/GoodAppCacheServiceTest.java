package com.lyw.appgeneration.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoodAppCacheServiceTest {

    private final CacheManager caches = new ConcurrentMapCacheManager();
    private final Cache featured = caches.getCache(GoodAppCacheService.CACHE_NAME);
    private final GoodAppCacheService service = new GoodAppCacheService(caches);

    @Test
    void noTransactionEvictsAllFeaturedPagesOnly() {
        featured.put("page-1", "old");
        featured.put("page-2", "old");
        caches.getCache("unrelated").put("session", "keep");

        service.evictAfterCommit(7L, "设置精选");

        assertNull(featured.get("page-1"));
        assertNull(featured.get("page-2"));
        assertEquals("keep", caches.getCache("unrelated").get("session", String.class));
    }

    @Test
    void transactionKeepsCacheUntilCommitCompletes() {
        featured.put("page", "old");
        RecordingTransactionManager manager = new RecordingTransactionManager();
        manager.beforeCommit = () -> assertNotNull(featured.get("page"));

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.evictAfterCommit(7L, "取消精选");
            assertNotNull(featured.get("page"));
        });

        assertTrue(manager.committed);
        assertNull(featured.get("page"));
    }

    @Test
    void rolledBackTransactionPreservesCache() {
        featured.put("page", "old");
        RecordingTransactionManager manager = new RecordingTransactionManager();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.evictAfterCommit(7L, "取消精选");
            status.setRollbackOnly();
        });
        assertFalse(manager.committed);
        assertNotNull(featured.get("page"));
    }

    @Test
    void retryStopsAfterFirstSuccessfulClear() {
        Cache cache = mock(Cache.class);
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache(GoodAppCacheService.CACHE_NAME)).thenReturn(cache);
        doThrow(new IllegalStateException("暂时不可用")).doNothing().when(cache).clear();

        assertDoesNotThrow(() -> new GoodAppCacheService(manager).evictAfterCommit(7L, "修改"));

        verify(cache, times(2)).clear();
    }

    @Test
    void failedClearDoesNotFailCommitAndLogsContextAfterThreeAttempts() {
        Cache cache = mock(Cache.class);
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache(GoodAppCacheService.CACHE_NAME)).thenReturn(cache);
        doThrow(new IllegalStateException("Redis 不可用")).when(cache).clear();
        GoodAppCacheService failingService = new GoodAppCacheService(manager);
        Logger logger = (Logger) LoggerFactory.getLogger(GoodAppCacheService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RecordingTransactionManager transactions = new RecordingTransactionManager();
            assertDoesNotThrow(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> failingService.evictAfterCommit(7L, "删除应用")));

            assertTrue(transactions.committed);
            verify(cache, times(3)).clear();
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertTrue(event.getFormattedMessage().contains("appId=7"));
            assertTrue(event.getFormattedMessage().contains("reason=删除应用"));
            assertNotNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void cacheLookupFailureIsAlsoRetriedWithoutChangingBusinessResult() {
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache(GoodAppCacheService.CACHE_NAME))
                .thenThrow(new IllegalStateException("连接失败"));
        assertDoesNotThrow(() -> new GoodAppCacheService(manager).evictAfterCommit(7L, "修改"));
        verify(manager, times(3)).getCache(GoodAppCacheService.CACHE_NAME);
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;
        private Runnable beforeCommit = () -> { };
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            beforeCommit.run();
            committed = true;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
