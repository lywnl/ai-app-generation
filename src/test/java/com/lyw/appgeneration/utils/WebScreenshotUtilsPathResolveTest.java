package com.lyw.appgeneration.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class WebScreenshotUtilsPathResolveTest {

    @Test
    void resolveExecutablePath_shouldReturnExplicitPathWhenExists() throws IOException {
        Path explicit = Files.createTempFile("chrome-bin", ".tmp");
        try {
            String resolved = WebScreenshotUtils.resolveExecutablePath(explicit.toString(), "/not/exist/candidate");
            Assertions.assertEquals(explicit.toString(), resolved);
        } finally {
            Files.deleteIfExists(explicit);
        }
    }

    @Test
    void resolveExecutablePath_shouldFallbackToFirstExistingCandidate() throws IOException {
        Path candidate = Files.createTempFile("chrome-candidate", ".tmp");
        try {
            String resolved = WebScreenshotUtils.resolveExecutablePath(
                    "/not/exist/explicit",
                    "/not/exist/one",
                    candidate.toString(),
                    "/not/exist/two"
            );
            Assertions.assertEquals(candidate.toString(), resolved);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    @Test
    void resolveExecutablePath_shouldReturnNullWhenNoPathExists() {
        String resolved = WebScreenshotUtils.resolveExecutablePath(
                "/not/exist/explicit",
                "/not/exist/one",
                "/not/exist/two"
        );
        Assertions.assertNull(resolved);
    }
}
