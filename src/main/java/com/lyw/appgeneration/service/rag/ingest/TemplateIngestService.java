package com.lyw.appgeneration.service.rag.ingest;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 模板摄取服务:从 {@link RagProperties#getTemplatesDir()} 根目录下的子目录
 * （html / multi-file / vue-project）扫描 *.json，生成 embedding 后存入 Milvus。
 * <p>
 * 仅在 rag.ingest.enabled=true 时实例化。日常运行保持 false,
 * 只在你新增/修改模板后临时改 true 重启一次,跑完即关。
 *
 * @author lyw
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "rag.ingest", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TemplateIngestService {

    private final Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStoreByType;
    private final RagProperties props;
    private final NativeTemplateIngestor nativeTemplateIngestor;
    private final VueKnowledgeIngestor vueKnowledgeIngestor;

    @PostConstruct
    public void ingestAll() {
        String root = props.getTemplatesDir();
        if (root == null || root.isBlank()) {
            log.warn("[RAG Ingest] templatesDir 未配置,跳过摄取");
            return;
        }
        Path rootPath = Paths.get(root);
        if (!Files.isDirectory(rootPath)) {
            log.warn("[RAG Ingest] 模板根目录不存在: {}", rootPath);
            return;
        }
        log.info("[RAG Ingest] 开始摄取,根目录: {}", rootPath);
        long start = System.currentTimeMillis();
        int total = 0;

        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            if (!props.getIngest().getTypes().contains(type)) {
                continue;
            }
            String subDir = RagConstants.TYPE_TO_DIR.get(type);
            if (subDir == null) {
                continue;
            }
            Path typeDir = rootPath.resolve(subDir);
            if (!Files.isDirectory(typeDir)) {
                log.info("[RAG Ingest] 子目录不存在,跳过: {}", typeDir);
                continue;
            }
            EmbeddingStore<TextSegment> store = embeddingStoreByType.get(type);
            if (store == null) {
                log.warn("[RAG Ingest] 未找到 type={} 对应的向量存储,跳过", type);
                continue;
            }
            if (type == CodeGenTypeEnum.VUE_PROJECT) {
                total += vueKnowledgeIngestor.ingest(typeDir, store).chunkCount();
            } else {
                NativeTemplateIngestor.IngestResult result =
                        nativeTemplateIngestor.ingest(typeDir, type, store);
                total += result.documentCount();
                log.info("[RAG Ingest] type={},目录版本={},模板数={}",
                        type, result.catalogVersion(), result.documentCount());
            }
        }

        log.info("[RAG Ingest] 摄取完成,共 {} 条,耗时 {}ms",
                total, System.currentTimeMillis() - start);
    }

}
