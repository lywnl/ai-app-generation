package com.lyw.appgeneration.rag.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicEvaluationReportWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 原子替换旧报告且不遗留临时文件() throws Exception {
        Path report = tempDirectory.resolve("report.md");
        Files.writeString(report, "状态：通过\n旧指标：10/10\n", StandardCharsets.UTF_8);

        AtomicEvaluationReportWriter.write(report, "状态：未通过\n运行标识：run-new\n");

        assertEquals("状态：未通过\n运行标识：run-new\n",
                Files.readString(report, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDirectory)) {
            List<Path> remaining = files.toList();
            assertEquals(List.of(report), remaining);
            assertTrue(remaining.stream().noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void 异常报告写入失败时保留原异常并追加suppressed() throws Exception {
        Path reportDirectory = tempDirectory.resolve("report-directory");
        Path report = reportDirectory.resolve("report.md");
        IllegalStateException original = new IllegalStateException("业务异常");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> EvaluationReportLifecycle.<String>execute(
                        report,
                        "状态：未通过\n",
                        () -> {
                            Files.delete(report);
                            Files.createDirectory(report);
                            Files.writeString(report.resolve("阻止后续报告写入"), "占位");
                            throw original;
                        },
                        value -> value,
                        "状态：未通过\n"));

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof java.io.IOException);
    }

    @Test
    void AssertionError原样传播前覆盖最终失败报告() throws Exception {
        Path report = tempDirectory.resolve("assertion-report.md");
        AssertionError original = new AssertionError("断言失败");

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> EvaluationReportLifecycle.execute(
                        report,
                        "状态：未通过\n原因：运行中\n",
                        () -> { throw original; },
                        value -> value.toString(),
                        "状态：未通过\n原因：执行异常\n"));

        assertSame(original, thrown);
        assertEquals("状态：未通过\n原因：执行异常\n",
                Files.readString(report, StandardCharsets.UTF_8));
    }

    @Test
    void 同一JVM并发时完整报告生命周期串行且不发生重叠锁异常() throws Exception {
        Path report = tempDirectory.resolve("same-jvm-report.md");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeOperations = new AtomicInteger();
        AtomicInteger maximumActiveOperations = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> executeTrackedLifecycle(
                    report, "first", firstEntered, releaseFirst,
                    activeOperations, maximumActiveOperations));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> executeTrackedLifecycle(
                    report, "second", new CountDownLatch(0), new CountDownLatch(0),
                    activeOperations, maximumActiveOperations));

            Thread.sleep(Duration.ofMillis(200));
            releaseFirst.countDown();

            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, maximumActiveOperations.get(), "同一报告的业务操作不得重叠");
        assertEquals("最终：second\n", Files.readString(report, StandardCharsets.UTF_8));
    }

    @Test
    void 同一父目录的符号链接别名也必须共享同一JVM生命周期锁() throws Exception {
        Path realDirectory = Files.createDirectory(tempDirectory.resolve("real-report-directory"));
        Path aliasDirectory = tempDirectory.resolve("report-directory-alias");
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "当前文件系统不支持符号链接: " + exception.getMessage());
        }
        Path realReport = realDirectory.resolve("report.md");
        Path aliasReport = aliasDirectory.resolve("report.md");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeOperations = new AtomicInteger();
        AtomicInteger maximumActiveOperations = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> executeTrackedLifecycle(
                    realReport, "real", firstEntered, releaseFirst,
                    activeOperations, maximumActiveOperations));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> executeTrackedLifecycle(
                    aliasReport, "alias", new CountDownLatch(0), new CountDownLatch(0),
                    activeOperations, maximumActiveOperations));
            Thread.sleep(Duration.ofMillis(200));
            releaseFirst.countDown();

            assertEquals("real", first.get(5, TimeUnit.SECONDS));
            assertEquals("alias", second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, maximumActiveOperations.get());
        assertEquals("最终：alias\n", Files.readString(realReport, StandardCharsets.UTF_8));
    }

    @Test
    void 等待同一JVM报告锁被中断时恢复中断标记并且不执行操作() throws Exception {
        Path report = tempDirectory.resolve("interrupted-report.md");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean interruptedStatus = new AtomicBoolean();
        AtomicBoolean operationCalled = new AtomicBoolean();

        Thread first = Thread.ofVirtual().start(() -> {
            try {
                EvaluationReportLifecycle.execute(
                        report, "失效：first\n",
                        () -> {
                            firstEntered.countDown();
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                            return "first";
                        },
                        value -> "最终：" + value + "\n",
                        "失败：first\n");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                EvaluationReportLifecycle.execute(
                        report, "失效：second\n",
                        () -> {
                            operationCalled.set(true);
                            return "second";
                        },
                        value -> "最终：" + value + "\n",
                        "失败：second\n");
                fail("等待锁的线程必须响应中断");
            } catch (InterruptedException expected) {
                interruptedStatus.set(Thread.currentThread().isInterrupted());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        Thread.sleep(Duration.ofMillis(200));
        waiter.interrupt();
        waiter.join(Duration.ofSeconds(5));
        releaseFirst.countDown();
        first.join(Duration.ofSeconds(5));

        assertFalse(waiter.isAlive());
        assertTrue(interruptedStatus.get(), "传播 InterruptedException 前必须恢复中断标记");
        assertFalse(operationCalled.get());
    }

    @Test
    void 两个真实JVM竞争同一报告时生命周期不交错() throws Exception {
        Path report = tempDirectory.resolve("cross-jvm-report.md");
        Path events = tempDirectory.resolve("events.txt");
        Path ready = tempDirectory.resolve("ready");
        Path go = tempDirectory.resolve("go");
        Files.createDirectories(ready);
        Process first = startFixture(report, events, ready, go, "first");
        Process second = startFixture(report, events, ready, go, "second");
        awaitReadyFiles(ready);
        Files.createFile(go);

        assertTrue(first.waitFor(10, TimeUnit.SECONDS));
        assertTrue(second.waitFor(10, TimeUnit.SECONDS));
        String firstOutput = new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String secondOutput = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, first.exitValue(), firstOutput);
        assertEquals(0, second.exitValue(), secondOutput);

        List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
        assertTrue(lines.equals(List.of("start-first", "end-first", "start-second", "end-second"))
                        || lines.equals(List.of("start-second", "end-second", "start-first", "end-first")),
                "跨 JVM 生命周期发生交错: " + lines);
    }

    private String executeTrackedLifecycle(
            Path report,
            String id,
            CountDownLatch entered,
            CountDownLatch release,
            AtomicInteger activeOperations,
            AtomicInteger maximumActiveOperations) throws Exception {
        return EvaluationReportLifecycle.execute(
                report,
                "失效：" + id + "\n",
                () -> {
                    int active = activeOperations.incrementAndGet();
                    maximumActiveOperations.accumulateAndGet(active, Math::max);
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return id;
                    } finally {
                        activeOperations.decrementAndGet();
                    }
                },
                value -> "最终：" + value + "\n",
                "失败：" + id + "\n");
    }

    private Process startFixture(
            Path report, Path events, Path ready, Path go, String id) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ReportLifecycleFixture.class.getName());
        command.add(report.toString());
        command.add(events.toString());
        command.add(ready.toString());
        command.add(go.toString());
        command.add(id);
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private void awaitReadyFiles(Path ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (var files = Files.list(ready)) {
                if (files.count() == 2) {
                    return;
                }
            }
            Thread.sleep(Duration.ofMillis(20));
        }
        fail("两个真实 JVM 未在期限内就绪");
    }

    public static final class ReportLifecycleFixture {

        private ReportLifecycleFixture() {
        }

        public static void main(String[] args) throws Exception {
            Path report = Path.of(args[0]);
            Path events = Path.of(args[1]);
            Path ready = Path.of(args[2]);
            Path go = Path.of(args[3]);
            String id = args[4];
            Files.createFile(ready.resolve(id));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(go) && System.nanoTime() < deadline) {
                Thread.sleep(Duration.ofMillis(10));
            }
            if (!Files.exists(go)) {
                throw new IllegalStateException("等待启动信号超时");
            }
            EvaluationReportLifecycle.execute(
                    report,
                    "失效：" + id + "\n",
                    () -> {
                        appendEvent(events, "start-" + id);
                        Thread.sleep(Duration.ofMillis(300));
                        appendEvent(events, "end-" + id);
                        return id;
                    },
                    value -> "最终：" + value + "\n",
                    "失败：" + id + "\n");
        }

        private static void appendEvent(Path events, String event) throws Exception {
            Files.writeString(events, event + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
