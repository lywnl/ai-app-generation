package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.ai.plan.PlanStoragePathResolver;
import com.lyw.appgeneration.service.rag.model.CurrentVueProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CurrentVueProjectReaderTest {
    @TempDir Path output;

    private CurrentVueProjectReader reader() { return new CurrentVueProjectReader(new PlanStoragePathResolver(output)); }
    private Path project() throws Exception { return Files.createDirectory(output.resolve("vue_project_7")); }

    @Test
    void 缺失或空目录为新工程但不会创建目录() throws Exception {
        assertEquals(CurrentVueProject.Status.ABSENT, reader().read(7).status());
        assertEquals("PROJECT_ABSENT", reader().read(7).reason());
        assertFalse(Files.exists(output.resolve("vue_project_7")));
        project();
        assertEquals("EMPTY_PROJECT_DIRECTORY", reader().read(7).reason());
        assertFalse(reader().read(7).existing());
    }

    @Test
    void 只有部分文件或子目录也属于已有工程() throws Exception {
        Path directory = project();
        Path partial = directory.resolve("partial.js");
        Files.writeString(partial, "// 已有代码");
        assertTrue(reader().read(7).available());
        assertTrue(reader().read(7).existing());
        assertFalse(Files.exists(directory.resolve("package.json")));
        Files.delete(partial);
        Files.createDirectory(directory.resolve("src"));
        assertTrue(reader().read(7).available());
    }

    @Test
    void 清单格式大小或依赖内容不影响目录状态() throws Exception {
        Path manifest = project().resolve("package.json");
        for (String value : new String[]{"{", "[]", "{}", "{} {}", " ".repeat(64 * 1024 + 1),
                "{\"dependencies\":{\"unsafe-lib\":\"latest\",\"vue\":\"^2\"},\"workspaces\":[]}"}) {
            Files.writeString(manifest, value);
            assertEquals(CurrentVueProject.Status.AVAILABLE, reader().read(7).status());
        }
    }

    @Test
    void 锁文件和解析配置不阻止资料召回() throws Exception {
        Path directory = project();
        for (String name : new String[]{"package-lock.json", "npm-shrinkwrap.json", ".npmrc"}) {
            Path file = directory.resolve(name);
            Files.writeString(file, "不应读取的内容");
            assertTrue(reader().read(7).available(), name);
            Files.delete(file);
        }
    }

    @Test
    void 目录内部链接只作为条目且不跟随目标() throws Exception {
        Path directory = project();
        // 悬空链接仍应标记已有工程，证明无需打开或检查链接目标。
        Files.createSymbolicLink(directory.resolve("package.json"), output.resolve("不存在的目标"));
        assertTrue(reader().read(7).available());
        assertFalse(Files.exists(output.resolve("不存在的目标")));
    }

    @Test
    void 工程和祖先路径链接拒绝且其他应用不受影响() throws Exception {
        Path real = Files.createDirectory(output.resolve("vue_project_8"));
        Files.writeString(real.resolve("existing.txt"), "已有工程");
        Files.createSymbolicLink(output.resolve("vue_project_7"), real);
        var rejected = reader().read(7);
        assertEquals(CurrentVueProject.Status.UNAVAILABLE, rejected.status());
        assertTrue(rejected.existing());
        assertTrue(reader().read(8).available());
        Path alias = output.resolve("alias");
        Files.createSymbolicLink(alias, output);
        assertEquals(CurrentVueProject.Status.UNAVAILABLE,
                new CurrentVueProjectReader(new PlanStoragePathResolver(alias)).read(8).status());
    }

    @Test
    void 无法作为目录打开时不伪装成新工程() throws Exception {
        Files.writeString(output.resolve("vue_project_7"), "普通文件不是目录");
        var result = reader().read(7);
        assertEquals(CurrentVueProject.Status.UNAVAILABLE, result.status());
        assertEquals("UNSAFE_PROJECT_PATH", result.reason());
        assertTrue(result.existing());
    }

    @Test
    void 无权读取的目录不可用但不可读的清单不影响目录识别() throws Exception {
        Path directory = project();
        Path manifest = Files.writeString(directory.resolve("package.json"), "不应读取");
        var filePermissions = Files.getPosixFilePermissions(manifest);
        var directoryPermissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(manifest, Set.of());
            assertTrue(reader().read(7).available());
            Files.setPosixFilePermissions(directory, Set.of());
            CurrentVueProject unavailable = reader().read(7);
            assertEquals(CurrentVueProject.Status.UNAVAILABLE, unavailable.status());
            assertTrue(unavailable.existing());
        } finally {
            Files.setPosixFilePermissions(directory, directoryPermissions);
            Files.setPosixFilePermissions(manifest, filePermissions);
        }
    }

    @Test
    void 截止前后都检查活跃状态() throws Exception {
        Files.writeString(project().resolve("partial.js"), "已有文件");
        assertEquals("DEADLINE_OR_CANCELLED", reader().read(7, () -> false).reason());
        AtomicInteger checks = new AtomicInteger();
        assertEquals("DEADLINE_OR_CANCELLED", reader().read(7, () -> checks.incrementAndGet() == 1).reason());
        checks.set(0);
        assertEquals("DEADLINE_OR_CANCELLED", reader().read(7, () -> checks.incrementAndGet() <= 2).reason());
        checks.set(0);
        assertEquals("DEADLINE_OR_CANCELLED", reader().read(99, () -> checks.incrementAndGet() == 1).reason());
    }

    @Test
    void 真实计划初始化创建空目录后仍允许首轮完整召回() {
        var paths = new PlanStoragePathResolver(output);
        var plans = new com.lyw.appgeneration.ai.plan.AppPlanStateManager(new com.fasterxml.jackson.databind.ObjectMapper(), paths);
        assertTrue(plans.loadForTurn(7, "initial").isEmpty());
        assertTrue(Files.isDirectory(paths.projectRoot(7)));
        var current = new CurrentVueProjectReader(paths).read(7);
        assertEquals(CurrentVueProject.Status.ABSENT, current.status());
        assertEquals("EMPTY_PROJECT_DIRECTORY", current.reason());
    }
}
