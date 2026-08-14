package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCorsConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void 默认空列表只允许同源且不注册跨域配置() {
        contextRunner.withPropertyValues("app.cors.allowed-origins=")
                .run(context -> {
            assertNull(context.getStartupFailure());
            AppCorsProperties properties = context.getBean(
                    AppCorsProperties.class);
            assertTrue(properties.getAllowedOrigins().isEmpty());
            assertNull(new CorsConfig(properties)
                    .generationCorsConfiguration());
                });
    }

    @Test
    void Spring绑定合法来源后规范化去重并只允许CookiePostJson() {
        contextRunner.withPropertyValues(
                        "app.cors.allowed-origins[0]=http://localhost:5173",
                        "app.cors.allowed-origins[1]=http://localhost:5173",
                        "app.cors.allowed-origins[2]=http://127.0.0.1:5173",
                        "app.cors.allowed-origins[3]= https://APP.example.com ")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AppCorsProperties properties = context.getBean(
                            AppCorsProperties.class);
                    assertEquals(List.of(
                                    "http://localhost:5173",
                                    "http://127.0.0.1:5173",
                                    "https://app.example.com"),
                            properties.getAllowedOrigins());
                    CorsConfiguration cors = new CorsConfig(properties)
                            .generationCorsConfiguration();
                    assertNotNull(cors);
                    assertEquals(Boolean.TRUE, cors.getAllowCredentials());
                    assertEquals(List.of(
                                    "GET", "POST", "PUT", "DELETE", "OPTIONS"),
                            cors.getAllowedMethods());
                    assertEquals(List.of("Content-Type", "Accept"),
                            cors.getAllowedHeaders());
                    assertEquals("http://localhost:5173",
                            cors.checkOrigin("http://localhost:5173"));
                    assertNull(cors.checkOrigin("https://evil.example"));
                    assertNull(cors.checkOrigin("null"));
                });
    }

    @Test
    void 显式跨域部署必须覆盖现有Api而非只覆盖生成入口()
            throws Exception {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of("https://APP.Example.com"));
        properties.afterPropertiesSet();
        CorsConfig config = new CorsConfig(properties);

        assertEquals(List.of("https://app.example.com"),
                properties.getAllowedOrigins());
        assertEquals("/**", config.corsPathPattern());
    }

    @Test
    void 可信来源白名单外的预检请求在业务处理前失败() throws Exception {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of("http://localhost:5173"));
        properties.afterPropertiesSet();
        CorsConfiguration cors = new CorsConfig(properties)
                .generationCorsConfiguration();
        CorsProcessor processor = new DefaultCorsProcessor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("OPTIONS");
        request.addHeader("Origin", "https://evil.example");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers",
                "Content-Type,Accept");

        assertEquals(false, processor.processRequest(
                cors, request,
                new org.springframework.mock.web.MockHttpServletResponse()));
    }

    @Test
    void 真实Cors过滤链只让可信开发来源进入后续处理() throws Exception {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of("http://localhost:5173"));
        properties.afterPropertiesSet();
        CorsConfiguration cors = new CorsConfig(properties)
                .generationCorsConfiguration();
        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        CorsFilter filter = new CorsFilter(source);

        MockFilterChain allowed = new MockFilterChain();
        MockHttpServletResponse allowedResponse =
                new MockHttpServletResponse();
        filter.doFilter(preflight("http://localhost:5173"),
                allowedResponse, allowed);
        assertEquals(200, allowedResponse.getStatus());
        assertEquals("http://localhost:5173",
                allowedResponse.getHeader("Access-Control-Allow-Origin"));

        MockFilterChain unknown = new MockFilterChain();
        MockHttpServletResponse unknownResponse =
                new MockHttpServletResponse();
        filter.doFilter(preflight("https://evil.example"),
                unknownResponse, unknown);
        assertEquals(403, unknownResponse.getStatus());
        assertNull(unknown.getRequest());

        MockFilterChain nullOrigin = new MockFilterChain();
        MockHttpServletResponse nullResponse =
                new MockHttpServletResponse();
        filter.doFilter(preflight("null"), nullResponse, nullOrigin);
        assertEquals(403, nullResponse.getStatus());
        assertNull(nullOrigin.getRequest());
    }

    @Test
    void 非法来源必须让真实Spring上下文启动失败() {
        assertStartupFails("app.cors.allowed-origins[0]=*");
        assertStartupFails("app.cors.allowed-origins[0]=null");
        assertStartupFails(
                "app.cors.allowed-origins[0]=http://example.com");
        assertStartupFails(
                "app.cors.allowed-origins[0]=https://user@example.com");
        assertStartupFails(
                "app.cors.allowed-origins[0]=https://example.com/path");
        assertStartupFails(
                "app.cors.allowed-origins[0]=https://example.com?x=1");
        assertStartupFails(
                "app.cors.allowed-origins[0]=https://example.com#fragment");
    }

    private void assertStartupFails(String property) {
        contextRunner.withPropertyValues(property).run(context ->
                assertNotNull(context.getStartupFailure(), property));
    }

    private MockHttpServletRequest preflight(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/app/chat/gen/code");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers",
                "Content-Type,Accept");
        return request;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppCorsProperties.class)
    static class PropertiesConfiguration {
    }
}
