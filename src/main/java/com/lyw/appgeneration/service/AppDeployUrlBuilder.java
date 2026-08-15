package com.lyw.appgeneration.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.lyw.appgeneration.config.AppCodeDeployProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/** 以当前运行环境配置为唯一来源生成部署产物访问地址。 */
@Component
public final class AppDeployUrlBuilder {

    private final String baseUrl;
    private final String cacheNamespace;

    public AppDeployUrlBuilder(AppCodeDeployProperties properties) {
        baseUrl = properties.getBaseUrl();
        cacheNamespace = DigestUtil.md5Hex(baseUrl);
    }

    public String buildUrl(String deployKey) {
        if (StrUtil.isBlank(deployKey)) {
            return null;
        }
        String encodedKey = UriUtils.encodePathSegment(
                deployKey, StandardCharsets.UTF_8);
        return baseUrl + "/" + encodedKey + "/";
    }

    /** 隔离不同部署基地址下的响应缓存。 */
    public String cacheNamespace() {
        return cacheNamespace;
    }
}
