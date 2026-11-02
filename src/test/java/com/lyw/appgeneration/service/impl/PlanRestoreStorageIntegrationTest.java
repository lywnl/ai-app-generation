package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.*;
import com.lyw.appgeneration.ai.plan.*;
import com.lyw.appgeneration.monitor.MemoryCompressionMetricsCollector;
import com.lyw.appgeneration.service.*;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Redis CAS/TTL 与 MySQL 摘要边界；仅使用夹具占用的三个随机应用键。 */
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(\\?.*)?")
@EnabledIfEnvironmentVariable(named = "UNIFIED_MEMORY_REDIS_PORT", matches = "[0-9]+")
class PlanRestoreStorageIntegrationTest extends UnifiedMemoryStorageFixture {
    @Test
    void 独立Java维护命令可对显式测试应用完成停写降级() throws Exception {
        var source = withRestore(memory.sourceSnapshot());
        redis.opsForValue().set(l0Key(), L0SnapshotCodec.encode(source), Duration.ofMinutes(3));
        var log = java.nio.file.Files.createTempFile("l0-downgrade-cli-", ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classpath,
                L0PlanRestoreDowngrade.class.getName(), "--writers-stopped", "--app-id", Long.toString(appId))
                .redirectErrorStream(true).redirectOutput(log.toFile());
        var environment = builder.environment();
        environment.put("L0_REDIS_HOST", "127.0.0.1");
        environment.put("L0_REDIS_PORT", System.getenv("UNIFIED_MEMORY_REDIS_PORT"));
        environment.put("L0_REDIS_DATABASE", "0");
        for (String suffix : List.of("USERNAME", "PASSWORD")) {
            String value = System.getenv("UNIFIED_MEMORY_REDIS_" + suffix);
            if (value == null || value.isBlank()) environment.remove("L0_REDIS_" + suffix);
            else environment.put("L0_REDIS_" + suffix, value);
        }
        var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "降级命令超时");
            assertEquals(0, process.exitValue(), () -> "维护进程失败，日志=" + log);
            assertTrue(java.nio.file.Files.readString(log).contains("result=DOWNGRADED"));
            assertEquals(L0SnapshotCodec.downgradeToV1(source), memory.sourceSnapshot());
            assertTrue(redis.getExpire(l0Key(), TimeUnit.MILLISECONDS) > 0);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void v1首次恢复原子升级且连续真实Redis请求复用相同窗口() {
        var before = memory.sourceSnapshot();
        String v1 = L0SnapshotCodec.encode(L0SnapshotCodec.downgradeToV1(before));
        redis.opsForValue().set(l0Key(), v1, Duration.ofMinutes(10));
        assertEquals(1, memory.sourceSnapshot().formatVersion());
        var reads = new AtomicInteger();
        var layered = layered();
        var boundary = boundary(reads);
        var coordinator = coordinator();
        String restoreId = null;
        for (int i = 0; i < 4; i++) {
            var result = coordinator.admit(layered, List.of(), List.of(), ignored -> { }, context, boundary);
            assertTrue(result.canProceed(), result.detail());
            var current = memory.sourceSnapshot();
            assertEquals(2, current.formatVersion());
            assertEquals(before.entries(), current.entries());
            assertEquals(before.memoryEpoch(), current.memoryEpoch());
            assertEquals(before.nextSequence(), current.nextSequence());
            assertEquals(before.appliedSummaryRevision(), current.appliedSummaryRevision());
            if (restoreId == null) restoreId = current.planRestore().restoreId();
            assertEquals(restoreId, current.planRestore().restoreId());
            assertTrue(result.requestMessages().contains(current.planRestore().messages().getFirst()));
            assertEquals(estimator.estimateRequest(result.requestMessages(), List.of()), result.finalTokens());
        }
        assertEquals(1, reads.get());
        String v2 = redis.opsForValue().get(l0Key());
        System.out.println("真实 Redis 快照比较：v1字节=" + v1.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "；v2字节=" + v2.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "；请求=4；上下文计划读取=1；恢复=1");
    }

