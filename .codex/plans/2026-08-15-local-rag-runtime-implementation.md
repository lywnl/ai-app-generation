# 本地 RAG 运行环境修复实施计划

> **执行要求：** 使用 `executing-plans` 按任务顺序执行，每个生产改动前先验证对应测试处于失败状态。

**目标：** 阻止本地 Spring Boot JVM 继承系统代理，并让 RAG 模板目录跨平台可用。

**架构：** 运行环境约束放在 Spring Boot Maven 插件的应用 JVM 参数中；文件系统路径通过 Spring 环境变量占位符配置。业务代码和 RAG 检索逻辑保持不变。

**技术栈：** Java 25、Spring Boot 3.5.4、Maven Wrapper、JUnit 5、LangChain4j PGVector。

## 全局约束

- 不修改 macOS 全局代理。
- 不在 Java 业务代码中调用 `System.clearProperty`。
- 不升级依赖，不修改 PGVector 数据。
- 所有文本使用 UTF-8。
- 生产 Docker 继续通过 `RAG_TEMPLATES_DIR=/app/embed_text` 覆盖默认值。

---

### 任务 1：增加本地运行配置回归测试

**文件：**

- 新增：`src/test/java/com/lyw/appgeneration/config/LocalRagRuntimeConfigTest.java`

**产出：**

- 验证 `pom.xml` 的 Spring Boot 插件为应用 JVM 显式设置无代理参数。
- 验证 `application.yml` 使用 `${RAG_TEMPLATES_DIR:embed_text}`。

- [x] 新增测试，读取项目根目录下的 `pom.xml` 和 `src/main/resources/application.yml`。
- [x] 运行 `sh ./mvnw -Dtest=LocalRagRuntimeConfigTest test`。
- [x] 确认旧配置同时因缺少 `jvmArguments` 和 Windows 路径而失败。

### 任务 2：实施最小配置修复

**文件：**

- 修改：`pom.xml`
- 修改：`src/main/resources/application.yml`
- 修改：`README.md`

**产出：**

- `spring-boot-maven-plugin.jvmArguments` 包含 `java.net.useSystemProxies=false`，并清空 HTTP、HTTPS、SOCKS 代理主机与端口。
- `rag.templates-dir` 为 `${RAG_TEMPLATES_DIR:embed_text}`。
- README 的示例与实际配置一致。

- [x] 在 Spring Boot Maven 插件中加入以下参数：

```xml
<configuration>
    <jvmArguments>
        -Djava.net.useSystemProxies=false
        -Dhttp.proxyHost=
        -Dhttp.proxyPort=
        -Dhttps.proxyHost=
        -Dhttps.proxyPort=
        -DsocksProxyHost=
        -DsocksProxyPort=
    </jvmArguments>
</configuration>
```

- [x] 将模板目录配置改为：

```yaml
templates-dir: ${RAG_TEMPLATES_DIR:embed_text}
```

- [x] 更新 README 中的模板目录示例。
- [x] 重新运行 `sh ./mvnw -Dtest=LocalRagRuntimeConfigTest test`，确认通过。

### 任务 3：回归验证并重启服务

**文件：** 无新增生产文件。

**产出：** 默认 Maven 启动即可完整连接 PGVector 并加载 Vue RAG 目录。

- [x] 运行相关测试：

```bash
sh ./mvnw -Dtest=LocalRagRuntimeConfigTest,RagPropertiesTest,RagConfigTest,ProductionRagDeploymentConfigTest test
```

- [x] 运行后端完整测试：

```bash
sh ./mvnw test
```

- [x] 停止临时带 `JAVA_TOOL_OPTIONS` 的后端，清除该临时变量后执行：

```bash
sh ./mvnw spring-boot:run
```

- [x] 验证应用 JVM 的 HTTP、HTTPS、SOCKS 代理属性为空。
- [x] 验证启动日志没有 PGVector 初始化异常或 Vue RAG 目录不可用错误。
- [x] 验证 `http://127.0.0.1:9025/api/actuator/health` 返回 HTTP 200。
- [x] 验证 `templates_vue` 仍有 23 条向量数据。
- [x] 检查 `git diff --check`、`git diff` 和 `git status --short`。
