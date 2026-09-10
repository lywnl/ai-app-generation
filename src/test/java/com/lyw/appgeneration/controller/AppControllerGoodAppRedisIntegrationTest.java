package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.config.RedisCacheManagerConfig;
import com.lyw.appgeneration.service.GoodAppCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 使用显式指定的本地测试 Redis；数据库由父类隔离数据夹具代替。 */
@EnabledIfEnvironmentVariable(named = "GOOD_APP_CACHE_REDIS_PORT", matches = "[0-9]+")
class AppControllerGoodAppRedisIntegrationTest extends AppControllerGoodAppCacheTest {

    private JedisConnectionFactory factory;
    private RedisCacheManager manager;
    private final String namespace = "good-app-cache-test:" + UUID.randomUUID() + ":";

    @Override
    protected CacheManager createCacheManager() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                "127.0.0.1", Integer.parseInt(System.getenv("GOOD_APP_CACHE_REDIS_PORT")));
        factory = new JedisConnectionFactory(standalone);
        factory.afterPropertiesSet();
        factory.start();
        RedisCacheManagerConfig configuration = new RedisCacheManagerConfig();
        ReflectionTestUtils.setField(configuration, "redisConnectionFactory", factory);
        RedisCacheManager configured = (RedisCacheManager) configuration.cacheManager();
        configured.afterPropertiesSet();
        RedisCache featured = (RedisCache) configured.getCache(GoodAppCacheService.CACHE_NAME);
        // 复用生产 writer 和序列化/TTL 配置，仅隔离本次测试的键前缀。
        RedisCacheConfiguration featuredConfig = configured.getCacheConfigurations()
                .get(GoodAppCacheService.CACHE_NAME)
                .computePrefixWith(name -> namespace + name + "::");
        manager = RedisCacheManager.builder(featured.getNativeCache())
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(30))
                        .computePrefixWith(name -> namespace + name + "::"))
                .withCacheConfiguration(GoodAppCacheService.CACHE_NAME, featuredConfig)
                .build();
        return manager;
    }

    @AfterEach
    void cleanupRedis() {
        try {
            if (manager != null) {
                for (String name : manager.getCacheNames()) {
                    manager.getCache(name).clear();
                }
            }
        } finally {
            if (factory != null) factory.destroy();
        }
    }

    @Test
    void scanClearsMoreThanOneBatchAndPreservesOtherCacheWithFiveMinuteTtl() {
        Cache featured = manager.getCache(GoodAppCacheService.CACHE_NAME);
        for (int index = 0; index < 1005; index++) {
            featured.put("page-" + index, "old");
        }
        manager.getCache("unrelated").put("session", "retained");
        try (RedisConnection connection = factory.getConnection();
             Cursor<byte[]> keys = connection.scan(ScanOptions.scanOptions()
                     .match(namespace + GoodAppCacheService.CACHE_NAME + "::*").count(1000).build())) {
            assertTrue(keys.hasNext());
            Long ttl = connection.pTtl(keys.next());
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(5).toMillis());
        }

        new GoodAppCacheService(manager).evictAfterCommit(7L, "隔离验收");

        try (RedisConnection connection = factory.getConnection();
             Cursor<byte[]> keys = connection.scan(ScanOptions.scanOptions()
                     .match(namespace + GoodAppCacheService.CACHE_NAME + "::*").count(1000).build())) {
            assertFalse(keys.hasNext());
        }
        assertEquals("retained", manager.getCache("unrelated").get("session", String.class));
    }
}
