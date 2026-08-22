package com.lyw.appgeneration.config;

import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.service.rag.store.MilvusEmbeddingStoreFactory;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * RAG 配置:EmbeddingModel(阿里 text-embedding-v4)+ 三个按生成类型隔离的 EmbeddingStore
 *
 * @author lyw
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RagConfig {

    private final RagProperties props;
    private final MilvusEmbeddingStoreFactory milvusEmbeddingStoreFactory;

    /**
     * 阿里 text-embedding-v4 走 DashScope 的 OpenAI 兼容端点,复用现有 dashscope.api-key
     */
    @Bean
    public EmbeddingModel ragEmbeddingModel(@Value("${dashscope.api-key}") String apiKey) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(props.getEmbedding().getBaseUrl())
                .apiKey(apiKey)
                .modelName(props.getEmbedding().getModelName())
                .dimensions(props.getEmbedding().getDimension())
                .timeout(Duration.ofMillis(props.getEmbedding().getTimeoutMs()))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 按代码生成类型隔离的向量存储 Map（一种类型一个 Collection）。
     * RAG 关闭时返回空 Map，保留依赖契约但不建立 Milvus 连接。
     */
    @Bean
    public Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStoreByType() {
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> map = new EnumMap<>(CodeGenTypeEnum.class);
        if (!props.isEnabled()) {
            return map;
        }
        RagConstants.TYPE_TO_COLLECTION.forEach((type, collectionName) -> {
            try {
                map.put(type, milvusEmbeddingStoreFactory.create(collectionName));
            } catch (Exception e) {
                log.error("[RAG] 构建向量存储失败,type={}, collection={},该类型将降级为无 RAG",
                        type, collectionName, e);
            }
        });
        return map;
    }
}
