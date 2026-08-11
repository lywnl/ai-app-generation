package com.lyw.appgeneration.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VueQualityGateEnvironmentSnapshotTest {

    private static final List<Path> ENVIRONMENT_MODELS = List.of(
            Path.of("src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironment.java"),
            Path.of("src/test/java/com/lyw/appgeneration/rag/vue/VueEvaluationEnvironment.java"),
            Path.of("src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildEnvironment.java"));
    private static final List<Path> QUALITY_GATE_ENTRIES = List.of(
            Path.of("src/test/java/com/lyw/appgeneration/rag/ingest/"
                    + "VueKnowledgeIngestionQualityGateTest.java"),
            Path.of("src/test/java/com/lyw/appgeneration/rag/vue/"
                    + "VueRetrievalQualityGateTest.java"),
            Path.of("src/test/java/com/lyw/appgeneration/rag/build/"
                    + "VueGenerationBuildQualityGateTest.java"));

    @Test
    void 三类门禁只在入口冻结一次系统环境且模型不保留平行入口() throws Exception {
        assertAll(ENVIRONMENT_MODELS.stream()
                .map(path -> () -> assertFalse(
                        Files.readString(path).contains("System.getenv()"), path.toString())));
        assertAll(QUALITY_GATE_ENTRIES.stream()
                .map(path -> () -> assertEquals(
                        1,
                        occurrences(Files.readString(path), "Map.copyOf(System.getenv())"),
                        path.toString())));
    }

    private int occurrences(String content, String expected) {
        return (content.length() - content.replace(expected, "").length()) / expected.length();
    }
}
