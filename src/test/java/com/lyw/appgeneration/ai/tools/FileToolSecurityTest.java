package com.lyw.appgeneration.ai.tools;

import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.manger.AppFileStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolSecurityTest {

    private static final long APP_ID = 987654321L;

    @TempDir
    Path temporaryDirectory;

    private final AppFileStateManager fileStateManager = new AppFileStateManager();
    private final FileReadTool fileReadTool = new FileReadTool();
    private final FileModifyTool fileModifyTool = new FileModifyTool();
    private final FileDeleteTool fileDeleteTool = new FileDeleteTool();
    private final FileDirReadTool fileDirReadTool = new FileDirReadTool();
    private final FileWriteTool fileWriteTool = new FileWriteTool();

    FileToolSecurityTest() {
        ReflectionTestUtils.setField(fileWriteTool, "appFileStateManager", fileStateManager);
    }

    @AfterEach
    void cleanProjectDirectory() throws IOException {
        Path projectRoot = projectRoot();
        if (Files.exists(projectRoot)) {
            Files.walk(projectRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
        Files.deleteIfExists(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "outside-file-tool-security-test.txt"));
        fileStateManager.reset(APP_ID);
    }

    @Test
    void allFileToolsRejectAbsolutePaths() throws IOException {
        Path externalFile = Files.writeString(temporaryDirectory.resolve("outside.txt"), "外部内容");
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-directory"));

        assertRejected(fileReadTool.readFile(externalFile.toString(), APP_ID));
        assertRejected(fileWriteTool.writeFile(externalFile.toString(), "篡改", APP_ID));
        assertRejected(fileModifyTool.modifyFile(externalFile.toString(), "外部", "篡改", APP_ID));
        assertRejected(fileDeleteTool.deleteFile(externalFile.toString(), APP_ID));
        assertRejected(fileDirReadTool.readDir(externalDirectory.toString(), APP_ID));
        assertEquals("外部内容", Files.readString(externalFile));
        assertEquals(0, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void allFileToolsRejectParentTraversal() throws IOException {
        Path externalFile = Files.writeString(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "outside-file-tool-security-test.txt"), "外部内容");
        String traversal = "../" + externalFile.getFileName();

        assertRejected(fileReadTool.readFile(traversal, APP_ID));
        assertRejected(fileWriteTool.writeFile(traversal, "篡改", APP_ID));
        assertRejected(fileModifyTool.modifyFile(traversal, "外部", "篡改", APP_ID));
        assertRejected(fileDeleteTool.deleteFile(traversal, APP_ID));
        assertRejected(fileDirReadTool.readDir("../", APP_ID));
        assertEquals("外部内容", Files.readString(externalFile));
        assertEquals(0, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void directoryReadRejectsExternalLinkNestedBelowProjectRoot() throws IOException {
        Path projectRoot = createProjectRoot();
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-directory"));
        Files.writeString(externalDirectory.resolve("secret.txt"), "绝不能泄露");
        Files.createSymbolicLink(projectRoot.resolve("linked-directory"), externalDirectory);

        String result = fileDirReadTool.readDir("", APP_ID);

        assertRejected(result);
        assertFalse(result.contains("绝不能泄露"));
    }

    @Test
    void directoryReadSkipsNodeModulesLinksAndKeepsProjectFilesVisible() throws IOException {
        Path projectRoot = createProjectRoot();
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/main.js"), "export default {}");
        Path externalTarget = Files.writeString(temporaryDirectory.resolve("vite-target"), "外部目标");
        Path viteLink = projectRoot.resolve("node_modules/.bin/vite");
        Files.createDirectories(viteLink.getParent());
        Files.createSymbolicLink(viteLink, externalTarget);

        String result = fileDirReadTool.readDir("", APP_ID);

        assertTrue(result.contains("main.js"), result);
        assertFalse(result.contains("node_modules"), result);
        assertFalse(result.contains("vite"), result);
    }

    @Test
    void allFileToolsRejectProjectRootThatIsExternalSymbolicLink() throws IOException {
        Path externalProjectRoot = Files.createDirectory(temporaryDirectory.resolve("external-project"));
        Files.writeString(externalProjectRoot.resolve("existing.txt"), "外部项目内容");
        Files.createSymbolicLink(projectRoot(), externalProjectRoot);

        assertRejected(fileReadTool.readFile("existing.txt", APP_ID));
        assertRejected(fileWriteTool.writeFile("new.txt", "篡改", APP_ID));
        assertRejected(fileModifyTool.modifyFile("existing.txt", "外部", "篡改", APP_ID));
        assertRejected(fileDeleteTool.deleteFile("existing.txt", APP_ID));
        assertRejected(fileDirReadTool.readDir("", APP_ID));
        assertEquals("外部项目内容", Files.readString(externalProjectRoot.resolve("existing.txt")));
        assertFalse(Files.exists(externalProjectRoot.resolve("new.txt")));
        assertEquals(0, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void allFileToolsRejectProjectLinksToOutside() throws IOException {
        Path projectRoot = createProjectRoot();
        Path externalFile = Files.writeString(temporaryDirectory.resolve("outside.txt"), "外部内容");
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-directory"));
        Files.writeString(externalDirectory.resolve("secret.txt"), "秘密");
        Files.createSymbolicLink(projectRoot.resolve("outside-file"), externalFile);
        Files.createSymbolicLink(projectRoot.resolve("outside-directory"), externalDirectory);

        assertRejected(fileReadTool.readFile("outside-file", APP_ID));
        assertRejected(fileWriteTool.writeFile("outside-directory/new.txt", "篡改", APP_ID));
        assertRejected(fileModifyTool.modifyFile("outside-file", "外部", "篡改", APP_ID));
        assertRejected(fileDeleteTool.deleteFile("outside-file", APP_ID));
        assertRejected(fileDirReadTool.readDir("outside-directory", APP_ID));
        assertEquals("外部内容", Files.readString(externalFile));
        assertFalse(Files.exists(externalDirectory.resolve("new.txt")));
        assertEquals(0, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void allFileToolsKeepNormalProjectOperationsAvailable() throws IOException {
        Path projectRoot = createProjectRoot();
        Files.writeString(projectRoot.resolve("read.txt"), "旧内容");
        Files.writeString(projectRoot.resolve("modify.txt"), "旧内容");
        Files.writeString(projectRoot.resolve("delete.txt"), "删除我");

        assertEquals("旧内容", fileReadTool.readFile("read.txt", APP_ID));
        assertTrue(fileWriteTool.writeFile("nested/write.txt", "新内容", APP_ID).contains("文件写入成功"));
        assertTrue(fileModifyTool.modifyFile("modify.txt", "旧", "新", APP_ID).contains("文件修改成功"));
        assertTrue(fileDeleteTool.deleteFile("delete.txt", APP_ID).contains("文件删除成功"));
        assertTrue(fileDirReadTool.readDir("", APP_ID).contains("read.txt"));
        assertEquals("新内容", Files.readString(projectRoot.resolve("nested/write.txt")));
        assertEquals("新内容", Files.readString(projectRoot.resolve("modify.txt")));
        assertFalse(Files.exists(projectRoot.resolve("delete.txt")));
    }

    @Test
    void emptyDirectoryPathReadsProjectRoot() throws IOException {
        Path projectRoot = createProjectRoot();
        Files.writeString(projectRoot.resolve("root.txt"), "根目录文件");

        String result = fileDirReadTool.readDir(null, APP_ID);
        assertTrue(result.contains("root.txt"), result);
    }

    @Test
    void firstWriteCreatesMissingProjectRootOnlyAfterPathValidation() throws IOException {
        assertFalse(Files.exists(projectRoot()));

        String result = fileWriteTool.writeFile("src/main.js", "export default {}", APP_ID);

        assertTrue(result.contains("文件写入成功"), result);
        assertTrue(Files.isRegularFile(projectRoot().resolve("src/main.js")));
    }

    @Test
    void invalidOrEmptyFilePathsReturnChineseErrorsInsteadOfEscapingToolFramework() {
        assertRejected(fileReadTool.readFile("\u0000", APP_ID));
        assertRejected(fileWriteTool.writeFile("", "内容", APP_ID));
        assertRejected(fileModifyTool.modifyFile(null, "旧", "新", APP_ID));
        assertRejected(fileDeleteTool.deleteFile("", APP_ID));
    }

    private Path createProjectRoot() throws IOException {
        return Files.createDirectories(projectRoot());
    }

    private Path projectRoot() {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP_ID);
    }

    private void assertRejected(String result) {
        assertTrue(result.startsWith("错误：路径不安全"), result);
    }
}
