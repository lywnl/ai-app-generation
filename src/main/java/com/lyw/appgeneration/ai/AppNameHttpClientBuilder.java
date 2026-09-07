package com.lyw.appgeneration.ai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/** 保留显式禁用重试的命名传输，避免默认 Spring 适配器重新选择请求工厂。 */
public final class AppNameHttpClientBuilder implements HttpClientBuilder {
    private final CloseableHttpClient transport;
    private Duration connectTimeout;
    private Duration readTimeout;

    public AppNameHttpClientBuilder(CloseableHttpClient transport) {
        this.transport = transport;
    }

    @Override
    public Duration connectTimeout() { return connectTimeout; }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        connectTimeout = timeout;
        return this;
    }

    @Override
    public Duration readTimeout() { return readTimeout; }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        readTimeout = timeout;
        return this;
    }

    @Override
    public HttpClient build() {
        var factory = new HttpComponentsClientHttpRequestFactory(transport);
        factory.setConnectTimeout(connectTimeout);
        factory.setConnectionRequestTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return new HttpClient() {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                try {
                    var call = client.method(HttpMethod.valueOf(request.method().name()))
                            .uri(request.url()).headers(headers -> headers.putAll(request.headers()));
                    if (request.body() != null) {
                        call.body(request.body());
                    }
                    var response = call.retrieve().toEntity(String.class);
                    return SuccessfulHttpResponse.builder().statusCode(response.getStatusCode().value())
                            .headers(response.getHeaders()).body(response.getBody()).build();
                } catch (RestClientResponseException exception) {
                    throw new HttpException(exception.getStatusCode().value(), "应用命名 HTTP 请求失败");
                }
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                throw new UnsupportedOperationException("应用命名不支持流式请求");
            }
        };
    }
}
