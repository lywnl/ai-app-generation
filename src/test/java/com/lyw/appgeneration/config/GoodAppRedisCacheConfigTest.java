package com.lyw.appgeneration.config;

import com.lyw.appgeneration.service.GoodAppCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoodAppRedisCacheConfigTest {

    @Test
    void featuredClearUsesScanWithBoundedBatchAndCachePrefix() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.scan(any(ScanOptions.class))).thenReturn(cursor);
        byte[] key = "good_app_page::test-page".getBytes(StandardCharsets.UTF_8);
        Iterator<byte[]> keys = List.of(key).iterator();
        when(cursor.hasNext()).thenAnswer(call -> keys.hasNext());
        when(cursor.next()).thenAnswer(call -> keys.next());
        RedisCacheManagerConfig configuration = new RedisCacheManagerConfig();
        ReflectionTestUtils.setField(configuration, "redisConnectionFactory", factory);
        RedisCacheManager manager = (RedisCacheManager) configuration.cacheManager();
        manager.afterPropertiesSet();

        manager.getCache(GoodAppCacheService.CACHE_NAME).clear();

        ArgumentCaptor<ScanOptions> scan = ArgumentCaptor.forClass(ScanOptions.class);
        verify(connection).scan(scan.capture());
        assertEquals(1000L, scan.getValue().getCount());
        assertEquals("good_app_page::*", scan.getValue().getPattern());
        verify(connection, never()).keys(any());
        verify(connection).del(new byte[][]{key});
        verify(connection).close();
        assertEquals(Duration.ofMinutes(5), manager.getCacheConfigurations()
                .get(GoodAppCacheService.CACHE_NAME).getTtl());
    }
}
