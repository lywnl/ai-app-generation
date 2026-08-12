package com.lyw.appgeneration.core.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** 仅删除项目根目录下明确命名的直属路径，且不跟随符号链接。 */
final class SafeBuildDirectoryCleaner {

    private SafeBuildDirectoryCleaner() {
    }

    static void deleteDirectChild(Path projectRoot, String childName) throws IOException {
        Path realRoot = projectRoot.toRealPath();
        Path target = realRoot.resolve(childName).normalize();
        if (!realRoot.equals(target.getParent())) {
            throw new IOException("拒绝删除项目根目录之外的路径");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) throws IOException {
                deleteWithin(target, file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(
                    Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                deleteWithin(target, directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteWithin(Path target, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(target)) {
            throw new IOException("拒绝删除目标目录之外的路径");
        }
        Files.delete(path);
    }
}
