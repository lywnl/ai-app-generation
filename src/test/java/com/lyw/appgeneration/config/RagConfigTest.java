package com.lyw.appgeneration.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagConfigTest {

    @Test
    void disabledRagReturnsEmptyStoresWithoutTryingPgVector() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        properties.getPgvector().setHost("127.0.0.1");
        properties.getPgvector().setPort(1);
        Logger logger = (Logger) LoggerFactory.getLogger(RagConfig.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);

        try {
            assertTrue(new RagConfig(properties).embeddingStoreByType().isEmpty());
            assertTrue(events.list.isEmpty(),
                    "RAG 关闭时不得尝试连接 PGVector 后再降级");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
