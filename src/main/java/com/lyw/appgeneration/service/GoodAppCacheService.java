package com.lyw.appgeneration.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 精选分页在数据提交后整体失效，避免分页位置和总数残留。 */
@Slf4j
@Service
public class GoodAppCacheService {

    public static final String CACHE_NAME = "good_app_page";
    private static final int MAX_ATTEMPTS = 3;

    private final CacheManager cacheManager;

    public GoodAppCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** 有事务时仅在提交后失效；缓存故障不改变已提交的业务结果。 */
    public void evictAfterCommit(long appId, String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictWithRetry(appId, reason);
                }
            });
            return;
        }
        evictWithRetry(appId, reason);
    }

    private void evictWithRetry(long appId, String reason) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Cache cache = cacheManager.getCache(CACHE_NAME);
                if (cache == null) {
                    throw new IllegalStateException("精选分页缓存未配置");
                }
                cache.clear();
                return;
            } catch (RuntimeException exception) {
                // 数据库已提交，有限重试后交由缓存剩余有效期兜底。
                lastFailure = exception;
            }
        }
        log.error("应用数据已提交，但精选分页缓存清理失败: appId={}, reason={}, attempts={}",
                appId, reason, MAX_ATTEMPTS, lastFailure);
    }
}
