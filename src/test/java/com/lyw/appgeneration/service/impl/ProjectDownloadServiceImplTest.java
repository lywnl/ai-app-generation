package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.service.ProjectDownloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectDownloadServiceImplTest {

    @TempDir
    Path tempDirectory;

    @Test
    void pathApiCreatesZipAndFiltersDependenciesBuildOutputGitAndState() throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Files.writeString(project.resolve("index.html"), "首页");
        writeIgnored(project, "node_modules/pkg/index.js");
        writeIgnored(project, "dist/index.html");
        writeIgnored(project, ".git/config");
        writeIgnored(project, ".ai-build-dependency-state.json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProjectDownloadServiceImpl().downloadProjectAsZip(project, "7", response);

        assertEquals(200, response.getStatus());
        assertEquals("application/zip", response.getContentType());
        Set<String> entries = zipEntries(response.getContentAsByteArray());
        assertTrue(entries.stream().anyMatch(name -> name.endsWith("index.html")));
        assertTrue(entries.stream().noneMatch(name -> name.contains("node_modules")));
        assertTrue(entries.stream().noneMatch(name -> name.contains("dist/")));
        assertTrue(entries.stream().noneMatch(name -> name.contains(".git/")));
        assertTrue(entries.stream().noneMatch(
                name -> name.endsWith(".ai-build-dependency-state.json")));
    }

    @Test
    void rejectsSymbolicLinkProjectRootAndNestedSymbolicLink() throws Exception {
        Path realProject = Files.createDirectory(tempDirectory.resolve("real-project"));
        Files.writeString(realProject.resolve("index.html"), "ok");
        Path projectLink = tempDirectory.resolve("project-link");
        Files.createSymbolicLink(projectLink, realProject);
        ProjectDownloadServiceImpl service = new ProjectDownloadServiceImpl();

        assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                projectLink, "7", new MockHttpServletResponse()));

        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Files.createSymbolicLink(project.resolve("outside"), realProject);
        assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                project, "7", new MockHttpServletResponse()));
    }

    @Test
    void rejectsMissingPathRegularFileAndBlankName() throws Exception {
        ProjectDownloadServiceImpl service = new ProjectDownloadServiceImpl();
        Path file = Files.writeString(tempDirectory.resolve("file.txt"), "x");

        assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                tempDirectory.resolve("missing"), "7", new MockHttpServletResponse()));
        assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                file, "7", new MockHttpServletResponse()));
        assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                tempDirectory, " ", new MockHttpServletResponse()));
    }

    @Test
    void rejectsFileSystemRootBeforeTraversal() throws Exception {
        Path archive = tempDirectory.resolve("root.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(
                uri, Map.of("create", "true"))) {
            Files.writeString(fileSystem.getPath("/index.html"), "root");
            HttpServletResponse response = mock(HttpServletResponse.class);

            assertThrows(BusinessException.class, () ->
                    new ProjectDownloadServiceImpl().downloadProjectAsZip(
                            fileSystem.getPath("/"), "7", response));
            verifyNoInteractions(response);
        }
    }

    @Test
    void legacyStringApiDelegatesToPathApi() throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Files.writeString(project.resolve("index.html"), "ok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProjectDownloadServiceImpl().downloadProjectAsZip(
                project.toString(), "7", response);

        assertFalse(zipEntries(response.getContentAsByteArray()).isEmpty());
    }

    @Test
    void legacyStringApiRejectsBlankPathBeforeDelegation() {
        AtomicBoolean delegated = new AtomicBoolean();
        ProjectDownloadService service = (path, name, response) -> delegated.set(true);

        for (String projectPath : new String[]{null, "", " ", "bad\0path"}) {
            assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                    projectPath, "7", new MockHttpServletResponse()));
        }
        assertFalse(delegated.get());
    }

    @Test
    void successfulZipIsCompleteWithoutClosingContainerStream()
            throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Files.writeString(project.resolve("index.html"), "ok");
        TrackingServletOutputStream output = new TrackingServletOutputStream(false);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(output);

        new ProjectDownloadServiceImpl().downloadProjectAsZip(project, "7", response);

        assertFalse(output.closed);
        assertTrue(zipEntries(output.bytes()).contains("index.html"));
    }

    @Test
    void outputFailureDoesNotCloseContainerStreamAndPropagatesBusinessFailure()
            throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Files.writeString(project.resolve("index.html"), "ok");
        TrackingServletOutputStream output = new TrackingServletOutputStream(true);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(output);

        assertThrows(BusinessException.class, () ->
                new ProjectDownloadServiceImpl().downloadProjectAsZip(
                        project, "7", response));

        assertFalse(output.closed);
    }

    @Test
    void ancestorReplacementAfterTraversalCannotLeakOutsideFile()
            throws Exception {
        Path project = Files.createDirectory(tempDirectory.resolve("project"));
        Path assets = Files.createDirectory(project.resolve("assets"));
        Files.writeString(assets.resolve("secret.txt"), "inside");
        Path outside = Files.createDirectory(tempDirectory.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "outside-secret");
        TrackingServletOutputStream output = new TrackingServletOutputStream(false);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenAnswer(invocation -> {
            Files.delete(assets.resolve("secret.txt"));
            Files.delete(assets);
            Files.createSymbolicLink(assets, outside);
            return output;
        });

        assertThrows(BusinessException.class, () ->
                new ProjectDownloadServiceImpl().downloadProjectAsZip(
                        project, "7", response));
        assertFalse(output.closed);
    }

    private void writeIgnored(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ignored", StandardCharsets.UTF_8);
    }

    private Set<String> zipEntries(byte[] bytes) throws Exception {
        Set<String> entries = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    private static final class TrackingServletOutputStream
            extends ServletOutputStream {

        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final boolean failWrites;
        private boolean closed;

        private TrackingServletOutputStream(boolean failWrites) {
            this.failWrites = failWrites;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
        }

        @Override
        public void write(int value) throws IOException {
            if (failWrites) {
                throw new IOException("response disconnected");
            }
            delegate.write(value);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private byte[] bytes() {
            return delegate.toByteArray();
        }
    }
}
