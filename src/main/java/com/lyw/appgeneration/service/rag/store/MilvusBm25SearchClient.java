package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Milvus 原生 BM25 查询协议适配器，查询文本由 Milvus 服务端 Analyzer 处理。
 */
@Component
@Slf4j
public class MilvusBm25SearchClient {

    public static final int QUERY_TOP_K = 10;
    private static final String BM25_FIELD = "bm25_sparse_vector";

    private final Supplier<MilvusClientV2> clientSupplier;

    @Autowired
    public MilvusBm25SearchClient(MilvusV2ClientProvider clientProvider) {
        this(clientProvider::getClient);
    }

    public MilvusBm25SearchClient(MilvusClientV2 client) {
        this(() -> client);
    }

    MilvusBm25SearchClient(Supplier<MilvusClientV2> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "Milvus V2 客户端不能为空");
    }

    public SearchResp search(String rawQuery,
                             String catalogVersion,
                             RagDocumentKind documentKind,
                             int topK) {
        if (rawQuery == null || rawQuery.isBlank()
                || catalogVersion == null || catalogVersion.isBlank()
                || documentKind == null || topK <= 0) {
            return SearchResp.builder().searchResults(List.of()).build();
        }
        SearchReq request = SearchReq.builder()
                .collectionName(RagConstants.VUE_BM25_COLLECTION)
                .annsField(BM25_FIELD)
                .metricType(IndexParam.MetricType.BM25)
                .topK(topK)
                .filter(filterExpression(catalogVersion, documentKind))
                .outputFields(List.of("id", "metadata"))
                .data(List.of(new EmbeddedText(rawQuery)))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build();
        return search(request);
    }

    public SearchResp search(SearchReq request) {
        Objects.requireNonNull(request, "Milvus BM25 查询请求不能为空");
        try {
            return clientSupplier.get().search(request);
        } catch (RuntimeException exception) {
            log.warn("[Vue RAG][BM25] Milvus 搜索失败,errorType={}",
                    exception.getClass().getSimpleName());
            throw new IllegalStateException("Milvus BM25 搜索失败", null);
        }
    }

    static String filterExpression(String catalogVersion, RagDocumentKind documentKind) {
        Objects.requireNonNull(catalogVersion, "目录版本不能为空");
        Objects.requireNonNull(documentKind, "文档类型不能为空");
        return "metadata[\"catalogVersion\"] == \"%s\" && metadata[\"documentKind\"] == \"%s\""
                .formatted(escapeExpressionValue(catalogVersion),
                        escapeExpressionValue(documentKind.name()));
    }

    private static String escapeExpressionValue(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
