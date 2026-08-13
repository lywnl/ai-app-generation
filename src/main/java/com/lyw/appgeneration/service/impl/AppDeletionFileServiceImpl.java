package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.service.AppDeletionFileService;
import com.lyw.appgeneration.service.AppStoragePathResolver.FrozenAppPaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** 通过固定目录句柄删除，不跟随源码或部署树中的符号链接。 */
@Service
public class AppDeletionFileServiceImpl implements AppDeletionFileService {

    private final Runnable entryDiscoveredHook;

    public AppDeletionFileServiceImpl() {
        this(() -> { });
    }

    /** 仅供包内测试确定性模拟“枚举后、读取属性前”条目消失。 */
    AppDeletionFileServiceImpl(Runnable entryDiscoveredHook) {
        this.entryDiscoveredHook = Objects.requireNonNull(entryDiscoveredHook);
    }

    @Override
    public void delete(FrozenAppPaths paths) {
        Objects.requireNonNull(paths, "冻结应用路径不能为空");
        try {
            deleteTree(paths.sourceDirectory());
            if (paths.deployDirectory().isPresent()) {
                deleteTree(paths.deployDirectory().orElseThrow());
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR, "删除应用文件失败");
        }
    }

    private void deleteTree(Path directory) throws IOException {
        Path target = normalizeLeaf(directory);
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("应用目录不能是符号链接");
        }
        try (SecureDirectoryStream<Path> parent = openSecureDirectory(
                target.getParent())) {
            SecureDirectoryStream<Path> root;
            try {
                root = parent.newDirectoryStream(
                        target.getFileName(), LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                return;
            }
            try (root) {
                deleteEntries(root);
            }
            parent.deleteDirectory(target.getFileName());
        }
    }

    private void deleteEntries(SecureDirectoryStream<Path> directory)
            throws IOException {
        for (Path entry : directory) {
            Path name = entry.getFileName();
            entryDiscoveredHook.run();
            BasicFileAttributes attributes = readAttributes(directory, name);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("应用目录包含符号链接或特殊文件");
            }
            if (attributes.isDirectory()) {
                try (SecureDirectoryStream<Path> child =
                             directory.newDirectoryStream(
                                     name, LinkOption.NOFOLLOW_LINKS)) {
                    deleteEntries(child);
                }
                directory.deleteDirectory(name);
            } else if (attributes.isRegularFile()) {
                directory.deleteFile(name);
            } else {
                throw new IOException("应用目录包含不支持的文件类型");
            }
        }
    }

    private SecureDirectoryStream<Path> openSecureDirectory(Path directory)
            throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("应用目录父路径不能是符号链接");
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        stream.close();
        throw new IOException("当前文件系统不支持安全目录句柄");
    }

    private BasicFileAttributes readAttributes(
            SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("无法读取文件属性");
        }
        return view.readAttributes();
    }

    private Path normalizeLeaf(Path path) {
        Objects.requireNonNull(path, "应用目录不能为空");
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null) {
            throw new IllegalArgumentException("应用目录不能是文件系统根目录");
        }
        return normalized;
    }
}
