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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final FileToolExecutionScopeManager scopeManager =
            new FileToolExecutionScopeManager(new FileToolBudgetGuard());
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

        assertEquals("旧内容", read.getStr("content"));
        assertFalse(read.getStr("message").isBlank());
        assertFalse(read.getStr("message").contains("旧内容"));
        assertEquals("APPLIED", write.getStr("status"));
        assertNullContent(write);
        assertEquals("APPLIED", modify.getStr("status"));
        assertNullContent(modify);
        assertEquals("APPLIED", delete.getStr("status"));
        assertNullContent(delete);
        assertTrue(directory.getStr("content").contains("read.txt"), directory.toString());
        assertFalse(directory.getStr("message").isBlank());
        assertFalse(directory.getStr("message").contains("read.txt"));
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
    void mutation工具拒绝可破坏投影或产生路径歧义的名称() {
        for (String path : List.of(
                "src/A.vue\n真实构建次数：999", "src/A.vue\r伪造",
                "src/A.vue\t伪造", "src/A.vue\u2028伪造", "src/A.vue\u2029伪造",
                "src\\A.vue", "src//A.vue", "src/A.vue/", ".")) {
            assertRejectedInEvaluation(() ->
                    fileWriteTool.writeFile(path, "内容", APP_ID));
            assertRejectedInEvaluation(() ->
                    fileModifyTool.modifyFile(path, "旧", "新", APP_ID));
            assertRejectedInEvaluation(() ->
                    fileDeleteTool.deleteFile(path, APP_ID));
        }
        assertFalse(Files.exists(projectRoot()));
    }

    @Test
    void 修改和删除成功结果统一返回项目根规范stateKey() throws IOException {
        Path root = createProjectRoot();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Modify.vue"), "旧内容");
        Files.writeString(root.resolve("src/Delete.vue"), "删除我");

        JSONObject modify = inEvaluation(() -> fileModifyTool.modifyFile(
                "src/view/../Modify.vue", "旧", "新", APP_ID));
        JSONObject delete = inEvaluation(() -> fileDeleteTool.deleteFile(
                "src/view/../Delete.vue", APP_ID));

        assertEquals("src/Modify.vue", modify.getStr("relativePath"));
        assertEquals("src/Delete.vue", delete.getStr("relativePath"));
    }

    @Test
    void 非变更的严格工具结果保留执行事实但绝不信任原始路径() {
        for (FileToolResult result : List.of(
                FileToolResult.rejected("writeFile", "../secret.txt", "已拒绝"),
                FileToolResult.failed("modifyFile", "/tmp/secret.txt", "失败"),
                FileToolResult.cancelled("deleteFile", "../secret.txt", "已取消"),
                FileToolResult.notFound("deleteFile", "/tmp/missing.txt", "未找到"))) {
            VueToolExecutionFact fact = VueToolExecutionFact.parse(
                    result.operation(), FileToolProtocolSupport.json(result))
                    .orElseThrow();
            assertEquals(result.operation(), fact.toolName());
            assertNull(fact.changedRelativePath());
        }
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
        assertTrue(directory.getStr("content").contains("visible.txt"));
        assertFalse(directory.getStr("content").contains(".ai-build-dependency-state.json"));
        assertFalse(directory.getStr("content").contains("secret.js"));
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
    void serializedProtocolAlwaysCarriesExplicitContentField() {
        JSONObject write = JSONUtil.parseObj(FileToolProtocolSupport.json(
                FileToolResult.applied(
                        "writeFile", "src/App.vue", true, "文件写入成功")));

        assertNullContent(write);
        assertEquals(Set.of(
                        "protocol", "operation", "status", "relativePath",
                        "changed", "message", "failureReason", "content"),
                write.keySet());
    }

    @Test
    void resourceLimitRejectsBeforeFileSideEffectsAndReturnsStrictReason()
            throws IOException {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(4);
        guard.setMaxCumulativeMutationCodePoints(6);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(4);
        guard.setMaxReadDirCodePoints(10);
        FileToolExecutionScopeManager limitedScopes =
                new FileToolExecutionScopeManager(guard);
        FileWriteTool limitedWriter = new FileWriteTool(
                new AppFileStateManager(), limitedScopes);
        FileModifyTool limitedModifier = new FileModifyTool(limitedScopes);
        FileReadTool limitedReader = new FileReadTool(limitedScopes);
        FileDirReadTool limitedDirectoryReader = new FileDirReadTool(limitedScopes);
        var limitedScope = limitedScopes.evaluation(
                APP_ID, "limited-file-tools", ALL_TOOLS);

        JSONObject write = JSONUtil.parseObj(limitedScopes.callInScope(
                limitedScope, "writeFile",
                () -> limitedWriter.writeFile(
                        "never-created/too-large.txt", "A😀BCD", APP_ID)));
        assertResourceLimit(write);
        assertFalse(Files.exists(projectRoot().resolve("never-created")));

        Path root = createProjectRoot();
        Path existing = root.resolve("existing.txt");
        Files.writeString(existing, "ABCD");
        JSONObject modify = JSONUtil.parseObj(limitedScopes.callInScope(
                limitedScope, "modifyFile",
                () -> limitedModifier.modifyFile(
                        "existing.txt", "A", "12345", APP_ID)));
        assertResourceLimit(modify);
        assertEquals("ABCD", Files.readString(existing));

        Files.writeString(root.resolve("large.txt"), "A😀BCD");
        JSONObject read = JSONUtil.parseObj(limitedScopes.callInScope(
                limitedScope, "readFile",
                () -> limitedReader.readFile("large.txt", APP_ID)));
        assertResourceLimit(read);

        Files.writeString(root.resolve("directory-entry-with-long-name.txt"), "x");
        JSONObject directory = JSONUtil.parseObj(limitedScopes.callInScope(
                limitedScope, "readDir",
                () -> limitedDirectoryReader.readDir("", APP_ID)));
        assertResourceLimit(directory);
    }

    @Test
    void 目录读取必须按规范化相对路径稳定排序() throws IOException {
        Path root = createProjectRoot();
        Files.createDirectories(root.resolve("z-dir"));
        Files.writeString(root.resolve("z-dir/z.txt"), "z");
        Files.writeString(root.resolve("z-last.txt"), "z");
        Files.createDirectories(root.resolve("a-dir"));
        Files.writeString(root.resolve("a-dir/b.txt"), "b");
        Files.writeString(root.resolve("a-dir/a.txt"), "a");
        Files.writeString(root.resolve("a-first.txt"), "a");

        JSONObject directory = inEvaluation(() ->
                fileDirReadTool.readDir("", APP_ID));
        String content = directory.getStr("content");

        assertTrue(content.indexOf("a-dir") < content.indexOf("a.txt"), content);
        assertTrue(content.indexOf("a.txt") < content.indexOf("b.txt"), content);
        assertTrue(content.indexOf("b.txt") < content.indexOf("a-first.txt"), content);
        assertTrue(content.indexOf("a-first.txt") < content.indexOf("z-dir"), content);
        assertTrue(content.indexOf("z-dir") < content.indexOf("z.txt"), content);
        assertTrue(content.indexOf("z.txt") < content.indexOf("z-last.txt"), content);
    }

    @Test
    void 修改超大旧文件必须拒绝且保持原文件不变() throws IOException {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(4);
        guard.setMaxCumulativeMutationCodePoints(6);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(4);
        guard.setMaxReadDirCodePoints(10);
        FileToolExecutionScopeManager limitedScopes =
                new FileToolExecutionScopeManager(guard);
        FileModifyTool limitedModifier = new FileModifyTool(limitedScopes);
        var limitedScope = limitedScopes.evaluation(
                APP_ID, "bounded-modify", ALL_TOOLS);
        Path root = createProjectRoot();
        Path target = root.resolve("large-existing.txt");
        Files.writeString(target, "ABCDE");

        JSONObject result = JSONUtil.parseObj(limitedScopes.callInScope(
                limitedScope, "modifyFile",
                () -> limitedModifier.modifyFile(
                        "large-existing.txt", "A", "Z", APP_ID)));

        assertResourceLimit(result);
        assertEquals("ABCDE", Files.readString(target));
    }

    @Test
    void 写入工具比较超大旧文件时必须有界拒绝且不改盘不记账()
            throws IOException {
        FileToolBudgetGuard guard = new FileToolBudgetGuard();
        guard.setMaxSingleFileCodePoints(4);
        guard.setMaxCumulativeMutationCodePoints(6);
        guard.setMaxCanonicalAiTextCodePoints(64);
        guard.setMaxReadFileCodePoints(4);
        guard.setMaxReadDirCodePoints(10);
        FileToolExecutionScopeManager limitedScopes =
                new FileToolExecutionScopeManager(guard);
        AppFileStateManager limitedState = new AppFileStateManager();
        FileWriteTool limitedWriter = new FileWriteTool(
                limitedState, limitedScopes);
        var limitedScope = limitedScopes.evaluation(
                APP_ID, "bounded-write-compare", ALL_TOOLS);
        Path root = createProjectRoot();
        Path target = root.resolve("large-existing-write.txt");
        Files.writeString(target, "ABCDE");

        JSONObject result = JSONUtil.parseObj(limitedScopes.callInScope(
                limitedScope, "writeFile",
                () -> limitedWriter.writeFile(
                        "large-existing-write.txt", "ABCD", APP_ID)));

        assertResourceLimit(result);
        assertEquals("ABCDE", Files.readString(target));
        assertEquals(0, limitedState.fileCount(APP_ID));
    }

    @Test
    void everyNonAppliedStatusSerializesNullContent() {
        List<FileToolResult> results = List.of(
                FileToolResult.noChange("writeFile", "src/App.vue", "未变更"),
                FileToolResult.rejected("readFile", "src/App.vue", "已拒绝"),
                FileToolResult.notFound("readFile", "src/App.vue", "未找到"),
                FileToolResult.cancelled("readFile", "src/App.vue", "已取消"),
                FileToolResult.failed("readFile", "src/App.vue", "失败"));

        for (FileToolResult result : results) {
            JSONObject json = JSONUtil.parseObj(FileToolProtocolSupport.json(result));
            assertEquals(result.status().name(), json.getStr("status"));
            assertNullContent(json);
        }
    }

    @Test
    void everyMutationAppliedResultSerializesNullContent() {
        for (String operation : List.of("writeFile", "modifyFile", "deleteFile")) {
            JSONObject json = JSONUtil.parseObj(FileToolProtocolSupport.json(
                    FileToolResult.applied(
                            operation, "src/App.vue", true, "已应用")));

            assertTrue(json.getBool("changed"));
            assertNullContent(json);
        }
    }

    @Test
    void protocolParserRejectsMissingOrContradictoryContentSemantics() {
        assertProtocolParseFailed("writeFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"writeFile",\
                "status":"APPLIED","relativePath":"src/App.vue",\
                "changed":true,"message":"已写入"}
                """);
        assertProtocolParseFailed("writeFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"writeFile",\
                "status":"APPLIED","relativePath":"src/App.vue",\
                "changed":true,"message":"已写入","content":"伪造内容"}
                """);
        assertProtocolParseFailed("readFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"readFile",\
                "status":"APPLIED","relativePath":"src/App.vue",\
                "changed":false,"message":"已读取","content":null}
                """);
        assertProtocolParseFailed("readFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"readFile",\
                "status":"APPLIED","relativePath":"src/App.vue",\
                "changed":true,"message":"已读取","content":"正文"}
                """);
        assertProtocolParseFailed("writeFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"writeFile",\
                "status":"APPLIED","relativePath":"src/App.vue",\
                "changed":false,"message":"已写入","content":null}
                """);
    }

    @Test
    void protocolParserRejectsContentOnEveryNonAppliedStatus() {
        for (String status : List.of(
                "NO_CHANGE", "REJECTED", "NOT_FOUND", "CANCELLED", "FAILED")) {
            String raw = """
                    {"protocol":"file-tool/v1","operation":"readFile",\
                    "status":"%s","relativePath":"src/App.vue",\
                    "changed":false,"message":"状态说明","content":"不应出现的正文"}
                    """.formatted(status);

            assertProtocolParseFailed("readFile", "src/App.vue", raw);
        }
    }

    @Test
    void protocolParserRejectsUnsupportedOperationsAndUnknownFieldShape() {
        assertThrows(IllegalArgumentException.class, () -> FileToolResult.applied(
                "unknownTool", "src/App.vue", false, "伪造操作"));
        assertThrows(IllegalArgumentException.class, () -> FileToolResult.failed(
                "readFile", "src/App.vue", " "));
        assertProtocolParseFailed("readFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"readFile",\
                "status":"APPLIED","relativePath":"src/App.vue",\
                "changed":false,"message":"已读取","content":"正文",\
                "inventedField":"不应被接受"}
                """);
        assertProtocolParseFailed("readFile", "src/App.vue", """
                {"protocol":"file-tool/v1","operation":"readFile",\
                "status":"FAILED","status":"APPLIED",\
                "relativePath":"src/App.vue","changed":false,\
                "message":"已读取","content":"正文"}
                """);
    }

    @Test
    void protocolParserEnforcesOperationSpecificStatusAndChangedSemantics() {
        assertProtocolParseFailed("readDir", "", """
                {"protocol":"file-tool/v1","operation":"readDir",\
                "status":"NO_CHANGE","relativePath":"",\
                "changed":false,"message":"伪造未变更","content":null}
                """);
        assertProtocolParseFailed("deleteFile", "src/old.js", """
                {"protocol":"file-tool/v1","operation":"deleteFile",\
                "status":"NO_CHANGE","relativePath":"src/old.js",\
                "changed":false,"message":"伪造未变更","content":null}
                """);
        assertProtocolParseFailed("exit", null, """
                {"protocol":"file-tool/v1","operation":"exit",\
                "status":"APPLIED","relativePath":null,\
                "changed":true,"message":"伪造变更","content":null}
                """);
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
    void 非变更展示允许安全规范等价路径但危险路径只能原文匹配() {
        String normalizedNoChange = fileWriteTool.generateToolExecutedResult(
                new JSONObject().set("relativeFilePath", "./concurrent.txt"),
                FileToolProtocolSupport.json(FileToolResult.noChange(
                        "writeFile", "concurrent.txt", "未变化")));
        String exactRejected = fileWriteTool.generateToolExecutedResult(
                new JSONObject().set("relativeFilePath", "../secret.txt"),
                FileToolProtocolSupport.json(FileToolResult.rejected(
                        "writeFile", "../secret.txt", "已拒绝")));
        String mismatchedRejected = fileWriteTool.generateToolExecutedResult(
                new JSONObject().set("relativeFilePath", "../other.txt"),
                FileToolProtocolSupport.json(FileToolResult.rejected(
                        "writeFile", "../secret.txt", "已拒绝")));

        assertTrue(normalizedNoChange.contains("未变更"), normalizedNoChange);
        assertTrue(exactRejected.contains("已拒绝"), exactRejected);
        assertTrue(mismatchedRejected.contains("失败"), mismatchedRejected);
    }

    @Test
    void readStableMarkdownNeverCopiesRealtimeContent() throws IOException {
        Path root = createProjectRoot();
        Files.writeString(root.resolve("secret.txt"), "仅当前模型可见的读取正文");
        JSONObject fileResult = inEvaluation(() ->
                fileReadTool.readFile("secret.txt", APP_ID));
        JSONObject directoryResult = inEvaluation(() ->
                fileDirReadTool.readDir("", APP_ID));

        String fileStable = fileReadTool.generateToolExecutedResult(
                new JSONObject().set("relativeFilePath", "secret.txt"),
                fileResult.toString());
        String directoryStable = fileDirReadTool.generateToolExecutedResult(
                new JSONObject().set("relativeDirPath", ""),
                directoryResult.toString());

        assertEquals("仅当前模型可见的读取正文", fileResult.getStr("content"));
        assertFalse(fileStable.contains("仅当前模型可见的读取正文"), fileStable);
        assertEquals("[工具调用] 读取文件 secret.txt（已应用）", fileStable);
        assertTrue(directoryResult.getStr("content").contains("secret.txt"));
        assertFalse(directoryStable.contains("secret.txt"), directoryStable);
        assertEquals("[工具调用] 读取目录（已应用）", directoryStable);
    }

    @Test
    void writeGuidanceDependsOnTrustedScopeType() {
        JSONObject evaluation = inEvaluation(() ->
                fileWriteTool.writeFile("evaluation.txt", "内容", APP_ID));
        assertTrue(evaluation.getStr("message").contains("exit"), evaluation.toString());

        try (OnlineHarness online = onlineHarness(Set.of("writeFile", "exit"))) {
            JSONObject onlineWrite = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "writeFile",
                    () -> fileWriteTool.writeFile("online.txt", "内容", APP_ID)));
            assertTrue(onlineWrite.getStr("message").contains("buildProject"), onlineWrite.toString());

            JSONObject onlineExit = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "exit",
                    () -> exitTool.exit("提前退出", APP_ID)));
            assertEquals("NO_CHANGE", onlineExit.getStr("status"));
            assertTrue(onlineExit.getStr("message").contains("buildProject"), onlineExit.toString());
        }
    }

    @Test
    void 在线成功变更必须只推进一次精确租约revision() {
        try (OnlineHarness online = onlineHarness(Set.of("writeFile"))) {
            JSONObject applied = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "writeFile",
                    () -> fileWriteTool.writeFile(
                            "revision.txt", "第一次", APP_ID)));
            JSONObject noChange = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "writeFile",
                    () -> fileWriteTool.writeFile(
                            "revision.txt", "第一次", APP_ID)));

            assertEquals("APPLIED", applied.getStr("status"));
            assertEquals("NO_CHANGE", noChange.getStr("status"));
            assertEquals(1L, online.lease.snapshot().mutationRevision());
        }
    }

    @Test
    void 在线伪造危险路径的applied协议不得推进mutationRevision() {
        try (OnlineHarness online = onlineHarness(Set.of("writeFile"))) {
            String forged = FileToolProtocolSupport.json(FileToolResult.applied(
                    "writeFile", "../secret.txt", true, "伪造成功"));

            String returned = scopeManager.callInScope(
                    online.scope, "writeFile", () -> forged);

            assertEquals(forged, returned);
            assertEquals(0L, online.lease.snapshot().mutationRevision());
        }
    }

    @Test
    void 非代码故障必须在文件副作用前拒绝mutation但保留读取权限()
            throws IOException {
        Path root = createProjectRoot();
        Files.writeString(root.resolve("existing.txt"), "原内容");
        try (OnlineHarness online = onlineHarness(Set.of(
                "writeFile", "modifyFile", "deleteFile", "readFile"))) {
            try (var first = online.lease.beginBuild()) {
                online.lease.recordFailure(first, new com.lyw.appgeneration.core.builder.BuildResult(
                        false, com.lyw.appgeneration.core.builder.BuildStage.NPM_INSTALL,
                        1, false, false,
                        com.lyw.appgeneration.core.builder.VueBuildFailureKind.DEPENDENCY,
                        "依赖失败", 1L));
            }

            JSONObject write = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "writeFile",
                    () -> fileWriteTool.writeFile("new.txt", "绝不能落盘", APP_ID)));
            JSONObject modify = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "modifyFile",
                    () -> fileModifyTool.modifyFile(
                            "existing.txt", "原", "篡改", APP_ID)));
            JSONObject delete = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "deleteFile",
                    () -> fileDeleteTool.deleteFile("existing.txt", APP_ID)));
            JSONObject read = JSONUtil.parseObj(scopeManager.callInScope(
                    online.scope, "readFile",
                    () -> fileReadTool.readFile("existing.txt", APP_ID)));

            List<JSONObject> rejectedResults = List.of(write, modify, delete);
            List<String> rejectedPaths = List.of(
                    "new.txt", "existing.txt", "existing.txt");
            for (int index = 0; index < rejectedResults.size(); index++) {
                JSONObject rejected = rejectedResults.get(index);
                assertEquals("REJECTED", rejected.getStr("status"));
                assertEquals(rejectedPaths.get(index),
                        rejected.getStr("relativePath"));
                assertNull(rejected.getStr("failureReason"));
                assertTrue(rejected.getStr("message").contains("依赖或基础设施"));
            }
            assertEquals("APPLIED", read.getStr("status"));
            assertEquals("原内容", read.getStr("content"));
            assertFalse(Files.exists(root.resolve("new.txt")));
            assertEquals("原内容", Files.readString(root.resolve("existing.txt")));
            assertEquals(0L, online.lease.snapshot().mutationRevision());
            assertEquals(1, online.lease.snapshot().buildAttempt());
        }
    }

    @Test
    void scopedValueDoesNotLeakIntoNewVirtualThread() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String raw = scopeManager.callInScope(
                    evaluationScope, "writeFile", () -> {
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
                staleScope, "writeFile",
                () -> fileWriteTool.writeFile("stale.txt", "绝不能落盘", APP_ID)));

        try (OnlineHarness replacement = onlineHarness(Set.of("writeFile"))) {
            assertThrows(IllegalStateException.class, () -> scopeManager.callInScope(
                    staleScope, "writeFile",
                    () -> fileWriteTool.writeFile("borrowed.txt", "绝不能落盘", APP_ID)));
            JSONObject valid = JSONUtil.parseObj(scopeManager.callInScope(
                    replacement.scope, "writeFile",
                    () -> fileWriteTool.writeFile("replacement.txt", "允许落盘", APP_ID)));
            assertEquals("APPLIED", valid.getStr("status"));
        }

        assertFalse(Files.exists(projectRoot().resolve("stale.txt")));
        assertFalse(Files.exists(projectRoot().resolve("borrowed.txt")));
    }

    @Test
    void revokedEvaluationScopeUsesBoundedDrainForBlockedInFlightAction()
            throws Exception {
        FileToolExecutionScopeManager.FileToolScope scope = scopeManager.evaluation(
                APP_ID, "blocked-evaluation", Set.of("writeFile"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocked = executor.submit(() -> scopeManager.callInScope(
                    scope, "writeFile", () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "完成";
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            scopeManager.revokeEvaluation(scope);
            long started = System.nanoTime();
            assertFalse(scopeManager.awaitEvaluationQuiescence(
                    scope, java.time.Duration.ofMillis(30)));
            long elapsedMillis = java.time.Duration.ofNanos(
                    System.nanoTime() - started).toMillis();

            assertTrue(elapsedMillis < 500, "有界 drain 不能被卡死工具无限阻塞");
            assertThrows(FileToolExecutionScopeManager.ScopeViolationException.class,
                    () -> scopeManager.callInScope(
                            scope, "writeFile", () -> "迟到动作"));
            release.countDown();
            assertEquals("完成", blocked.get(1, TimeUnit.SECONDS));
            assertTrue(scopeManager.awaitEvaluationQuiescence(
                    scope, java.time.Duration.ofSeconds(1)));
        }
    }

    @Test
    void toolWhitelistAndAppIdentityAreEnforcedInsideBean() {
        FileToolExecutionScopeManager.FileToolScope readOnly =
                scopeManager.evaluation(APP_ID, "read-only", Set.of("readFile"));
        String notAllowed = scopeManager.callInScope(
                readOnly, "readFile",
                () -> fileWriteTool.writeFile("denied.txt", "不能落盘", APP_ID));
        String wrongApp = scopeManager.callInScope(
                evaluationScope, "writeFile",
                () -> fileWriteTool.writeFile("wrong-app.txt", "不能落盘", APP_ID + 1));

        assertStatus(notAllowed, "REJECTED", false);
        assertStatus(wrongApp, "REJECTED", false);
        assertFalse(Files.exists(projectRoot().resolve("denied.txt")));
    }

    private JSONObject inEvaluation(Supplier<String> action) {
        return JSONUtil.parseObj(scopeManager.callInScope(
                evaluationScope, "writeFile", action));
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
        assertNullContent(json);
    }

    private void assertNullContent(JSONObject json) {
        assertTrue(json.containsKey("content"), json.toString());
        assertTrue(json.isNull("content"), json.toString());
        assertNull(json.getStr("content"));
    }

    private void assertResourceLimit(JSONObject json) {
        assertStatus(json, "REJECTED", false);
        assertEquals("RESOURCE_LIMIT_EXCEEDED", json.getStr("failureReason"));
    }

    private void assertProtocolParseFailed(
            String operation, String relativePath, String rawResult) {
        FileToolResult result = FileToolProtocolSupport.parse(
                rawResult, operation, relativePath);
        assertEquals(FileToolResult.FileToolStatus.FAILED, result.status());
        assertEquals("工具结果协议解析失败", result.message());
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
