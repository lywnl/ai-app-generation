package com.lyw.appgeneration.config;

import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.ToString;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** L2 偏好抽取专用模型，限制单次等待且禁用 SDK 自动重试。 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
@Data
public class UserMemoryExtractionChatModelConfig {

    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);
    private static final int SDK_MAX_RETRIES = 0;

    private String baseUrl;

    @ToString.Exclude
    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    @Bean(name = "userMemoryExtractionChatModel")
    public ChatModel userMemoryExtractionChatModel(
            @Qualifier("openAiChatModelHttpClientBuilder")
            ObjectProvider<HttpClientBuilder> httpClientBuilderProvider) {
        HttpClientBuilder httpClientBuilder =
                httpClientBuilderProvider.getIfAvailable();
        if (httpClientBuilder == null) {
            httpClientBuilder = SpringRestClient.builder();
        }
        return OpenAiChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(MODEL_TIMEOUT)
                .maxRetries(SDK_MAX_RETRIES)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
