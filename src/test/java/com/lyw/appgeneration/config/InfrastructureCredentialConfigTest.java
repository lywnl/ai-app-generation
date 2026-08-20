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

    @Test
    void 应用和独立PgVector统一读取基础设施密码环境变量() throws IOException {
        String application = readProjectFile(
                "src/main/resources/application.yml");
        String ragProperties = readProjectFile(
                "src/main/java/com/lyw/appgeneration/config/RagProperties.java");
        String pgVectorCompose = readProjectFile("docker/pgvector.yml");

        assertEquals(3, occurrences(application,
                "password: ${" + SHARED_PASSWORD_VARIABLE + "}"));
        assertTrue(ragProperties.contains("private String password;"));
        assertTrue(pgVectorCompose.contains(
                "POSTGRES_PASSWORD: " + REQUIRED_SHARED_PASSWORD));
    }

    @Test
    void 生产Compose只从统一变量注入所有基础设施密码() throws IOException {
        String compose = readProjectFile("prod/docker-compose.yml");

        List<String> passwordMappings = List.of(
                "INFRA_SHARED_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "MYSQL_ROOT_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "MYSQL_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "POSTGRES_PASSWORD: " + REQUIRED_SHARED_PASSWORD,
                "GF_SECURITY_ADMIN_PASSWORD: " + REQUIRED_SHARED_PASSWORD);
        for (String mapping : passwordMappings) {
            assertTrue(compose.contains(mapping), "缺少统一密码映射: " + mapping);
        }

        assertFalse(compose.contains(
                "MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}"));
        assertFalse(compose.contains(
                "MYSQL_PASSWORD: ${MYSQL_PASSWORD}"));
        assertFalse(compose.contains(
                "SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}"));
        assertFalse(compose.contains("POSTGRES_PASSWORD: ${PG_PASSWORD}"));
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
    void 生产环境模板只声明统一密码且真实文件被忽略() throws IOException {
        String environmentExample = readProjectFile("prod/.env.example");
        String gitignore = readProjectFile(".gitignore");

        assertEquals(List.of(SHARED_PASSWORD_VARIABLE + "="),
                matchingLines(environmentExample, SHARED_PASSWORD_VARIABLE));
        assertFalse(environmentExample.contains("MYSQL_ROOT_PASSWORD="));
        assertFalse(environmentExample.contains("MYSQL_PASSWORD="));
        assertFalse(environmentExample.contains("REDIS_PASSWORD="));
        assertFalse(environmentExample.contains("PG_PASSWORD="));
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
