package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandExecutorTest {

    private static final List<String> SECRET_VARIABLES = List.of(
            "DASHSCOPE_API_KEY",
            "DEEPSEEK_API_KEY",
            "RAG_PGVECTOR_PASSWORD",
            "SPRING_DATASOURCE_PASSWORD");

    @TempDir
    Path tempDir;

    @Test
    void concurrentlyDrainsLargeStdoutAndStderrAndKeepsCharacterTail() throws Exception {
        CommandResult result = new ProcessCommandExecutor().execute(
                tempDir,
                javaCommand("large-output"),
                Duration.ofSeconds(10));

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertEquals(BuildResult.MAX_OUTPUT_TAIL_CHARS, result.outputTail().length());
        assertTrue(result.outputTail().contains("标准错误结束"));
    }

    @Test
    void streamsRawChunksAndLogSinkReconstructsBoundaryLines() throws Exception {
        StringBuilder raw = new StringBuilder();
        CommandResult result = new ProcessCommandExecutor().execute(
                tempDir,
                javaCommand("large-output"),
                Duration.ofSeconds(10),
                raw::append,
                new BuildCancellationSignal());

        assertTrue(raw.length() > BuildResult.MAX_OUTPUT_TAIL_CHARS);
        assertEquals(BuildResult.MAX_OUTPUT_TAIL_CHARS, result.outputTail().length());
        assertFalse(result.cancelled());

        List<String> events = new ArrayList<>();
        BuildLogSink sink = new BuildLogSink(
                7L, "turn\r\n1", 2, BuildStage.NPM_BUILD,
                events::add, ignored -> { });
        sink.accept("第一");
        sink.accept("行\r\n" + "界".repeat(1_024));
        sink.close();
        assertTrue(events.getFirst().endsWith("第一行"));
        assertTrue(events.stream().noneMatch(event -> event.contains("turn\r\n1")));
        assertTrue(events.getLast().contains("end=true"));
        assertEquals("界".repeat(1_024), events.subList(1, events.size()).stream()
                .map(this::eventBody).collect(java.util.stream.Collectors.joining()));

        events.clear();
        BuildLogSink carriageReturnSink = new BuildLogSink(
                7L, "turn-1", 2, BuildStage.NPM_BUILD,
                events::add, ignored -> { });
        carriageReturnSink.accept("甲\r");
        carriageReturnSink.accept("\n乙\r丙");
        carriageReturnSink.close();
        assertEquals(List.of("甲", "乙", "丙"), events.stream()
                .map(this::eventBody).toList());

        events.clear();
        BuildLogSink unicodeSink = new BuildLogSink(
                7L, "turn-1", 2, BuildStage.NPM_BUILD,
                events::add, ignored -> { });
        String unicodeLine = "界".repeat(1_023) + "😀";
        unicodeSink.accept(unicodeLine.substring(0, unicodeLine.length() - 1));
        unicodeSink.accept(unicodeLine.substring(unicodeLine.length() - 1));
        unicodeSink.close();
        assertEquals(unicodeLine, events.stream()
                .map(this::eventBody).collect(java.util.stream.Collectors.joining()));
        assertTrue(events.stream().noneMatch(event -> {
            String body = eventBody(event);
            return (!body.isEmpty() && Character.isLowSurrogate(body.charAt(0)))
                    || (!body.isEmpty()
                    && Character.isHighSurrogate(body.charAt(body.length() - 1)));
        }));
    }

    @Test
    void cancellationReturnsControlledResultAndCleansRealProcessTree() throws Exception {
        Path parentPidFile = tempDir.resolve("cancel-parent.pid");
        Path childPidFile = tempDir.resolve("cancel-child.pid");
        BuildCancellationSignal cancellation = new BuildCancellationSignal();
        AtomicReference<CommandResult> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                result.set(new ProcessCommandExecutor().execute(
                        tempDir,
                        javaCommand("tree-parent", parentPidFile.toString(), childPidFile.toString()),
                        Duration.ofSeconds(30),
                        ignored -> { }, cancellation));
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        long parentPid = awaitPid(parentPidFile);
        long childPid = awaitPid(childPidFile);

        cancellation.cancel();
        worker.join(Duration.ofSeconds(3));

        assertFalse(worker.isAlive());
        assertTrue(result.get().cancelled());
        assertFalse(result.get().timedOut());
        assertTrue(interrupted.get());
        assertFalse(isAlive(parentPid));
        assertFalse(isAlive(childPid));
    }

    @Test
    void completedCommandUnregistersThreadFromCancellationSignal() throws Exception {
        BuildCancellationSignal cancellation = new BuildCancellationSignal();

        CommandResult result = new ProcessCommandExecutor().execute(
                tempDir,
                javaCommand("large-output"),
                Duration.ofSeconds(10),
                ignored -> { },
                cancellation);
        Thread.interrupted();

        assertEquals(0, result.exitCode());
        cancellation.cancel();
        assertFalse(Thread.currentThread().isInterrupted(),
                "命令结束后取消信号不得误中断复用线程");
    }

    @Test
    void preCancelledSignalDoesNotStartCommand() throws Exception {
        BuildCancellationSignal cancellation = new BuildCancellationSignal();
        cancellation.cancel();

        CommandResult result = new ProcessCommandExecutor().execute(
                tempDir,
                List.of("definitely-not-an-executable"),
                Duration.ofSeconds(1),
                ignored -> { },
                cancellation);

        assertTrue(result.cancelled());
        assertTrue(Thread.interrupted(), "预取消必须保留调用线程的中断状态");
    }

    private String eventBody(String event) {
        int separator = event.lastIndexOf(" | ");
        return event.substring(separator + 3);
    }

    @Test
    void clearsParentSecretsAndAllowsOnlyPathInRealNestedProcess() throws Exception {
        ProcessBuilder parentBuilder = new ProcessBuilder(javaCommand("environment-parent"));
        Map<String, String> parentEnvironment = parentBuilder.environment();
        SECRET_VARIABLES.forEach(name -> parentEnvironment.put(name, "secret-" + name));
        parentEnvironment.put("NOT_ALLOWED_MARKER", "must-not-cross-boundary");
        parentEnvironment.put("PATH", javaBinDirectory() + java.io.File.pathSeparator
                + parentEnvironment.getOrDefault("PATH", ""));
        Process parent = parentBuilder.redirectErrorStream(true).start();

        String output = new String(parent.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(parent.waitFor(10, TimeUnit.SECONDS), "父 JVM 必须有界结束");

        assertEquals(0, parent.exitValue(), output);
        assertTrue(output.contains("PATH_USABLE=true"), output);
        SECRET_VARIABLES.forEach(name -> assertTrue(
                output.contains(name + "=<absent>"), name + " 泄漏到命令子进程: " + output));
        assertTrue(output.contains("NOT_ALLOWED_MARKER=<absent>"), output);
    }

    @Test
    void removesRelativeAndCurrentDirectoryEntriesFromChildPath() throws Exception {
        Path maliciousCommand = tempDir.resolve("model-command");
        Files.writeString(maliciousCommand, "#!/bin/sh\necho MODEL_COMMAND_EXECUTED\n");
        Files.setPosixFilePermissions(maliciousCommand, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        ProcessBuilder parentBuilder = new ProcessBuilder(javaCommand("relative-path-parent"));
        parentBuilder.directory(tempDir.toFile());
        parentBuilder.environment().put("PATH", "." + java.io.File.pathSeparator
                + "relative-bin" + java.io.File.pathSeparator + tempDir
                + java.io.File.pathSeparator + javaBinDirectory());
        Process parent = parentBuilder.redirectErrorStream(true).start();

        String output = new String(parent.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(parent.waitFor(10, TimeUnit.SECONDS));

        assertEquals(0, parent.exitValue(), output);
        assertTrue(output.contains("COMMAND_BLOCKED=true"), output);
        assertTrue(output.contains("PATH_HAS_ONLY_ABSOLUTE_ENTRIES=true"), output);
        assertFalse(output.contains("MODEL_COMMAND_EXECUTED"), output);
    }

    @Test
    void terminatesTimedOutProcessAndWaitsForOutputReader() throws Exception {
        long startNanos = System.nanoTime();

        CommandResult result = new ProcessCommandExecutor().execute(
                tempDir,
                javaCommand("sleep"),
                Duration.ofSeconds(2));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertTrue(result.timedOut());
        assertNull(result.exitCode());
        assertTrue(result.outputTail().contains("睡眠进程已启动"));
        assertTrue(elapsedMillis < 5_000, "超时清理不能遗留阻塞的进程或读流任务");
    }

    @Test
    void timeoutTerminatesRealParentAndPipeHoldingDescendantWithinBound() throws Exception {
        Path parentPidFile = tempDir.resolve("timeout-parent.pid");
        Path childPidFile = tempDir.resolve("timeout-child.pid");

        CommandResult result = executeWithTestBound(
                javaCommand("tree-parent", parentPidFile.toString(), childPidFile.toString()),
                Duration.ofSeconds(2),
                Duration.ofSeconds(8));

        long parentPid = awaitPid(parentPidFile);
        long childPid = awaitPid(childPidFile);
        assertTrue(result.timedOut());
        assertFalse(isAlive(parentPid), "超时后父进程必须死亡");
        assertFalse(isAlive(childPid), "超时后持有输出管道的后代进程必须死亡");
        assertTrue(result.outputTail().contains("父进程已启动子进程"));
    }

    @Test
    void interruptRestoresFlagReportsInterruptionAndCleansRealProcessTree() throws Exception {
        Path parentPidFile = tempDir.resolve("interrupt-parent.pid");
        Path childPidFile = tempDir.resolve("interrupt-child.pid");
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlag = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(1);
        Thread worker = Thread.ofVirtual().start(() -> {
            executionThread.set(Thread.currentThread());
            try {
                new ProcessCommandExecutor().execute(
                        tempDir,
                        javaCommand("tree-parent", parentPidFile.toString(), childPidFile.toString()),
                        Duration.ofSeconds(30));
            } catch (InterruptedException exception) {
                interruptFlag.set(Thread.currentThread().isInterrupted());
                thrown.set(exception);
            } catch (Exception exception) {
                thrown.set(exception);
            } finally {
                completed.countDown();
            }
        });

        long parentPid = awaitPid(parentPidFile);
        long childPid = awaitPid(childPidFile);
        executionThread.get().interrupt();
        try {
            assertTrue(completed.await(3, TimeUnit.SECONDS), "中断清理必须有界返回");
            assertTrue(thrown.get() instanceof InterruptedException);
            assertTrue(thrown.get().getMessage().contains("中断"));
            assertTrue(interruptFlag.get(), "重新抛出前必须恢复中断标志");
            assertFalse(isAlive(parentPid), "中断后父进程必须死亡");
            assertFalse(isAlive(childPid), "中断后后代进程必须死亡");
        } finally {
            terminateFixture(parentPid);
            terminateFixture(childPid);
            worker.interrupt();
        }
    }

    @Test
    void timeoutTracksAndCleansDescendantSpawnedWhileParentIsTerminating() throws Exception {
        Path childPidFile = tempDir.resolve("late-child.pid");
        long childPid = -1;

        try {
            CommandResult result = executeWithTestBound(
                    javaCommand("late-spawn-parent", childPidFile.toString()),
                    Duration.ofMillis(150));

            childPid = awaitPid(childPidFile);
            assertTrue(result.timedOut());
            assertFalse(isAlive(childPid), "清理快照后新派生的后代也必须死亡");
            assertNoProcessMonitorThreadLeak();
        } finally {
            terminateFixture(childPid);
        }
    }

    @Test
    void successfulParentWithPipeHoldingDescendantFailsBoundedlyAndCleansDescendant()
            throws Exception {
        Path childPidFile = tempDir.resolve("detached-child.pid");
        long childPid = -1;

        try {
            IOException exception = assertThrows(IOException.class, () -> executeWithTestBound(
                    javaCommand("exiting-parent", childPidFile.toString()),
                    Duration.ofSeconds(5)));

            childPid = awaitPid(childPidFile);
            assertTrue(exception.getMessage().contains("读取命令输出超时"));
            assertFalse(isAlive(childPid), "父进程正常退出后，已登记的管道持有者也必须死亡");
            assertNoProcessMonitorThreadLeak();
        } finally {
            terminateFixture(childPid);
        }
    }

    private CommandResult executeWithTestBound(List<String> command, Duration timeout)
            throws Exception {
        return executeWithTestBound(command, timeout, Duration.ofSeconds(3));
    }

    private CommandResult executeWithTestBound(
            List<String> command,
            Duration timeout,
            Duration executionBound) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<CommandResult> result = executor.submit(
                () -> new ProcessCommandExecutor().execute(tempDir, command, timeout));
        try {
            return result.get(executionBound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "进程树超时清理未在 " + executionBound.toMillis() + " 毫秒内返回",
                    exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        } finally {
            result.cancel(true);
            executor.shutdownNow();
        }
    }

    private long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.isRegularFile(pidFile)) {
                String content = java.nio.file.Files.readString(pidFile).strip();
                if (!content.isEmpty()) {
                    return Long.parseLong(content);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未在期限内获取进程 PID: " + pidFile);
    }

    private boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private void terminateFixture(long pid) {
        if (pid <= 0) {
            return;
        }
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .ifPresent(ProcessHandle::destroyForcibly);
    }

    private void assertNoProcessMonitorThreadLeak() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            boolean monitorAlive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive()
                            && thread.getName().startsWith("process-command-monitor-"));
            if (!monitorAlive) {
                return;
            }
            Thread.sleep(10);
        }
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(thread -> thread.isAlive()
                                && thread.getName().startsWith("process-command-monitor-")),
                "进程监控线程必须有界终止");
    }

    private List<String> javaCommand(String mode, String... additionalArguments) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>(List.of(
                java.toString(), "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), mode));
        command.addAll(List.of(additionalArguments));
        return List.copyOf(command);
    }

    private String javaBinDirectory() {
        return Path.of(System.getProperty("java.home"), "bin").toString();
    }

    public static final class ProcessFixture {

        public static void main(String[] args) throws Exception {
            if ("environment-parent".equals(args[0])) {
                CommandResult result = new ProcessCommandExecutor().execute(
                        Path.of("."),
                        List.of("java", "-cp", System.getProperty("java.class.path"),
                                ProcessFixture.class.getName(), "environment-child"),
                        Duration.ofSeconds(5));
                System.out.print(result.outputTail());
                if (!Integer.valueOf(0).equals(result.exitCode())) {
                    System.exit(2);
                }
                return;
            }
            if ("environment-child".equals(args[0])) {
                System.out.println("PATH_USABLE=" + !System.getenv().getOrDefault("PATH", "").isBlank());
                SECRET_VARIABLES.forEach(name -> System.out.println(
                        name + "=" + System.getenv().getOrDefault(name, "<absent>")));
                System.out.println("NOT_ALLOWED_MARKER="
                        + System.getenv().getOrDefault("NOT_ALLOWED_MARKER", "<absent>"));
                return;
            }
            if ("relative-path-parent".equals(args[0])) {
                CommandResult result = new ProcessCommandExecutor().execute(
                        Path.of("."),
                        List.of("/bin/sh", "-c", "if model-command; then exit 9; else "
                                + "echo COMMAND_BLOCKED=true; fi; "
                                + "case \"$PATH\" in *':.'*|'.:'*|*':relative-bin'*|"
                                + "'relative-bin:'*) echo PATH_HAS_ONLY_ABSOLUTE_ENTRIES=false;; "
                                + "*) echo PATH_HAS_ONLY_ABSOLUTE_ENTRIES=true;; esac"),
                        Duration.ofSeconds(5));
                System.out.print(result.outputTail());
                if (!Integer.valueOf(0).equals(result.exitCode())) {
                    System.exit(2);
                }
                return;
            }
            if ("late-spawn-parent".equals(args[0])) {
                Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                    try {
                        Process child = new ProcessBuilder(javaCommandForFixture(
                                "tree-child", args[1]))
                                .inheritIO()
                                .start();
                        java.nio.file.Files.writeString(Path.of(args[1]),
                                Long.toString(child.pid()));
                        System.out.println("父进程终止期间启动后代:" + child.pid());
                        System.out.flush();
                        Thread.sleep(200);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
                System.out.println("等待超时触发父进程清理");
                System.out.flush();
                Thread.sleep(15_000);
                return;
            }
            if ("exiting-parent".equals(args[0])) {
                Process child = new ProcessBuilder(javaCommandForFixture(
                        "tree-child", args[1]))
                        .inheritIO()
                        .start();
                java.nio.file.Files.writeString(Path.of(args[1]),
                        Long.toString(child.pid()));
                System.out.println("父进程正常退出，后代继续持有管道:" + child.pid());
                System.out.flush();
                Thread.sleep(200);
                return;
            }
            if ("tree-parent".equals(args[0])) {
                java.nio.file.Files.writeString(Path.of(args[1]),
                        Long.toString(ProcessHandle.current().pid()));
                Process child = new ProcessBuilder(javaCommandForFixture(
                        "tree-child", args[2]))
                        .inheritIO()
                        .start();
                System.out.println("父进程已启动子进程:" + child.pid());
                System.out.flush();
                Thread.sleep(15_000);
                return;
            }
            if ("tree-child".equals(args[0])) {
                java.nio.file.Files.writeString(Path.of(args[1]),
                        Long.toString(ProcessHandle.current().pid()));
                System.out.println("子进程持有合并输出管道");
                System.out.flush();
                Thread.sleep(15_000);
                return;
            }
            if ("sleep".equals(args[0])) {
                System.out.println("睡眠进程已启动");
                System.out.flush();
                Thread.sleep(60_000);
                return;
            }
            for (int index = 0; index < 20_000; index++) {
                System.out.print("标准输出" + index + '\n');
                System.err.print("标准错误" + index + '\n');
            }
            System.err.println("标准错误结束");
        }

        private static List<String> javaCommandForFixture(
                String mode,
                String argument) {
            return List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessFixture.class.getName(),
                    mode,
                    argument);
        }
    }
}
