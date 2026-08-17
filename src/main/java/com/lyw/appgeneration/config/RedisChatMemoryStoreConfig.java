package com.lyw.appgeneration.config;

import com.lyw.appgeneration.ai.memory.AtomicChatMemoryStore;
import com.lyw.appgeneration.ai.memory.SpringRedisChatMemoryStore;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedisChatMemoryStoreConfig {

    private static final Duration REQUIRED_TIMEOUT = Duration.ofSeconds(3L);

    private String host;
    private int port;
    private int database;
    @ToString.Exclude
    private String password;
    private long ttl;
    private String username;
    private Duration connectTimeout = REQUIRED_TIMEOUT;
    private Duration timeout = REQUIRED_TIMEOUT;
    private Jedis jedis = new Jedis();

    @Bean
    public JedisConnectionFactory redisConnectionFactory() {
        requireCompatibilitySettings();
        requireThreeSeconds(connectTimeout, "Redis 建连超时");
        requireThreeSeconds(timeout, "Redis 命令响应超时");
        requireThreeSeconds(jedis.pool.maxWait, "Redis 连接池等待超时");
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(database);
        standalone.setUsername(username);
        standalone.setPassword(RedisPassword.of(password));
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxWait(jedis.pool.maxWait);
        JedisClientConfiguration client = JedisClientConfiguration.builder()
                .connectTimeout(connectTimeout)
                .readTimeout(timeout)
                .usePooling()
                .poolConfig(poolConfig)
                .and()
                .build();
        return new JedisConnectionFactory(standalone, client);
    }

    @Bean
    public SpringRedisChatMemoryStore springRedisChatMemoryStore(
            StringRedisTemplate redisTemplate) {
        return new SpringRedisChatMemoryStore(
                redisTemplate, ttl, jedis.pool.maxWait,
                connectTimeout, timeout);
    }

    @Bean
    @Primary
    public AtomicChatMemoryStore atomicChatMemoryStore(
            SpringRedisChatMemoryStore redisStore) {
        return new AtomicChatMemoryStore(redisStore);
    }

    private void requireThreeSeconds(Duration actual, String name) {
        if (!REQUIRED_TIMEOUT.equals(actual)) {
            throw new IllegalStateException(name + "必须严格等于 3 秒");
        }
    }

    private void requireCompatibilitySettings() {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Redis 主机不能为空");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("Redis 端口必须位于 1..65535");
        }
        if (database != 0) {
            throw new IllegalStateException("L0 Redis database 必须严格等于 0");
        }
        if (ttl != 3_600L) {
            throw new IllegalStateException("L0 Redis TTL 必须严格等于 3600 秒");
        }
    }

    @Data
    public static class Jedis {
        private Pool pool = new Pool();
    }

    @Data
    public static class Pool {
        private Duration maxWait = REQUIRED_TIMEOUT;
    }

}
