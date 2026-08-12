package com.lyw.appgeneration.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.manger.AppFileStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolSecurityTest {

    private static final long APP_ID = 987654321L;
    private static final long USER_ID = 9527L;
    private static final String TURN_ID = "file-tool-turn";
    private static final Set<String> ALL_TOOLS = Set.of(
            "readFile", "writeFile", "modifyFile", "deleteFile", "readDir", "exit");

    @TempDir
    Path temporaryDirectory;

    private final AppFileStateManager fileStateManager = new AppFileStateManager();
    private final FileToolExecutionScopeManager scopeManager = new FileToolExecutionScopeManager();
    private final FileReadTool fileReadTool = new FileReadTool(scopeManager);
    private final FileModifyTool fileModifyTool = new FileModifyTool(scopeManager);
    private final FileDeleteTool fileDeleteTool = new FileDeleteTool(scopeManager);
    private final FileDirReadTool fileDirReadTool = new FileDirReadTool(scopeManager);
    private final FileWriteTool fileWriteTool = new FileWriteTool(fileStateManager, scopeManager);
    private final ExitTool exitTool = new ExitTool(fileStateManager, scopeManager);
    private final FileToolExecutionScopeManager.FileToolScope evaluationScope =
            scopeManager.evaluation(APP_ID, "evaluation-file-tools", ALL_TOOLS);

    @AfterEach
    void cleanProjectDirectory() throws IOException {
        Path projectRoot = projectRoot();
        if (Files.exists(projectRoot)) {
            try (var paths = Files.walk(projectRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
        Files.deleteIfExists(Path.of(
                AppConstant.CODE_OUTPUT_ROOT_DIR,
                "outside-file-tool-security-test.txt"));
        fileStateManager.reset(APP_ID);
    }

    @Test
    void allToolsRejectMissingScopeWithoutSideEffects() {
        assertStatus(fileReadTool.readFile("missing.txt", APP_ID), "REJECTED", false);
        assertStatus(fileWriteTool.writeFile("created.txt", "绝不能落盘", APP_ID), "REJECTED", false);
        assertStatus(fileModifyTool.modifyFile("missing.txt", "旧", "新", APP_ID), "REJECTED", false);
        assertStatus(fileDeleteTool.deleteFile("missing.txt", APP_ID), "REJECTED", false);
        assertStatus(fileDirReadTool.readDir("", APP_ID), "REJECTED", false);
        assertStatus(exitTool.exit("误注册调用", APP_ID), "REJECTED", false);

        assertFalse(Files.exists(projectRoot()));
        assertEquals(0, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void explicitEvaluationScopeKeepsNormalOperationsAvailable() throws IOException {
        Path root = createProjectRoot();
        Files.writeString(root.resolve("read.txt"), "旧内容");
        Files.writeString(root.resolve("modify.txt"), "旧内容");
        Files.writeString(root.resolve("delete.txt"), "删除我");

        JSONObject read = inEvaluation(() -> fileReadTool.readFile("read.txt", APP_ID));
        JSONObject write = inEvaluation(() ->
                fileWriteTool.writeFile("nested/write.txt", "新内容", APP_ID));
        JSONObject modify = inEvaluation(() ->
                fileModifyTool.modifyFile("modify.txt", "旧", "新", APP_ID));
        JSONObject delete = inEvaluation(() -> fileDeleteTool.deleteFile("delete.txt", APP_ID));
        JSONObject directory = inEvaluation(() -> fileDirReadTool.readDir("", APP_ID));

        assertEquals("旧内容", read.getStr("message"));
        assertEquals("APPLIED", write.getStr("status"));
        assertEquals("APPLIED", modify.getStr("status"));
        assertEquals("APPLIED", delete.getStr("status"));
        assertTrue(directory.getStr("message").contains("read.txt"), directory.toString());
        assertEquals("新内容", Files.readString(root.resolve("nested/write.txt")));
        assertEquals("新内容", Files.readString(root.resolve("modify.txt")));
        assertFalse(Files.exists(root.resolve("delete.txt")));
    }

    @Test
    void allToolsRejectAbsoluteAndParentTraversalPaths() throws IOException {
        Path externalFile = Files.writeString(temporaryDirectory.resolve("outside.txt"), "外部内容");
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-directory"));
        Path sibling = Files.writeString(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "outside-file-tool-security-test.txt"),
                "相邻内容");

        assertRejectedInEvaluation(() -> fileReadTool.readFile(externalFile.toString(), APP_ID));
        assertRejectedInEvaluation(() -> fileWriteTool.writeFile("../" + sibling.getFileName(), "篡改", APP_ID));
        assertRejectedInEvaluation(() -> fileModifyTool.modifyFile(externalFile.toString(), "外部", "篡改", APP_ID));
        assertRejectedInEvaluation(() -> fileDeleteTool.deleteFile("../" + sibling.getFileName(), APP_ID));
        assertRejectedInEvaluation(() -> fileDirReadTool.readDir(externalDirectory.toString(), APP_ID));

        assertEquals("外部内容", Files.readString(externalFile));
        assertEquals("相邻内容", Files.readString(sibling));
        assertEquals(0, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void protectedSegmentsAreRejectedAtEveryPathDepthByAllTools() throws IOException {
        Path root = createProjectRoot();
        List<String> paths = List.of(
                "node_modules/pkg/index.js",
                "src/dist/app.js",
                "src/.git/config",
                "cache/.ai-build-dependency-state.json");
        for (String path : paths) {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "受保护内容");

            assertRejectedInEvaluation(() -> fileReadTool.readFile(path, APP_ID));
            assertRejectedInEvaluation(() -> fileWriteTool.writeFile(path, "篡改", APP_ID));
            assertRejectedInEvaluation(() -> fileModifyTool.modifyFile(path, "受保护", "篡改", APP_ID));
            assertRejectedInEvaluation(() -> fileDeleteTool.deleteFile(path, APP_ID));
            assertRejectedInEvaluation(() -> fileDirReadTool.readDir(path, APP_ID));
            assertEquals("受保护内容", Files.readString(target));
        }
    }

    @Test
    void projectLinksCannotAliasProtectedDirectories() throws IOException {
        Path root = createProjectRoot();
        Path protectedDirectory = Files.createDirectories(root.resolve("node_modules/pkg"));
        Path protectedFile = Files.writeString(protectedDirectory.resolve("index.js"), "受保护内容");
        Files.createSymbolicLink(root.resolve("protected-directory-alias"), root.resolve("node_modules"));
        Files.createSymbolicLink(root.resolve("protected-file-alias"), protectedFile);

        assertRejectedInEvaluation(() -> fileReadTool.readFile("protected-file-alias", APP_ID));
        assertRejectedInEvaluation(() -> fileWriteTool.writeFile(
                "protected-directory-alias/new.js", "篡改", APP_ID));
        assertRejectedInEvaluation(() -> fileModifyTool.modifyFile(
                "protected-file-alias", "受保护", "篡改", APP_ID));
        assertRejectedInEvaluation(() -> fileDeleteTool.deleteFile("protected-file-alias", APP_ID));
        assertRejectedInEvaluation(() -> fileDirReadTool.readDir(
                "protected-directory-alias", APP_ID));

        assertEquals("受保护内容", Files.readString(protectedFile));
        assertFalse(Files.exists(root.resolve("node_modules/new.js")));
    }

    @Test
    void fileToolScopeUsesRequiredRecordShape() {
        assertTrue(FileToolExecutionScopeManager.FileToolScope.class.isRecord());
    }

    @Test
    void rootDirectoryListingNeverExposesProtectedEntries() throws IOException {
        Path root = createProjectRoot();
        Files.writeString(root.resolve("visible.txt"), "可见");
        Files.writeString(root.resolve(".ai-build-dependency-state.json"), "内部状态");
        Files.createDirectories(root.resolve("dist"));
        Files.writeString(root.resolve("dist/secret.js"), "产物");

        JSONObject directory = inEvaluation(() -> fileDirReadTool.readDir("", APP_ID));

        assertEquals("APPLIED", directory.getStr("status"));
        assertTrue(directory.getStr("message").contains("visible.txt"));
        assertFalse(directory.getStr("message").contains(".ai-build-dependency-state.json"));
        assertFalse(directory.getStr("message").contains("secret.js"));
    }

    @Test
    void projectLinksCannotEscapeRoot() throws IOException {
        Path root = createProjectRoot();
        Path externalFile = Files.writeString(temporaryDirectory.resolve("linked-file.txt"), "秘密");
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("linked-directory"));
        Files.writeString(externalDirectory.resolve("secret.txt"), "绝不能泄露");
        Files.createSymbolicLink(root.resolve("outside-file"), externalFile);
        Files.createSymbolicLink(root.resolve("outside-directory"), externalDirectory);

        assertRejectedInEvaluation(() -> fileReadTool.readFile("outside-file", APP_ID));
        assertRejectedInEvaluation(() ->
                fileWriteTool.writeFile("outside-directory/new.txt", "篡改", APP_ID));
        assertRejectedInEvaluation(() ->
                fileModifyTool.modifyFile("outside-file", "秘密", "篡改", APP_ID));
        assertRejectedInEvaluation(() -> fileDeleteTool.deleteFile("outside-file", APP_ID));
        JSONObject directory = inEvaluation(() -> fileDirReadTool.readDir("", APP_ID));
        assertEquals("REJECTED", directory.getStr("status"));
        assertFalse(directory.getStr("message").contains("绝不能泄露"));

        assertEquals("秘密", Files.readString(externalFile));
        assertFalse(Files.exists(externalDirectory.resolve("new.txt")));
    }

    @Test
    void writeAndModifyReturnMachineDecidableStatuses() throws IOException {
        JSONObject applied = inEvaluation(() ->
                fileWriteTool.writeFile("same.txt", "相同内容", APP_ID));
        JSONObject duplicate = inEvaluation(() ->
                fileWriteTool.writeFile("./same.txt", "相同内容", APP_ID));
        JSONObject failed = inEvaluation(() ->
                fileWriteTool.writeFile("null.txt", null, APP_ID));
        JSONObject noMatch = inEvaluation(() ->
                fileModifyTool.modifyFile("same.txt", "不存在", "新内容", APP_ID));

        assertStatus(applied, "APPLIED", true);
        assertEquals("same.txt", applied.getStr("relativePath"));
        assertStatus(duplicate, "NO_CHANGE", false);
        assertStatus(failed, "FAILED", false);
        assertStatus(noMatch, "NO_CHANGE", false);
        assertEquals("相同内容", Files.readString(projectRoot().resolve("same.txt")));
        assertFalse(Files.exists(projectRoot().resolve("null.txt")));
    }

    @Test
    void writeNoChangeUsesCurrentDiskContentAfterModifyOrDelete() throws IOException {
        assertEquals("APPLIED", inEvaluation(() ->
                fileWriteTool.writeFile("state.txt", "版本A", APP_ID)).getStr("status"));
        assertEquals("APPLIED", inEvaluation(() ->
                fileModifyTool.modifyFile("state.txt", "版本A", "版本B", APP_ID)).getStr("status"));

        JSONObject afterModify = inEvaluation(() ->
                fileWriteTool.writeFile("state.txt", "版本A", APP_ID));
        assertEquals("APPLIED", afterModify.getStr("status"));
        assertEquals("版本A", Files.readString(projectRoot().resolve("state.txt")));

        assertEquals("APPLIED", inEvaluation(() ->
                fileDeleteTool.deleteFile("state.txt", APP_ID)).getStr("status"));
        JSONObject afterDelete = inEvaluation(() ->
                fileWriteTool.writeFile("state.txt", "版本A", APP_ID));
        assertEquals("APPLIED", afterDelete.getStr("status"));
        assertEquals("版本A", Files.readString(projectRoot().resolve("state.txt")));
    }

    @Test
    void concurrentSameContentWritesHaveOneAppliedAndOneNoChange() throws Exception {
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        List<JSONObject> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                startBarrier.await();
                return inEvaluation(() -> fileWriteTool.writeFile(
                        "concurrent.txt", "并发内容", APP_ID));
            });
            var second = executor.submit(() -> {
                startBarrier.await();
                return inEvaluation(() -> fileWriteTool.writeFile(
                        "./concurrent.txt", "并发内容", APP_ID));
            });
            results = List.of(
                    first.get(3, TimeUnit.SECONDS),
                    second.get(3, TimeUnit.SECONDS));
        }

        assertEquals(1, results.stream()
                .filter(result -> "APPLIED".equals(result.getStr("status"))).count());
        assertEquals(1, results.stream()
                .filter(result -> "NO_CHANGE".equals(result.getStr("status"))).count());
        assertEquals("并发内容", Files.readString(projectRoot().resolve("concurrent.txt")));
        assertEquals(1, fileStateManager.fileCount(APP_ID));
    }

    @Test
    void stableMarkdownIncludesCodeOnlyForAppliedChanges() {
        JSONObject writeArguments = new JSONObject()
                .set("relativeFilePath", "src/App.vue")
                .set("content", "<template>绝密参数代码</template>");
        String applied = fileWriteTool.generateToolExecutedResult(
                writeArguments,
                FileToolProtocolSupport.json(FileToolResult.applied(
                        "writeFile", "src/App.vue", true, "已写入")));
        String noChange = fileWriteTool.generateToolExecutedResult(
                writeArguments,
                FileToolProtocolSupport.json(FileToolResult.noChange(
                        "writeFile", "src/App.vue", "未变化")));
        String malformed = fileWriteTool.generateToolExecutedResult(writeArguments, "不是 JSON");

        assertTrue(applied.contains("绝密参数代码"), applied);
        assertFalse(noChange.contains("绝密参数代码"), noChange);
        assertFalse(malformed.contains("绝密参数代码"), malformed);
        assertTrue(malformed.contains("失败"), malformed);

        JSONObject modifyArguments = new JSONObject()
                .set("relativeFilePath", "src/App.vue")
                .set("oldContent", "旧绝密代码")
                .set("newContent", "新绝密代码");
        String rejected = fileModifyTool.generateToolExecutedResult(
                modifyArguments,
                FileToolProtocolSupport.json(FileToolResult.rejected(
                        "modifyFile", "src/App.vue", "拒绝")));
        assertFalse(rejected.contains("旧绝密代码"), rejected);
        assertFalse(rejected.contains("新绝密代码"), rejected);

        String mismatchedPath = fileWriteTool.generateToolExecutedResult(
                writeArguments,
                FileToolProtocolSupport.json(FileToolResult.applied(
                        "writeFile", "src/Other.vue", true, "另一路径已写入")));
        assertFalse(mismatchedPath.contains("绝密参数代码"), mismatchedPath);
        assertTrue(mismatchedPath.contains("失败"), mismatchedPath);
    }

    @Test
    void writeGuidanceDependsOnTrustedScopeType() {
        JSONObject evaluation = inEvaluation(() ->
                fileWriteTool.writeFile("evaluation.txt", "内容", APP_ID));
        assertTrue(evaluation.getStr("message").contains("exit"), evaluation.toString());

        try (OnlineHarness online = onlineHarness(Set.of("writeFile", "exit"))) {
            JSONObject onlineWrite = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope,
                    () -> fileWriteTool.writeFile("online.txt", "内容", APP_ID)));
            assertTrue(onlineWrite.getStr("message").contains("buildProject"), onlineWrite.toString());

            JSONObject onlineExit = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope,
                    () -> exitTool.exit("提前退出", APP_ID)));
            assertEquals("NO_CHANGE", onlineExit.getStr("status"));
            assertTrue(onlineExit.getStr("message").contains("buildProject"), onlineExit.toString());
        }
    }

    @Test
    void scopedValueDoesNotLeakIntoNewVirtualThread() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String raw = scopeManager.callInScope(evaluationScope, () -> {
                try {
                    return executor.submit(() ->
                                    fileWriteTool.writeFile("leaked.txt", "绝不能落盘", APP_ID))
                            .get(2, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            assertStatus(raw, "REJECTED", false);
            assertFalse(Files.exists(projectRoot().resolve("leaked.txt")));
        }
    }

    @Test
    void staleOnlineScopeCannotContinueOrBorrowReplacementLease() {
        OnlineHarness old = onlineHarness(Set.of("writeFile"));
        FileToolExecutionScopeManager.FileToolScope staleScope = old.scope;
        old.close();

        assertThrows(IllegalStateException.class, () -> scopeManager.callInScope(
                staleScope,
                () -> fileWriteTool.writeFile("stale.txt", "绝不能落盘", APP_ID)));

        try (OnlineHarness replacement = onlineHarness(Set.of("writeFile"))) {
            assertThrows(IllegalStateException.class, () -> scopeManager.callInScope(
                    staleScope,
                    () -> fileWriteTool.writeFile("borrowed.txt", "绝不能落盘", APP_ID)));
            JSONObject valid = JSONUtil.parseObj(scopeManager.callInScope(
                    replacement.scope,
                    () -> fileWriteTool.writeFile("replacement.txt", "允许落盘", APP_ID)));
            assertEquals("APPLIED", valid.getStr("status"));
        }

        assertFalse(Files.exists(projectRoot().resolve("stale.txt")));
        assertFalse(Files.exists(projectRoot().resolve("borrowed.txt")));
    }

    @Test
    void toolWhitelistAndAppIdentityAreEnforcedInsideBean() {
        FileToolExecutionScopeManager.FileToolScope readOnly =
                scopeManager.evaluation(APP_ID, "read-only", Set.of("readFile"));
        String notAllowed = scopeManager.callInScope(
                readOnly,
                () -> fileWriteTool.writeFile("denied.txt", "不能落盘", APP_ID));
        String wrongApp = scopeManager.callInScope(
                evaluationScope,
                () -> fileWriteTool.writeFile("wrong-app.txt", "不能落盘", APP_ID + 1));

        assertStatus(notAllowed, "REJECTED", false);
        assertStatus(wrongApp, "REJECTED", false);
        assertFalse(Files.exists(projectRoot().resolve("denied.txt")));
    }

    private JSONObject inEvaluation(Supplier<String> action) {
        return JSONUtil.parseObj(scopeManager.callInScope(evaluationScope, action));
    }

    private void assertRejectedInEvaluation(Supplier<String> action) {
        assertEquals("REJECTED", inEvaluation(action).getStr("status"));
    }

    private void assertStatus(String raw, String status, boolean changed) {
        assertStatus(JSONUtil.parseObj(raw), status, changed);
    }

    private void assertStatus(JSONObject json, String status, boolean changed) {
        assertEquals("file-tool/v1", json.getStr("protocol"));
        assertEquals(status, json.getStr("status"));
        assertEquals(changed, json.getBool("changed"));
    }

    private Path createProjectRoot() throws IOException {
        return Files.createDirectories(projectRoot());
    }

    private Path projectRoot() {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP_ID);
    }

    private OnlineHarness onlineHarness(Set<String> allowedTools) {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        var operation = operationManager.acquire(
                APP_ID, AppOperationLeaseManager.AppOperationType.GENERATE, TURN_ID);
        var lease = new VueBuildSessionManager().open(operation, USER_ID, TURN_ID);
        var scope = scopeManager.online(lease, TURN_ID, APP_ID, allowedTools);
        return new OnlineHarness(operation, lease, scope);
    }

    private record OnlineHarness(
            AppOperationLeaseManager.AppOperationLease operation,
            VueBuildSessionManager.VueBuildLease lease,
            FileToolExecutionScopeManager.FileToolScope scope
    ) implements AutoCloseable {

        @Override
        public void close() {
            lease.close();
            operation.close();
        }
    }
}
