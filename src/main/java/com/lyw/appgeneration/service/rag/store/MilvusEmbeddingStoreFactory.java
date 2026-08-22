package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 延迟复用 Milvus Client，并为每个 Collection 缓存通过协议校验的向量存储。
 */
@Component
@Slf4j
public class MilvusEmbeddingStoreFactory {

    private final RagProperties properties;
    private final MilvusCollectionVerifier collectionVerifier;
    private final MilvusClientProvider clientProvider;
    private final MilvusStoreBuilder storeBuilder;
    private final Map<String, EmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();
    private MilvusServiceClient client;
    private boolean closed;

    @Autowired
    public MilvusEmbeddingStoreFactory(
            RagProperties properties,
            MilvusCollectionSchemaVerifier schemaVerifier) {
        this(properties, schemaVerifier::verify, MilvusEmbeddingStoreFactory::createClient,
                MilvusEmbeddingStoreFactory::buildStore);
    }

    MilvusEmbeddingStoreFactory(
            RagProperties properties,
            MilvusCollectionVerifier collectionVerifier,
            MilvusClientProvider clientProvider,
            MilvusStoreBuilder storeBuilder) {
        this.properties = properties;
        this.collectionVerifier = collectionVerifier;
        this.clientProvider = clientProvider;
        this.storeBuilder = storeBuilder;
    }

    public EmbeddingStore<TextSegment> create(String collectionName) {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Milvus Client 已关闭");
            }
            return stores.computeIfAbsent(collectionName, this::createStore);
        }
    }

    @PreDestroy
    public void close() {
        MilvusServiceClient clientToClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            clientToClose = client;
            client = null;
            stores.clear();
        }
        if (clientToClose == null) {
            return;
        }
        try {
            clientToClose.close(5L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("[RAG] 关闭 Milvus Client 时被中断");
        } catch (RuntimeException exception) {
            log.warn("[RAG] 关闭 Milvus Client 失败,errorType={}", exception.getClass().getSimpleName());
        }
    }

    private EmbeddingStore<TextSegment> createStore(String collectionName) {
        MilvusServiceClient client = getClient();
        boolean existed = collectionExists(client, collectionName);
        if (existed) {
            collectionVerifier.verify(client, collectionName, properties.getEmbedding().getDimension());
        }
        MilvusEmbeddingStore delegate = storeBuilder.build(client, collectionName,
                properties.getEmbedding().getDimension());
        if (!existed) {
            collectionVerifier.verify(client, collectionName, properties.getEmbedding().getDimension());
        }
        return new MilvusEmbeddingStoreAdapter(client, collectionName, delegate);
    }

    private MilvusServiceClient getClient() {
        if (client == null) {
            client = clientProvider.create(properties.getMilvus());
        }
        return client;
    }

    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        if (response == null || response.getStatus() == null || response.getStatus() != R.Status.Success.getCode()
                || response.getData() == null) {
            throw new IllegalStateException("Milvus Collection=" + collectionName + " 存在性检查失败");
        }
        return response.getData();
    }

    private static MilvusServiceClient createClient(RagProperties.Milvus milvus) {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(milvus.getHost())
                .withPort(milvus.getPort())
                .withDatabaseName(milvus.getDatabase())
                .withAuthorization(milvus.getUsername(), milvus.getPassword())
                .build());
    }

    private static MilvusEmbeddingStore buildStore(
            MilvusServiceClient client,
            String collectionName,
            int dimension) {
        return MilvusEmbeddingStore.builder()
                .milvusClient(client)
                .collectionName(collectionName)
                .dimension(dimension)
                .indexType(IndexType.FLAT)
                .metricType(MetricType.COSINE)
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .retrieveEmbeddingsOnSearch(false)
                .autoFlushOnInsert(true)
                .idFieldName("id")
                .textFieldName("text")
                .metadataFieldName("metadata")
                .vectorFieldName("vector")
                .build();
    }
}

@FunctionalInterface
interface MilvusClientProvider {
    MilvusServiceClient create(RagProperties.Milvus properties);
}

@FunctionalInterface
interface MilvusStoreBuilder {
    MilvusEmbeddingStore build(MilvusServiceClient client, String collectionName, int dimension);
}

@FunctionalInterface
interface MilvusCollectionVerifier {
    void verify(MilvusServiceClient client, String collectionName, int dimension);
}
