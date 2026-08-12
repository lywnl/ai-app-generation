package com.lyw.appgeneration.core.builder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 使用真实 {@link ProcessBuilder} 执行构建命令。
 */
final class ProcessCommandExecutor implements CommandExecutor {

    private static final Duration GRACEFUL_TERMINATION_WAIT = Duration.ofMillis(500);
    private static final Duration FORCED_TERMINATION_WAIT = Duration.ofMillis(500);
    private static final Duration OUTPUT_DRAIN_WAIT = Duration.ofMillis(750);
    private static final Duration TASK_TERMINATION_WAIT = Duration.ofMillis(250);
    private static final Duration PROCESS_POLL_INTERVAL = Duration.ofMillis(10);
    private static final Duration MONITOR_POLL_INTERVAL = Duration.ofMillis(5);
    private static final int CLEANUP_SCAN_ROUNDS = 4;
    private static final int READ_BUFFER_CHARS = 2_048;

    @Override
    public CommandResult execute(Path workingDirectory, List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        return CommandExecutor.super.execute(workingDirectory, command, timeout);
    }

    @Override
    public CommandResult execute(
            Path workingDirectory,
            List<String> command,
            Duration timeout,
            Consumer<String> rawOutputConsumer,
            BuildCancellationSignal cancellation) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(rawOutputConsumer, "rawOutputConsumer 不能为空");
        java.util.Objects.requireNonNull(cancellation, "cancellation 不能为空");
        try (BuildCancellationSignal.Registration ignored = cancellation.registerCurrentThread()) {
            if (cancellation.isCancelled()) {
                return new CommandResult(null, false, true, "");
            }
            return executeRegistered(
                    workingDirectory, command, timeout, rawOutputConsumer, cancellation);
        }
    }

    private CommandResult executeRegistered(
            Path workingDirectory,
            List<String> command,
            Duration timeout,
            Consumer<String> rawOutputConsumer,
            BuildCancellationSignal cancellation) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        isolateEnvironment(processBuilder.environment(), workingDirectory);
        Process process = processBuilder.start();
        ProcessTracker tracker = new ProcessTracker(process.toHandle());
        CharacterTailBuffer outputTail = new CharacterTailBuffer(
                BuildResult.MAX_OUTPUT_TAIL_CHARS);
        TaskResources tasks = startTasks(process, tracker, outputTail, rawOutputConsumer);
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                CleanupResult cleanup = cleanup(process, tracker, tasks, outputTail);
                throwIfCleanupInterrupted(cleanup, null);
                throwIfCleanupFailed(cleanup, null);
                return new CommandResult(null, true, false, cleanup.outputTail());
            }
            awaitOutput(tasks.outputReader());
            stopMonitor(tasks, tracker);
            shutdownTasks(tasks.executor());
            return new CommandResult(process.exitValue(), false, false, outputTail.toString());
        } catch (InterruptedException exception) {
            CleanupResult cleanup = cleanup(process, tracker, tasks, outputTail);
            Thread.currentThread().interrupt();
            if (cancellation.isCancelled()) {
                if (cleanup.failure() != null) {
                    throw new ProcessCleanupException(
                            "命令取消后的进程树清理失败", cleanup.failure());
                }
                return new CommandResult(null, false, true, cleanup.outputTail());
            }
            InterruptedException interrupted = interruptedException(
                    "命令执行被中断，已完成有界进程清理", exception, cleanup.failure());
            throw interrupted;
        } catch (IOException exception) {
            CleanupResult cleanup = cleanup(process, tracker, tasks, outputTail);
            throwIfCleanupInterrupted(cleanup, exception);
            if (cleanup.failure() != null) {
                exception.addSuppressed(cleanup.failure());
            }
            throw exception;
        } catch (RuntimeException exception) {
            CleanupResult cleanup = cleanup(process, tracker, tasks, outputTail);
            throwIfCleanupInterrupted(cleanup, exception);
            if (cleanup.failure() != null) {
                exception.addSuppressed(cleanup.failure());
            }
            throw exception;
        }
    }

    private void isolateEnvironment(
            Map<String, String> childEnvironment,
            Path workingDirectory) {
        String path = childEnvironment.get("PATH");
        childEnvironment.clear();
        if (path != null && !path.isBlank()) {
            String isolatedPath = isolatePath(path, workingDirectory);
            if (!isolatedPath.isBlank()) {
                childEnvironment.put("PATH", isolatedPath);
            }
        }
    }

    private String isolatePath(String path, Path workingDirectory) {
        Path projectRoot = realOrAbsolutePath(workingDirectory);
        return java.util.Arrays.stream(path.split(
                        java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1))
                .filter(entry -> isTrustedPathEntry(entry, projectRoot))
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private boolean isTrustedPathEntry(String entry, Path projectRoot) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        try {
            Path candidate = Path.of(entry);
            return candidate.isAbsolute()
                    && !realOrAbsolutePath(candidate).startsWith(projectRoot);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Path realOrAbsolutePath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath().normalize();
        }
    }

    private TaskResources startTasks(Process process,
                                     ProcessTracker tracker,
                                     CharacterTailBuffer outputTail,
                                     Consumer<String> rawOutputConsumer) {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("process-command-monitor-", 0).factory());
        Future<?> outputReader = executor.submit(() -> {
            readOutput(process, outputTail, rawOutputConsumer);
            return null;
        });
        Future<?> monitor = executor.submit(() -> monitorDescendants(tracker));
        return new TaskResources(executor, outputReader, monitor);
    }

    private void monitorDescendants(ProcessTracker tracker) {
        while (!tracker.stopRequested()) {
            tracker.scanDescendants();
            try {
                Thread.sleep(MONITOR_POLL_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        tracker.scanDescendants();
    }

    private CleanupResult cleanup(Process process,
                                  ProcessTracker tracker,
                                  TaskResources tasks,
                                  CharacterTailBuffer outputTail) {
        CleanupState state = new CleanupState(Thread.interrupted());
        terminateProcessTree(tracker, state);
        awaitOutputDuringCleanup(process, tasks.outputReader(), state);
        stopMonitorDuringCleanup(tasks, tracker, state);
        shutdownTasksDuringCleanup(tasks.executor(), state);
        return new CleanupResult(outputTail.toString(), state.interrupted, state.failure);
    }

    private void terminateProcessTree(ProcessTracker tracker, CleanupState state) {
        tracker.scanDescendants(state);
        signalRoot(tracker.root(), false, state);
        for (int round = 0; round < CLEANUP_SCAN_ROUNDS; round++) {
            tracker.scanDescendants(state);
            signalTrackedDescendants(tracker, false, state);
            awaitTrackedExit(tracker, GRACEFUL_TERMINATION_WAIT, state);
            tracker.scanDescendants(state);
            signalTrackedDescendants(tracker, true, state);
            signalRoot(tracker.root(), true, state);
            awaitTrackedExit(tracker, FORCED_TERMINATION_WAIT, state);
            tracker.scanDescendants(state);
            if (aliveProcessIds(tracker, state).isEmpty()) {
                return;
            }
        }
        List<Long> aliveProcessIds = aliveProcessIds(tracker, state);
        if (!aliveProcessIds.isEmpty()) {
            state.recordFailure(new IOException("命令进程树未能在期限内终止: " + aliveProcessIds));
        }
    }

    private void signalTrackedDescendants(ProcessTracker tracker,
                                          boolean forcibly,
                                          CleanupState state) {
        List<ProcessHandle> descendants = tracker.trackedDescendants().stream()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        descendants.forEach(processHandle -> signal(processHandle, forcibly, state));
    }

    private void signalRoot(ProcessHandle root, boolean forcibly, CleanupState state) {
        signal(root, forcibly, state);
    }

    private void signal(ProcessHandle processHandle,
                        boolean forcibly,
                        CleanupState state) {
        try {
            if (!processHandle.isAlive()) {
                return;
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

    private void awaitTrackedExit(ProcessTracker tracker,
                                  Duration wait,
                                  CleanupState state) {
        long deadlineNanos = System.nanoTime() + wait.toNanos();
        while (!aliveProcessIds(tracker, state).isEmpty()) {
            tracker.scanDescendants(state);
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

    private List<Long> aliveProcessIds(ProcessTracker tracker, CleanupState state) {
        Set<ProcessHandle> processTree = new LinkedHashSet<>(tracker.trackedDescendants());
        processTree.add(tracker.root());
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

    private void readOutput(
            Process process,
            CharacterTailBuffer tail,
            Consumer<String> rawOutputConsumer) throws IOException {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[READ_BUFFER_CHARS];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                try {
                    rawOutputConsumer.accept(new String(buffer, 0, read));
                } catch (RuntimeException ignored) {
                    // 日志旁路故障不得改变命令执行结果。
                }
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

    private void stopMonitor(TaskResources tasks, ProcessTracker tracker)
            throws IOException, InterruptedException {
        tracker.requestStop();
        try {
            tasks.monitor().get(TASK_TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            tasks.monitor().cancel(true);
            throw new IOException("命令进程监控任务未在期限内终止", exception);
        } catch (ExecutionException exception) {
            throw new IOException("命令进程监控失败", exception.getCause());
        }
    }

    private void stopMonitorDuringCleanup(TaskResources tasks,
                                          ProcessTracker tracker,
                                          CleanupState state) {
        tracker.requestStop();
        tasks.monitor().cancel(true);
        awaitTaskDuringCleanup(tasks.monitor(), "命令进程监控任务未在期限内终止", state);
    }

    private void awaitTaskDuringCleanup(Future<?> task,
                                        String timeoutMessage,
                                        CleanupState state) {
        long deadlineNanos = System.nanoTime() + TASK_TERMINATION_WAIT.toNanos();
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                state.recordFailure(new IOException(timeoutMessage));
                return;
            }
            try {
                task.get(remainingNanos, TimeUnit.NANOSECONDS);
                return;
            } catch (InterruptedException exception) {
                state.interrupted = true;
            } catch (CancellationException exception) {
                return;
            } catch (ExecutionException exception) {
                state.recordFailure(exception.getCause());
                return;
            } catch (TimeoutException exception) {
                state.recordFailure(new IOException(timeoutMessage, exception));
                return;
            }
        }
    }

    private void shutdownTasks(ExecutorService executor)
            throws IOException, InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(TASK_TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("命令后台任务未在期限内终止");
        }
    }

    private void shutdownTasksDuringCleanup(ExecutorService executor, CleanupState state) {
        executor.shutdownNow();
        long deadlineNanos = System.nanoTime() + TASK_TERMINATION_WAIT.toNanos();
        while (!executor.isTerminated()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                state.recordFailure(new IOException("命令后台任务未在期限内终止"));
                return;
            }
            try {
                executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
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

    private record TaskResources(ExecutorService executor,
                                 Future<?> outputReader,
                                 Future<?> monitor) {
    }

    private record CleanupResult(String outputTail, boolean interrupted, Throwable failure) {
    }

    private static final class ProcessTracker {

        private final ProcessHandle root;
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stopRequested = new AtomicBoolean();

        private ProcessTracker(ProcessHandle root) {
            this.root = root;
            scanDescendants();
        }

        private ProcessHandle root() {
            return root;
        }

        private Set<ProcessHandle> trackedDescendants() {
            return Set.copyOf(descendants);
        }

        private boolean stopRequested() {
            return stopRequested.get();
        }

        private void requestStop() {
            stopRequested.set(true);
        }

        private void scanDescendants() {
            try {
                descendants.addAll(root.descendants().toList());
            } catch (RuntimeException ignored) {
                // 监控任务只做尽力登记；清理路径会记录扫描异常。
            }
        }

        private void scanDescendants(CleanupState state) {
            try {
                descendants.addAll(root.descendants().toList());
            } catch (RuntimeException exception) {
                state.recordFailure(exception);
            }
        }
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
            if (!output.isEmpty() && Character.isLowSurrogate(output.charAt(0))) {
                output.deleteCharAt(0);
            }
            return output.toString();
        }
    }
}
