package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void successTimeoutAndCancellationAreMutuallyExclusive() {
        BuildResult cancelled = new BuildResult(
                false, BuildStage.NPM_BUILD, null, false, true,
                null, "已取消", 1);

        assertTrue(cancelled.cancelled());
        assertThrows(IllegalArgumentException.class, () -> new BuildResult(
                true, BuildStage.SUCCESS, 0, false, true, null, "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> new BuildResult(
                false, BuildStage.NPM_BUILD, null, true, true, null, "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> new CommandResult(
                0, false, true, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new CommandResult(
                null, true, true, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new CommandResult(
                null, false, false, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new BuildResult(
                false, BuildStage.SUCCESS, 0, false, false,
                VueBuildFailureKind.CODE, "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> new BuildResult(
                false, BuildStage.NPM_BUILD, 0, false, false,
                VueBuildFailureKind.CODE, "bad", 1));
    }
}
