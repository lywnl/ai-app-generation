package com.lyw.appgeneration.config;

import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
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
     * 按代码生成类型隔离的向量存储 Map(一种类型一张表,比 metadata filter 更快)
     * 即使 RAG 被关闭也要构建,避免启动期报依赖缺失;真正是否检索由 RagRetrievalService 判定
     */
    @Bean
    public Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> embeddingStoreByType() {
        Map<CodeGenTypeEnum, EmbeddingStore<TextSegment>> map = new EnumMap<>(CodeGenTypeEnum.class);
        RagConstants.TYPE_TO_TABLE.forEach((type, table) -> {
            try {
                map.put(type, buildStore(table));
            } catch (Exception e) {
                log.error("[RAG] 构建向量存储失败,type={}, table={},该类型将降级为无 RAG",
                        type, table, e);
            }
        });
        return map;
    }

    /**
     * 构建单个 PGVector 存储;createTable=true 让 LangChain4j 自动建表,
     * 索引走 HNSW 由 V1__hnsw_index.sql 手动建(比 IVFFlat 效果更好)
     */
    private EmbeddingStore<TextSegment> buildStore(String table) {
        RagProperties.PgVector pg = props.getPgvector();
        return PgVectorEmbeddingStore.builder()
                .host(pg.getHost())
                .port(pg.getPort())
                .database(pg.getDatabase())
                .user(pg.getUser())
                .password(pg.getPassword())
                .table(table)
                .dimension(props.getEmbedding().getDimension())
                .createTable(true)
                .useIndex(false)
                .build();
    }
}
