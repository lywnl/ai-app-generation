package com.lyw.appgeneration.rag.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * 管理单轮高成本评测报告的失效、完成与异常覆盖生命周期。
 */
public final class EvaluationReportLifecycle {

    private EvaluationReportLifecycle() {
    }

    public static <T> T execute(
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
        } catch (AssertionError original) {
            writeFailureReport(target, failureMarkdown, original);
            throw original;
        }
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
