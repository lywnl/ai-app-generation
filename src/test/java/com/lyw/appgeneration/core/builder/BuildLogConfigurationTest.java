package com.lyw.appgeneration.core.builder;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildLogConfigurationTest {

    @Test
    void rawLoggerUsesDedicatedBoundedRollingFileWithoutRootDuplication() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("vue-build-raw"));
            assertTrue(xml.contains("additivity=\"false\""));
            assertTrue(xml.contains("${VUE_BUILD_LOG_DIR:-logs/vue-build}"));
            assertTrue(xml.contains("<maxFileSize>10MB</maxFileSize>"));
            assertTrue(xml.contains("<maxHistory>14</maxHistory>"));
            assertTrue(xml.contains("<totalSizeCap>1GB</totalSizeCap>"));
        }
    }
}
