package com.lyw.appgeneration.utils;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** 基于目录句柄读取，避免校验后按绝对路径重开时跟随替换链接。 */
public final class SecureFileAccess {

    private SecureFileAccess() {
    }

    @FunctionalInterface
    public interface DirectoryAction<T> {
        T apply(SecureDirectoryStream<Path> directory) throws IOException;
    }

    public static <T> T withDirectory(Path directory, DirectoryAction<T> action)
            throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        try (DirectoryStream<Path> root = Files.newDirectoryStream(absolute.getRoot())) {
            if (!(root instanceof SecureDirectoryStream<Path> secure)) {
                throw new IOException("文件系统不支持安全目录读取");
            }
            return descend(secure, absolute, 0, action);
        }
    }

    private static <T> T descend(SecureDirectoryStream<Path> parent, Path path,
                                 int offset, DirectoryAction<T> action) throws IOException {
        if (offset == path.getNameCount()) {
            return action.apply(parent);
        }
        try (var child = parent.newDirectoryStream(path.getName(offset), LinkOption.NOFOLLOW_LINKS)) {
            return descend(child, path, offset + 1, action);
        }
    }

    public static BasicFileAttributes regularAttributes(SecureDirectoryStream<Path> directory,
                                                        Path name) throws IOException {
        requireFileName(name);
        BasicFileAttributes attributes = directory.getFileAttributeView(
                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("资源不是普通文件");
        }
        return attributes;
    }

    public static SeekableByteChannel openRegularFile(SecureDirectoryStream<Path> directory,
                                                      Path name) throws IOException {
        regularAttributes(directory, name);
        return directory.newByteChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    private static void requireFileName(Path name) {
        if (name == null || name.isAbsolute() || name.getNameCount() != 1
                || name.toString().isBlank() || name.toString().equals(".")
                || name.toString().equals("..")) {
            throw new IllegalArgumentException("资源名称必须是单个文件名");
        }
    }
}
