package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPropertiesTest {

    @Test
    void hybridRetrievalIsDisabledByDefault() {
        assertFalse(new RagProperties().getHybrid().isEnabled());
    }

    @Test
    void applicationYamlExplicitlyKeepsHybridDisabled() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.matches("(?s).*rag:\\s.*hybrid:\\s+enabled:\\s+false.*"));
        }
    }
}
