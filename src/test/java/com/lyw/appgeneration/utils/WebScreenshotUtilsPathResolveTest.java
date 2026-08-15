package com.lyw.appgeneration.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

class WebScreenshotUtilsPathResolveTest {

    private static final String MAC_OS_GOOGLE_CHROME_PATH =
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

    @Test
    void defaultCandidates_shouldIncludeMacOsGoogleChrome() throws Exception {
        Field candidatesField = WebScreenshotUtils.class.getDeclaredField(
                "CHROME_BINARY_CANDIDATES");
        candidatesField.setAccessible(true);
        String[] candidates = (String[]) candidatesField.get(null);

        Assertions.assertTrue(
                Arrays.asList(candidates).contains(MAC_OS_GOOGLE_CHROME_PATH));
    }

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
