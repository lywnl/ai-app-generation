package com.lyw.appgeneration.service;

import com.lyw.appgeneration.config.AppCodeDeployProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppDeployUrlBuilderTest {

    @Test
    void 使用配置基地址生成带结尾斜杠的完整部署地址() {
        AppDeployUrlBuilder builder = builder("https://example.com/");

        assertEquals("https://example.com/deploy7/",
                builder.buildUrl("deploy7"));
    }

    @Test
    void 部署标识必须按单个URL路径段编码() {
        AppDeployUrlBuilder builder = builder("http://localhost");

        assertEquals("http://localhost/key%20with%20space/",
                builder.buildUrl("key with space"));
    }

    @Test
    void 未部署应用不生成访问地址() {
        AppDeployUrlBuilder builder = builder("http://localhost");

        assertNull(builder.buildUrl(null));
        assertNull(builder.buildUrl("  "));
    }

    private AppDeployUrlBuilder builder(String baseUrl) {
        AppCodeDeployProperties properties = new AppCodeDeployProperties();
        properties.setBaseUrl(baseUrl);
        properties.afterPropertiesSet();
        return new AppDeployUrlBuilder(properties);
    }
}
