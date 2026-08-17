package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MemorySecretConfigToStringTest {

    private static final String SECRET_MARKER = "memory-secret-marker";

    @Test
    void 记忆相关模型与Redis配置不得通过toString泄露密钥() {
        MemorySummaryChatModelConfig summaryConfig =
                new MemorySummaryChatModelConfig();
        summaryConfig.setApiKey(SECRET_MARKER);
        UserMemoryExtractionChatModelConfig extractionConfig =
                new UserMemoryExtractionChatModelConfig();
        extractionConfig.setApiKey(SECRET_MARKER);
        RedisChatMemoryStoreConfig redisConfig =
                new RedisChatMemoryStoreConfig();
        redisConfig.setPassword(SECRET_MARKER);

        assertFalse(summaryConfig.toString().contains(SECRET_MARKER));
        assertFalse(extractionConfig.toString().contains(SECRET_MARKER));
        assertFalse(redisConfig.toString().contains(SECRET_MARKER));
    }
}
