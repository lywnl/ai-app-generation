# 本地 RAG 运行环境修复设计

## 问题与证据

本地后端通过 `sh ./mvnw spring-boot:run` 启动时，实际应用 JVM 继承了 macOS 系统代理：

```text
http.proxyHost=127.0.0.1
http.proxyPort=7897
https.proxyHost=127.0.0.1
https.proxyPort=7897
socksProxyHost=127.0.0.1
socksProxyPort=7897
```

PostgreSQL JDBC 因此通过 `SocksSocketImpl` 连接本地 PGVector，最终抛出 `UnknownHostException`。同一代码和容器在显式清空上述代理后成功初始化 `templates_html`、`templates_multi`、`templates_vue` 三张向量表，证明代理继承是 PGVector 初始化异常的根因。

另一个独立问题是 `rag.templates-dir` 写死为 Windows 路径 `D:/ai-app-generation/embed_text`，导致 macOS 本地模板目录不可用。项目工作区实际存在 `embed_text`，生产 Docker 已通过 `RAG_TEMPLATES_DIR=/app/embed_text` 覆盖该配置。

## 目标

- 本地 Maven 启动的实际 Spring Boot JVM 不继承 HTTP、HTTPS、SOCKS 系统代理。
- RAG 模板目录默认使用工作区相对路径 `embed_text`，并允许环境变量覆盖。
- 不修改 macOS 全局网络设置，不在 Java 业务代码中清理系统属性，不升级依赖。
- 生产 Docker 继续使用既有 `/app/embed_text` 挂载和环境变量。

## 设计

在 `spring-boot-maven-plugin` 的 `jvmArguments` 中设置：

```text
-Djava.net.useSystemProxies=false
-Dhttp.proxyHost=
-Dhttp.proxyPort=
-Dhttps.proxyHost=
-Dhttps.proxyPort=
-DsocksProxyHost=
-DsocksProxyPort=
```

使用插件参数而不是 `.mvn/jvm.config`，因为 Spring Boot Maven 插件会派生实际应用 JVM，`jvmArguments` 明确传递给该进程，修复边界更准确。

将应用配置改为：

```yaml
rag:
  templates-dir: ${RAG_TEMPLATES_DIR:embed_text}
```

本地默认从项目根目录读取 `embed_text`；生产 Docker 保持 `RAG_TEMPLATES_DIR=/app/embed_text`。

## 测试与验收

1. 配置回归测试必须检查 Spring Boot 插件显式禁用并清空三类代理。
2. 配置回归测试必须检查模板目录使用 `${RAG_TEMPLATES_DIR:embed_text}`。
3. 相关配置测试和后端完整测试必须通过。
4. 重新以普通 `sh ./mvnw spring-boot:run` 启动，不再额外设置 `JAVA_TOOL_OPTIONS`。
5. 启动日志不得出现 `[RAG] 构建向量存储失败` 或 `[Vue RAG] 目录不可用`。
6. 后端健康接口返回 HTTP 200，PGVector 的 `templates_vue` 表仍包含 23 条数据。

## 回滚

改动只涉及 `pom.xml`、`application.yml`、README 和配置测试。回滚这些文件即可恢复原行为，不涉及数据库结构或数据迁移。
