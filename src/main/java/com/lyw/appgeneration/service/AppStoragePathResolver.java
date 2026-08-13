package com.lyw.appgeneration.service;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** 将应用元数据解析为受存储根约束且不经过符号链接的路径。 */
@Component
public final class AppStoragePathResolver {

    private final Path sourceRoot;
    private final Path deployRoot;

    public AppStoragePathResolver() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR));
    }

    public AppStoragePathResolver(Path sourceRoot, Path deployRoot) {
        this.sourceRoot = normalizeRoot(sourceRoot, "源码");
        this.deployRoot = normalizeRoot(deployRoot, "部署");
    }

    public FrozenAppPaths resolveForDeletion(App app) {
        return new FrozenAppPaths(resolveSourceDirectory(app),
                resolveDeployDirectory(app));
    }

    public Path resolveSourceDirectory(App app) {
        App validApp = requireApp(app);
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(
                validApp.getCodeGenType());
        if (codeGenType == null) {
            throw new IllegalArgumentException("代码生成类型无效");
        }
        String directoryName = codeGenType.getValue() + "_" + validApp.getId();
        return resolveDirectChild(sourceRoot, directoryName, "源码");
    }

    public Optional<Path> resolveDeployDirectory(App app) {
        App validApp = requireApp(app);
        if (StrUtil.isBlank(validApp.getDeployKey())) {
            return Optional.empty();
        }
        return Optional.of(resolveDirectChild(
                deployRoot, validApp.getDeployKey(), "部署"));
    }

    private App requireApp(App app) {
        Objects.requireNonNull(app, "应用不能为空");
        if (app.getId() == null || app.getId() <= 0) {
            throw new IllegalArgumentException("应用 ID 必须为正数");
        }
        return app;
    }

    private Path normalizeRoot(Path root, String kind) {
        Objects.requireNonNull(root, kind + "存储根不能为空");
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException(kind + "存储根不能是文件系统根目录");
        }
        rejectSymbolicLink(normalized, kind + "存储根");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return normalized;
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(kind + "存储根不是目录");
        }
        try {
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException(kind + "存储根无法安全解析", exception);
        }
    }

    private Path resolveDirectChild(Path root, String child, String kind) {
        if (child.indexOf('/') >= 0 || child.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(kind + "路径必须是存储根的直接子路径");
        }
        Path relative;
        try {
            relative = Path.of(child);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(kind + "路径格式无效", exception);
        }
        if (relative.isAbsolute() || relative.getNameCount() != 1
                || ".".equals(relative.toString())
                || "..".equals(relative.toString())) {
            throw new IllegalArgumentException(kind + "路径必须是存储根的直接子路径");
        }
        Path candidate = root.resolve(relative).normalize();
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new IllegalArgumentException(kind + "路径不能指向或越出存储根");
        }
        validateExistingSegments(root, candidate, kind);
        return candidate;
    }

    private void validateExistingSegments(Path root, Path candidate, String kind) {
        Path current = root;
        rejectSymbolicLink(current, kind + "存储根");
        Path relative = root.relativize(candidate);
        for (Path segment : relative) {
            current = current.resolve(segment);
            rejectSymbolicLink(current, kind + "路径");
        }
    }

    private void rejectSymbolicLink(Path path, String kind) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(kind + "不能包含符号链接");
        }
    }

    public record FrozenAppPaths(
            Path sourceDirectory, Optional<Path> deployDirectory) {

        public FrozenAppPaths {
            Objects.requireNonNull(sourceDirectory, "源码目录不能为空");
            Objects.requireNonNull(deployDirectory, "部署目录选项不能为空");
        }
    }
}
