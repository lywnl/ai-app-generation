package com.lyw.appgeneration.core.builder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用真实 {@link ProcessBuilder} 执行构建命令。
 */
final class ProcessCommandExecutor implements CommandExecutor {

    private static final Duration GRACEFUL_TERMINATION_WAIT = Duration.ofMillis(500);
    private static final Duration FORCED_TERMINATION_WAIT = Duration.ofMillis(500);
    private static final Duration OUTPUT_DRAIN_WAIT = Duration.ofMillis(750);
    private static final Duration READER_TERMINATION_WAIT = Duration.ofMillis(250);
    private static final Duration PROCESS_POLL_INTERVAL = Duration.ofMillis(10);
    private static final int READ_BUFFER_CHARS = 2_048;

    @Override
    public CommandResult execute(Path workingDirectory, List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        ExecutorService readerExecutor = null;
        Future<?> outputReader = null;
        CharacterTailBuffer outputTail = new CharacterTailBuffer(
                BuildResult.MAX_OUTPUT_TAIL_CHARS);
        try {
            readerExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            outputReader = readerExecutor.submit(() -> {
                readOutput(process, outputTail);
                return null;
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                CleanupResult cleanup = cleanup(process, outputReader, readerExecutor, outputTail);
                throwIfCleanupInterrupted(cleanup, null);
                throwIfCleanupFailed(cleanup, null);
                return new CommandResult(null, true, cleanup.outputTail());
            }
            awaitOutput(outputReader);
            shutdownReaderExecutor(readerExecutor);
            return new CommandResult(process.exitValue(), false, outputTail.toString());
        } catch (InterruptedException exception) {
            CleanupResult cleanup = cleanup(process, outputReader, readerExecutor, outputTail);
            InterruptedException interrupted = interruptedException(
                    "命令执行被中断，已完成有界进程清理", exception, cleanup.failure());
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (IOException exception) {
            CleanupResult cleanup = cleanup(process, outputReader, readerExecutor, outputTail);
            throwIfCleanupInterrupted(cleanup, exception);
            throwIfCleanupFailed(cleanup, exception);
            throw exception;
        } catch (RuntimeException exception) {
            CleanupResult cleanup = cleanup(process, outputReader, readerExecutor, outputTail);
            throwIfCleanupInterrupted(cleanup, exception);
            if (cleanup.failure() != null) {
                exception.addSuppressed(cleanup.failure());
            }
            throw exception;
        }
    }

    private CleanupResult cleanup(Process process,
                                  Future<?> outputReader,
                                  ExecutorService readerExecutor,
                                  CharacterTailBuffer outputTail) {
        CleanupState state = new CleanupState(Thread.interrupted());
        List<ProcessHandle> processTree = snapshotProcessTree(process, state);
        terminateProcessTree(processTree, state);
        awaitOutputDuringCleanup(process, outputReader, state);
        shutdownReaderExecutorDuringCleanup(readerExecutor, state);
        return new CleanupResult(outputTail.toString(), state.interrupted, state.failure);
    }

    private List<ProcessHandle> snapshotProcessTree(Process process, CleanupState state) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> processTree = new ArrayList<>();
        try {
            processTree.addAll(root.descendants().toList());
            Collections.reverse(processTree);
        } catch (RuntimeException exception) {
            state.recordFailure(exception);
        }
        processTree.add(root);
        return processTree;
    }

    private void terminateProcessTree(List<ProcessHandle> processTree, CleanupState state) {
        signalProcessTree(processTree, false, state);
        awaitProcessTreeExit(processTree, GRACEFUL_TERMINATION_WAIT, state);
        signalProcessTree(processTree, true, state);
        awaitProcessTreeExit(processTree, FORCED_TERMINATION_WAIT, state);
        List<Long> aliveProcessIds = aliveProcessIds(processTree, state);
        if (!aliveProcessIds.isEmpty()) {
            state.recordFailure(new IOException("命令进程树未能在期限内终止: " + aliveProcessIds));
        }
    }

    private void signalProcessTree(List<ProcessHandle> processTree,
                                   boolean forcibly,
                                   CleanupState state) {
        for (ProcessHandle processHandle : processTree) {
            try {
                if (!processHandle.isAlive()) {
                    continue;
                }
                if (forcibly) {
                    processHandle.destroyForcibly();
                } else {
                    processHandle.destroy();
                }
            } catch (RuntimeException exception) {
                state.recordFailure(exception);
            }
        }
    }

    private void awaitProcessTreeExit(List<ProcessHandle> processTree,
                                      Duration wait,
                                      CleanupState state) {
        long deadlineNanos = System.nanoTime() + wait.toNanos();
        while (!aliveProcessIds(processTree, state).isEmpty()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remainingNanos,
                        PROCESS_POLL_INTERVAL.toNanos()));
            } catch (InterruptedException exception) {
                state.interrupted = true;
            }
        }
    }

    private List<Long> aliveProcessIds(List<ProcessHandle> processTree, CleanupState state) {
        List<Long> aliveProcessIds = new ArrayList<>();
        for (ProcessHandle processHandle : processTree) {
            try {
                if (processHandle.isAlive()) {
                    aliveProcessIds.add(processHandle.pid());
                }
            } catch (RuntimeException exception) {
                state.recordFailure(exception);
            }
        }
        return aliveProcessIds;
    }

    private void readOutput(Process process, CharacterTailBuffer tail) throws IOException {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[READ_BUFFER_CHARS];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                tail.append(buffer, read);
            }
        }
    }

    private void awaitOutput(Future<?> outputReader) throws IOException, InterruptedException {
        try {
            outputReader.get(OUTPUT_DRAIN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IOException("读取命令输出超时", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("读取命令输出失败", cause);
        }
    }

    private void awaitOutputDuringCleanup(Process process,
                                          Future<?> outputReader,
                                          CleanupState state) {
        if (outputReader == null) {
            closeProcessOutput(process, state);
            return;
        }
        long deadlineNanos = System.nanoTime() + OUTPUT_DRAIN_WAIT.toNanos();
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                closeProcessOutput(process, state);
                outputReader.cancel(true);
                return;
            }
            try {
                outputReader.get(remainingNanos, TimeUnit.NANOSECONDS);
                return;
            } catch (InterruptedException exception) {
                state.interrupted = true;
            } catch (TimeoutException exception) {
                closeProcessOutput(process, state);
                outputReader.cancel(true);
                return;
            } catch (CancellationException exception) {
                return;
            } catch (ExecutionException exception) {
                state.recordFailure(exception.getCause());
                return;
            }
        }
    }

    private void closeProcessOutput(Process process, CleanupState state) {
        try {
            process.getInputStream().close();
        } catch (IOException exception) {
            state.recordFailure(exception);
        }
    }

    private void shutdownReaderExecutor(ExecutorService readerExecutor)
            throws IOException, InterruptedException {
        readerExecutor.shutdownNow();
        if (!readerExecutor.awaitTermination(
                READER_TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("命令输出读取线程未在期限内终止");
        }
    }

    private void shutdownReaderExecutorDuringCleanup(ExecutorService readerExecutor,
                                                     CleanupState state) {
        if (readerExecutor == null) {
            return;
        }
        readerExecutor.shutdownNow();
        long deadlineNanos = System.nanoTime() + READER_TERMINATION_WAIT.toNanos();
        while (!readerExecutor.isTerminated()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                state.recordFailure(new IOException("命令输出读取线程未在期限内终止"));
                return;
            }
            try {
                readerExecutor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                state.interrupted = true;
            }
        }
    }

    private void throwIfCleanupInterrupted(CleanupResult cleanup, Throwable cause)
            throws InterruptedException {
        if (!cleanup.interrupted()) {
            return;
        }
        InterruptedException exception = interruptedException(
                "命令清理被中断，已完成有界进程清理", cause, cleanup.failure());
        Thread.currentThread().interrupt();
        throw exception;
    }

    private void throwIfCleanupFailed(CleanupResult cleanup, Throwable cause) throws IOException {
        if (cleanup.failure() == null) {
            return;
        }
        IOException exception = new IOException("命令进程清理失败", cleanup.failure());
        if (cause != null) {
            exception.addSuppressed(cause);
        }
        throw exception;
    }

    private InterruptedException interruptedException(String message,
                                                      Throwable cause,
                                                      Throwable cleanupFailure) {
        InterruptedException exception = new InterruptedException(message);
        if (cause != null) {
            exception.initCause(cause);
        }
        if (cleanupFailure != null) {
            exception.addSuppressed(cleanupFailure);
        }
        return exception;
    }

    private static final class CharacterTailBuffer {

        private final char[] chars;
        private int start;
        private int size;

        private CharacterTailBuffer(int capacity) {
            this.chars = new char[capacity];
        }

        private synchronized void append(char[] source, int length) {
            for (int index = 0; index < length; index++) {
                int target = (start + size) % chars.length;
                chars[target] = source[index];
                if (size < chars.length) {
                    size++;
                } else {
                    start = (start + 1) % chars.length;
                }
            }
        }

        @Override
        public synchronized String toString() {
            StringBuilder output = new StringBuilder(size);
            for (int index = 0; index < size; index++) {
                output.append(chars[(start + index) % chars.length]);
            }
            return output.toString();
        }
    }

    private record CleanupResult(String outputTail, boolean interrupted, Throwable failure) {
    }

    private static final class CleanupState {

        private boolean interrupted;
        private Throwable failure;

        private CleanupState(boolean interrupted) {
            this.interrupted = interrupted;
        }

        private void recordFailure(Throwable newFailure) {
            if (failure == null) {
                failure = newFailure;
                return;
            }
            failure.addSuppressed(newFailure);
        }
    }
}
