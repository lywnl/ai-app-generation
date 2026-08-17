package com.lyw.appgeneration.config;

import com.lyw.appgeneration.ai.memory.DeadlineAwareChatMemoryStore;
import com.lyw.appgeneration.ai.memory.SpringRedisChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedisL0StoreProductionConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            RedisAutoConfiguration.class))
                    .withUserConfiguration(RedisChatMemoryStoreConfig.class)
                    .withPropertyValues(
                            "spring.data.redis.host=127.0.0.1",
                            "spring.data.redis.port=6379",
                            "spring.data.redis.database=0",
                            "spring.data.redis.username=default",
                            "spring.data.redis.password=secret",
                            "spring.data.redis.ttl=3600",
                            "spring.data.redis.connect-timeout=3s",
                            "spring.data.redis.timeout=3s",
                            "spring.data.redis.jedis.pool.max-wait=3s");

    @Test
    void 生产Bean必须使用三段三秒上限的Jedis并保留数据库与TTL() {
        contextRunner.run(context -> {
            RedisConnectionFactory connectionFactory =
                    context.getBean(RedisConnectionFactory.class);
            JedisConnectionFactory jedis = assertInstanceOf(
                    JedisConnectionFactory.class, connectionFactory);
            SpringRedisChatMemoryStore store = context.getBean(
                    SpringRedisChatMemoryStore.class);

            assertEquals(0, jedis.getDatabase());
            assertEquals(Duration.ofSeconds(3L),
                    jedis.getClientConfiguration().getConnectTimeout());
            assertEquals(Duration.ofSeconds(3L),
                    jedis.getClientConfiguration().getReadTimeout());
            assertTrue(jedis.getClientConfiguration().isUsePooling());
            assertEquals(Duration.ofSeconds(3L), jedis.getPoolConfig()
                    .getMaxWaitDuration());
            assertEquals(3_600L, store.ttlSeconds());
            assertEquals(Duration.ofSeconds(9L),
                    store.worstCaseCommitDuration());
            assertInstanceOf(DeadlineAwareChatMemoryStore.class, store);
        });
    }

    @Test
    void Spring上下文不得再注册无超时LangChain4jRedisStore() {
        contextRunner.run(context -> {
            assertFalse(context.containsBean("redisChatMemoryStore"));
            assertTrue(context.getBeansOfType(SpringRedisChatMemoryStore.class)
                    .size() == 1);
            assertTrue(Arrays.stream(context.getBeanDefinitionNames())
                    .map(context::getType)
                    .filter(type -> type != null)
                    .noneMatch(type -> type.getName().equals(
                            "dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore")));
        });
    }

    @Test
    void 非兼容数据库TTL主机或端口必须拒绝启动() {
        assertConfigurationRejected("spring.data.redis.database=1");
        assertConfigurationRejected("spring.data.redis.ttl=0");
        assertConfigurationRejected("spring.data.redis.ttl=60");
        assertConfigurationRejected("spring.data.redis.host=");
        assertConfigurationRejected("spring.data.redis.port=0");
        assertConfigurationRejected("spring.data.redis.port=65536");
    }

    private void assertConfigurationRejected(String override) {
        contextRunner.withPropertyValues(override).run(context ->
                assertNotNull(context.getStartupFailure(),
                        () -> "配置应拒绝启动：" + override));
    }
}
