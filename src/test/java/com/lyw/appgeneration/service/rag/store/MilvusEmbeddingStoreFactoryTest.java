package com.lyw.appgeneration.service.rag.store;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.constants.RagConstants;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusEmbeddingStoreFactoryTest {

    private MilvusServiceClient client;
    private MilvusEmbeddingStore delegate;
    private List<String> events;
    private AtomicInteger providerCalls;
    private MilvusEmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        client = mock(MilvusServiceClient.class);
        delegate = mock(MilvusEmbeddingStore.class);
        events = new ArrayList<>();
        providerCalls = new AtomicInteger();
        MilvusClientProvider provider = ignored -> {
            providerCalls.incrementAndGet();
            return client;
        };
        factory = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> events.add("校验:" + collectionName),
                provider, (builtClient, collectionName, dimension) -> {
                    events.add("构建:" + collectionName);
                    return delegate;
                });
    }

    @Test
    void 多个Collection复用一个延迟创建的Client() {
        when(client.hasCollection(any())).thenReturn(R.success(true));

        assertNotNull(factory.create("templates_html"));
        assertNotNull(factory.create("templates_vue"));

        org.junit.jupiter.api.Assertions.assertEquals(1, providerCalls.get());
    }

    @Test
    void 同一Collection重复创建时返回缓存实例() {
        when(client.hasCollection(any())).thenReturn(R.success(true));

        var first = factory.create("templates_vue");
        var second = factory.create("templates_vue");

        assertSame(first, second);
        assertEquals(List.of("校验:templates_vue", "构建:templates_vue"), events);
    }

    @Test
    void 已存在Collection先校验再构建Store() {
        when(client.hasCollection(any())).thenReturn(R.success(true));

        factory.create("templates_vue");

        assertEquals(List.of("校验:templates_vue", "构建:templates_vue"), events);
    }

    @Test
    void 缺失Collection先由官方Store创建再校验协议() {
        when(client.hasCollection(any())).thenReturn(R.success(false));

        factory.create("templates_vue");

        assertEquals(List.of("构建:templates_vue", "校验:templates_vue"), events);
    }

    @Test
    void 关闭工厂只关闭一次共享Client() throws Exception {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        factory.create("templates_html");

        factory.close();
        factory.close();

        verify(client, times(1)).close(5L);
    }

    @Test
    void 未创建Client时关闭工厂不执行关闭操作() throws Exception {
        factory.close();

        verify(client, never()).close(any(Long.class));
    }

    @Test
    void 未创建旧Client时关闭工厂仍释放V2ClientProvider() throws Exception {
        MilvusV2ClientProvider v2Provider = mock(MilvusV2ClientProvider.class);
        MilvusVueBm25CollectionProvisioner provisioner = mock(MilvusVueBm25CollectionProvisioner.class);
        MilvusEmbeddingStoreFactory factoryWithOnlyV2Provider = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> { },
                ignored -> client, (builtClient, collectionName, dimension) -> delegate,
                v2Provider, provisioner);

        factoryWithOnlyV2Provider.close();

        verify(v2Provider).close();
        verify(client, never()).close(any(Long.class));
    }

    @Test
    void 关闭Client被中断时恢复线程中断标记() throws Exception {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        factory.create("templates_html");
        org.mockito.Mockito.doThrow(new InterruptedException("关闭中断")).when(client).close(5L);

        factory.close();

        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    void 关闭Client失败日志不得携带下游敏感异常() throws Exception {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        factory.create("templates_html");
        org.mockito.Mockito.doThrow(new IllegalStateException("敏感密码=不得出现在日志"))
                .when(client).close(5L);
        Logger logger = (Logger) LoggerFactory.getLogger(MilvusEmbeddingStoreFactory.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            factory.close();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        assertTrue(!event.getFormattedMessage().contains("敏感密码"));
        assertEquals(null, event.getThrowableProxy());
    }

    @Test
    void 协议校验失败后可复用同一Client重试并在关闭时释放() throws Exception {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        AtomicInteger verifierCalls = new AtomicInteger();
        MilvusEmbeddingStoreFactory retryableFactory = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> {
                    if (verifierCalls.getAndIncrement() == 0) {
                        throw new IllegalStateException("协议校验失败");
                    }
                }, ignored -> {
                    providerCalls.incrementAndGet();
                    return client;
                }, (builtClient, collectionName, dimension) -> delegate);

        assertThrows(IllegalStateException.class, () -> retryableFactory.create("templates_vue"));
        assertNotNull(retryableFactory.create("templates_vue"));
        retryableFactory.close();

        assertEquals(1, providerCalls.get());
        assertEquals(2, verifierCalls.get());
        verify(client).close(5L);
    }

    @Test
    void Store构建失败后可复用同一Client重试并在关闭时释放() throws Exception {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        AtomicInteger builderCalls = new AtomicInteger();
        MilvusEmbeddingStoreFactory retryableFactory = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> {
                }, ignored -> {
                    providerCalls.incrementAndGet();
                    return client;
                }, (builtClient, collectionName, dimension) -> {
                    if (builderCalls.getAndIncrement() == 0) {
                        throw new IllegalStateException("Store 构建失败");
                    }
                    return delegate;
                });

        assertThrows(IllegalStateException.class, () -> retryableFactory.create("templates_vue"));
        assertNotNull(retryableFactory.create("templates_vue"));
        retryableFactory.close();

        assertEquals(1, providerCalls.get());
        assertEquals(2, builderCalls.get());
        verify(client).close(5L);
    }

    @Test
    void VueBm25创建前先Provision并在工厂关闭时释放V2Client() throws Exception {
        MilvusV2ClientProvider v2Provider = mock(MilvusV2ClientProvider.class);
        MilvusVueBm25CollectionProvisioner provisioner = mock(MilvusVueBm25CollectionProvisioner.class);
        MilvusClientV2 v2Client = mock(MilvusClientV2.class);
        when(v2Provider.getClient()).thenReturn(v2Client);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        MilvusEmbeddingStoreFactory bm25Factory = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> events.add("校验:" + collectionName),
                ignored -> client, (builtClient, collectionName, dimension) -> delegate,
                v2Provider, provisioner);

        bm25Factory.create(RagConstants.VUE_BM25_COLLECTION);
        bm25Factory.close();

        verify(provisioner).ensureCollection(v2Client, 1024);
        verify(v2Provider).getClient();
        verify(v2Provider).close();
    }

    @Test
    void VueBm25Store显式写入使用V2客户端() {
        MilvusV2ClientProvider v2Provider = mock(MilvusV2ClientProvider.class);
        MilvusVueBm25CollectionProvisioner provisioner = mock(MilvusVueBm25CollectionProvisioner.class);
        MilvusClientV2 v2Client = mock(MilvusClientV2.class);
        when(v2Provider.getClient()).thenReturn(v2Client);
        when(v2Client.upsert(any(UpsertReq.class))).thenReturn(UpsertResp.builder().upsertCnt(1).build());
        when(client.hasCollection(any())).thenReturn(R.success(true));
        MilvusEmbeddingStoreFactory bm25Factory = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> { },
                ignored -> client, (builtClient, collectionName, dimension) -> delegate,
                v2Provider, provisioner);

        @SuppressWarnings("unchecked")
        var store = (dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>)
                bm25Factory.create(RagConstants.VUE_BM25_COLLECTION);
        store.addAll(List.of("固定标识"), List.of(Embedding.from(new float[]{0.1F})),
                List.of(TextSegment.from("片段")));

        verify(v2Client).upsert(any(UpsertReq.class));
        verify(client, never()).upsert(any());
        bm25Factory.close();
    }

    @Test
    void HTML和旧兼容Collection创建时不触发BM25Provision() {
        MilvusV2ClientProvider v2Provider = mock(MilvusV2ClientProvider.class);
        MilvusVueBm25CollectionProvisioner provisioner = mock(MilvusVueBm25CollectionProvisioner.class);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        MilvusEmbeddingStoreFactory denseFactory = new MilvusEmbeddingStoreFactory(
                properties(), (verifiedClient, collectionName, dimension) -> { },
                ignored -> client, (builtClient, collectionName, dimension) -> delegate,
                v2Provider, provisioner);

        denseFactory.create("templates_html");

        verifyNoInteractions(v2Provider, provisioner);
        denseFactory.close();
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setDimension(1024);
        return properties;
    }
}
