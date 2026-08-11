package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 在应用生命周期内持有同一目录版本的 Vue 目录与 BM25 索引。
 */
@Component
@Slf4j
public class VueRetrievalResourceProvider {

    private final VueRetrievalResources resources;

    @Autowired
    public VueRetrievalResourceProvider(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Bm25Retriever::new);
    }

    /**
     * 使用调用方已经加载并校验的目录快照，避免质量门禁在同一轮重复读盘。
     *
     * @param catalog 已加载的 Vue 知识目录
     */
    public VueRetrievalResourceProvider(TemplateCatalog catalog) {
        this.resources = strictResources(
                java.util.Objects.requireNonNull(catalog, "Vue 知识目录不能为空"),
                Bm25Retriever::new);
    }

    /**
     * 为离线质量评测创建严格资源，BM25 不可用时禁止退化运行。
     */
    public static VueRetrievalResourceProvider forEvaluation(TemplateCatalog catalog) {
        return new VueRetrievalResourceProvider(catalog);
    }

    static VueRetrievalResourceProvider forEvaluation(
            TemplateCatalog catalog,
            Bm25RetrieverFactory bm25RetrieverFactory) {
        return new VueRetrievalResourceProvider(catalog, bm25RetrieverFactory);
    }

    private VueRetrievalResourceProvider(
            TemplateCatalog catalog,
            Bm25RetrieverFactory bm25RetrieverFactory) {
        this.resources = strictResources(
                java.util.Objects.requireNonNull(catalog, "Vue 知识目录不能为空"),
                bm25RetrieverFactory);
    }

    VueRetrievalResourceProvider(RagProperties properties,
                                 ObjectMapper objectMapper,
                                 Bm25RetrieverFactory bm25RetrieverFactory) {
        this.resources = load(properties, objectMapper, bm25RetrieverFactory);
    }

    public Optional<VueRetrievalResources> current() {
        return Optional.ofNullable(resources);
    }

    private VueRetrievalResources load(RagProperties properties,
                                       ObjectMapper objectMapper,
                                       Bm25RetrieverFactory bm25RetrieverFactory) {
        String templatesDir = properties.getTemplatesDir();
        if (templatesDir == null || templatesDir.isBlank()) {
            log.error("[Vue RAG] 模板根目录未配置,Vue 检索将返回无 RAG");
            return null;
        }
        try {
            Path vueRoot = Path.of(templatesDir)
                    .resolve(RagConstants.TYPE_TO_DIR.get(CodeGenTypeEnum.VUE_PROJECT));
            TemplateCatalog catalog = new TemplateCatalog(vueRoot, objectMapper);
            return resourcesWithOptionalBm25(catalog, bm25RetrieverFactory);
        } catch (Exception exception) {
            log.error("[Vue RAG] 目录不可用,Vue 检索将返回无 RAG,candidateCount=0");
            return null;
        }
    }

    private VueRetrievalResources resourcesWithOptionalBm25(
            TemplateCatalog catalog,
            Bm25RetrieverFactory bm25RetrieverFactory) {
        try {
            return new VueRetrievalResources(catalog, bm25RetrieverFactory.create(catalog));
        } catch (Exception exception) {
            log.warn("[Vue RAG] BM25 索引不可用,将使用 Dense 单通道,candidateCount=0");
            return new VueRetrievalResources(catalog, Optional.empty());
        }
    }

    private VueRetrievalResources strictResources(
            TemplateCatalog catalog,
            Bm25RetrieverFactory bm25RetrieverFactory) {
        try {
            return new VueRetrievalResources(catalog, bm25RetrieverFactory.create(catalog));
        } catch (Exception exception) {
            throw new IllegalStateException("评测 BM25 索引初始化失败", exception);
        }
    }

    @PreDestroy
    public void close() {
        if (resources == null) {
            return;
        }
        resources.bm25Retriever().ifPresent(this::closeBm25);
    }

    private void closeBm25(Bm25Retriever bm25Retriever) {
        try {
            bm25Retriever.close();
        } catch (IOException exception) {
            log.warn("[Vue RAG] 关闭 BM25 索引失败,candidateCount=0");
        }
    }

    @FunctionalInterface
    interface Bm25RetrieverFactory {
        Bm25Retriever create(TemplateCatalog catalog) throws IOException;
    }
}
