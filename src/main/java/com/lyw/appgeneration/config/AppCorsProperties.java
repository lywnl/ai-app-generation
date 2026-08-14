package com.lyw.appgeneration.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 只绑定显式可信 Origin；空列表表示部署为同源。 */
@Component
@ConfigurationProperties(prefix = "app.cors")
public final class AppCorsProperties implements InitializingBean {

    private List<String> allowedOrigins = new ArrayList<>();

    @Override
    public void afterPropertiesSet() {
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            validated.add(validateOrigin(origin));
        }
        allowedOrigins = List.copyOf(validated);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null
                ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    private String validateOrigin(String origin) {
        if (origin == null) {
            throw invalidOrigin(origin);
        }
        String candidate = origin.trim();
        if (candidate.isEmpty() || "*".equals(candidate)
                || "null".equalsIgnoreCase(candidate)) {
            throw invalidOrigin(origin);
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException exception) {
            throw invalidOrigin(origin);
        }
        String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean hasOnlyOriginParts = uri.isAbsolute()
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!hasOnlyOriginParts || !isTrustedSchemeHost(scheme, uri.getHost())) {
            throw invalidOrigin(origin);
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = uri.getHost().toLowerCase(Locale.ROOT);
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return normalizedScheme + "://" + normalizedHost + port;
    }

    private boolean isTrustedSchemeHost(String scheme, String host) {
        if ("https".equals(scheme)) {
            return true;
        }
        if (!"http".equals(scheme)) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host);
    }

    private IllegalStateException invalidOrigin(String origin) {
        return new IllegalStateException(
                "app.cors.allowed-origins 包含不可信来源: " + origin);
    }
}
