package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.entity.App;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppStoragePathResolverTest {

    @TempDir
    Path tempDirectory;

    @Test
    void resolvesTrustedSourceNameAndBlankDeployKeyAsEmpty() throws Exception {
        Path sourceRoot = Files.createDirectory(tempDirectory.resolve("source"));
        Path deployRoot = Files.createDirectory(tempDirectory.resolve("deploy"));
        AppStoragePathResolver resolver = new AppStoragePathResolver(sourceRoot, deployRoot);
        App app = App.builder().id(7L).codeGenType("vue_project").deployKey(" ").build();

        assertEquals(sourceRoot.resolve("vue_project_7"),
                resolver.resolveSourceDirectory(app));
        assertTrue(resolver.resolveDeployDirectory(app).isEmpty());
    }

    @Test
    void rejectsUnknownCodeTypeAndInjectedDeployKeys() throws Exception {
        Path sourceRoot = Files.createDirectory(tempDirectory.resolve("source"));
        Path deployRoot = Files.createDirectory(tempDirectory.resolve("deploy"));
        AppStoragePathResolver resolver = new AppStoragePathResolver(sourceRoot, deployRoot);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolveSourceDirectory(
                App.builder().id(7L).codeGenType("../../outside").build()));
        for (String deployKey : new String[]{"../outside", "a/../../outside",
                deployRoot.toString(), ".", "a/b", "a//", "safe/", "a\\b"}) {
            App app = App.builder().id(7L).codeGenType("vue_project")
                    .deployKey(deployKey).build();
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.resolveDeployDirectory(app), deployKey);
        }
    }

    @Test
    void rejectsRootAndExistingSymbolicLinkSegments() throws Exception {
        Path sourceRoot = Files.createDirectory(tempDirectory.resolve("source"));
        Path deployRoot = Files.createDirectory(tempDirectory.resolve("deploy"));
        Path outside = Files.createDirectory(tempDirectory.resolve("outside"));
        Files.createSymbolicLink(sourceRoot.resolve("vue_project_7"), outside);
        Files.createSymbolicLink(deployRoot.resolve("alias"), outside);
        AppStoragePathResolver resolver = new AppStoragePathResolver(sourceRoot, deployRoot);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolveSourceDirectory(
                App.builder().id(7L).codeGenType("vue_project").build()));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveDeployDirectory(
                App.builder().id(7L).codeGenType("vue_project").deployKey("alias").build()));
    }

    @Test
    void allowsMissingLeafForIdempotentDeletionAndFreezesBothPaths() throws Exception {
        Path sourceRoot = Files.createDirectory(tempDirectory.resolve("source"));
        Path deployRoot = Files.createDirectory(tempDirectory.resolve("deploy"));
        AppStoragePathResolver resolver = new AppStoragePathResolver(sourceRoot, deployRoot);
        App app = App.builder().id(9L).codeGenType("vue_project")
                .deployKey("deploy-9").build();

        AppStoragePathResolver.FrozenAppPaths paths = resolver.resolveForDeletion(app);

        assertEquals(sourceRoot.resolve("vue_project_9"), paths.sourceDirectory());
        assertEquals(deployRoot.resolve("deploy-9"), paths.deployDirectory().orElseThrow());
    }

    @Test
    void rejectsSymbolicLinkStorageRoot() throws Exception {
        Path realRoot = Files.createDirectory(tempDirectory.resolve("real"));
        Path sourceRoot = tempDirectory.resolve("source-link");
        Files.createSymbolicLink(sourceRoot, realRoot);
        Path deployRoot = Files.createDirectory(tempDirectory.resolve("deploy"));

        assertThrows(IllegalArgumentException.class,
                () -> new AppStoragePathResolver(sourceRoot, deployRoot));
    }
}
