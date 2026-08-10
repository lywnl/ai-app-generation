package com.lyw.appgeneration.service.rag.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagChunkKind;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import com.lyw.appgeneration.service.rag.support.TemplateTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeChunkFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KnowledgeChunkFactory chunkFactory = new KnowledgeChunkFactory();

    @Test
    void createsOneOverviewChunkForFeatureSnippet(@TempDir Path tempDir) throws IOException {
        TemplateTestData.write(tempDir.resolve("feature.json"),
                TemplateTestData.featureDocument("feature-login"));
        TemplateDoc document = loadOnlyDocument(tempDir);

        List<KnowledgeChunk> chunks = chunkFactory.createChunks(document);

        assertEquals(1, chunks.size());
        KnowledgeChunk chunk = chunks.getFirst();
        assertEquals("feature-login:overview", chunk.chunkId());
        assertEquals("feature-login", chunk.documentId());
        assertEquals(RagDocumentKind.FEATURE_SNIPPET, chunk.documentKind());
        assertEquals(RagChunkKind.OVERVIEW, chunk.chunkKind());
        assertTrue(chunk.searchText().contains("登录功能片段"));
        assertTrue(chunk.searchText().contains("auth"));
        assertTrue(chunk.searchText().contains("账号密码输入"));
        assertTrue(chunk.searchText().contains("适用于需要登录表单"));
        assertTrue(chunk.searchText().contains("minimal"));
        assertTrue(chunk.searchText().contains("Vue Router"));
        assertFalse(chunk.searchText().contains(TemplateTestData.SOURCE_MARKER));
    }

    @Test
    void createsOverviewAndEngineeringChunksForProjectSkeleton(@TempDir Path tempDir) throws IOException {
        TemplateTestData.write(tempDir.resolve("skeleton.json"),
                TemplateTestData.skeletonDocument("skeleton-vue"));
        TemplateDoc document = loadOnlyDocument(tempDir);

        List<KnowledgeChunk> chunks = chunkFactory.createChunks(document);

        assertEquals(List.of("skeleton-vue:overview", "skeleton-vue:engineering"),
                chunks.stream().map(KnowledgeChunk::chunkId).toList());
        KnowledgeChunk engineering = chunks.get(1);
        assertEquals(RagChunkKind.ENGINEERING, engineering.chunkKind());
        assertTrue(engineering.searchText().contains("Vue 3"));
        assertTrue(engineering.searchText().contains("JavaScript"));
        assertTrue(engineering.searchText().contains("Vite"));
        assertTrue(engineering.searchText().contains("vue-router"));
        assertTrue(engineering.searchText().contains("^4.5.0"));
        assertTrue(engineering.searchText().contains("package.json"));
        assertTrue(engineering.searchText().contains("src/App.vue"));
        assertFalse(engineering.searchText().contains(TemplateTestData.SOURCE_MARKER));
    }

    @Test
    void engineeringTextOrderIsStable(@TempDir Path tempDir) throws IOException {
        TemplateTestData.write(tempDir.resolve("skeleton.json"),
                TemplateTestData.skeletonDocument("skeleton-vue"));
        TemplateDoc document = loadOnlyDocument(tempDir);

        String firstText = chunkFactory.createChunks(document).get(1).searchText();
        String secondText = chunkFactory.createChunks(document).get(1).searchText();

        assertEquals(firstText, secondText);
        assertTrue(firstText.indexOf("vue:") < firstText.indexOf("vue-router:"));
        assertTrue(firstText.indexOf("index.html") < firstText.indexOf("package.json"));
    }

    private TemplateDoc loadOnlyDocument(Path root) {
        return new TemplateCatalog(root, objectMapper).getDocuments().getFirst();
    }
}
