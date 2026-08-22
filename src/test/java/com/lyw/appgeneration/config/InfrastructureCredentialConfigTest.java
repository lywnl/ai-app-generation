package com.lyw.appgeneration.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureCredentialConfigTest {

    private static final String SHARED_PASSWORD_VARIABLE =
            "INFRA_SHARED_PASSWORD";
    private static final String REQUIRED_SHARED_PASSWORD =
            "${INFRA_SHARED_PASSWORD:?INFRA_SHARED_PASSWORD不能为空}";
    private static final String REQUIRED_MINIO_PASSWORD =
            "${MILVUS_MINIO_PASSWORD:?MILVUS_MINIO_PASSWORD不能为空}";

    @Test
    void 应用和本地Milvus分别读取共享与对象存储密码环境变量() throws IOException {
        String application = readProjectFile(
                "src/main/resources/application.yml");
        String ragProperties = readProjectFile(
                "src/main/java/com/lyw/appgeneration/config/RagProperties.java");
        String milvusCompose = readProjectFile("docker/milvus.yml");

        assertEquals(2, occurrences(application,
                "password: ${" + SHARED_PASSWORD_VARIABLE + "}"));
        assertTrue(application.contains(
                "password: ${RAG_MILVUS_PASSWORD:${INFRA_SHARED_PASSWORD}}"));
        assertTrue(ragProperties.contains("private String password;"));
        assertTrue(milvusCompose.contains(
                "MINIO_ROOT_PASSWORD: " + REQUIRED_MINIO_PASSWORD));
        assertTrue(milvusCompose.contains(
                "MINIO_SECRET_ACCESS_KEY: " + REQUIRED_MINIO_PASSWORD));
        assertTrue(milvusCompose.contains(
                "COMMON_SECURITY_DEFAULTROOTPASSWORD: "
                        + REQUIRED_SHARED_PASSWORD));
        assertTrue(serviceBlock(milvusCompose, "milvus").contains(
                "QUOTAANDLIMITS_FLUSHRATE_COLLECTION_MAX: \"-1\""));
        assertFalse(milvusCompose.contains(
                "MINIO_ROOT_PASSWORD: " + REQUIRED_SHARED_PASSWORD));
        assertFalse(milvusCompose.contains(
                "MINIO_SECRET_ACCESS_KEY: " + REQUIRED_SHARED_PASSWORD));
    }

    @Test
    void 本地Milvus使用固定版本鉴权健康检查和仅本机端口发布() throws IOException {
        String compose = readProjectFile("docker/milvus.yml");

        assertTrue(compose.contains("image: milvusdb/milvus:v2.5.9"));
        assertTrue(compose.contains("image: quay.io/coreos/etcd:v3.5.18"));
        assertTrue(compose.contains(
                "image: minio/minio:RELEASE.2023-03-20T20-16-18Z"));
        assertTrue(compose.contains(
                "COMMON_SECURITY_AUTHORIZATIONENABLED: \"true\""));
        assertEquals(1, occurrences(compose, "    ports:"));
        assertTrue(compose.contains("- \"127.0.0.1:19530:19530\""));
        assertTrue(compose.contains("- \"127.0.0.1:9091:9091\""));
        assertFalse(compose.contains("2379:2379"));
        assertFalse(compose.contains("9000:9000"));
        assertFalse(compose.contains("9001:9001"));
        assertTrue(compose.contains("test: [\"CMD\", \"etcdctl\", \"endpoint\", \"health\"]"));
        assertTrue(compose.contains(
                "http://localhost:9000/minio/health/live"));
        assertTrue(compose.contains("http://localhost:9091/healthz"));
        assertTrue(compose.contains("milvus_etcd_data:"));
        assertTrue(compose.contains("milvus_minio_data:"));
        assertTrue(compose.contains("milvus_data:"));
    }

    @Test
    void 生产Compose使用共享密码和独立MinIO密码注入基础设施() throws IOException {
        String compose = readProjectFile("prod/docker-compose.yml");

        List<String> passwordMappings = List.of(
                "INFRA_SHARED_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "MYSQL_ROOT_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "MYSQL_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "COMMON_SECURITY_DEFAULTROOTPASSWORD: "
                        + REQUIRED_SHARED_PASSWORD,
                "GF_SECURITY_ADMIN_PASSWORD: " + REQUIRED_SHARED_PASSWORD);
        for (String mapping : passwordMappings) {
            assertTrue(compose.contains(mapping), "缺少统一密码映射: " + mapping);
        }
        assertTrue(compose.contains(
                "MINIO_ROOT_PASSWORD: " + REQUIRED_MINIO_PASSWORD));
        assertTrue(compose.contains(
                "MINIO_SECRET_ACCESS_KEY: " + REQUIRED_MINIO_PASSWORD));
        assertTrue(serviceBlock(compose, "milvus").contains(
                "QUOTAANDLIMITS_FLUSHRATE_COLLECTION_MAX: \"-1\""));

        assertFalse(compose.contains(
                "MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}"));
        assertFalse(compose.contains(
                "MYSQL_PASSWORD: ${MYSQL_PASSWORD}"));
        assertFalse(compose.contains(
                "SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}"));
        assertFalse(compose.contains(
                "MINIO_ROOT_PASSWORD: " + REQUIRED_SHARED_PASSWORD));
        assertFalse(compose.contains(
                "MINIO_SECRET_ACCESS_KEY: " + REQUIRED_SHARED_PASSWORD));
        assertFalse(compose.contains("MINIO_ROOT_PASSWORD: ${MINIO_PASSWORD}"));
        assertFalse(compose.contains(
                "GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}"));
        assertTrue(compose.contains(
                "MYSQL_PWD=$${MYSQL_ROOT_PASSWORD} mysqladmin ping -uroot --silent"));
        assertTrue(compose.contains(
                "REDISCLI_AUTH=$${INFRA_SHARED_PASSWORD} redis-cli --user "
                        + "$${REDIS_USERNAME} ping"));
    }

    @Test
    void Redis启动时动态生成Acl且仓库不再保存明文Acl() throws IOException {
        Path staticAcl = projectRoot().resolve("prod/redis/users.acl");
        String startupScript = readProjectFile("prod/redis/start-redis.sh");
        String compose = readProjectFile("prod/docker-compose.yml");

        assertFalse(Files.exists(staticAcl), "生产仓库不得保留带密码的静态 ACL");
        assertTrue(startupScript.contains("${REDIS_USERNAME}"));
        assertTrue(startupScript.contains("REDIS_USERNAME只能包含"));
        assertTrue(startupScript.contains("${INFRA_SHARED_PASSWORD}"));
        assertTrue(startupScript.contains("/tmp/users.acl"));
        assertTrue(startupScript.contains("exec redis-server"));
        assertTrue(compose.contains(
                "./redis/start-redis.sh:/usr/local/bin/start-redis.sh:ro"));
        assertFalse(compose.contains("./redis/users.acl:"));
    }

    @Test
    void 生产环境模板声明共享密码和独立MinIO密码且真实文件被忽略() throws IOException {
        String environmentExample = readProjectFile("prod/.env.example");
        String gitignore = readProjectFile(".gitignore");

        assertEquals(List.of(SHARED_PASSWORD_VARIABLE + "="),
                matchingLines(environmentExample, SHARED_PASSWORD_VARIABLE));
        assertEquals(List.of("MILVUS_MINIO_PASSWORD="),
                matchingLines(environmentExample, "MILVUS_MINIO_PASSWORD"));
        assertFalse(environmentExample.contains("MYSQL_ROOT_PASSWORD="));
        assertFalse(environmentExample.contains("MYSQL_PASSWORD="));
        assertFalse(environmentExample.contains("REDIS_PASSWORD="));
        assertFalse(environmentExample.contains("PG_" + "PASSWORD="));
        assertFalse(environmentExample.contains("PG_" + "USER="));
        assertFalse(environmentExample.contains("GRAFANA_ADMIN_PASSWORD="));
        assertTrue(gitignore.lines()
                .map(String::strip)
                .anyMatch("/prod/.env"::equals));
    }

    private List<String> matchingLines(String content, String variable) {
        return content.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(variable + "="))
                .toList();
    }

    private String serviceBlock(String compose, String serviceName) {
        String serviceStart = "\n  " + serviceName + ":\n";
        int startIndex = compose.indexOf(serviceStart);
        assertTrue(startIndex >= 0, "缺少服务: " + serviceName);
        int endIndex = compose.length();
        for (int index = startIndex + serviceStart.length(); index < compose.length() - 3; index++) {
            if (compose.charAt(index) == '\n'
                    && compose.charAt(index + 1) == ' '
                    && compose.charAt(index + 2) == ' '
                    && compose.charAt(index + 3) != ' ') {
                endIndex = index;
                break;
            }
        }
        return compose.substring(startIndex, endIndex);
    }

    private int occurrences(String content, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = content.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private String readProjectFile(String relativePath) throws IOException {
        return Files.readString(projectRoot().resolve(relativePath),
                StandardCharsets.UTF_8);
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
