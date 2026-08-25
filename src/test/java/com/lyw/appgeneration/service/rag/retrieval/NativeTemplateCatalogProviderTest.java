package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeTemplateCatalogProviderTest {

    @Test
    void loadsEachNativeTypeIndependently() {
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir("templates");
        NativeTemplateCatalog html = mock(NativeTemplateCatalog.class);
        NativeTemplateCatalog multi = mock(NativeTemplateCatalog.class);
        NativeTemplateCatalogProvider.CatalogLoader loader = mock(
                NativeTemplateCatalogProvider.CatalogLoader.class);
        when(loader.load(Path.of("templates/html"), CodeGenTypeEnum.HTML))
                .thenReturn(html);
        when(loader.load(Path.of("templates/multi-file"), CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(multi);

        NativeTemplateCatalogProvider provider =
                new NativeTemplateCatalogProvider(properties, loader);

        assertEquals(Optional.of(html), provider.current(CodeGenTypeEnum.HTML));
        assertEquals(Optional.of(multi), provider.current(CodeGenTypeEnum.MULTI_FILE));
        assertTrue(provider.current(CodeGenTypeEnum.VUE_PROJECT).isEmpty());
    }

    @Test
    void oneInvalidCatalogDoesNotDisableTheOtherType() {
        RagProperties properties = new RagProperties();
        properties.setTemplatesDir("templates");
        NativeTemplateCatalog html = mock(NativeTemplateCatalog.class);
        NativeTemplateCatalogProvider.CatalogLoader loader = mock(
                NativeTemplateCatalogProvider.CatalogLoader.class);
        when(loader.load(Path.of("templates/html"), CodeGenTypeEnum.HTML))
                .thenReturn(html);
        when(loader.load(Path.of("templates/multi-file"), CodeGenTypeEnum.MULTI_FILE))
                .thenThrow(new IllegalArgumentException("目录非法"));

        NativeTemplateCatalogProvider provider =
                new NativeTemplateCatalogProvider(properties, loader);

        assertEquals(Optional.of(html), provider.current(CodeGenTypeEnum.HTML));
        assertTrue(provider.current(CodeGenTypeEnum.MULTI_FILE).isEmpty());
    }

    @Test
    void disabledRagDoesNotLoadFilesystem() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        properties.setTemplatesDir("templates");
        NativeTemplateCatalogProvider.CatalogLoader loader = mock(
                NativeTemplateCatalogProvider.CatalogLoader.class);

        NativeTemplateCatalogProvider provider =
                new NativeTemplateCatalogProvider(properties, loader);

        assertTrue(provider.current(CodeGenTypeEnum.HTML).isEmpty());
        assertTrue(provider.current(CodeGenTypeEnum.MULTI_FILE).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(loader);
    }
}
