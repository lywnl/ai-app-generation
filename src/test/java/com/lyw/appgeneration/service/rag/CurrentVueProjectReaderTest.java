package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.ai.plan.PlanStoragePathResolver;
import com.lyw.appgeneration.service.rag.model.CurrentVueProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CurrentVueProjectReaderTest {
    @TempDir Path output;
    private static final String PACKAGE = "{\"dependencies\":{\"vue\":\"3.3.4\"},"
            + "\"devDependencies\":{\"vite\":\"4.4.5\",\"@vitejs/plugin-vue\":\"4.2.3\"}}";
    private CurrentVueProjectReader reader() { return new CurrentVueProjectReader(new PlanStoragePathResolver(output)); }
    private Path project() throws Exception { return Files.createDirectory(output.resolve("vue_project_7")); }

    @Test
    void 缺失工程不创建文件存在但缺清单不能当空工程() throws Exception {
        assertEquals(CurrentVueProject.Status.ABSENT, reader().read(7).status());
        assertFalse(Files.exists(output.resolve("vue_project_7")));
        Path existing = project();
        assertEquals(CurrentVueProject.Status.ABSENT, reader().read(7).status());
        Files.writeString(existing.resolve("partial.js"), "// 已经生成的部分工程");
        assertEquals("PACKAGE_MISSING", reader().read(7).reason());
        assertTrue(reader().read(7).existing());
    }
    @Test
    void 读取当前版本和依赖且大小有界() throws Exception {
        Path path = project().resolve("package.json");
        Files.writeString(path, PACKAGE);
        var current = reader().read(7);
        assertTrue(current.available());
        assertEquals("vue@3.3.4", current.compatibilityDocument().getFramework());
        assertEquals("vite@4.4.5", current.compatibilityDocument().getBuildTool());
        assertThrows(UnsupportedOperationException.class, () -> current.dependencies().put("extra", "1"));
        Files.writeString(path, " ".repeat(CurrentVueProjectReader.MAX_PACKAGE_BYTES + 1));
        assertEquals("PACKAGE_TOO_LARGE", reader().read(7).reason());
    }
    @Test
    void 无效清单未知依赖和已截止不注入() throws Exception {
        Path path = project().resolve("package.json");
        for (String invalid : new String[]{"{", "[]", "{}", PACKAGE + " {}",
                PACKAGE.replace("\"vue\"", "\"unsafe-lib\"")}) {
            Files.writeString(path, invalid);
            assertFalse(reader().read(7).available());
        }
        Files.writeString(path, PACKAGE);
        assertEquals("DEADLINE_OR_CANCELLED", reader().read(7, () -> false).reason());
    }
    @Test
    void 工程和清单符号链接均拒绝且应用隔离() throws Exception {
        Path real = Files.createDirectory(output.resolve("vue_project_8"));
        Files.writeString(real.resolve("package.json"), PACKAGE);
        Path project = output.resolve("vue_project_7");
        Files.createSymbolicLink(project, real);
        assertFalse(reader().read(7).available());
        Files.delete(project);
        Files.createDirectory(project);
        Files.createSymbolicLink(project.resolve("package.json"), real.resolve("package.json"));
        assertFalse(reader().read(7).available());
        assertTrue(reader().read(8).available());
    }

    @Test
    void 完整复用固定依赖版本和禁用依赖图规则() throws Exception {
        Path directory = project();
        Path packageJson = directory.resolve("package.json");
        for (String version : new String[]{"latest", "*", "^3.3.4", "3.4.0", ">=2 <4"}) {
            Files.writeString(packageJson, PACKAGE.replace("3.3.4", version));
            assertFalse(reader().read(7).available(), version);
        }
        for (String field : new String[]{"overrides", "optionalDependencies", "peerDependencies", "workspaces"}) {
            Files.writeString(packageJson, PACKAGE.substring(0, PACKAGE.length() - 1) + ",\"" + field + "\":{}}");
            assertEquals("UNTRUSTED_DEPENDENCY_CONTRACT", reader().read(7).reason(), field);
        }
        Files.writeString(packageJson, PACKAGE);
        for (String name : com.lyw.appgeneration.core.builder.VueProjectBuilder.forbiddenDependencyResolutionFiles()) {
            Path file = directory.resolve(name);
            Files.writeString(file, "测试不允许的解析文件");
            assertEquals("UNTRUSTED_DEPENDENCY_RESOLUTION", reader().read(7).reason());
            Files.delete(file);
        }
        assertTrue(reader().read(7).available());
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
