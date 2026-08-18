package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 保持 LangChain4j JSON 协议并提供服务端截止检查的 Redis L0 store。 */
public final class SpringRedisChatMemoryStore
        implements ChatMemoryStore, DeadlineAwareChatMemoryStore {

    private static final String REDIS_KEY_PREFIX = "chat-memory:l0:v2:";

    static final String COMPARE_AND_REPLACE_SCRIPT = """
            local function deadline_expired()
                local server_time = redis.call('TIME')
                local server_millis = server_time[1] * 1000
                        + math.floor(server_time[2] / 1000)
                return server_millis >= tonumber(ARGV[5])
            end
            if deadline_expired() then
                return -1
            end
            local current = redis.call('GET', KEYS[1])
            if not current then
                current = '[]'
            end
            if current ~= ARGV[1] then
                return 0
            end
            if deadline_expired() then
                return -1
            end
            if ARGV[3] == '1' then
                redis.call('DEL', KEYS[1])
            elseif tonumber(ARGV[4]) > 0 then
                redis.call('SETEX', KEYS[1], tonumber(ARGV[4]), ARGV[2])
            else
                redis.call('SET', KEYS[1], ARGV[2])
            end
            return 1
            """;

    private static final byte[] COMPARE_AND_REPLACE =
            COMPARE_AND_REPLACE_SCRIPT.getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;
    private final Duration worstCaseCommitDuration;

    public SpringRedisChatMemoryStore(
            StringRedisTemplate redisTemplate,
            long ttlSeconds,
            Duration poolWaitTimeout,
            Duration connectTimeout,
            Duration readTimeout) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate, "Redis 模板不能为空");
        if (ttlSeconds < 0L) {
            throw new IllegalArgumentException("L0 Redis TTL 不能为负数");
        }
        this.ttlSeconds = ttlSeconds;
        this.worstCaseCommitDuration = addDurations(
                poolWaitTimeout, connectTimeout, readTimeout);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(redisKey(memoryId));
        return json == null ? List.of()
                : List.copyOf(ChatMessageDeserializer.messagesFromJson(json));
    }

    @Override
    public void updateMessages(
            Object memoryId, List<ChatMessage> messages) {
        List<ChatMessage> snapshot = List.copyOf(Objects.requireNonNull(
                messages, "L0 消息不能为空"));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("空 L0 必须通过删除表达");
        }
        String key = redisKey(memoryId);
        String json = ChatMessageSerializer.messagesToJson(snapshot);
        if (ttlSeconds > 0L) {
            redisTemplate.opsForValue().set(
                    key, json, Duration.ofSeconds(ttlSeconds));
        } else {
            redisTemplate.opsForValue().set(key, json);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(redisKey(memoryId));
    }

    @Override
    public Duration worstCaseCommitDuration() {
        return worstCaseCommitDuration;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    @Override
    public DeadlineAwareReplaceResult replaceMessagesIfMatches(
            Object memoryId,
            List<ChatMessage> expected,
            List<ChatMessage> replacement,
            AdmissionDeadline deadline) {
        String key = redisKey(memoryId);
        Objects.requireNonNull(deadline, "绝对截止不能为空");
        List<ChatMessage> expectedSnapshot = List.copyOf(
                Objects.requireNonNull(expected, "旧快照不能为空"));
        List<ChatMessage> replacementSnapshot = List.copyOf(
                Objects.requireNonNull(replacement, "新快照不能为空"));
        try {
            Long result = redisTemplate.execute((RedisCallback<Long>)
                    connection -> connection.eval(
                    COMPARE_AND_REPLACE, ReturnType.INTEGER, 1,
                    utf8(key),
                    utf8(ChatMessageSerializer.messagesToJson(expectedSnapshot)),
                    utf8(ChatMessageSerializer.messagesToJson(replacementSnapshot)),
                    utf8(replacementSnapshot.isEmpty() ? "1" : "0"),
                    utf8(Long.toString(ttlSeconds)),
                    utf8(Long.toString(deadline.serverDeadlineEpochMillis()))));
            if (Long.valueOf(1L).equals(result)) {
                return DeadlineAwareReplaceResult.REPLACED;
            }
            if (Long.valueOf(0L).equals(result)) {
                return DeadlineAwareReplaceResult.PREFIX_CHANGED;
            }
            if (Long.valueOf(-1L).equals(result)) {
                return DeadlineAwareReplaceResult.TIMED_OUT;
            }
            if (result == null) {
                return awaitUncertainResult(deadline);
            }
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        } catch (RedisConnectionFailureException exception) {
            return awaitUncertainResult(deadline);
        } catch (DataAccessException exception) {
            return DeadlineAwareReplaceResult.DEPENDENCY_FAILED;
        }
    }

    private DeadlineAwareReplaceResult awaitUncertainResult(
            AdmissionDeadline deadline) {
        return deadline.awaitExpirationPreservingInterrupt()
                ? DeadlineAwareReplaceResult.INTERRUPTED
                : DeadlineAwareReplaceResult.TIMED_OUT;
    }

    private String redisKey(Object memoryId) {
        String key = Objects.requireNonNull(
                memoryId, "memoryId 不能为空").toString();
        if (key.trim().isEmpty()) {
            throw new IllegalArgumentException("memoryId 不能为空字符串");
        }
        return REDIS_KEY_PREFIX + key;
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Duration addDurations(Duration... durations) {
        Duration total = Duration.ZERO;
        for (Duration duration : durations) {
            try {
                total = total.plus(Objects.requireNonNull(
                        duration, "Redis 超时不能为空"));
            } catch (ArithmeticException exception) {
                return Duration.ofNanos(Long.MAX_VALUE);
            }
        }
        return total;
    }
}
