package com.lyw.appgeneration.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StaticPreviewResourceResolverTest {
    @TempDir Path root;

    @Test
    void rejectsProtectedNamesAndAmbiguousPaths() throws Exception {
        var resolver = new StaticPreviewResourceResolver(root);
        for (String path : new String[]{".plan.json", "%2eplan.json", "%252eplan.json", ".PLAN.JSON",
                ".plan-save.tmp", ".PLAN-X.TMP", "dist/../.plan.json", "dist/%2e%2e/.plan.json",
                "dist/%2F.plan.json", "dist/%5c.plan.json", "dist/a;b", "dist/%00", "dist/%ff",
                "dist/%", "dist/%GG", "dist//index.html", "../vue_project_8/index.html"}) {
            assertThrows(IOException.class, () -> resolver.resolve("vue_project_7",
                    "/api/static/vue_project_7/" + path, "/api"), path);
        }
        assertThrows(IOException.class, () -> resolver.resolve("different", "/api/static/vue_project_7/", "/api"));
    }

    @Test
    void readsUtf8SpacesAndPlusWithoutFormDecoding() throws Exception {
        Path dist = Files.createDirectories(root.resolve("vue_project_7/dist"));
        var resolver = new StaticPreviewResourceResolver(root);
        String[][] names = {{"中文.js", "%E4%B8%AD%E6%96%87.js"}, {"a b.css", "a%20b.css"}, {"a+b.js", "a+b.js"}};
        for (String[] entry : names) {
            Files.writeString(dist.resolve(entry[0]), entry[0]);
            var resource = resolver.resolve("vue_project_7", "/api/static/vue_project_7/dist/" + entry[1], "/api").resource();
            try (var input = resource.getInputStream()) {
                assertEquals(entry[0], new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void rejectsLinksAtFileParentAndRoot() throws Exception {
        Path project = Files.createDirectories(root.resolve("vue_project_7"));
        Path secret = Files.writeString(project.resolve(".plan.json"), "secret");
        Files.createSymbolicLink(project.resolve("alias.txt"), secret);
        Files.createSymbolicLink(project.resolve("alias-dir"), project);
        var resolver = new StaticPreviewResourceResolver(root);
        assertThrows(IOException.class, () -> resolver.resolve("vue_project_7", "/static/vue_project_7/alias.txt", ""));
        assertThrows(IOException.class, () -> resolver.resolve("vue_project_7", "/static/vue_project_7/alias-dir/index.html", ""));
        Path linkedRoot = root.resolve("linked");
        Files.createSymbolicLink(linkedRoot, root);
        assertThrows(IOException.class, () -> new StaticPreviewResourceResolver(linkedRoot)
                .resolve("vue_project_7", "/static/vue_project_7/alias.txt", ""));
    }

    @Test
    void rejectsFileAndDirectoryReplacementAtActualRead() throws Exception {
        Path project = Files.createDirectories(root.resolve("vue_project_7"));
        Path dist = Files.createDirectory(project.resolve("dist"));
        Path file = Files.writeString(dist.resolve("index.html"), "public");
        Path secret = Files.writeString(project.resolve(".plan.json"), "secret");
        var resolver = new StaticPreviewResourceResolver(root);
        var resource = resolver.resolve("vue_project_7", "/static/vue_project_7/dist/index.html", "").resource();
        Files.delete(file);
        Files.createSymbolicLink(file, secret);
        assertThrows(IOException.class, resource::getInputStream);
        Files.delete(file);
        Files.writeString(file, "public");
        var resource2 = resolver.resolve("vue_project_7", "/static/vue_project_7/dist/index.html", "").resource();
        Files.move(dist, project.resolve("saved-dist"));
        Files.createSymbolicLink(dist, project.resolve("saved-dist"));
        assertThrows(IOException.class, resource2::getInputStream);
    }
}
