package com.lyw.appgeneration.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 跨域配置
 *
 * @author lyw
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String API_PATH_PATTERN = "/**";

    private final AppCorsProperties properties;

    public CorsConfig(AppCorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsConfiguration cors = generationCorsConfiguration();
        if (cors == null) {
            return;
        }
        registry.addMapping(corsPathPattern())
                .combine(cors);
    }

    CorsConfiguration generationCorsConfiguration() {
        List<String> origins = properties.getAllowedOrigins();
        if (origins.isEmpty()) {
            return null;
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(),
                HttpMethod.PUT.name(), HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));
        cors.setAllowedHeaders(List.of(
                HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        return cors;
    }

    String corsPathPattern() {
        return API_PATH_PATTERN;
    }
}
