package com.lyw.appgeneration.config;

import com.lyw.appgeneration.ai.AiAppNameService;
import com.lyw.appgeneration.ai.AppNameHttpClientBuilder;
import com.lyw.appgeneration.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.Data;
import lombok.ToString;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** 命名使用独立的短超时和输出预算，不影响路由或生成模型。 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.app-name-chat-model")
@Data
public class AppNameAiConfig {
    private String baseUrl;
    @ToString.Exclude
    private String apiKey;
    private String modelName;
    private Duration timeout = Duration.ofSeconds(5);
    private Integer maxTokens = 256;
    private Double temperature = 0.0;

    @Bean(destroyMethod = "close")
    public CloseableHttpClient appNameTransport() {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("应用命名超时必须为正数");
        }
        Timeout limit = Timeout.of(timeout);
        // 响应超时不覆盖 TLS 握手，必须在连接管理器中单独限定。
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(limit).setSocketTimeout(limit).build())
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(limit).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections)
                .disableAutomaticRetries().disableRedirectHandling().build();
    }

    @Bean
    public ChatModel appNameChatModel(
            @Qualifier("appNameTransport") CloseableHttpClient transport,
            AiModelMonitorListener listener) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(new AppNameHttpClientBuilder(transport))
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .listeners(List.of(listener))
                .build();
    }

    @Bean
    public AiAppNameService aiAppNameService(@Qualifier("appNameChatModel") ChatModel model) {
        return AiServices.builder(AiAppNameService.class).chatModel(model).build();
    }
}
