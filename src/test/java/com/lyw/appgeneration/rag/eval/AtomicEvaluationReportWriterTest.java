package com.lyw.appgeneration.rag.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                            Files.delete(reportDirectory);
                            Files.writeString(reportDirectory, "阻止后续报告写入");
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
}
