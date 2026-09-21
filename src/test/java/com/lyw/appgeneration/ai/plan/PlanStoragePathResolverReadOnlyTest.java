package com.lyw.appgeneration.ai.plan;

import com.lyw.appgeneration.utils.SecureFileAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.Channels;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanStoragePathResolverReadOnlyTest {

    @TempDir
    Path temporary;

    @Test
    void 缺失目录和计划不会被查询创建() throws Exception {
        Path root = temporary.resolve("absent");
        PlanStoragePathResolver resolver = new PlanStoragePathResolver(root);
        assertTrue(read(resolver).isEmpty());
        assertFalse(Files.exists(root));
        Files.createDirectory(root);
        assertTrue(read(resolver).isEmpty());
        assertFalse(Files.exists(resolver.projectRoot(7)));
        Files.createDirectory(resolver.projectRoot(7));
        assertTrue(read(resolver).isEmpty());
        assertFalse(Files.exists(resolver.planPath(7)));
    }

    @Test
    void 合法文件通过已打开目录读取() throws Exception {
        PlanStoragePathResolver resolver = resolver();
        Files.writeString(resolver.planPath(7), "计划原文");
        assertEquals("计划原文", read(resolver).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> resolver.withExistingProjectDirectory(0, ignored -> ""));
    }

    @Test
    void 拒绝计划链接和非普通文件() throws Exception {
        PlanStoragePathResolver resolver = resolver();
        Path outside = Files.writeString(temporary.resolve("outside"), "不可读取");
        Files.createSymbolicLink(resolver.planPath(7), outside);
        assertThrows(IllegalStateException.class, () -> read(resolver));
        Files.delete(resolver.planPath(7));
        Files.createDirectory(resolver.planPath(7));
        assertThrows(IllegalStateException.class, () -> read(resolver));
    }

    @Test
    void 根目录和父目录链接均被拒绝() throws Exception {
        PlanStoragePathResolver resolver = resolver();
        Path alias = temporary.resolve("alias");
        Files.createSymbolicLink(alias, resolver.projectRoot(7).getParent());
        assertThrows(IllegalStateException.class,
                () -> read(new PlanStoragePathResolver(alias)));
        Path parentAlias = temporary.resolve("parent-alias");
        Files.createSymbolicLink(parentAlias, temporary);
        assertThrows(IllegalStateException.class,
                () -> read(new PlanStoragePathResolver(parentAlias.resolve("output"))));
    }

    @Test
    void 已打开目录被替换时不会跟随新链接() throws Exception {
        PlanStoragePathResolver resolver = resolver();
        Files.writeString(resolver.planPath(7), "原计划");
        Path other = Files.createDirectory(temporary.resolve("other"));
        Files.writeString(other.resolve(".plan.json"), "其他应用计划");
        String result = resolver.withExistingProjectDirectory(7, directory -> {
            Files.move(resolver.projectRoot(7), temporary.resolve("moved"));
            Files.createSymbolicLink(resolver.projectRoot(7), other);
            return readContent(directory);
        }).orElseThrow();
        assertEquals("原计划", result);
        assertThrows(IllegalStateException.class, () -> read(resolver));
    }

    @Test
    void 不支持安全目录的真实文件系统拒绝而非降级() throws Exception {
        URI uri = URI.create("jar:" + temporary.resolve("archive.zip").toUri());
        try (var zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Files.createDirectories(zip.getPath("/output/vue_project_7"));
            Files.writeString(zip.getPath("/output/vue_project_7/.plan.json"), "内容");
            assertThrows(IllegalStateException.class,
                    () -> read(new PlanStoragePathResolver(zip.getPath("/output"))));
        }
    }

    private PlanStoragePathResolver resolver() throws Exception {
        Path root = temporary.resolve("output");
        Files.createDirectories(root.resolve("vue_project_7"));
        return new PlanStoragePathResolver(root);
    }

    private java.util.Optional<String> read(PlanStoragePathResolver resolver) {
        return resolver.withExistingProjectDirectory(7, this::readContent);
    }

    private String readContent(java.nio.file.SecureDirectoryStream<Path> directory)
            throws java.io.IOException {
        try (var channel = SecureFileAccess.openRegularFile(directory, Path.of(".plan.json"))) {
            return new String(Channels.newInputStream(channel).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
