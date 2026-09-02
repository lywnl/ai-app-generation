package com.lyw.appgeneration.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lyw.appgeneration.service.rag.store.MilvusCollectionSchemaVerifier;
import com.lyw.appgeneration.service.rag.store.MilvusEmbeddingStoreFactory;
import com.lyw.appgeneration.constants.RagConstants;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagConfigTest {

    @Test
    void 关闭Rag时不创建Milvus客户端() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        properties.getMilvus().setHost("127.0.0.1");
        properties.getMilvus().setPort(1);
        Logger logger = (Logger) LoggerFactory.getLogger(RagConfig.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);

        try {
            assertTrue(new RagConfig(properties, new MilvusEmbeddingStoreFactory(
                    properties, new MilvusCollectionSchemaVerifier())).embeddingStoreByType().isEmpty());
            assertTrue(events.list.isEmpty(),
                    "RAG 关闭时不得尝试连接 Milvus 后再降级");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void 单个Collection初始化失败时仅降级对应类型() {
        RagProperties properties = new RagProperties();
        MilvusEmbeddingStoreFactory factory = mock(MilvusEmbeddingStoreFactory.class);
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(factory.create("templates_html")).thenReturn(store);
        when(factory.create("templates_multi")).thenThrow(new IllegalStateException("schema 不匹配"));
        when(factory.create(RagConstants.VUE_BM25_COLLECTION)).thenReturn(store);

        var stores = new RagConfig(properties, factory).embeddingStoreByType();

        assertEquals(2, stores.size());
        assertTrue(stores.containsKey(CodeGenTypeEnum.HTML));
        assertTrue(stores.containsKey(CodeGenTypeEnum.VUE_PROJECT));
        assertTrue(!stores.containsKey(CodeGenTypeEnum.MULTI_FILE));
    }
}
