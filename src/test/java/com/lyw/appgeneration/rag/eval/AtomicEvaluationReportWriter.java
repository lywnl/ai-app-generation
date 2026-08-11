package com.lyw.appgeneration.rag.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * 以同目录临时文件原子替换质量报告，避免覆盖期间出现正式路径空窗。
 */
public final class AtomicEvaluationReportWriter {

    private AtomicEvaluationReportWriter() {
    }

    public static void write(Path target, String markdown) throws IOException {
        Path absoluteTarget = Objects.requireNonNull(target, "报告路径不能为空")
                .toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent, absoluteTarget.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, Objects.requireNonNull(markdown, "报告内容不能为空"),
                    StandardCharsets.UTF_8);
            replace(temporary, absoluteTarget);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
