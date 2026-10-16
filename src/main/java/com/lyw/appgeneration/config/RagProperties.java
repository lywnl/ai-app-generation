package com.lyw.appgeneration.config;

import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

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

    /** Vue 混合检索策略开关 */
    private Hybrid hybrid = new Hybrid();

    /** Milvus 连接配置 */
    private Milvus milvus = new Milvus();

    /** Embedding 模型配置 */
    private Embedding embedding = new Embedding();

    /** 检索参数 */
    private Retrieval retrieval = new Retrieval();

    /** Vue 检索的请求接纳与外部调用并发上限 */
    private Execution execution = new Execution();

    /** Rerank 精排参数 */
    private Rerank rerank = new Rerank();

    /** Prompt 拼装参数 */
    private Prompt prompt = new Prompt();

    @Data
    public static class Ingest {
        private boolean enabled = false;
        /** 本次显式摄取的生成类型；空集合表示不摄取任何类型。 */
        private Set<CodeGenTypeEnum> types = Set.of();
    }

    @Data
    public static class Hybrid {
        private boolean enabled = true;
    }

    @Data
    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String database = "default";
        private String username = "root";
        private String password;
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
    public static class Execution {
        private int workerThreads = 12;
        private int maxConcurrentRequests = 4;
        private int queueCapacity = 64;

        public void validate() {
            if (workerThreads <= 0 || maxConcurrentRequests <= 0) {
                throw new IllegalArgumentException("RAG 工作线程数和最大并发请求数必须大于 0");
            }
            // 每个已接纳的混合请求最多提交 1 次向量计算、4 次搜索、2 次重排。
            if (queueCapacity < 7L * maxConcurrentRequests) {
                throw new IllegalArgumentException(
                        "rag.execution.queue-capacity 必须不少于 7 × max-concurrent-requests");
            }
        }
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
        private long timeoutMs = 5000L;
    }

    @Data
    public static class Prompt {
        private int maxContextChars = 4000;
    }
}
