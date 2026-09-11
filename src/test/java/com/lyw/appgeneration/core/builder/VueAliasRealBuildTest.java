package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按需运行真实 npm 构建，验证固定配置的路径别名与生成项目兼容性。 */
@EnabledIfEnvironmentVariable(named = "VUE_ALIAS_REAL_BUILD", matches = "(?i)true")
class VueAliasRealBuildTest {

    @Test
    void buildsAliasAndRelativeImportsWithoutLoadingProjectConfig() throws IOException {
        Path directory = createBuildDirectory();
        write(directory, "package.json", """
                {
                  "type": "module",
                  "scripts": {"build": "vite build"},
                  "dependencies": {"vue": "3.3.4"},
                  "devDependencies": {"vite": "4.4.5", "@vitejs/plugin-vue": "4.2.3"}
                }
                """);
        write(directory, "index.html", """
                <div id="app"></div><script type="module" src="/src/main.js"></script>
                """);
        write(directory, "src/main.js", """
                import { createApp } from 'vue'
                import App from './App.vue'
                createApp(App).mount('#app')
                """);
        write(directory, "src/App.vue", """
                <script setup>
                import HomeView from '@/views/HomeView.vue'
                import Header from '@/components/Header.vue'
                </script>
                <template><Header /><HomeView /></template>
                """);
        write(directory, "src/views/HomeView.vue", "<template>首页</template>");
        write(directory, "src/components/Header.vue", "<template>导航</template>");
        write(directory, "vite.config.js", "throw new Error('不得加载应用配置')");
        write(directory, "postcss.config.js", "throw new Error('不得加载应用 PostCSS 配置')");

        assertBuildSucceeds(directory);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VUE_ALIAS_SAMPLE_DIRECTORY", matches = ".+")
    void buildsIsolatedCopyOfGeneratedProject() throws IOException {
        Path source = Path.of(System.getenv("VUE_ALIAS_SAMPLE_DIRECTORY")).toRealPath();
        Path directory = createBuildDirectory();
        // 只复制源码和入口，依赖由构建器安装，不修改真实应用或复用其构建产物。
        for (String entry : List.of("package.json", "index.html", "src", "public", "vite.config.js")) {
            Path sourceEntry = source.resolve(entry);
            if (!Files.exists(sourceEntry)) {
                continue;
            }
            try (var paths = Files.walk(sourceEntry)) {
                for (Path path : paths.toList()) {
                    assertTrue(path.toRealPath().startsWith(source), "样本文件不能指向项目外部");
                    Path destination = directory.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                }
            }
        }
        assertBuildSucceeds(directory);
    }

    private Path createBuildDirectory() throws IOException {
        Path root = Path.of(".codex", "test-artifacts", "vite-alias");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "中文 项目-");
    }

    private void write(Path directory, String relativePath, String content) throws IOException {
        Path destination = directory.resolve(relativePath);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, content, StandardCharsets.UTF_8);
    }

    private void assertBuildSucceeds(Path directory) throws IOException {
        BuildResult result = new VueProjectBuilder().buildProjectDetailed(directory.toString());
        String report = "success=" + result.success() + "\nstage=" + result.stage()
                + "\nexitCode=" + result.exitCode() + "\n" + result.outputTail();
        Files.writeString(directory.resolve("build-result.txt"), report, StandardCharsets.UTF_8);
        assertTrue(result.success(), () -> directory + " 真实构建失败：\n" + report);
        assertTrue(Files.isRegularFile(directory.resolve("dist/index.html")));
    }
}
