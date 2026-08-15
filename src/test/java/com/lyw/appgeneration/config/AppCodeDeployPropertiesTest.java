package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppCodeDeployPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void 部署基地址必须由运行环境显式提供() {
        AppCodeDeployProperties properties = new AppCodeDeployProperties();

        assertThrows(IllegalStateException.class,
                properties::afterPropertiesSet);
    }

    @Test
    void 合法地址必须规范化主机大小写和尾部斜杠() {
        contextRunner.withPropertyValues(
                        "app.code.deploy.base-url= HTTPS://Example.COM/ ")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("https://example.com",
                            context.getBean(AppCodeDeployProperties.class)
                                    .getBaseUrl());
                });
    }

    @Test
    void 根地址端口和IPv6必须保持可访问语义() {
        assertNormalized("http://localhost", "http://localhost");
        assertNormalized("http://localhost:8080/", "http://localhost:8080");
        assertNormalized("http://[2001:DB8::1]:8080/",
                "http://[2001:db8::1]:8080");
    }

    @Test
    void 非法或带有非路径URL部件的地址必须让启动失败() {
        assertStartupFails("app.code.deploy.base-url=/relative");
        assertStartupFails("app.code.deploy.base-url=ftp://example.com");
        assertStartupFails(
                "app.code.deploy.base-url=https://user@example.com");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com/apps");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com/%E4%B8%AD%E6%96%87/");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com/apps?from=test");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com/apps#section");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com:");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com:0");
        assertStartupFails(
                "app.code.deploy.base-url=https://example.com:65536");
    }

    private void assertNormalized(String value, String expected) {
        contextRunner.withPropertyValues(
                        "app.code.deploy.base-url=" + value)
                .run(context -> {
                    assertNull(context.getStartupFailure(), value);
                    assertEquals(expected,
                            context.getBean(AppCodeDeployProperties.class)
                                    .getBaseUrl());
                });
    }

    private void assertStartupFails(String property) {
        contextRunner.withPropertyValues(property).run(context ->
                assertNotNull(context.getStartupFailure(), property));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppCodeDeployProperties.class)
    static class PropertiesConfiguration {
    }
}
