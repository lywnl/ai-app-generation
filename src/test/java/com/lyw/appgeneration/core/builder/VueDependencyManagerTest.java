package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueDependencyManagerTest {

    @TempDir
    Path root;

    @Test
    void reusesOnlyMatchingSuccessfulInstallFromSameServiceInstance() throws Exception {
        VueDependencyManager manager = new VueDependencyManager("instance-a");
        assertTrue(manager.prepare(root, "sha-a").requiresInstall());
        Files.createDirectories(root.resolve("node_modules/vite"));
        manager.markInstallationSucceeded(root, "sha-a");
        assertFalse(manager.prepare(root, "sha-a").requiresInstall());

        assertTrue(manager.prepare(root, "sha-b").requiresInstall());
        assertFalse(Files.exists(root.resolve("node_modules"), LinkOption.NOFOLLOW_LINKS));
        Files.createDirectories(root.resolve("node_modules/vite"));
        manager.markInstallationSucceeded(root, "sha-a");
        assertTrue(new VueDependencyManager("instance-b")
                .prepare(root, "sha-a").requiresInstall());
    }

    @Test
    void rejectsMissingCorruptAndSymbolicLinkStateWithoutFollowingExternalTarget()
            throws Exception {
        VueDependencyManager manager = new VueDependencyManager("instance-a");
        Files.createDirectories(root.resolve("node_modules"));
        assertTrue(manager.prepare(root, "sha-a").requiresInstall());

        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/.ai-build-dependency-state.json"), "bad");
        assertTrue(manager.prepare(root, "sha-a").requiresInstall());

        Path external = Files.createDirectory(root.resolve("external"));
        Path keep = Files.writeString(external.resolve("keep.js"), "keep");
        Files.createSymbolicLink(root.resolve("node_modules"), external);
        assertTrue(manager.prepare(root, "sha-a").requiresInstall());
        assertTrue(Files.isRegularFile(keep));
        assertFalse(Files.exists(root.resolve("node_modules"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void failedInstallNeverCreatesMarkerAndNextAttemptStillInstalls() throws Exception {
        VueDependencyManager manager = new VueDependencyManager("instance-a");
        assertTrue(manager.prepare(root, "sha-a").requiresInstall());
        assertFalse(Files.exists(root.resolve("node_modules/.ai-build-dependency-state.json")));
        assertTrue(manager.prepare(root, "sha-a").requiresInstall());
    }
}
