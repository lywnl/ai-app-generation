package com.lyw.appgeneration.service.rag.store;

import com.lyw.appgeneration.config.RagProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 延迟创建并复用 Milvus V2 客户端，生命周期由向量存储工厂统一管理。
 */
@Component
@Slf4j
public class MilvusV2ClientProvider implements AutoCloseable {

    private final RagProperties properties;
    private final MilvusV2ClientFactory clientFactory;
    private MilvusClientV2 client;
    private boolean closed;

    @Autowired
    public MilvusV2ClientProvider(RagProperties properties) {
        this(properties, MilvusV2ClientProvider::createClient);
    }

    MilvusV2ClientProvider(RagProperties properties, MilvusV2ClientFactory clientFactory) {
        this.properties = Objects.requireNonNull(properties, "RAG 配置不能为空");
        this.clientFactory = Objects.requireNonNull(clientFactory, "Milvus V2 客户端工厂不能为空");
    }

    public synchronized MilvusClientV2 getClient() {
        if (closed) {
            throw new IllegalStateException("Milvus V2 Client 已关闭");
        }
        if (client == null) {
            client = clientFactory.create(properties.getMilvus());
        }
        return client;
    }

    @Override
    public void close() {
        MilvusClientV2 clientToClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            clientToClose = client;
            client = null;
        }
        if (clientToClose == null) {
            return;
        }
        try {
            clientToClose.close(5L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("[RAG] 关闭 Milvus V2 Client 时被中断");
        } catch (RuntimeException exception) {
            log.warn("[RAG] 关闭 Milvus V2 Client 失败,errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static MilvusClientV2 createClient(RagProperties.Milvus milvus) {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri("http://%s:%d".formatted(milvus.getHost(), milvus.getPort()))
                .dbName(milvus.getDatabase())
                .username(milvus.getUsername())
                .password(milvus.getPassword())
                .build());
    }
}

@FunctionalInterface
interface MilvusV2ClientFactory {
    MilvusClientV2 create(RagProperties.Milvus properties);
}
