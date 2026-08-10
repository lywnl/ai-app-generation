package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandExecutorTest {

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
    void terminatesTimedOutProcessAndWaitsForOutputReader() throws Exception {
        long startNanos = System.nanoTime();

        CommandResult result = new ProcessCommandExecutor().execute(
                tempDir,
                javaCommand("sleep"),
                Duration.ofMillis(100));

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
                Duration.ofMillis(200));

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

    private CommandResult executeWithTestBound(List<String> command, Duration timeout)
            throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<CommandResult> result = executor.submit(
                () -> new ProcessCommandExecutor().execute(tempDir, command, timeout));
        try {
            return result.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError("进程树超时清理未在 3 秒内返回", exception);
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
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .ifPresent(ProcessHandle::destroyForcibly);
    }

    private List<String> javaCommand(String mode, String... additionalArguments) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>(List.of(
                java.toString(), "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), mode));
        command.addAll(List.of(additionalArguments));
        return List.copyOf(command);
    }

    public static final class ProcessFixture {

        public static void main(String[] args) throws Exception {
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
