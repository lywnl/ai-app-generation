package com.lyw.appgeneration.core.builder;

import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueProjectBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsMissingProjectDirectoryBeforeStartingACommand() {
        RecordingCommandExecutor executor = new RecordingCommandExecutor();
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result = builder.buildProjectDetailed(tempDir.resolve("missing").toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertNull(result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.outputTail().contains("项目目录"));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void rejectsProjectWithoutPackageJsonBeforeStartingACommand() {
        RecordingCommandExecutor executor = new RecordingCommandExecutor();
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result = builder.buildProjectDetailed(tempDir.toString());

        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains("package.json"));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void reportsNpmInstallFailureWithExactSafeArguments() throws IOException {
        createPackageJson();
        RecordingCommandExecutor executor = new RecordingCommandExecutor(
                new CommandResult(17, false, "install failed"));
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm.cmd");

        BuildResult result = builder.buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.NPM_INSTALL, result.stage());
        assertEquals(17, result.exitCode());
        assertFalse(result.timedOut());
        assertEquals("install failed", result.outputTail());
        CommandInvocation invocation = executor.invocations.getFirst();
        assertEquals(tempDir.toRealPath(), invocation.workingDirectory());
        assertEquals(List.of("npm.cmd", "install", "--ignore-scripts", "--package-lock=false",
                "--no-audit", "--no-fund"), invocation.command());
        assertEquals(Duration.ofSeconds(300), invocation.timeout());
    }

    @Test
    void reportsNpmBuildTimeoutAndUsesBuildTimeout() throws IOException {
        createPackageJson();
        RecordingCommandExecutor executor = new RecordingCommandExecutor(
                new CommandResult(0, false, "installed"),
                new CommandResult(null, true, "build timed out"));
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result = builder.buildProjectDetailed(tempDir.toString());

        assertEquals(BuildStage.NPM_BUILD, result.stage());
        assertNull(result.exitCode());
        assertTrue(result.timedOut());
        assertTrue(result.outputTail().endsWith("build timed out"));
        CommandInvocation buildInvocation = executor.invocations.get(1);
        Path projectRoot = tempDir.toRealPath();
        assertEquals(projectRoot, buildInvocation.workingDirectory());
        assertEquals(Duration.ofSeconds(180), buildInvocation.timeout());
        assertEquals("node", buildInvocation.command().get(0));
        Path viteCli = Path.of(buildInvocation.command().get(1));
        assertTrue(viteCli.isAbsolute());
        assertTrue(viteCli.startsWith(projectRoot));
        assertEquals(projectRoot.resolve("node_modules/vite/bin/vite.js"), viteCli);
        Path trustedConfig = Path.of(buildInvocation.command().get(4));
        assertTrue(trustedConfig.isAbsolute());
        assertTrue(trustedConfig.startsWith(projectRoot));
        assertFalse(Files.exists(trustedConfig));
        assertEquals(projectRoot.resolve("node_modules/vite/bin/vite.js").toString(),
                buildInvocation.command().get(1));
        assertEquals("build", buildInvocation.command().get(2));
        assertEquals("--config", buildInvocation.command().get(3));
        assertTrue(buildInvocation.command().get(4).contains(".trusted-vite-config-"));
    }

    @Test
    void rejectsModelControlledBuildScriptBeforeStartingNpm() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "scripts": {"build": "node -e \\"process.exit(0)\\""},
                  "dependencies": {"vue": "3.3.4"},
                  "devDependencies": {
                    "vite": "4.4.5",
                    "@vitejs/plugin-vue": "4.2.3"
                  }
                }
                """);
        RecordingCommandExecutor executor = new RecordingCommandExecutor();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains("build"));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void rejectsUntrustedDependencyBeforeInstallation() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "scripts": {"build": "vite build"},
                  "dependencies": {
                    "vue": "3.3.4",
                    "malicious-build-plugin": "1.0.0"
                  },
                  "devDependencies": {
                    "vite": "4.4.5",
                    "@vitejs/plugin-vue": "4.2.3"
                  }
                }
                """);
        RecordingCommandExecutor executor = new RecordingCommandExecutor();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains("malicious-build-plugin"));
        assertTrue(executor.invocations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "npm:evil@1.0.0",
            "git+https://example.invalid/evil.git",
            "file:../evil",
            "https://example.invalid/evil.tgz",
            "workspace:*"
    })
    void rejectsNonRegistrySemverDependencySourceBeforeInstallation(String version)
            throws IOException {
        Files.writeString(tempDir.resolve("package.json"), trustedPackageJson()
                .replace("\"vue\": \"3.3.4\"", "\"vue\": \"" + version + "\""));
        RecordingCommandExecutor executor = new RecordingCommandExecutor();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains("vue"));
        assertTrue(result.outputTail().contains("版本"));
        assertTrue(executor.invocations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"package-lock.json", "npm-shrinkwrap.json", ".npmrc"})
    void rejectsProjectControlledNpmResolutionFilesBeforeInstallation(String fileName)
            throws IOException {
        createPackageJson();
        Files.writeString(tempDir.resolve(fileName), "{}");
        RecordingCommandExecutor executor = new RecordingCommandExecutor();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains(fileName));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void replacesUntrustedPreexistingNodeModulesBeforeInstallation() throws IOException {
        createPackageJson();
        Path maliciousVite = tempDir.resolve("node_modules/vite/bin/vite.js");
        Files.createDirectories(maliciousVite.getParent());
        Files.writeString(maliciousVite, "throw new Error('模型控制的 Vite CLI')");
        RecordingCommandExecutor executor = new RecordingCommandExecutor(
                new CommandResult(1, false, "install failed"));

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.NPM_INSTALL, result.stage());
        assertEquals(1, executor.invocations.size());
        assertFalse(Files.exists(maliciousVite, LinkOption.NOFOLLOW_LINKS));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "overrides",
            "optionalDependencies",
            "peerDependencies",
            "bundledDependencies",
            "bundleDependencies",
            "workspaces"
    })
    void rejectsPackageFieldsThatCanChangeInstalledDependencyGraph(String fieldName)
            throws IOException {
        Files.writeString(tempDir.resolve("package.json"), trustedPackageJson()
                .replace("\n}", ",\n  \"" + fieldName + "\": {}\n}"));
        RecordingCommandExecutor executor = new RecordingCommandExecutor();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains(fieldName));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void acceptsEveryApprovedDependencyWithItsExactVersion() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "scripts": {"build": "vite build"},
                  "dependencies": {
                    "vue": "3.3.4",
                    "vue-router": "4.2.4",
                    "element-plus": "2.8.8",
                    "@element-plus/icons-vue": "2.3.1",
                    "echarts": "5.5.1"
                  },
                  "devDependencies": {
                    "vite": "4.4.5",
                    "@vitejs/plugin-vue": "4.2.3"
                  }
                }
                """);
        RecordingCommandExecutor executor = successfulExecutorCreatingDist();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertTrue(result.success());
        assertEquals(2, executor.invocations.size());
    }

    @ParameterizedTest
    @CsvSource({
            "vue, 3.3.4, ^3.3.4",
            "vue, 3.3.4, ~3.3.4",
            "vue, 3.3.4, 3.3.4-beta.1",
            "vue, 3.3.4, 999.0.0",
            "vite, 4.4.5, 999.0.0",
            "@vitejs/plugin-vue, 4.2.3, ^4.2.3"
    })
    void rejectsUnapprovedDependencyVersionBeforeInstalling(
            String dependencyName, String approvedVersion, String version) throws IOException {
        Files.writeString(tempDir.resolve("package.json"), trustedPackageJson()
                .replace("\"" + dependencyName + "\": \"" + approvedVersion + "\"",
                        "\"" + dependencyName + "\": \"" + version + "\""));
        RecordingCommandExecutor executor = new RecordingCommandExecutor();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(result.outputTail().contains(dependencyName));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void usesOnlyTrustedViteConfigAndDeletesItAfterSuccessfulBuild() throws IOException {
        createPackageJson();
        Files.writeString(tempDir.resolve("vite.config.js"),
                "throw new Error('项目配置不得加载')");
        Files.writeString(tempDir.resolve("postcss.config.js"),
                "throw new Error('PostCSS 配置不得加载')");
        RecordingCommandExecutor executor = successfulExecutorCreatingDist();

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir.toString());

        assertTrue(result.success());
        CommandInvocation buildInvocation = executor.invocations.get(1);
        Path trustedConfig = Path.of(buildInvocation.command().get(4));
        assertEquals("--config", buildInvocation.command().get(3));
        assertTrue(executor.buildConfigContents.contains("plugins: [vue()]"));
        assertTrue(executor.buildConfigContents.contains("postcss"));
        assertFalse(executor.buildConfigContents.contains("vite.config.js"));
        assertFalse(Files.exists(trustedConfig));
    }

    @Test
    void reportsMissingDistAfterBothCommandsSucceed() throws IOException {
        createPackageJson();
        RecordingCommandExecutor executor = successfulExecutor();
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result = builder.buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.DIST_CHECK, result.stage());
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.outputTail().contains("dist"));
    }

    @Test
    void doesNotAcceptPreexistingDistWhenBuildDoesNotGenerateFreshOutput() throws IOException {
        createPackageJson();
        Path staleAsset = tempDir.resolve("dist/assets/stale.js");
        Files.createDirectories(staleAsset.getParent());
        Files.writeString(staleAsset, "stale");
        RecordingCommandExecutor executor = successfulExecutor();
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result = builder.buildProjectDetailed(tempDir.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.DIST_CHECK, result.stage());
        assertEquals(0, result.exitCode());
        assertFalse(Files.exists(tempDir.resolve("dist"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(2, executor.invocations.size());
    }

    @Test
    void returnsSuccessAndKeepsBooleanCompatibility() throws IOException {
        createPackageJson();
        RecordingCommandExecutor detailedExecutor = successfulExecutorCreatingDist();
        RecordingCommandExecutor booleanExecutor = successfulExecutorCreatingDist();

        BuildResult detailed = new VueProjectBuilder(detailedExecutor, "npm")
                .buildProjectDetailed(tempDir.toString());
        boolean compatible = new VueProjectBuilder(booleanExecutor, "npm")
                .buildProject(tempDir.toString());

        assertTrue(detailed.success());
        assertEquals(BuildStage.SUCCESS, detailed.stage());
        assertEquals(0, detailed.exitCode());
        assertFalse(detailed.timedOut());
        assertTrue(detailed.durationMillis() >= 0);
        assertTrue(compatible);
        assertTrue(Files.isRegularFile(tempDir.resolve("dist/index.html")));
    }

    @Test
    void pathEntryUsesExactOnlineContextForRawLogsAndCancellation() throws IOException {
        createPackageJson();
        RecordingCommandExecutor executor = successfulExecutorCreatingDist();
        List<String> logEvents = new ArrayList<>();
        BuildLogSink rootSink = new BuildLogSink(
                7L, "turn-1", 2, BuildStage.VALIDATION,
                logEvents::add, ignored -> { });
        BuildCancellationSignal cancellation = new BuildCancellationSignal();
        BuildExecutionContext context = new BuildExecutionContext(
                7L, "turn-1", 2, cancellation, rootSink);

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir, context);

        assertTrue(result.success());
        assertEquals(2, executor.invocations.size());
        executor.invocations.forEach(invocation -> assertSame(
                cancellation, invocation.cancellation()));
        assertTrue(logEvents.stream().anyMatch(event -> event.contains("stage=NPM_INSTALL")));
        assertTrue(logEvents.stream().anyMatch(event -> event.contains("stage=NPM_BUILD")));
        assertTrue(logEvents.stream().anyMatch(event -> event.contains("原始输出")));
    }

    @Test
    void buildAttemptTicketCanCancelConcreteBuildSignal() {
        AppOperationLeaseManager operationManager = new AppOperationLeaseManager();
        VueBuildSessionManager sessionManager = new VueBuildSessionManager();
        try (var operation = operationManager.acquire(
                7L, AppOperationLeaseManager.AppOperationType.GENERATE, "turn-1");
             var lease = sessionManager.open(operation, 9L, "turn-1");
             var ticket = lease.beginBuild()) {
            BuildCancellationSignal signal = new BuildCancellationSignal();
            ticket.registerCancellation(signal::cancel);

            lease.cancel();

            assertTrue(signal.isCancelled());
            assertEquals(VueBuildPhase.CANCELLED, lease.snapshot().phase());
        }
    }

    @Test
    void preCancelledOnlineBuildDoesNotMutateProjectOrStartCommand() throws IOException {
        createPackageJson();
        Path staleAsset = tempDir.resolve("dist/stale.js");
        Files.createDirectories(staleAsset.getParent());
        Files.writeString(staleAsset, "stale");
        RecordingCommandExecutor executor = new RecordingCommandExecutor();
        BuildCancellationSignal cancellation = new BuildCancellationSignal();
        cancellation.cancel();
        BuildLogSink sink = new BuildLogSink(7L, "turn-1", 1, BuildStage.VALIDATION,
                ignored -> { }, ignored -> { });

        BuildResult result = new VueProjectBuilder(executor, "npm")
                .buildProjectDetailed(tempDir, new BuildExecutionContext(
                        7L, "turn-1", 1, cancellation, sink));

        assertTrue(result.cancelled());
        assertEquals(BuildStage.VALIDATION, result.stage());
        assertTrue(Files.isRegularFile(staleAsset));
        assertTrue(executor.invocations.isEmpty());
    }

    @Test
    void keepsAsynchronousCompatibilityByDelegatingToDetailedBuild() throws Exception {
        createPackageJson();
        RecordingCommandExecutor executor = successfulExecutorCreatingDist();
        executor.completionLatch = new CountDownLatch(2);
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        builder.buildProjectAsync(tempDir.toString());

        assertTrue(executor.completionLatch.await(2, TimeUnit.SECONDS));
        awaitVueBuilderCompletion();
        assertEquals(2, executor.invocations.size());
    }

    @Test
    void reportsNpmBuildFailureAndSkipsBuildWhenPreexistingDistCannotBeDeleted() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        createPackageJson();
        Path distDirectory = Files.createDirectory(tempDir.resolve("dist"));
        Files.writeString(distDirectory.resolve("stale.js"), "stale");
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(distDirectory);
        Set<PosixFilePermission> readOnlyPermissions = EnumSet.copyOf(originalPermissions);
        readOnlyPermissions.remove(PosixFilePermission.OWNER_WRITE);
        readOnlyPermissions.remove(PosixFilePermission.GROUP_WRITE);
        readOnlyPermissions.remove(PosixFilePermission.OTHERS_WRITE);
        RecordingCommandExecutor executor = successfulExecutor();
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result;
        Files.setPosixFilePermissions(distDirectory, readOnlyPermissions);
        try {
            result = builder.buildProjectDetailed(tempDir.toString());
        } finally {
            Files.setPosixFilePermissions(distDirectory, originalPermissions);
        }

        assertFalse(result.success());
        assertEquals(BuildStage.NPM_BUILD, result.stage());
        assertNull(result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.outputTail().contains("清理旧 dist 目录失败"));
        assertEquals(0, executor.invocations.size());
    }

    @Test
    void deletesDistSymbolicLinkWithoutTouchingItsExternalTarget() throws IOException {
        Path projectDirectory = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(projectDirectory.resolve("package.json"), trustedPackageJson());
        Path externalDirectory = Files.createDirectory(tempDir.resolve("external"));
        Path externalAsset = externalDirectory.resolve("keep.js");
        Files.writeString(externalAsset, "keep");
        Path distLink = projectDirectory.resolve("dist");
        Files.createSymbolicLink(distLink, externalDirectory);
        RecordingCommandExecutor executor = successfulExecutor();
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        BuildResult result = builder.buildProjectDetailed(projectDirectory.toString());

        assertFalse(result.success());
        assertEquals(BuildStage.DIST_CHECK, result.stage());
        assertFalse(Files.exists(distLink, LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(externalAsset));
    }

    @Test
    void selectsWindowsNpmExecutableWithoutShellSplitting() {
        assertEquals("npm.cmd", VueProjectBuilder.npmExecutable("Windows 11"));
        assertEquals("npm", VueProjectBuilder.npmExecutable("Mac OS X"));
        assertEquals("npm", VueProjectBuilder.npmExecutable("Linux"));
    }

    private void createPackageJson() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), trustedPackageJson());
    }

    private void awaitVueBuilderCompletion() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            boolean running = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive()
                            && thread.getName().startsWith("vue-builder-"));
            if (!running) {
                return;
            }
            Thread.sleep(10);
        }
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().startsWith("vue-builder-")));
    }

    private static String trustedPackageJson() {
        return """
                {
                  "scripts": {"build": "vite build"},
                  "dependencies": {"vue": "3.3.4"},
                  "devDependencies": {
                    "vite": "4.4.5",
                    "@vitejs/plugin-vue": "4.2.3"
                  }
                }
                """;
    }

    private RecordingCommandExecutor successfulExecutor() {
        return new RecordingCommandExecutor(
                new CommandResult(0, false, "installed"),
                new CommandResult(0, false, "built"));
    }

    private RecordingCommandExecutor successfulExecutorCreatingDist() {
        return successfulExecutor().onSuccessfulBuild(projectDirectory -> {
            Path distDirectory = Files.createDirectory(projectDirectory.resolve("dist"));
            Files.writeString(distDirectory.resolve("index.html"), "fresh");
        });
    }

    private static final class RecordingCommandExecutor implements CommandExecutor {

        private final Queue<CommandResult> results = new ArrayDeque<>();
        private final List<CommandInvocation> invocations = new ArrayList<>();
        private CountDownLatch completionLatch;
        private String buildConfigContents = "";
        private BuildAction successfulBuildAction = projectDirectory -> {
        };

        private RecordingCommandExecutor(CommandResult... results) {
            this.results.addAll(List.of(results));
        }

        private RecordingCommandExecutor onSuccessfulBuild(BuildAction successfulBuildAction) {
            this.successfulBuildAction = successfulBuildAction;
            return this;
        }

        @Override
        public synchronized CommandResult execute(
                Path workingDirectory,
                List<String> command,
                Duration timeout,
                Consumer<String> rawOutputConsumer,
                BuildCancellationSignal cancellation) throws IOException {
            invocations.add(new CommandInvocation(
                    workingDirectory, List.copyOf(command), timeout, cancellation));
            if (results.isEmpty()) {
                throw new AssertionError("测试没有配置命令结果");
            }
            CommandResult result = results.remove();
            rawOutputConsumer.accept("原始输出\n");
            if (isSuccessfulInstall(command, result)) {
                Files.createDirectories(workingDirectory.resolve("node_modules/vite/bin"));
            }
            if (command.size() == 5 && "build".equals(command.get(2))) {
                buildConfigContents = Files.readString(Path.of(command.get(4)));
            }
            if (isSuccessfulBuild(command, result)) {
                successfulBuildAction.run(workingDirectory);
            }
            if (completionLatch != null) {
                completionLatch.countDown();
            }
            return result;
        }

        private boolean isSuccessfulBuild(List<String> command, CommandResult result) {
            return command.size() == 5
                    && "build".equals(command.get(2))
                    && Integer.valueOf(0).equals(result.exitCode())
                    && !result.timedOut();
        }

        private boolean isSuccessfulInstall(List<String> command, CommandResult result) {
            return command.size() >= 2
                    && "install".equals(command.get(1))
                    && Integer.valueOf(0).equals(result.exitCode())
                    && !result.timedOut()
                    && !result.cancelled();
        }
    }

    @FunctionalInterface
    private interface BuildAction {

        void run(Path projectDirectory) throws IOException;
    }

    private record CommandInvocation(
            Path workingDirectory,
            List<String> command,
            Duration timeout,
            BuildCancellationSignal cancellation
    ) {
    }
}
