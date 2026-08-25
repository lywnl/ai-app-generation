package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.NativeTemplateCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 在应用生命周期内分别持有 HTML 与多文件模板的受校验目录快照。
 */
@Component
@Slf4j
public class NativeTemplateCatalogProvider {

    private final Map<CodeGenTypeEnum, NativeTemplateCatalog> catalogs;

    @Autowired
    public NativeTemplateCatalogProvider(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, (root, type) -> new NativeTemplateCatalog(root, type, objectMapper));
    }

    NativeTemplateCatalogProvider(RagProperties properties, CatalogLoader catalogLoader) {
        this.catalogs = loadCatalogs(properties, catalogLoader);
    }

    public Optional<NativeTemplateCatalog> current(CodeGenTypeEnum type) {
        return Optional.ofNullable(catalogs.get(type));
    }

    private Map<CodeGenTypeEnum, NativeTemplateCatalog> loadCatalogs(
            RagProperties properties,
            CatalogLoader catalogLoader) {
        Map<CodeGenTypeEnum, NativeTemplateCatalog> loaded =
                new EnumMap<>(CodeGenTypeEnum.class);
        if (!properties.isEnabled()) {
            return Map.copyOf(loaded);
        }
        String templatesDir = properties.getTemplatesDir();
        if (templatesDir == null || templatesDir.isBlank()) {
            log.error("[Native RAG] 模板根目录未配置,HTML 与多文件检索将返回无 RAG");
            return Map.copyOf(loaded);
        }
        for (CodeGenTypeEnum type : new CodeGenTypeEnum[]{
                CodeGenTypeEnum.HTML, CodeGenTypeEnum.MULTI_FILE}) {
            try {
                Path root = Path.of(templatesDir).resolve(RagConstants.TYPE_TO_DIR.get(type));
                loaded.put(type, catalogLoader.load(root, type));
            } catch (RuntimeException exception) {
                log.error("[Native RAG] 目录不可用,type={},reasonType={},该类型检索将返回无 RAG",
                        type, exception.getClass().getSimpleName());
            }
        }
        return Map.copyOf(loaded);
    }

    @FunctionalInterface
    interface CatalogLoader {
        NativeTemplateCatalog load(Path root, CodeGenTypeEnum type);
    }
}
