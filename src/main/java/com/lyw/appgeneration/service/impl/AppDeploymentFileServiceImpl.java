package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.service.AppDeploymentFileService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过固定目录句柄完成不跟随符号链接的部署复制。
 *
 * <p>存储根由后端服务独占管理；本类防御服务内路径竞争、静态符号链接以及
 * 枚举到打开之间的链接替换，不把同权限恶意本地进程篡改私有存储根纳入
 * 单 JVM 租约保证。目录复制是非事务覆盖，失败时可能保留部分文件。
 */
@Service
public class AppDeploymentFileServiceImpl implements AppDeploymentFileService {

    private static final Set<OpenOption> READ_OPTIONS = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    private static final Set<OpenOption> CREATE_OPTIONS = Set.of(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS);
    private static final Set<OpenOption> OVERWRITE_OPTIONS = Set.of(
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private final Runnable handlesOpenedHook;

    public AppDeploymentFileServiceImpl() {
        this(() -> { });
    }

    AppDeploymentFileServiceImpl(Runnable handlesOpenedHook) {
        this.handlesOpenedHook = Objects.requireNonNull(handlesOpenedHook);
    }

    @Override
    public void copyDirectory(Path sourceDirectory, Path deployDirectory) {
        try {
            copySecurely(sourceDirectory, deployDirectory);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR, "部署目录拷贝失败");
        }
    }

    private void copySecurely(Path sourceDirectory, Path deployDirectory)
            throws IOException {
        Path source = normalizeLeaf(sourceDirectory, "源码目录");
        Path target = normalizeLeaf(deployDirectory, "部署目录");
        rejectSymbolicLink(source);
        requireDirectory(source);
        rejectSymbolicLink(target);
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(target);
        }
        requireDirectory(target);

        try (SecureDirectoryStream<Path> sourceParent = openSecureDirectory(
                source.getParent());
             SecureDirectoryStream<Path> targetParent = openSecureDirectory(
                     target.getParent());
             SecureDirectoryStream<Path> sourceRoot = sourceParent.newDirectoryStream(
                     source.getFileName(), LinkOption.NOFOLLOW_LINKS);
             SecureDirectoryStream<Path> targetRoot = targetParent.newDirectoryStream(
                     target.getFileName(), LinkOption.NOFOLLOW_LINKS)) {
            List<EntrySnapshot> entries = prepareDirectorySkeleton(
                    sourceRoot, targetRoot, target);
            handlesOpenedHook.run();
            copyEntries(sourceRoot, targetRoot, entries);
        }
    }

    private List<EntrySnapshot> prepareDirectorySkeleton(
            SecureDirectoryStream<Path> source,
            SecureDirectoryStream<Path> target,
            Path targetAbsolute) throws IOException {
        List<EntrySnapshot> entries = new ArrayList<>();
        for (Path entry : source) {
            Path name = entry.getFileName();
            BasicFileAttributes sourceAttributes = readAttributes(source, name);
            if (sourceAttributes.isSymbolicLink() || sourceAttributes.isOther()) {
                throw new IOException("部署源码包含不受支持的链接或特殊文件");
            }
            if (!sourceAttributes.isDirectory()) {
                entries.add(new EntrySnapshot(name, false, List.of()));
                continue;
            }
            BasicFileAttributes targetAttributes = readAttributesIfPresent(target, name);
            if (targetAttributes == null) {
                Files.createDirectory(targetAbsolute.resolve(name));
            } else if (!targetAttributes.isDirectory()
                    || targetAttributes.isSymbolicLink()) {
                throw new IOException("部署目标包含非目录或符号链接");
            }
            try (SecureDirectoryStream<Path> sourceChild = source.newDirectoryStream(
                    name, LinkOption.NOFOLLOW_LINKS);
                 SecureDirectoryStream<Path> targetChild = target.newDirectoryStream(
                         name, LinkOption.NOFOLLOW_LINKS)) {
                List<EntrySnapshot> children = prepareDirectorySkeleton(
                        sourceChild, targetChild, targetAbsolute.resolve(name));
                entries.add(new EntrySnapshot(name, true, children));
            }
        }
        return List.copyOf(entries);
    }

    private void copyEntries(
            SecureDirectoryStream<Path> source,
            SecureDirectoryStream<Path> target,
            List<EntrySnapshot> entries) throws IOException {
        for (EntrySnapshot entry : entries) {
            Path name = entry.name();
            BasicFileAttributes attributes = readAttributes(source, name);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("部署源码包含不受支持的链接或特殊文件");
            }
            if (entry.directory()) {
                if (!attributes.isDirectory()) {
                    throw new IOException("部署源码目录类型在复制期间发生变化");
                }
                copyDirectoryEntry(source, target, entry);
            } else if (attributes.isRegularFile()) {
                copyFile(source, target, name);
            } else {
                throw new IOException("部署源码包含不受支持的文件类型");
            }
        }
    }

    private void copyDirectoryEntry(
            SecureDirectoryStream<Path> source,
            SecureDirectoryStream<Path> target,
            EntrySnapshot entry) throws IOException {
        Path name = entry.name();
        BasicFileAttributes targetAttributes = readAttributesIfPresent(target, name);
        if (targetAttributes == null || !targetAttributes.isDirectory()
                || targetAttributes.isSymbolicLink()) {
            throw new IOException("部署目标包含非目录或符号链接");
        }
        try (SecureDirectoryStream<Path> sourceChild = source.newDirectoryStream(
                name, LinkOption.NOFOLLOW_LINKS);
             SecureDirectoryStream<Path> targetChild = target.newDirectoryStream(
                     name, LinkOption.NOFOLLOW_LINKS)) {
            copyEntries(sourceChild, targetChild, entry.children());
        }
    }

    private void copyFile(
            SecureDirectoryStream<Path> source,
            SecureDirectoryStream<Path> target,
            Path name) throws IOException {
        BasicFileAttributes targetAttributes = readAttributesIfPresent(target, name);
        if (targetAttributes != null
                && (!targetAttributes.isRegularFile()
                || targetAttributes.isSymbolicLink())) {
            throw new IOException("部署目标包含非普通文件或符号链接");
        }
        Set<OpenOption> targetOptions = targetAttributes == null
                ? CREATE_OPTIONS : OVERWRITE_OPTIONS;
        try (SeekableByteChannel input = source.newByteChannel(name, READ_OPTIONS);
             SeekableByteChannel output = target.newByteChannel(name, targetOptions)) {
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
        }
    }

    private SecureDirectoryStream<Path> openSecureDirectory(Path directory)
            throws IOException {
        rejectSymbolicLink(directory);
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

    private BasicFileAttributes readAttributesIfPresent(
            SecureDirectoryStream<Path> directory, Path name) throws IOException {
        try {
            return readAttributes(directory, name);
        } catch (java.nio.file.NoSuchFileException exception) {
            return null;
        }
    }

    private Path normalizeLeaf(Path path, String kind) {
        Objects.requireNonNull(path, kind + "不能为空");
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null) {
            throw new IllegalArgumentException(kind + "不能是文件系统根目录");
        }
        return normalized;
    }

    private void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("部署路径不能是符号链接");
        }
    }

    private void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("部署路径不是目录");
        }
    }

    private record EntrySnapshot(
            Path name, boolean directory, List<EntrySnapshot> children) {

        private EntrySnapshot {
            Objects.requireNonNull(name);
            children = List.copyOf(children);
        }
    }
}
