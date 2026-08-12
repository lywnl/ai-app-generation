package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.constants.AppConstant;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Vue 工程文件工具的路径边界解析器。
 *
 * <p>所有工具都只能操作 appId 对应工程根目录内的相对路径；已存在路径必须同时通过真实路径校验，
 * 防止项目内符号链接把文件操作导向工程外。</p>
 */
final class ProjectPathResolver {

    private static final Set<String> PROTECTED_SEGMENTS = Set.of(
            "node_modules", "dist", ".git", ".ai-build-dependency-state.json");

    Path resolveExisting(Long appId, String relativePath, boolean allowEmpty) throws UnsafeProjectPathException {
        Path projectRoot = projectRoot(appId);
        Path candidate = resolveRelativePath(projectRoot, relativePath, allowEmpty);
        if (Files.exists(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            requireExistingProjectRoot(projectRoot);
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }

        Path realProjectRoot = requireExistingProjectRoot(projectRoot);
        requireRealPathWithinRoot(candidate, realProjectRoot);
        return candidate;
    }

    ResolvedProjectPath resolveForWrite(Long appId, String relativePath) throws UnsafeProjectPathException {
        Path projectRoot = projectRoot(appId);
        Path candidate = resolveRelativePath(projectRoot, relativePath, false);
        Path realProjectRoot = createAndResolveProjectRoot(projectRoot);
        validateExistingParents(candidate, projectRoot, realProjectRoot);
        return new ResolvedProjectPath(candidate, projectRoot.relativize(candidate).toString());
    }

    List<Path> collectSafeDirectoryEntries(
            Path directory, Long appId, Predicate<String> shouldIgnore) throws UnsafeProjectPathException {
        Path projectRoot = projectRoot(appId);
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path realProjectRoot = requireExistingProjectRoot(projectRoot);
        requireRealPathWithinRoot(normalizedDirectory, realProjectRoot);
        List<Path> entries = new ArrayList<>();
        try {
            Files.walkFileTree(normalizedDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                        throws IOException {
                    if (!current.equals(normalizedDirectory)
                            && shouldSkipTraversalEntry(
                                    current.getFileName().toString(), shouldIgnore)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    rejectSymbolicLink(current);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    if (shouldSkipTraversalEntry(
                            file.getFileName().toString(), shouldIgnore)) {
                        return FileVisitResult.CONTINUE;
                    }
                    rejectSymbolicLink(file);
                    entries.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            return List.copyOf(entries);
        } catch (IOException exception) {
            throw unsafe("目录包含无法安全读取的符号链接或路径", exception);
        }
    }

    private boolean shouldSkipTraversalEntry(
            String name, Predicate<String> shouldIgnore) {
        return PROTECTED_SEGMENTS.contains(name) || shouldIgnore.test(name);
    }

    private Path projectRoot(Long appId) throws UnsafeProjectPathException {
        if (appId == null) {
            throw new UnsafeProjectPathException("应用标识不能为空");
        }
        try {
            return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                    .toAbsolutePath()
                    .normalize();
        } catch (RuntimeException exception) {
            throw unsafe("项目根目录无效", exception);
        }
    }

    private Path resolveRelativePath(Path projectRoot, String relativePath, boolean allowEmpty)
            throws UnsafeProjectPathException {
        if (relativePath == null) {
            if (allowEmpty) {
                return projectRoot;
            }
            throw new UnsafeProjectPathException("相对路径不能为空");
        }
        if (!allowEmpty && relativePath.isBlank()) {
            throw new UnsafeProjectPathException("相对路径不能为空");
        }
        if (allowEmpty && relativePath.isBlank()) {
            return projectRoot;
        }
        try {
            Path path = Path.of(relativePath);
            if (path.isAbsolute()) {
                throw new UnsafeProjectPathException("不允许使用绝对路径");
            }
            rejectProtectedSegments(path);
            Path candidate = projectRoot.resolve(path).normalize();
            if (!candidate.startsWith(projectRoot)) {
                throw new UnsafeProjectPathException("相对路径不能越出项目根目录");
            }
            return candidate;
        } catch (UnsafeProjectPathException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unsafe("相对路径格式无效", exception);
        }
    }

    private void rejectProtectedSegments(Path path) throws UnsafeProjectPathException {
        for (Path segment : path) {
            if (PROTECTED_SEGMENTS.contains(segment.toString())) {
                throw new UnsafeProjectPathException("不允许访问受保护路径段: " + segment);
            }
        }
    }

    private Path createAndResolveProjectRoot(Path projectRoot) throws UnsafeProjectPathException {
        try {
            if (Files.exists(projectRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(projectRoot)) {
                throw new UnsafeProjectPathException("项目根目录不能是符号链接");
            }
            Files.createDirectories(projectRoot);
            return requireExistingProjectRoot(projectRoot);
        } catch (UnsafeProjectPathException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unsafe("无法安全创建项目根目录", exception);
        }
    }

    private Path requireExistingProjectRoot(Path projectRoot) throws UnsafeProjectPathException {
        try {
            if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new UnsafeProjectPathException("项目根目录不存在或不是目录");
            }
            if (Files.isSymbolicLink(projectRoot)) {
                throw new UnsafeProjectPathException("项目根目录不能是符号链接");
            }
            return projectRoot.toRealPath();
        } catch (UnsafeProjectPathException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unsafe("项目根目录无法解析", exception);
        }
    }

    private void validateExistingParents(Path candidate, Path projectRoot, Path realProjectRoot)
            throws UnsafeProjectPathException {
        Path current = candidate;
        while (current != null && current.startsWith(projectRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireRealPathWithinRoot(current, realProjectRoot);
            }
            if (current.equals(projectRoot)) {
                return;
            }
            current = current.getParent();
        }
        throw new UnsafeProjectPathException("写入路径不能越出项目根目录");
    }

    private void requireRealPathWithinRoot(Path candidate, Path realProjectRoot)
            throws UnsafeProjectPathException {
        try {
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realProjectRoot)) {
                throw new UnsafeProjectPathException("路径真实位置越出项目根目录");
            }
            rejectProtectedSegments(realProjectRoot.relativize(realCandidate));
        } catch (UnsafeProjectPathException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unsafe("路径无法安全解析", exception);
        }
    }

    private void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("目录中包含符号链接: " + path.getFileName());
        }
    }

    private UnsafeProjectPathException unsafe(String message, Exception cause) {
        return new UnsafeProjectPathException(message + "：" + safeMessage(cause), cause);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    static final class UnsafeProjectPathException extends Exception {

        private UnsafeProjectPathException(String message) {
            super(message);
        }

        private UnsafeProjectPathException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record ResolvedProjectPath(Path path, String stateKey) {
    }
}
