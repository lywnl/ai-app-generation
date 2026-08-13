package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.service.AppDeletionFileService;
import com.lyw.appgeneration.service.AppStoragePathResolver.FrozenAppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Optional;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDeletionFileServiceImplTest {

    @TempDir
    private Path temporaryDirectory;

    private final AppDeletionFileService service = new AppDeletionFileServiceImpl();

    @Test
    void deletesNestedSourceAndDeployDirectories() throws Exception {
        Path source = temporaryDirectory.resolve("source/vue_project_7");
        Path deploy = temporaryDirectory.resolve("deploy/deploy7");
        Files.createDirectories(source.resolve("src/assets"));
        Files.createDirectories(deploy.resolve("assets"));
        Files.writeString(source.resolve("src/assets/app.js"), "source");
        Files.writeString(deploy.resolve("assets/app.js"), "deploy");

        service.delete(new FrozenAppPaths(source, Optional.of(deploy)));

        assertFalse(Files.exists(source));
        assertFalse(Files.exists(deploy));
        assertTrue(Files.isDirectory(source.getParent()));
        assertTrue(Files.isDirectory(deploy.getParent()));
    }

    @Test
    void missingDirectoriesAreIdempotentAndEmptyDeployPathIsSkipped() {
        Path source = temporaryDirectory.resolve("source/vue_project_7");

        service.delete(new FrozenAppPaths(source, Optional.empty()));
        service.delete(new FrozenAppPaths(source, Optional.empty()));

        assertFalse(Files.exists(source));
    }

    @Test
    void rejectsSymbolicLinkInsideSourceWithoutDeletingOutsideTarget()
            throws Exception {
        Path source = temporaryDirectory.resolve("source/vue_project_7");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(source.resolve("assets"));
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(source.resolve("assets/outside"), outside);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.delete(new FrozenAppPaths(
                        source, Optional.empty())));

        assertEquals("删除应用文件失败", exception.getMessage());
        assertTrue(Files.exists(outside.resolve("secret.txt")));
    }

    @Test
    void rejectsSymbolicLinkDirectoryRootWithoutDeletingTarget()
            throws Exception {
        Path realSource = temporaryDirectory.resolve("real-source");
        Path sourceLink = temporaryDirectory.resolve("source/vue_project_7");
        Files.createDirectories(realSource);
        Files.createDirectories(sourceLink.getParent());
        Files.writeString(realSource.resolve("index.html"), "content");
        Files.createSymbolicLink(sourceLink, realSource);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.delete(new FrozenAppPaths(
                        sourceLink, Optional.empty())));

        assertEquals("删除应用文件失败", exception.getMessage());
        assertTrue(Files.exists(realSource.resolve("index.html")));
    }

    @Test
    void sourceFailurePreventsDeployDeletion() throws Exception {
        Path source = temporaryDirectory.resolve("source/vue_project_7");
        Path deploy = temporaryDirectory.resolve("deploy/deploy7");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(source);
        Files.createDirectories(deploy);
        Files.createDirectories(outside);
        Files.createSymbolicLink(source.resolve("bad-link"), outside);
        Files.writeString(deploy.resolve("index.html"), "deploy");

        assertThrows(
                BusinessException.class,
                () -> service.delete(new FrozenAppPaths(
                        source, Optional.of(deploy))));

        assertTrue(Files.exists(deploy.resolve("index.html")));
    }

    @Test
    void internalEntryDisappearanceFailsAndKeepsDeployUntouched()
            throws Exception {
        Path source = temporaryDirectory.resolve("source/vue_project_7");
        Path deploy = temporaryDirectory.resolve("deploy/deploy7");
        Path disappearing = source.resolve("disappearing.txt");
        Files.createDirectories(source);
        Files.createDirectories(deploy);
        Files.writeString(disappearing, "gone");
        Files.writeString(deploy.resolve("index.html"), "deploy");
        AtomicBoolean removed = new AtomicBoolean();
        AppDeletionFileServiceImpl racingService =
                new AppDeletionFileServiceImpl(() -> {
                    if (removed.compareAndSet(false, true)) {
                        try {
                            Files.delete(disappearing);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }
                });

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> racingService.delete(new FrozenAppPaths(
                        source, Optional.of(deploy))));

        assertEquals("删除应用文件失败", exception.getMessage());
        assertTrue(Files.exists(source));
        assertTrue(Files.exists(deploy.resolve("index.html")));
    }

    @Test
    void fileSystemWithoutSecureDirectoryStreamFailsClosed() throws Exception {
        Path archive = temporaryDirectory.resolve("archive.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(
                uri, Map.of("create", "true"))) {
            Path source = zip.getPath("/source");
            Files.createDirectories(source);
            Files.writeString(source.resolve("index.html"), "content");

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.delete(new FrozenAppPaths(
                            source, Optional.empty())));

            assertEquals("删除应用文件失败", exception.getMessage());
            assertTrue(Files.exists(source.resolve("index.html")));
        }
    }
}
