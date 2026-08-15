package com.lyw.appgeneration.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 绑定并校验应用部署产物的外部访问基地址。 */
@Component
@ConfigurationProperties(prefix = "app.code.deploy")
public final class AppCodeDeployProperties implements InitializingBean {

    private String baseUrl;

    @Override
    public void afterPropertiesSet() {
        baseUrl = normalizeBaseUrl(baseUrl);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw invalidBaseUrl();
        }
        URI uri;
        try {
            uri = new URI(value.trim()).normalize();
        } catch (URISyntaxException exception) {
            throw invalidBaseUrl();
        }
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean valid = ("http".equals(scheme) || "https".equals(scheme))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && hasNoBusinessPath(uri)
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && hasValidPort(uri);
        if (!valid) {
            throw invalidBaseUrl();
        }
        String authority = uri.getRawAuthority().toLowerCase(Locale.ROOT);
        return scheme + "://" + authority;
    }

    private boolean hasNoBusinessPath(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() || "/".equals(path);
    }

    private boolean hasValidPort(URI uri) {
        String authority = uri.getRawAuthority();
        int portSeparator = authority.lastIndexOf(':');
        if (portSeparator < 0
                || authority.endsWith("]")) {
            return true;
        }
        int port = uri.getPort();
        return port >= 1 && port <= 65535;
    }

    private IllegalStateException invalidBaseUrl() {
        return new IllegalStateException(
                "app.code.deploy.base-url 必须显式配置为不含路径的合法 HTTP 或 HTTPS 地址");
    }
}
