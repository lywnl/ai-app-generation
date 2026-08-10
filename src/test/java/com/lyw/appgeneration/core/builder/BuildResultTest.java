package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildResultTest {

    @Test
    void storesAnImmutableCharacterBoundedOutputTail() {
        String output = "前".repeat(100) + "后".repeat(8_100);

        BuildResult result = new BuildResult(
                false,
                BuildStage.NPM_BUILD,
                2,
                false,
                output,
                123L);

        assertTrue(BuildResult.class.isRecord());
        assertFalse(result.success());
        assertEquals(BuildStage.NPM_BUILD, result.stage());
        assertEquals(2, result.exitCode());
        assertEquals("后".repeat(8_000), result.outputTail());
        assertEquals(123L, result.durationMillis());
    }
}
