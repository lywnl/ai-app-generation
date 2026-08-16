package com.lyw.appgeneration.config;

import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** L1 摘要专用模型，复用主模型参数但禁止 SDK 在截止时间后自动重试。 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
@Data
public class MemorySummaryChatModelConfig {

    private static final int SDK_MAX_RETRIES = 0;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private Duration timeout;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    @Bean(name = "memorySummaryChatModel")
    public ChatModel memorySummaryChatModel(
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
                .timeout(timeout)
                .maxRetries(SDK_MAX_RETRIES)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
