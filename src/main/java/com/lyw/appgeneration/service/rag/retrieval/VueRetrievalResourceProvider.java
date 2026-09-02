package com.lyw.appgeneration.service.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 在应用生命周期内持有同一目录版本的 Vue 目录快照。
 */
@Component
@Slf4j
public class VueRetrievalResourceProvider {

    private final VueRetrievalResources resources;

    @Autowired
    public VueRetrievalResourceProvider(RagProperties properties, ObjectMapper objectMapper) {
        this.resources = load(properties, objectMapper);
    }

    /**
     * 使用调用方已经加载并校验的目录快照，避免质量门禁在同一轮重复读盘。
     *
     * @param catalog 已加载的 Vue 知识目录
     */
    public VueRetrievalResourceProvider(TemplateCatalog catalog) {
        this.resources = new VueRetrievalResources(
                java.util.Objects.requireNonNull(catalog, "Vue 知识目录不能为空"));
    }

    /**
     * 为离线质量评测创建严格资源，BM25 不可用时禁止退化运行。
     */
    public static VueRetrievalResourceProvider forEvaluation(TemplateCatalog catalog) {
        return new VueRetrievalResourceProvider(catalog);
    }

    public Optional<VueRetrievalResources> current() {
        return Optional.ofNullable(resources);
    }

    private VueRetrievalResources load(RagProperties properties,
                                       ObjectMapper objectMapper) {
        if (!properties.isEnabled()) {
            return null;
        }
        String templatesDir = properties.getTemplatesDir();
        if (templatesDir == null || templatesDir.isBlank()) {
            log.error("[Vue RAG] 模板根目录未配置,Vue 检索将返回无 RAG");
            return null;
        }
        try {
            Path vueRoot = Path.of(templatesDir)
                    .resolve(RagConstants.TYPE_TO_DIR.get(CodeGenTypeEnum.VUE_PROJECT));
            TemplateCatalog catalog = new TemplateCatalog(vueRoot, objectMapper);
            return new VueRetrievalResources(catalog);
        } catch (Exception exception) {
            log.error("[Vue RAG] 目录不可用,Vue 检索将返回无 RAG,candidateCount=0");
            return null;
        }
    }

    public void close() {
        // BM25 索引由 Milvus 持久化，Provider 不再持有本地索引资源。
    }
}
