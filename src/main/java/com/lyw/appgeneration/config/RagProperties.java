package com.lyw.appgeneration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 相关配置(与 application.yml 的 rag.* 对应)
 *
 * @author lyw
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** RAG 总开关 */
    private boolean enabled = true;

    /** 模板库根目录(文件系统路径) */
    private String templatesDir;

    /** 摄取开关 */
    private Ingest ingest = new Ingest();

    /** PGVector 连接配置 */
    private PgVector pgvector = new PgVector();

    /** Embedding 模型配置 */
    private Embedding embedding = new Embedding();

    /** 检索参数 */
    private Retrieval retrieval = new Retrieval();

    /** Rerank 精排参数 */
    private Rerank rerank = new Rerank();

    /** Prompt 拼装参数 */
    private Prompt prompt = new Prompt();

    @Data
    public static class Ingest {
        private boolean enabled = false;
    }

    @Data
    public static class PgVector {
        private String host = "localhost";
        private int port = 5432;
        private String database = "ai_codegen_rag";
        private String user = "admin";
        private String password = "lyw666";
    }

    @Data
    public static class Embedding {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String modelName = "text-embedding-v4";
        private int dimension = 1024;
        private long timeoutMs = 10000L;
    }

    @Data
    public static class Retrieval {
        /** 最终返回给上游的片段数(rerank 精排后取 topK;无 rerank 时直接向量 topK) */
        private int topK = 3;
        /** 向量相似度下限。Rerank 开启时应调低以便给精排留候选池 */
        private double minScore = 0.30;
        private long timeoutMs = 2000L;
    }

    @Data
    public static class Rerank {
        /** Rerank 总开关;关闭时 retrieve 退化为纯向量召回 */
        private boolean enabled = true;
        /** DashScope rerank endpoint */
        private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        /** 模型名,默认 gte-rerank-v2 */
        private String modelName = "gte-rerank-v2";
        /** 粗召回候选数(喂给 rerank 的 document 数);最终仍按 retrieval.topK 截断 */
        private int topN = 10;
        /** 单条 document 的字符截断阈值,规避 gte-rerank-v2 单条 4000 token 上限 */
        private int docCharLimit = 2000;
        /** HTTP 超时 */
        private long timeoutMs = 3000L;
    }

    @Data
    public static class Prompt {
        private int maxContextChars = 4000;
    }
}
