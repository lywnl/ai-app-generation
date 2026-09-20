package com.lyw.appgeneration.ai.plan;

import java.nio.file.Path;
import com.lyw.appgeneration.ai.tools.ProjectPathResolver;
import java.util.List;
import java.util.Objects;

/** 计划中的单个文件动作。 */
public record PlanFile(
        String path,
        String purpose,
        PlanFileAction action,
        List<String> dependsOn,
        PlanFileState state) {

    public PlanFile {
        path = normalizePath(path);
        purpose = requireText(purpose, "文件用途");
        action = Objects.requireNonNull(action, "文件动作不能为空");
        dependsOn = copyPaths(dependsOn);
        state = Objects.requireNonNull(state, "文件状态不能为空");
    }

    private static String normalizePath(String value) {
        String path = requireText(value, "文件路径");
        if (path.indexOf('\\') >= 0 || path.contains("//")) {
            throw new IllegalArgumentException("计划文件路径包含歧义分隔符");
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")
                || normalized.toString().equals(".")) {
            throw new IllegalArgumentException("计划文件路径必须是项目内相对路径");
        }
        if (ProjectPathResolver.isProtectedPath(normalized)) {
            throw new IllegalArgumentException("计划文件路径包含受保护路径段");
        }
        return normalized.toString().replace('\\', '/');
    }

    private static List<String> copyPaths(List<String> values) {
        List<String> source = values == null ? List.of() : values;
        return source.stream().map(PlanFile::normalizePath).distinct().toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
