package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppDeploymentFileServiceImplTest {

    @TempDir
    private Path temporaryDirectory;

    private final AppDeploymentFileServiceImpl service =
            new AppDeploymentFileServiceImpl();

    @Test
    void copiesNestedFilesAndOverwritesExistingTarget() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectories(source.resolve("assets"));
        Files.createDirectories(target.resolve("assets"));
        Files.writeString(
                source.resolve("index.html"), "new-index", StandardCharsets.UTF_8);
        Files.writeString(
                source.resolve("assets/app.js"), "new-js", StandardCharsets.UTF_8);
        Files.writeString(
                target.resolve("index.html"), "old-index", StandardCharsets.UTF_8);

        service.copyDirectory(source, target);

        assertEquals("new-index", Files.readString(
                target.resolve("index.html"), StandardCharsets.UTF_8));
        assertEquals("new-js", Files.readString(
                target.resolve("assets/app.js"), StandardCharsets.UTF_8));
    }

    @Test
    void createsMissingTargetParentForFirstDeployment() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path deployRoot = temporaryDirectory.resolve("deploy-root");
        Path target = deployRoot.resolve("deploy-key");
        Files.createDirectories(source);
        Files.writeString(
                source.resolve("index.html"), "content", StandardCharsets.UTF_8);

        service.copyDirectory(source, target);

        assertEquals("content", Files.readString(
                target.resolve("index.html"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsSymbolicLinkTargetParentWithoutWritingOutside() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path outsideTarget = temporaryDirectory.resolve("outside-target");
        Path deployRootLink = temporaryDirectory.resolve("deploy-root");
        Path target = deployRootLink.resolve("deploy-key");
        Files.createDirectories(source);
        Files.createDirectories(outsideTarget);
        Files.writeString(
                source.resolve("index.html"), "content", StandardCharsets.UTF_8);
        Files.createSymbolicLink(deployRootLink, outsideTarget);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(source, target));

        assertEquals("部署目录拷贝失败", exception.getMessage());
        assertFalse(Files.exists(outsideTarget.resolve("deploy-key")));
    }

    @Test
    void rejectsRegularFileTargetParent() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path deployRootFile = temporaryDirectory.resolve("deploy-root");
        Path target = deployRootFile.resolve("deploy-key");
        Files.createDirectories(source);
        Files.writeString(deployRootFile, "not-a-directory", StandardCharsets.UTF_8);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(source, target));

        assertEquals("部署目录拷贝失败", exception.getMessage());
    }

    @Test
    void supportsConcurrentFirstDeploymentsToMissingTargetParent() throws Exception {
        int deploymentCount = 4;
        Path deployRoot = temporaryDirectory.resolve("deploy-root");
        CountDownLatch ready = new CountDownLatch(deploymentCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(deploymentCount);
        List<Future<?>> deployments = new ArrayList<>();
        try {
            for (int index = 0; index < deploymentCount; index++) {
                Path source = temporaryDirectory.resolve("source-" + index);
                Path target = deployRoot.resolve("deploy-key-" + index);
                Files.createDirectories(source);
                Files.writeString(
                        source.resolve("index.html"), "content-" + index,
                        StandardCharsets.UTF_8);
                deployments.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    service.copyDirectory(source, target);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> deployment : deployments) {
                deployment.get();
            }
        } finally {
            executor.shutdownNow();
        }

        for (int index = 0; index < deploymentCount; index++) {
            assertEquals("content-" + index, Files.readString(
                    deployRoot.resolve("deploy-key-" + index).resolve("index.html"),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsSymbolicLinkAnywhereInsideSourceTree() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Path outside = temporaryDirectory.resolve("outside-secret.txt");
        Files.createDirectories(source.resolve("assets"));
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(source.resolve("assets/secret.txt"), outside);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(source, target));

        assertEquals("部署目录拷贝失败", exception.getMessage());
        assertFalse(Files.exists(target.resolve("assets/secret.txt")));
    }

    @Test
    void rejectsSymbolicLinkSourceRoot() throws Exception {
        Path realSource = temporaryDirectory.resolve("real-source");
        Path sourceLink = temporaryDirectory.resolve("source-link");
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectories(realSource);
        Files.createSymbolicLink(sourceLink, realSource);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(sourceLink, target));

        assertEquals("部署目录拷贝失败", exception.getMessage());
    }

    @Test
    void rejectsSymbolicLinkTargetDirectory() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path outsideTarget = temporaryDirectory.resolve("outside-target");
        Path targetLink = temporaryDirectory.resolve("target-link");
        Files.createDirectories(source);
        Files.createDirectories(outsideTarget);
        Files.writeString(source.resolve("index.html"), "content");
        Files.createSymbolicLink(targetLink, outsideTarget);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(source, targetLink));

        assertEquals("部署目录拷贝失败", exception.getMessage());
        assertFalse(Files.exists(outsideTarget.resolve("index.html")));
    }

    @Test
    void rejectsSymbolicLinkInsideTargetTree() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Path outsideTarget = temporaryDirectory.resolve("outside-target");
        Files.createDirectories(source.resolve("assets"));
        Files.createDirectories(target);
        Files.createDirectories(outsideTarget);
        Files.writeString(source.resolve("assets/app.js"), "content");
        Files.createSymbolicLink(target.resolve("assets"), outsideTarget);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(source, target));

        assertEquals("部署目录拷贝失败", exception.getMessage());
        assertFalse(Files.exists(outsideTarget.resolve("app.js")));
    }

    @Test
    void fixedHandlesResistDeterministicRootReplacement() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Path movedSource = temporaryDirectory.resolve("moved-source");
        Path movedTarget = temporaryDirectory.resolve("moved-target");
        Path hostileSource = temporaryDirectory.resolve("hostile-source");
        Path hostileTarget = temporaryDirectory.resolve("hostile-target");
        Files.createDirectories(source.resolve("assets"));
        Files.createDirectories(target);
        Files.createDirectories(hostileSource.resolve("assets"));
        Files.createDirectories(hostileTarget);
        Files.writeString(source.resolve("assets/app.js"), "trusted");
        Files.writeString(hostileSource.resolve("assets/app.js"), "hostile");
        AppDeploymentFileServiceImpl replacingService =
                new AppDeploymentFileServiceImpl(() -> {
                    try {
                        Files.move(source, movedSource);
                        Files.createSymbolicLink(source, hostileSource);
                        Files.move(target, movedTarget);
                        Files.createSymbolicLink(target, hostileTarget);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });

        replacingService.copyDirectory(source, target);

        assertEquals("trusted", Files.readString(
                movedTarget.resolve("assets/app.js")));
        assertFalse(Files.exists(hostileTarget.resolve("assets/app.js")));
    }

    @Test
    void missingSourceMapsToStableBusinessError() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.copyDirectory(
                        temporaryDirectory.resolve("missing"),
                        temporaryDirectory.resolve("target")));

        assertEquals("部署目录拷贝失败", exception.getMessage());
    }
}
