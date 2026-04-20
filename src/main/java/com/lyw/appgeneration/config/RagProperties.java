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
        private int topK = 3;
        private double minScore = 0.55;
        private long timeoutMs = 2000L;
    }

    @Data
    public static class Prompt {
        private int maxContextChars = 4000;
    }
}
