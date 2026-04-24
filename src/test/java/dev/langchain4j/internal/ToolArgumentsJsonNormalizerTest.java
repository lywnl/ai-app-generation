package dev.langchain4j.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolArgumentsJsonNormalizerTest {

    @Test
    void shouldRepairMissingCommaBetweenFields() {
        String malformed = "{\"relativeFilePath\":\"src/main/App.vue\" \"content\":\"hello\"}";

        ToolArgumentsJsonNormalizer.Result result = ToolArgumentsJsonNormalizer.normalize(malformed);

        assertTrue(result.isValid());
        assertTrue(result.repaired());
        assertJsonEquals("{\"relativeFilePath\":\"src/main/App.vue\",\"content\":\"hello\"}", result.normalizedArguments());
    }

    @Test
    void shouldNormalizeCodeFenceWrappedJson() {
        String wrapped = "```json\n{\"k\":\"v\",}\n```";

        ToolArgumentsJsonNormalizer.Result result = ToolArgumentsJsonNormalizer.normalize(wrapped);

        assertTrue(result.isValid());
        assertTrue(result.repaired());
        assertJsonEquals("{\"k\":\"v\"}", result.normalizedArguments());
    }

    @Test
    void shouldMarkAsInvalidWhenUnrecoverable() {
        String malformed = "{\"a\":\"1\", \"b\": ??? }";

        ToolArgumentsJsonNormalizer.Result result = ToolArgumentsJsonNormalizer.normalize(malformed);

        assertFalse(result.isValid());
        assertEquals(ToolArgumentsJsonNormalizer.Status.INVALID, result.status());
        assertNull(result.normalizedArguments());
    }

    private static void assertJsonEquals(String expected, String actual) {
        assertEquals(Json.fromJson(expected, Object.class), Json.fromJson(actual, Object.class));
    }
}
