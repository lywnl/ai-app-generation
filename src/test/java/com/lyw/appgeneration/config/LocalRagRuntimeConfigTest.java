package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRagRuntimeConfigTest {

    private static final List<String> NO_PROXY_JVM_ARGUMENTS = List.of(
            "-Djava.net.useSystemProxies=false",
            "-Dhttp.proxyHost=",
            "-Dhttp.proxyPort=",
            "-Dhttps.proxyHost=",
            "-Dhttps.proxyPort=",
            "-DsocksProxyHost=",
            "-DsocksProxyPort=");

    @Test
    void springBoot应用进程必须显式禁用系统代理() throws Exception {
        NodeList plugins;
        try (InputStream input = Files.newInputStream(projectRoot().resolve("pom.xml"))) {
            plugins = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(input)
                    .getElementsByTagName("plugin");
        }

        Element springBootPlugin = findSpringBootPlugin(plugins);
        NodeList jvmArguments = springBootPlugin.getElementsByTagName("jvmArguments");

        assertEquals(1, jvmArguments.getLength(),
                "Spring Boot Maven 插件必须为实际应用 JVM 配置 jvmArguments");
        List<String> actualArguments = jvmArguments.item(0).getTextContent().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        assertEquals(NO_PROXY_JVM_ARGUMENTS, actualArguments,
                "应用 JVM 必须完整禁用并清空 HTTP、HTTPS 与 SOCKS 代理");
    }

    @Test
    void Rag模板目录必须支持环境变量覆盖且默认使用工作区相对路径() throws IOException {
        String applicationYaml = readProjectFile("src/main/resources/application.yml");
        Object configuration = new Yaml().load(applicationYaml);

        assertTrue(configuration instanceof Map<?, ?>, "application.yml 根节点必须是映射");
        Object rag = ((Map<?, ?>) configuration).get("rag");
        assertTrue(rag instanceof Map<?, ?>, "application.yml 必须包含 rag 配置");
        assertEquals("${RAG_TEMPLATES_DIR:embed_text}",
                ((Map<?, ?>) rag).get("templates-dir"),
                "RAG 模板目录不得写死为特定操作系统的绝对路径");
    }

    private Element findSpringBootPlugin(NodeList plugins) {
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            NodeList artifactIds = plugin.getElementsByTagName("artifactId");
            if (artifactIds.getLength() > 0
                    && "spring-boot-maven-plugin".equals(artifactIds.item(0).getTextContent())) {
                return plugin;
            }
        }
        throw new AssertionError("pom.xml 缺少 spring-boot-maven-plugin");
    }

    private String readProjectFile(String relativePath) throws IOException {
        return Files.readString(projectRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path projectRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到包含 pom.xml 的项目根目录");
    }
}
