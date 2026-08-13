package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store", ".env",
            "target", ".mvn", ".idea", ".vscode",
            ".ai-build-dependency-state.json"
    );

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache"
    );

    @Override
    public void downloadProjectAsZip(
            Path projectPath, String downloadFileName, HttpServletResponse response) {
        ThrowUtils.throwIf(projectPath == null, ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(downloadFileName),
                ErrorCode.PARAMS_ERROR, "下载文件名不能为空");
        Path projectDirectory = projectPath.toAbsolutePath().normalize();
        ThrowUtils.throwIf(projectDirectory.getParent() == null,
                ErrorCode.PARAMS_ERROR, "项目目录不能是文件系统根目录");
        ThrowUtils.throwIf(!Files.exists(projectDirectory, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.NOT_FOUND_ERROR, "项目目录不存在");
        ThrowUtils.throwIf(Files.isSymbolicLink(projectDirectory)
                        || !Files.isDirectory(projectDirectory, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.PARAMS_ERROR, "指定路径不是安全目录");
        try (DirectoryStream<Path> parent = Files.newDirectoryStream(
                projectDirectory.getParent())) {
            SecureDirectoryStream<Path> secureParent = requireSecureStream(parent);
            try (SecureDirectoryStream<Path> project = secureParent.newDirectoryStream(
                    projectDirectory.getFileName(), LinkOption.NOFOLLOW_LINKS)) {
                writeArchive(project, downloadFileName, response);
            }
            log.info("项目打包下载完成: {}", downloadFileName);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("项目打包下载异常", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "项目打包下载失败");
        }
    }

    private void writeArchive(
            SecureDirectoryStream<Path> project,
            String downloadFileName,
            HttpServletResponse response) throws IOException {
        log.info("开始打包下载项目: {}.zip", downloadFileName);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.addHeader("Content-Disposition",
                String.format("attachment; filename=\"%s.zip\"", downloadFileName));
        OutputStream containerOutput = response.getOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new CloseShieldOutputStream(containerOutput)), StandardCharsets.UTF_8)) {
            archiveDirectory(project, Path.of(""), zip);
        }
    }

    private void archiveDirectory(
            SecureDirectoryStream<Path> directory,
            Path relativeDirectory,
            ZipOutputStream zip) throws IOException {
        for (Path entry : directory) {
            Path entryName = entry.getFileName();
            BasicFileAttributes attributes = directory.getFileAttributeView(
                    entryName, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            ThrowUtils.throwIf(attributes.isSymbolicLink(),
                    ErrorCode.PARAMS_ERROR, "项目目录不能包含符号链接");
            Path relativePath = relativeDirectory.resolve(entryName.toString());
            if (!isPathAllowed(relativePath)) {
                continue;
            }
            if (attributes.isDirectory()) {
                try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(
                        entryName, LinkOption.NOFOLLOW_LINKS)) {
                    archiveDirectory(child, relativePath, zip);
                }
            } else if (attributes.isRegularFile()) {
                archiveFile(directory, entryName, relativePath, zip);
            }
        }
    }

    private void archiveFile(
            SecureDirectoryStream<Path> directory,
            Path entryName,
            Path relativePath,
            ZipOutputStream zip) throws IOException {
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = directory.newByteChannel(entryName, options);
             InputStream input = Channels.newInputStream(channel)) {
            zip.putNextEntry(new ZipEntry(
                    relativePath.toString().replace('\\', '/')));
            input.transferTo(zip);
            zip.closeEntry();
        }
    }

    @SuppressWarnings("unchecked")
    private SecureDirectoryStream<Path> requireSecureStream(
            DirectoryStream<Path> directoryStream) {
        if (!(directoryStream instanceof SecureDirectoryStream<?>)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "当前文件系统不支持安全目录访问");
        }
        return (SecureDirectoryStream<Path>) directoryStream;
    }

    private boolean isPathAllowed(Path relativePath) {
        for (Path part : relativePath) {
            String partName = part.toString();
            if (IGNORED_NAMES.contains(partName)
                    || IGNORED_EXTENSIONS.stream().anyMatch(partName::endsWith)) {
                return false;
            }
        }
        return true;
    }

    /** 允许 ZIP 正常收尾，但不关闭 Servlet 容器拥有的底层响应流。 */
    private static final class CloseShieldOutputStream extends FilterOutputStream {

        private CloseShieldOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
