package com.lyw.appgeneration.rag.eval;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 管理单轮高成本评测报告的失效、完成与异常覆盖生命周期。
 */
public final class EvaluationReportLifecycle {

    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS =
            new ConcurrentHashMap<>();

    private EvaluationReportLifecycle() {
    }

    public static <T> T execute(
            Path target,
            String invalidatedMarkdown,
            Callable<T> operation,
            Function<T, String> renderer,
            String failureMarkdown) throws Exception {
        Path normalizedTarget = normalizeTarget(target);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(
                normalizedTarget, ignored -> new ReentrantLock());
        lockInterruptibly(jvmLock);
        try {
            Path lockFile = lockFile(normalizedTarget);
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockProcessInterruptibly(channel)) {
                return executeLocked(
                        normalizedTarget,
                        invalidatedMarkdown,
                        operation,
                        renderer,
                        failureMarkdown);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private static <T> T executeLocked(
            Path target,
            String invalidatedMarkdown,
            Callable<T> operation,
            Function<T, String> renderer,
            String failureMarkdown) throws Exception {
        AtomicEvaluationReportWriter.write(target, invalidatedMarkdown);
        try {
            T result = Objects.requireNonNull(operation.call(), "评测结果不能为空");
            AtomicEvaluationReportWriter.write(target, renderer.apply(result));
            return result;
        } catch (Exception original) {
            writeFailureReport(target, failureMarkdown, original);
            throw original;
        } catch (Error original) {
            writeFailureReport(target, failureMarkdown, original);
            throw original;
        }
    }

    private static void lockInterruptibly(ReentrantLock lock) throws InterruptedException {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static FileLock lockProcessInterruptibly(FileChannel channel)
            throws IOException, InterruptedException {
        try {
            return channel.lock();
        } catch (FileLockInterruptionException exception) {
            Thread.currentThread().interrupt();
            InterruptedException interrupted = new InterruptedException("等待报告文件锁被中断");
            interrupted.initCause(exception);
            throw interrupted;
        }
    }

    private static Path lockFile(Path target) {
        return target.resolveSibling("." + target.getFileName() + ".lock");
    }

    private static Path normalizeTarget(Path target) throws IOException {
        Path absoluteTarget = Objects.requireNonNull(target, "报告路径不能为空")
                .toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        Files.createDirectories(parent);
        return parent.toRealPath().resolve(absoluteTarget.getFileName());
    }

    private static void writeFailureReport(
            Path target,
            String failureMarkdown,
            Throwable original) {
        try {
            AtomicEvaluationReportWriter.write(target, failureMarkdown);
        } catch (IOException | RuntimeException reportFailure) {
            original.addSuppressed(reportFailure);
        }
    }
}
