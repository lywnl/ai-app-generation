package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringRedisChatMemoryStoreTest {

    private static final List<ChatMessage> EXPECTED = List.of(
            UserMessage.from("旧问题"), AiMessage.from("旧回答"));
    private static final List<ChatMessage> REPLACEMENT = List.of(
            UserMessage.from("新问题"), AiMessage.from("新回答"));

    @AfterEach
    void 清理中断标记() {
        Thread.interrupted();
    }

    @Test
    void 原子脚本参数必须支持匹配写入不匹配不写删除缺失键与服务端截止() {
        AtomicReference<String> redisValue = new AtomicReference<>(
                ChatMessageSerializer.messagesToJson(EXPECTED));
        AtomicLong redisTimeMillis = new AtomicLong(1_000L);
        AtomicLong writtenTtl = new AtomicLong(-1L);
        StringRedisTemplate template = semanticTemplate(
                redisValue, redisTimeMillis, writtenTtl);
        SpringRedisChatMemoryStore store = store(template);

        assertEquals(DeadlineAwareReplaceResult.REPLACED,
                store.replaceMessagesIfMatches(
                        7L, EXPECTED, REPLACEMENT,
                        deadlineAt(2_000L)));
        assertEquals(REPLACEMENT, ChatMessageDeserializer.messagesFromJson(
                redisValue.get()));
        assertEquals(3_600L, writtenTtl.get());

        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED,
                store.replaceMessagesIfMatches(
                        7L, EXPECTED, List.of(), deadlineAt(2_000L)));
        assertEquals(REPLACEMENT, ChatMessageDeserializer.messagesFromJson(
                redisValue.get()));

        assertEquals(DeadlineAwareReplaceResult.REPLACED,
                store.replaceMessagesIfMatches(
                        7L, REPLACEMENT, List.of(), deadlineAt(2_000L)));
        assertEquals(null, redisValue.get());

        assertEquals(DeadlineAwareReplaceResult.REPLACED,
                store.replaceMessagesIfMatches(
                        7L, List.of(), EXPECTED, deadlineAt(2_000L)));
        assertEquals(EXPECTED, ChatMessageDeserializer.messagesFromJson(
                redisValue.get()));

        redisTimeMillis.set(2_000L);
        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT,
                store.replaceMessagesIfMatches(
                        7L, EXPECTED, REPLACEMENT, deadlineAt(2_000L)));
        assertEquals(EXPECTED, ChatMessageDeserializer.messagesFromJson(
                redisValue.get()));

        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT,
                store.replaceMessagesIfMatches(
                        7L, REPLACEMENT, List.of(), deadlineAt(2_000L)),
                "脚本开始时已过期必须优先于快照不匹配");
        assertEquals(EXPECTED, ChatMessageDeserializer.messagesFromJson(
                redisValue.get()));
    }

    @Test
    void 连接响应异常必须等待到绝对截止才返回不确定超时() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenThrow(
                new RedisConnectionFailureException("响应读取超时"));
        AtomicLong now = new AtomicLong();
        AdmissionDeadline deadline = AdmissionDeadline.start(
                Duration.ofNanos(10L), now::get, () -> 1_000L,
                now::addAndGet);

        DeadlineAwareReplaceResult result = store(template)
                .replaceMessagesIfMatches(
                        7L, EXPECTED, REPLACEMENT, deadline);

        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT, result);
        assertEquals(10L, now.get());
    }

    @Test
    void 脚本返回空结果也必须按结果不确定等待到截止() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenReturn(null);
        AtomicLong now = new AtomicLong();
        AdmissionDeadline deadline = AdmissionDeadline.start(
                Duration.ofNanos(10L), now::get, () -> 1_000L,
                now::addAndGet);

        DeadlineAwareReplaceResult result = store(template)
                .replaceMessagesIfMatches(
                        7L, EXPECTED, REPLACEMENT, deadline);

        assertEquals(DeadlineAwareReplaceResult.TIMED_OUT, result);
        assertEquals(10L, now.get());
    }

    @Test
    void 不确定结果等待被中断时恢复标记并返回中断() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenThrow(
                new RedisConnectionFailureException("连接中断"));
        AtomicLong now = new AtomicLong();
        java.util.concurrent.atomic.AtomicInteger waits =
                new java.util.concurrent.atomic.AtomicInteger();
        AdmissionDeadline deadline = AdmissionDeadline.start(
                Duration.ofNanos(10L), now::get, () -> 1_000L,
                nanos -> {
                    if (waits.incrementAndGet() == 1) {
                        throw new InterruptedException("取消回合");
                    }
                    now.addAndGet(nanos);
                });

        DeadlineAwareReplaceResult result = store(template)
                .replaceMessagesIfMatches(
                        7L, EXPECTED, REPLACEMENT, deadline);

        assertEquals(DeadlineAwareReplaceResult.INTERRUPTED, result);
        assertEquals(10L, now.get(), "中断后仍必须等到绝对截止");
        assertEquals(2, waits.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void 新旧快照相等也必须校验记忆ID与截止() {
        SpringRedisChatMemoryStore store = store(
                mock(StringRedisTemplate.class));

        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> store.replaceMessagesIfMatches(
                        null, EXPECTED, EXPECTED,
                        AdmissionDeadline.start(Duration.ofSeconds(1L))));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> store.replaceMessagesIfMatches(
                        7L, EXPECTED, EXPECTED, null));
    }

    @Test
    void 新旧参数相等时Redis当前快照变化仍应拒绝提交() {
        AtomicReference<String> redisValue = new AtomicReference<>(
                ChatMessageSerializer.messagesToJson(REPLACEMENT));
        StringRedisTemplate template = semanticTemplate(
                redisValue, new AtomicLong(1_000L), new AtomicLong(-1L));

        DeadlineAwareReplaceResult result = store(template)
                .replaceMessagesIfMatches(
                        7L, EXPECTED, EXPECTED, deadlineAt(2_000L));

        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED, result);
        assertEquals(REPLACEMENT, ChatMessageDeserializer.messagesFromJson(
                redisValue.get()));
    }

    private StringRedisTemplate semanticTemplate(
            AtomicReference<String> redisValue,
            AtomicLong redisTimeMillis,
            AtomicLong writtenTtl) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(template.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<Long> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        when(connection.eval(any(byte[].class),
                org.mockito.ArgumentMatchers.eq(ReturnType.INTEGER),
                org.mockito.ArgumentMatchers.eq(1),
                any(byte[][].class))).thenAnswer(invocation -> {
            String script = new String(invocation.getArgument(0),
                    java.nio.charset.StandardCharsets.UTF_8);
            byte[][] rawArguments = new byte[][]{
                    invocation.getArgument(3), invocation.getArgument(4),
                    invocation.getArgument(5), invocation.getArgument(6),
                    invocation.getArgument(7), invocation.getArgument(8)};
            String[] arguments = java.util.Arrays.stream(rawArguments)
                    .map(bytes -> new String(bytes,
                            java.nio.charset.StandardCharsets.UTF_8))
                    .toArray(String[]::new);
            assertTrue(script.contains("redis.call('TIME')"));
            int compareIndex = script.indexOf("current ~= ARGV[1]");
            int initialDeadlineCheckIndex = script.indexOf(
                    "if deadline_expired()");
            int writeDeadlineCheckIndex = script.indexOf(
                    "if deadline_expired()", compareIndex);
            int firstWriteIndex = script.indexOf("redis.call('DEL'",
                    writeDeadlineCheckIndex);
            assertTrue(initialDeadlineCheckIndex < compareIndex);
            assertTrue(compareIndex < writeDeadlineCheckIndex);
            assertTrue(writeDeadlineCheckIndex < firstWriteIndex);
            long deadlineMillis = Long.parseLong(arguments[5]);
            if (redisTimeMillis.get() >= deadlineMillis) {
                return -1L;
            }
            String current = redisValue.get() == null
                    ? "[]" : redisValue.get();
            if (!current.equals(arguments[1])) {
                return 0L;
            }
            if ("1".equals(arguments[3])) {
                redisValue.set(null);
            } else {
                redisValue.set(arguments[2]);
                writtenTtl.set(Long.parseLong(arguments[4]));
            }
            return 1L;
        });
        return template;
    }

    private SpringRedisChatMemoryStore store(StringRedisTemplate template) {
        return new SpringRedisChatMemoryStore(
                template, 3_600L,
                Duration.ofSeconds(3L), Duration.ofSeconds(3L),
                Duration.ofSeconds(3L));
    }

    private AdmissionDeadline deadlineAt(long epochMillis) {
        return AdmissionDeadline.start(
                Duration.ofMillis(epochMillis - 1_000L),
                () -> 0L, () -> 1_000L, ignored -> { });
    }
}
