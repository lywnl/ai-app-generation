package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.memory.ToolMessageCollapser;
import com.lyw.appgeneration.core.concurrency.AppDataLifecycleFence;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.VueTurnFinalizer;
import com.lyw.appgeneration.monitor.VueBuildRepairMetricsCollector;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.UserMemoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FileToolBudgetGuardTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(BudgetConfiguration.class);

    private final ApplicationContextRunner finalizerContextRunner = contextRunner
            .withBean(ChatHistoryService.class,
                    () -> mock(ChatHistoryService.class))
            .withBean(ToolMessageCollapser.class,
                    () -> mock(ToolMessageCollapser.class))
            .withBean(MemorySummaryService.class,
                    () -> mock(MemorySummaryService.class))
            .withBean(UserMemoryService.class,
                    () -> mock(UserMemoryService.class))
            .withInitializer(context -> context.getBeanFactory()
                    .registerSingleton("aiGeneratorServiceFactory",
                            mock(AiGeneratorServiceFactory.class)))
            .withBean(AppDataLifecycleFence.class, AppDataLifecycleFence::new)
            .withBean(VueBuildRepairMetricsCollector.class,
                    () -> new VueBuildRepairMetricsCollector(
                            new SimpleMeterRegistry()))
            .withBean(VueTurnFinalizer.class);

    @Test
    void Spring上下文必须绑定五项预算覆盖值() {
        contextRunner.withPropertyValues(
                        "ai.vue.tool-budget.max-single-file-code-points=11",
                        "ai.vue.tool-budget.max-cumulative-mutation-code-points=22",
                        "ai.vue.tool-budget.max-canonical-ai-text-code-points=33",
                        "ai.vue.tool-budget.max-read-file-code-points=44",
                        "ai.vue.tool-budget.max-read-dir-code-points=55")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    FileToolBudgetGuard guard = context.getBean(
                            FileToolBudgetGuard.class);
                    assertEquals(11, guard.getMaxSingleFileCodePoints());
                    assertEquals(22, guard.getMaxCumulativeMutationCodePoints());
                    assertEquals(33, guard.getMaxCanonicalAiTextCodePoints());
                    assertEquals(44, guard.getMaxReadFileCodePoints());
                    assertEquals(55, guard.getMaxReadDirCodePoints());
                });
    }

    @Test
    void Spring上下文必须在预算为零负数或层级倒置时启动失败() {
        assertContextStartupFails(
                "ai.vue.tool-budget.max-single-file-code-points=0");
        assertContextStartupFails(
                "ai.vue.tool-budget.max-read-file-code-points=-1");
        assertContextStartupFails(
                "ai.vue.tool-budget.max-single-file-code-points=300000");
        assertContextStartupFails(
                "ai.vue.tool-budget.max-canonical-ai-text-code-points=200000");
    }

    @Test
    void Spring上下文必须拒绝无法容纳固定终态预留的配置() {
        int reserve = VueTurnFinalizer.terminalReserveCodePoints();
        String canonicalProperty =
                "ai.vue.tool-budget.max-canonical-ai-text-code-points=";

        finalizerContextRunner.withPropertyValues(
                        "ai.vue.tool-budget.max-single-file-code-points=1",
                        "ai.vue.tool-budget.max-cumulative-mutation-code-points=1",
                        canonicalProperty + reserve,
                        "ai.vue.tool-budget.max-read-file-code-points=1",
                        "ai.vue.tool-budget.max-read-dir-code-points=1")
                .run(context -> assertNotNull(context.getStartupFailure()));

        finalizerContextRunner.withPropertyValues(
                        "ai.vue.tool-budget.max-single-file-code-points=1",
                        "ai.vue.tool-budget.max-cumulative-mutation-code-points=1",
                        canonicalProperty + (reserve + 1),
                        "ai.vue.tool-budget.max-read-file-code-points=1",
                        "ai.vue.tool-budget.max-read-dir-code-points=1")
                .run(context -> {
                    if (context.getStartupFailure() != null) {
                        throw new AssertionError("临界合法配置不应启动失败",
                                context.getStartupFailure());
                    }
                });
    }

    @Test
    void 生产上下文和作用域管理器不得暴露绕过Spring配置的默认预算构造器() {
        assertTrue(Arrays.stream(FileToolExecutionScopeManager.class
                        .getConstructors())
                .noneMatch(constructor -> constructor.getParameterCount() == 0));
        assertTrue(Arrays.stream(VueTurnContext.class.getConstructors())
                .allMatch(constructor -> Arrays.asList(
                                constructor.getParameterTypes())
                        .contains(FileToolBudgetGuard.Session.class)));
    }

    @Test
    void 默认配置与Unicode码点边界必须稳定() {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();

        assertEquals(128_000, guard.getMaxSingleFileCodePoints());
        assertEquals(256_000, guard.getMaxCumulativeMutationCodePoints());
        assertEquals(384_000, guard.getMaxCanonicalAiTextCodePoints());
        assertEquals(128_000, guard.getMaxReadFileCodePoints());
        assertEquals(20_000, guard.getMaxReadDirCodePoints());
        assertEquals(1, FileToolBudgetGuard.codePointCount("😀"));
        assertEquals("A😀", FileToolBudgetGuard.prefixByCodePoints("A😀B", 2));
    }

    @Test
    void 非法配置必须在初始化阶段失败() {
        FileToolBudgetGuard zero = configured(0, 2, 128, 2, 2);
        FileToolBudgetGuard inverted = configured(3, 2, 128, 2, 2);
        FileToolBudgetGuard canonicalTooSmall = configured(2, 3, 2, 2, 2);

        assertThrows(IllegalStateException.class, zero::afterPropertiesSet);
        assertThrows(IllegalStateException.class, inverted::afterPropertiesSet);
        assertThrows(IllegalStateException.class, canonicalTooSmall::afterPropertiesSet);
    }

    @Test
    void 单文件与成功变更累计按码点计算且回滚不占额度() {
        FileToolBudgetGuard.Session session = configured(4, 6, 128, 4, 4).newSession();

        try (FileToolBudgetGuard.MutationReservation exact =
                     session.reserveMutation("A😀BC", "A😀BC")) {
            assertTrue(exact.accepted());
            exact.commit();
        }
        try (FileToolBudgetGuard.MutationReservation overflow =
                     session.reserveMutation("ABCDE", "A")) {
            assertFalse(overflow.accepted());
        }
        try (FileToolBudgetGuard.MutationReservation rolledBack =
                     session.reserveMutation("XY", "XY")) {
            assertTrue(rolledBack.accepted());
        }
        try (FileToolBudgetGuard.MutationReservation afterRollback =
                     session.reserveMutation("XY", "XY")) {
            assertTrue(afterRollback.accepted());
            afterRollback.commit();
        }
        try (FileToolBudgetGuard.MutationReservation cumulativeOverflow =
                     session.reserveMutation("Z", "Z")) {
            assertFalse(cumulativeOverflow.accepted());
        }
    }

    @Test
    void 并发预留不能共同越过累计上限() throws Exception {
        FileToolBudgetGuard.Session session = configured(4, 6, 128, 4, 4).newSession();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> reserveAndHold(session, start));
            var second = executor.submit(() -> reserveAndHold(session, start));
            start.countDown();

            assertEquals(1, (first.get(1, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(1, TimeUnit.SECONDS) ? 1 : 0));
        }
    }

    @Test
    void 参数广播读取和稳定正文拥有相互独立的硬边界() {
        FileToolBudgetGuard.Session session = configured(4, 6, 128, 4, 4).newSession();

        FileToolBudgetGuard.ArgumentDecision exact = session.acceptArgumentDelta(
                "tool-1", "content", "A😀BC");
        FileToolBudgetGuard.ArgumentDecision remaining = session.acceptArgumentDelta(
                "tool-2", "newContent", "XY");
        FileToolBudgetGuard.ArgumentDecision overflow = session.acceptArgumentDelta(
                "tool-2", "newContent", "Z");
        assertEquals("A😀BC", exact.acceptedPrefix());
        assertFalse(exact.resourceLimitExceeded());
        assertEquals("XY", remaining.acceptedPrefix());
        assertEquals("", overflow.acceptedPrefix());
        assertTrue(overflow.resourceLimitExceeded());

        assertTrue(session.validateReadFile("A😀BC").accepted());
        assertFalse(session.validateReadFile("A😀BCD").accepted());
        assertTrue(session.validateReadDir("A😀BC").accepted());
        assertFalse(session.validateReadDir("A😀BCD").accepted());

        FileToolBudgetGuard.CanonicalAccumulator canonical =
                session.newCanonicalAccumulator();
        assertTrue(canonical.append("正文").accepted());
        assertEquals("正文", canonical.content());
        assertTrue(session.claimResourceLimit());
        assertFalse(session.claimResourceLimit());
    }

    @Test
    void 每个会话必须从零开始计数() {
        FileToolBudgetGuard guard = configured(4, 6, 128, 4, 4);
        FileToolBudgetGuard.Session first = guard.newSession();
        assertTrue(first.acceptArgumentDelta("tool-1", "content", "123456")
                .acceptedPrefix().equals("1234"));

        FileToolBudgetGuard.Session second = guard.newSession();
        assertEquals("1234", second.acceptArgumentDelta(
                "tool-1", "content", "1234").acceptedPrefix());
    }

    @Test
    void 流式参数跨块代理对必须作为一个码点接收() {
        FileToolBudgetGuard.Session session = configured(2, 2, 64, 2, 2)
                .newSession();
        String emoji = "😀";

        FileToolBudgetGuard.ArgumentDecision high = session.acceptArgumentDelta(
                "tool-emoji", "content", emoji.substring(0, 1));
        FileToolBudgetGuard.ArgumentDecision low = session.acceptArgumentDelta(
                "tool-emoji", "content", emoji.substring(1));
        FileToolBudgetGuard.ArgumentDecision suffix = session.acceptArgumentDelta(
                "tool-emoji", "content", "A");

        assertEquals("", high.acceptedPrefix());
        assertFalse(high.resourceLimitExceeded());
        assertEquals(emoji, low.acceptedPrefix());
        assertFalse(low.resourceLimitExceeded());
        assertEquals("A", suffix.acceptedPrefix());
    }

    @Test
    void 稳定正文跨块代理对必须作为一个码点接收() {
        FileToolBudgetGuard.CanonicalAccumulator accumulator =
                configured(2, 2, 2, 2, 2).newSession()
                        .newCanonicalAccumulator();
        String emoji = "😀";

        assertEquals("", accumulator.append(emoji.substring(0, 1))
                .acceptedPrefix());
        assertEquals(emoji, accumulator.append(emoji.substring(1))
                .acceptedPrefix());
        assertTrue(accumulator.append("A").accepted());
        assertEquals(emoji + "A", accumulator.content());
    }

    @Test
    void 读取预算必须在EOF结算未配对的高代理项() {
        FileToolBudgetGuard.ReadAccumulator accepted =
                configured(2, 2, 2, 1, 1).newSession()
                        .newReadFileAccumulator();
        FileToolBudgetGuard.ReadAccumulator rejected =
                configured(2, 2, 2, 1, 1).newSession()
                        .newReadFileAccumulator();

        assertTrue(accepted.accept("\uD83D").accepted());
        assertEquals("\uFFFD", accepted.finish().acceptedText());

        assertTrue(rejected.accept("A\uD83D").accepted());
        assertFalse(rejected.finish().accepted());
    }

    private boolean reserveAndHold(
            FileToolBudgetGuard.Session session, CountDownLatch start) throws Exception {
        assertTrue(start.await(1, TimeUnit.SECONDS));
        try (FileToolBudgetGuard.MutationReservation reservation =
                     session.reserveMutation("1234", "1234")) {
            if (!reservation.accepted()) {
                return false;
            }
            reservation.commit();
            return true;
        }
    }

    private FileToolBudgetGuard configured(
            int single, int cumulative, int canonical, int readFile, int readDir) {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(single);
        guard.setMaxCumulativeMutationCodePoints(cumulative);
        guard.setMaxCanonicalAiTextCodePoints(canonical);
        guard.setMaxReadFileCodePoints(readFile);
        guard.setMaxReadDirCodePoints(readDir);
        return guard;
    }

    private void assertContextStartupFails(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootCause(context.getStartupFailure())
                    instanceof IllegalStateException);
        });
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileToolBudgetGuard.class)
    static class BudgetConfiguration {
    }
}