    @Test
    void v1期望比较成功升级且迟到恢复不能覆盖并发原文() {
        var initial = L0SnapshotCodec.downgradeToV1(memory.sourceSnapshot());
        redis.opsForValue().set(l0Key(), L0SnapshotCodec.encode(initial), Duration.ofMinutes(10));
        var expected = memory.sourceSnapshot();
        var appended = L0SourceOperations.append(expected, AiMessage.from("并发追加胜出"), null);
        assertEquals(DeadlineAwareReplaceResult.REPLACED, memory.replaceSourceSnapshot(expected, appended, deadline()));
        assertEquals(2, memory.sourceSnapshot().formatVersion());
        assertEquals(DeadlineAwareReplaceResult.PREFIX_CHANGED,
                memory.replaceSourceSnapshot(expected, withRestore(expected), deadline()));
        assertEquals(appended, memory.sourceSnapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 停写降级保留原文来源摘要依赖和剩余TTL(boolean expires) {
        var source = withRestore(memory.sourceSnapshot());
        if (expires) redis.opsForValue().set(l0Key(), L0SnapshotCodec.encode(source), Duration.ofMinutes(3));
        else redis.opsForValue().set(l0Key(), L0SnapshotCodec.encode(source));
        long ttlBefore = redis.getExpire(l0Key(), TimeUnit.MILLISECONDS);
        assertEquals(L0PlanRestoreDowngrade.Result.DOWNGRADED, L0PlanRestoreDowngrade.downgrade(redis, appId));
        var after = memory.sourceSnapshot();
        assertEquals(1, after.formatVersion());
        assertEquals(source.entries(), after.entries());
        assertEquals(source.turns(), after.turns());
        assertEquals(source.nextSequence(), after.nextSequence());
        assertEquals(source.memoryEpoch(), after.memoryEpoch());
        assertEquals(source.appliedSummaryRevision(), after.appliedSummaryRevision());
        assertNull(after.planRestore());
        long ttlAfter = redis.getExpire(l0Key(), TimeUnit.MILLISECONDS);
        if (expires) assertTrue(ttlAfter > 0 && ttlAfter <= ttlBefore);
        else assertEquals(-1, ttlAfter);
        assertEquals(L0PlanRestoreDowngrade.Result.ALREADY_OLD_FORMAT, L0PlanRestoreDowngrade.downgrade(redis, appId));
    }

    @Test
    void 降级精确CAS保留竞态胜出者未知格式与缺失键不会被覆盖() {
        var source = withRestore(memory.sourceSnapshot());
        redis.opsForValue().set(l0Key(), L0SnapshotCodec.encode(source));
        var winner = L0SourceOperations.append(source, AiMessage.from("回退期间的并发原文"), null);
        var racing = new StringRedisTemplate(redis.getConnectionFactory()) {
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                redis.opsForValue().set(l0Key(), L0SnapshotCodec.encode(winner));
                return super.execute(script, keys, args);
            }
        };
        assertEquals(L0PlanRestoreDowngrade.Result.CONFLICT, L0PlanRestoreDowngrade.downgrade(racing, appId));
        assertEquals(winner, memory.sourceSnapshot());
        String invalid = L0SnapshotCodec.encode(source).replace("\"formatVersion\":2", "\"formatVersion\":99");
        redis.opsForValue().set(l0Key(), invalid);
        assertThrows(IllegalArgumentException.class, () -> L0PlanRestoreDowngrade.downgrade(redis, appId));
        assertEquals(invalid, redis.opsForValue().get(l0Key()));
        redis.delete(l0Key());
        assertEquals(L0PlanRestoreDowngrade.Result.ABSENT, L0PlanRestoreDowngrade.downgrade(redis, appId));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(l0Key())));
    }

    @Test
    void 真实摘要提交排除恢复材料并按新覆盖恢复一次() {
        var reads = new AtomicInteger();
        var layered = layered();
        var coordinator = coordinator();
        var boundary = boundary(reads);
        assertTrue(coordinator.admit(layered, List.of(), List.of(), ignored -> { }, context, boundary).canProceed());
        var restore = memory.sourceSnapshot().planRestore();
        assertNotNull(restore);
        when(model.chat(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0);
            assertFalse(input.contains(restore.body()));
            assertFalse(input.contains("恢复时的计划快照"));
            assertFalse(input.contains(restore.restoreId()));
            return UnifiedSummaryCoordinationTest.SUMMARY;
        });
        var compressed = service.compressUnified(request, deadline());
        assertEquals(MemoryCompressionResult.Status.COMPRESSED, compressed.status());
        assertNull(memory.sourceSnapshot().planRestore());
        var result = coordinator.admit(layered, List.of(), List.of(), ignored -> { }, context, boundary);
        assertTrue(result.canProceed(), result.detail());
        var latest = memory.sourceSnapshot().planRestore();
        assertNotEquals(restore.restoreId(), latest.restoreId());
        assertEquals(PlanCoverageBoundary.of(service.readSnapshot(appId)), latest.coverage());
        assertEquals(2, reads.get());
        assertTrue(coordinator.admit(layered, List.of(), List.of(), ignored -> { }, context, boundary).canProceed());
        assertEquals(latest, memory.sourceSnapshot().planRestore());
        assertEquals(2, reads.get());
        verify(model, times(1)).chat(anyString());
        assertFalse(historiesMapper.selectOneById(10L).getDecisionEvidence().contains(restore.restoreId()));
    }

    private CompressionAwareChatMemory layered() {
        return new CompressionAwareChatMemory(memory, service, mock(UserMemoryService.class),
                new UserPreferenceMessageFragmentBuilder(estimator, properties), true);
    }
    private ContextCompressionCoordinator coordinator() {
        return new ContextCompressionCoordinator(estimator, histories, service, properties, models, workers, workers,
                lifecycle, new MemoryCompressionMetricsCollector(registry));
    }
    private TurnRequestBoundary boundary(AtomicInteger reads) {
        return TurnRequestBoundary.of(initialUserText(), List.of(), 10, context.turnId()).withPlanReader(() -> {
            reads.incrementAndGet(); return Optional.of(plan());
        });
    }
    private AppPlan plan() {
        return new AppPlan("storage-plan", context.turnId(), context.turnId(), 1, 1, PlanMode.FULL,
                "真实存储合成计划", List.of(new PlanFile("src/App.vue", "中文页面", PlanFileAction.CREATE,
                List.of(), PlanFileState.PENDING)), List.of(), PlanStatus.PLANNED);
    }
    private L0Snapshot withRestore(L0Snapshot source) {
        var window = new ArrayList<L0Snapshot.Entry>(source.window());
        window.add(new L0Snapshot.PlanRestore("storage-restore", PlanDefinition.of(plan()).identity(),
                PlanCoverageBoundary.of(service.readSnapshot(appId)), "恢复时的计划快照：仅用于隔离存储验证"));
        return new L0Snapshot(2, source.memoryId(), source.memoryEpoch(), source.nextSequence(),
                source.appliedSummaryRevision(), window, source.turns());
    }
}
