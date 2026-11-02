package com.lyw.appgeneration.ai.memory;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/** 显式单应用停写回退入口；不启动 Spring 应用，不扫描键或接触业务数据库。 */
public final class L0PlanRestoreDowngrade {
    private L0PlanRestoreDowngrade() { }

    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current ~= ARGV[1] then return 0 end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl == -2 or ttl == 0 then return 0 end
            redis.call('SET', KEYS[1], ARGV[2])
            if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end
            return 1
            """, Long.class);

    public enum Result { ABSENT, ALREADY_OLD_FORMAT, DOWNGRADED, CONFLICT }

    public static Result downgrade(StringRedisTemplate redis, long appId) {
        if (appId <= 0) throw new IllegalArgumentException("必须指定正数应用 ID");
        String key = "chat-memory:l0:v2:" + appId;
        String raw = redis.opsForValue().get(key);
        if (raw == null) return Result.ABSENT;
        var source = L0SnapshotCodec.decode(Long.toString(appId), raw);
        if (source.formatVersion() < 2) return Result.ALREADY_OLD_FORMAT;
        // 完整检查来源及工具批次，损坏窗口不应被回退掩盖。
        new L0SourceParser().parse(source);
        String replacement = L0SnapshotCodec.encode(L0SnapshotCodec.downgradeToV1(source));
        Long result = redis.execute(REPLACE, List.of(key), raw, replacement);
        return Long.valueOf(1).equals(result) ? Result.DOWNGRADED : Result.CONFLICT;
    }

    public static void main(String[] args) {
        if (args.length != 3 || !"--writers-stopped".equals(args[0]) || !"--app-id".equals(args[1])) {
            throw new IllegalArgumentException("用法：--writers-stopped --app-id <应用ID>；必须先停止所有该应用的写入进程");
        }
        long appId = Long.parseLong(args[2]);
        if (appId <= 0) throw new IllegalArgumentException("应用 ID 必须为正数");
        var env = System.getenv();
        var config = new RedisStandaloneConfiguration(env.getOrDefault("L0_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("L0_REDIS_PORT", "6379")));
        config.setDatabase(Integer.parseInt(env.getOrDefault("L0_REDIS_DATABASE", "0")));
        if (env.containsKey("L0_REDIS_USERNAME")) config.setUsername(env.get("L0_REDIS_USERNAME"));
        if (env.containsKey("L0_REDIS_PASSWORD")) config.setPassword(env.get("L0_REDIS_PASSWORD"));
        var factory = new JedisConnectionFactory(config, JedisClientConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2)).build());
        try {
            factory.afterPropertiesSet();
            factory.start();
            Result result = downgrade(new StringRedisTemplate(factory), appId);
            System.out.println("L0 停写回退 appId=" + appId + " result=" + result);
            if (result == Result.CONFLICT) throw new IllegalStateException("窗口发生并发变化；保持停写并核对后重试");
        } finally {
            factory.destroy();
        }
    }
}
