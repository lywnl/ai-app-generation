package com.lyw.appgeneration.service.rag.ingest;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 模板摄取服务:从 {@link RagProperties#getTemplatesDir()} 根目录下的子目录
 * (html / multi-file / vue-project)扫描 *.json,做 embedding 后存入 PGVector
 * <p>
 * 仅在 rag.ingest.enabled=true 时实例化。日常运行保持 false,
 * 只在你新增/修改模板后临时改 true 重启一次,跑完即关。
 *
 * @author lyw
 */
@Service
@ConditionalOnProperty(prefix = "rag.ingest", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TemplateIngestService {

    private final EmbeddingModel ragEmbeddingModel;
    private final Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStoreByType;
    private final RagProperties props;
    private final VueKnowledgeIngestor vueKnowledgeIngestor;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            String subDir = RagConstants.TYPE_TO_DIR.get(type);
            if (subDir == null) continue;
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
                total += ingestDir(typeDir, type, store);
            }
        }

        log.info("[RAG Ingest] 摄取完成,共 {} 条,耗时 {}ms",
                total, System.currentTimeMillis() - start);
    }

    private int ingestDir(Path typeDir, CodeGenTypeEnum type, EmbeddingStore<TextSegment> store) {
        int count = 0;
        try (Stream<Path> files = Files.list(typeDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path file : jsonFiles) {
                try {
                    if (ingestOne(file, type, store)) {
                        count++;
                    }
                } catch (Exception e) {
                    log.error("[RAG Ingest] 摄取失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("[RAG Ingest] 扫描目录失败: {}", typeDir, e);
        }
        log.info("[RAG Ingest] type={} 摄取 {} 条", type, count);
        return count;
    }

    private boolean ingestOne(Path file, CodeGenTypeEnum type, EmbeddingStore<TextSegment> store) throws IOException {
        String json = Files.readString(file);
        TemplateDoc doc = objectMapper.readValue(json, TemplateDoc.class);
        if (doc.getId() == null || doc.getId().isBlank()
                || doc.getEmbedText() == null || doc.getEmbedText().isBlank()) {
            log.warn("[RAG Ingest] id 或 embedText 为空,跳过: {}", file);
            return false;
        }
        if (doc.getType() == null) {
            doc.setType(RagConstants.TYPE_TO_DIR.get(type));
        }

        Embedding embedding = ragEmbeddingModel.embed(doc.getEmbedText()).content();

        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("id", doc.getId());
        metaMap.put("title", nullSafe(doc.getTitle()));
        metaMap.put("category", nullSafe(doc.getCategory()));
        metaMap.put("code", doc.getFiles() == null ? "" : JSONUtil.toJsonStr(doc.getFiles()));
        Metadata metadata = Metadata.from(metaMap);

        TextSegment segment = TextSegment.from(doc.getEmbedText(), metadata);
        store.add(embedding, segment);
        log.debug("[RAG Ingest] 入库 id={}, title={}", doc.getId(), doc.getTitle());
        return true;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
