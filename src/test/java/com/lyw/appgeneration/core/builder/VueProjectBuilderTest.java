package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(new CommandInvocation(
                        tempDir,
                        List.of("npm.cmd", "install", "--ignore-scripts", "--no-audit", "--no-fund"),
                        Duration.ofSeconds(300)),
                executor.invocations.getFirst());
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
        assertEquals(new CommandInvocation(
                        tempDir,
                        List.of("npm", "run", "build"),
                        Duration.ofSeconds(180)),
                executor.invocations.get(1));
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
    void keepsAsynchronousCompatibilityByDelegatingToDetailedBuild() throws Exception {
        createPackageJson();
        RecordingCommandExecutor executor = successfulExecutorCreatingDist();
        executor.completionLatch = new CountDownLatch(2);
        VueProjectBuilder builder = new VueProjectBuilder(executor, "npm");

        builder.buildProjectAsync(tempDir.toString());

        assertTrue(executor.completionLatch.await(2, TimeUnit.SECONDS));
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
        assertEquals(1, executor.invocations.size());
    }

    @Test
    void deletesDistSymbolicLinkWithoutTouchingItsExternalTarget() throws IOException {
        Path projectDirectory = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(projectDirectory.resolve("package.json"), "{}");
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
        Files.writeString(tempDir.resolve("package.json"), "{}");
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
                Duration timeout) throws IOException {
            invocations.add(new CommandInvocation(workingDirectory, List.copyOf(command), timeout));
            if (completionLatch != null) {
                completionLatch.countDown();
            }
            if (results.isEmpty()) {
                throw new AssertionError("测试没有配置命令结果");
            }
            CommandResult result = results.remove();
            if (isSuccessfulBuild(command, result)) {
                successfulBuildAction.run(workingDirectory);
            }
            return result;
        }

        private boolean isSuccessfulBuild(List<String> command, CommandResult result) {
            return command.size() == 3
                    && "run".equals(command.get(1))
                    && "build".equals(command.get(2))
                    && Integer.valueOf(0).equals(result.exitCode())
                    && !result.timedOut();
        }
    }

    @FunctionalInterface
    private interface BuildAction {

        void run(Path projectDirectory) throws IOException;
    }

    private record CommandInvocation(
            Path workingDirectory,
            List<String> command,
            Duration timeout
    ) {
    }
}
