package com.lyw.appgeneration.service.rag.ingest;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateIngestServiceTest {

    @Test
    void ingestsOnlyExplicitlySelectedTypes(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("html"));
        Files.createDirectories(tempDir.resolve("multi-file"));
        Files.createDirectories(tempDir.resolve("vue-project"));
        NativeTemplateIngestor nativeIngestor = mock(NativeTemplateIngestor.class);
        when(nativeIngestor.ingest(anyPath(), org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.HTML), anyStore()))
                .thenReturn(new NativeTemplateIngestor.IngestResult("native-version", 9));
        EmbeddingStore<TextSegment> vueStore = store();
        EmbeddingStore<TextSegment> htmlStore = store();
        VueKnowledgeIngestor vueIngestor = mock(VueKnowledgeIngestor.class);

        service(tempDir, Set.of(CodeGenTypeEnum.HTML), Map.of(
                CodeGenTypeEnum.HTML, htmlStore,
                CodeGenTypeEnum.VUE_PROJECT, vueStore), nativeIngestor, vueIngestor)
                .ingestAll();

        verify(nativeIngestor).ingest(
                org.mockito.ArgumentMatchers.eq(tempDir.resolve("html")),
                org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.HTML), sameStore(htmlStore));
        verify(vueIngestor, never()).ingest(anyPath(), sameStore(vueStore));
    }

    @Test
    void emptyTypeSelectionDoesNotTouchAnyStore(@TempDir Path tempDir) {
        NativeTemplateIngestor nativeIngestor = mock(NativeTemplateIngestor.class);
        VueKnowledgeIngestor vueIngestor = mock(VueKnowledgeIngestor.class);

        service(tempDir, Set.of(), Map.of(), nativeIngestor, vueIngestor).ingestAll();

        verify(nativeIngestor, never()).ingest(anyPath(), org.mockito.ArgumentMatchers.any(), anyStore());
        verify(vueIngestor, never()).ingest(anyPath(), anyStore());
    }

    private TemplateIngestService service(Path root,
                                          Set<CodeGenTypeEnum> selectedTypes,
                                          Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> stores,
                                          NativeTemplateIngestor nativeIngestor,
                                          VueKnowledgeIngestor vueIngestor) {
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir(root.toString());
        properties.getIngest().setTypes(selectedTypes);
        return new TemplateIngestService(stores, properties, nativeIngestor, vueIngestor);
    }

    private Path anyPath() {
        return org.mockito.ArgumentMatchers.any(Path.class);
    }

    private EmbeddingStore<TextSegment> anyStore() {
        return org.mockito.ArgumentMatchers.any();
    }

    private EmbeddingStore<TextSegment> sameStore(EmbeddingStore<TextSegment> store) {
        return org.mockito.ArgumentMatchers.same(store);
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> store() {
        return mock(EmbeddingStore.class);
    }
}
