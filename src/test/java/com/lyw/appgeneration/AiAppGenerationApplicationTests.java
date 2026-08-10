package com.lyw.appgeneration;

import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.image.ImageCollectionPlanService;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.mapper.UserMapper;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.MemorySummaryService;
import com.lyw.appgeneration.service.ProjectDownloadService;
import com.lyw.appgeneration.service.ScreenshotService;
import com.lyw.appgeneration.service.UserMemoryService;
import com.lyw.appgeneration.service.UserService;
import com.lyw.appgeneration.service.rag.RagRetrievalService;
import com.lyw.appgeneration.service.rag.RagRerankService;
import com.qcloud.cos.COSClient;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.redisson.api.RedissonClient;

import java.util.Map;

/**
 * 默认上下文冒烟测试保留生产组件扫描，但在测试层替换所有会建立外部连接的边界。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration,"
                + "com.mybatisflex.spring.boot.FlexTransactionAutoConfiguration,"
                + "com.mybatisflex.spring.boot.MultiDataSourceAutoConfiguration,"
                + "com.mybatisflex.spring.boot.MybatisFlexAutoConfiguration",
        "DEEPSEEK_API_KEY=test-placeholder",
        "DASHSCOPE_API_KEY=test-placeholder",
        "PEXELS_API_KEY=test-placeholder",
        "COS_HOST=test-host",
        "TEN_SERCET_ID=test-id",
        "TEN_SECRET_KEY=test-key"
})
class AiAppGenerationApplicationTests {

    @MockitoBean(name = "openAiChatModel")
    private ChatModel chatModel;

    @MockitoBean(name = "redisChatMemoryStore")
    private RedisChatMemoryStore redisChatMemoryStore;

    @MockitoBean(name = "redissonClient")
    private RedissonClient redissonClient;

    @MockitoBean(name = "cosClient")
    private COSClient cosClient;

    @MockitoBean(name = "ragEmbeddingModel")
    private EmbeddingModel embeddingModel;

    @MockitoBean(name = "embeddingStoreByType")
    private Map<?, ? extends EmbeddingStore<?>> embeddingStoreByType;

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    private AppMapper appMapper;

    @MockitoBean
    private AppMemoryExtractCursorMapper appMemoryExtractCursorMapper;

    @MockitoBean
    private AppMemoryMapper appMemoryMapper;

    @MockitoBean
    private AppMemorySummaryMapper appMemorySummaryMapper;

    @MockitoBean
    private ChatHistoryMapper chatHistoryMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private AppService appService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ChatHistoryService chatHistoryService;

    @MockitoBean
    private ProjectDownloadService projectDownloadService;

    @MockitoBean
    private ScreenshotService screenshotService;

    @MockitoBean
    private MemorySummaryService memorySummaryService;

    @MockitoBean
    private UserMemoryService userMemoryService;

    @MockitoBean
    private RagRetrievalService ragRetrievalService;

    @MockitoBean
    private RagRerankService ragRerankService;

    @MockitoBean
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @MockitoBean
    private AiCodeGeneratorService aiCodeGeneratorService;

    @MockitoBean
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @MockitoBean
    private ImageCollectionPlanService imageCollectionPlanService;

    @Test
    void contextLoads() {
    }

}
