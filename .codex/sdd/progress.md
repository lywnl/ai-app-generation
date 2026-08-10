# Vue RAG 混合检索执行进度

## 五骨架真实构建门禁补强（2026-08-11）

- 完成度审计发现：历史任务 2 曾手工验证 5/5 骨架，但永久测试 `VueSkeletonRealBuildTest` 只构建 `vue-skeleton-basic-001`，无法在后续回归中证明计划要求的 5/5。该问题是门禁覆盖不足，不是骨架实现失败。
- TDD RED：先增加“五个来源”断言并保留原单一来源，定向测试按预期失败，结果为 1 项、1 failure，错误为 `expected: <5> but was: <1>`。
- GREEN：测试现在固定校验五个计划骨架文件，使用动态测试从唯一知识源 JSON 提取工程，逐个调用真实 `VueProjectBuilder.buildProjectDetailed`，分别检查构建成功与 `dist` 目录存在；骨架 ID 和内嵌文件路径均不得逃逸各自构建目录。
- 离线默认模式：`VueSkeletonRealBuildTest` 共发现 2 项，来源完整性测试实际执行并通过，真实 npm 动态测试工厂按 `RAG_SKELETON_BUILD` 跳过 1 项。来源数量与固定 ID 不再因未设置外部开关而完全跳过。
- 显式真实模式：`RAG_SKELETON_BUILD=true` 执行 1 个来源断言和 5 个动态真实构建，共 6 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`；五个独立结果均为 `success=true`、`stage=SUCCESS`、`exitCode=0`、`timedOut=false`，且 `dist` 全部存在。
- 最终完整 Maven：显式清空模型、图片、COS、外部集成和三类 RAG 门控变量，以项目 JDK 25 执行；279 项、0 failure、0 error、7 skipped，`BUILD SUCCESS`。日志明确包含 `Started AiAppGenerationApplicationTests`，Spring 上下文门禁未跳过。
- 本轮只修改测试门禁和审计文档，不修改 `src/main/**`、模板数据、依赖或生产配置。

## 真实外部门禁基础设施补验（2026-08-11）

- 已在项目忽略目录 `.codex/runtime/pgvector-data` 准备临时 PGVector，容器 `ai-codegen-rag-eval-pg` 状态为 `running/healthy`，仅映射 `127.0.0.1:5432`，数据库 `ai_codegen_rag` 的 `vector` 扩展版本为 `0.8.6`。
- 已使用项目 `.codex/runtime/jdk25` 和当前依赖 `langchain4j-pgvector 1.1.0-beta7` 执行 Java 协议探针：实际批量写入一个 1024 维向量，通过相同向量检索得到唯一命中，向量 ID、文本、相似度 `1.000000` 和 `documentId` metadata 均正确；SQL 侧确认维度与 metadata 后已删除独立探针表。
- 当前 JVM 全局配置了 SOCKS 代理，且默认 `socksNonProxyHosts` 不包含回环地址。直接连接 PGVector 会把本地 PostgreSQL 连接错误送入代理并报 `UnknownHostException: 127.0.0.1`；评测 JVM 显式增加 `-DsocksNonProxyHosts=localhost|127.*|[::1]` 后协议探针通过。该问题属于本机运行参数，不需要修改生产代码。
- 正式表 `templates_vue` 当前不存在。真实摄取必须由 `VueKnowledgeIngestor` 调用指定的 `text-embedding-v4`，为 5 个骨架和 13 个功能片段生成 23 个真实检索块向量；不能用探针向量、假向量或手工空表替代。
- 已提供本地临时数据库凭据，并用项目要求的 JDK 25 重新运行 `VueRetrievalQualityGateTest`：门禁入口 1/1，0 failure、0 error、0 skipped，`BUILD SUCCESS`；报告仍为“未执行”，当前只缺少 `DASHSCOPE_API_KEY`，未取得 Skeleton Hit@1、Feature Recall@4 或相对 Dense 退化指标。
- 已用相同数据库配置重新运行 `VueGenerationBuildQualityGateTest`：门禁入口 1/1，0 failure、0 error、0 skipped，`BUILD SUCCESS`；报告仍为“未执行”，当前只缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，未取得 10/10 真实生成构建结果。
- 结论：PGVector 基础设施与 Java 协议已经就绪；`DASHSCOPE_API_KEY` 是正式摄取、Dense、Rerank 和真实检索指标的不可替代阻断，`DEEPSEEK_API_KEY` 是十条真实生成的额外不可替代阻断。门禁入口通过不等于真实质量门槛通过，当前仍不可发布。

## 全分支最终审查修复

- 已修正任务 7 的旧链解释：`rag.hybrid.enabled=false` 只关闭 BM25、RRF、Rerank，不再回到旧 Vue `id/title/category/code` metadata 与通用 `assemble`；生产改用新版 Dense-only、当前目录父文档回查和 `assembleVueProject`。
- `rag.enabled` 现为 Vue 普通生成与真实生成评测的优先总开关；Service 层同步防御，不允许 Facade 之外的生产调用绕过。
- 跨层验收已串联同一 InMemory 存储、真实当前摄取、真实 Dense、父文档与 Hybrid 关闭 Facade，证明旧 schema/旧目录版本不可见、当前完整源码可用。
- 验证：指定覆盖 52/52，纯单元 265/265，均为 0 failure、0 error、0 skipped；`git diff --check` 通过。

## 修复后全范围验证

- 任务 1～8 目标回归：176 项，0 failure、0 error、1 skipped；跳过项为默认门控的真实骨架联网构建。
- 纯单元回归：266 项，0 failure、0 error、0 skipped。
- 完整 `mvn test`：补强五骨架门禁后为 279 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。运行进程显式清空模型、Pexels、COS、数据库及外部门控变量；`AiAppGenerationApplicationTests.contextLoads` 实际启动 Spring 上下文并通过，不在跳过项中。
- 七个跳过项均为显式外部测试：真实 npm 骨架构建 1 项、旧 RAG 联网评测 1 项、真实模型/网页外部集成 5 项。`CodeParserTest` 已移除无用 Spring 上下文，`JsonMessageStreamHandlerTest` 已补齐 `ToolMessageCollapser` 并验证非空折叠快照原样恢复。
- Maven 门禁独立最终复审：Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；所有历史 finding 已关闭。
- 真实检索门禁报告：状态“未执行”；数据库前置条件已补齐，当前只缺少 `DASHSCOPE_API_KEY`，未取得 Hit@1、Recall@4 或 Dense 相对退化指标。
- 十条真实生成构建报告：状态“未执行”；数据库前置条件已补齐，当前只缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`，未取得 10/10 构建结果。
- `git diff --check`：通过；未推送远程。

- 任务 1：完成（提交 `f32d576..ac2bca9`，独立审查通过）
- 任务 2：完成（提交 `ac2bca9..8aead1b`，独立审查通过）
- 任务 3：完成（提交 `8aead1b..e4ce5fe`，独立审查通过）
- 任务 4：完成（提交 `e4ce5fe..b2a2db6`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 5：完成（提交 `b2a2db6..ede5a53`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 6：完成（提交 `ede5a53..12c5d61`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 7：完成（提交 `12c5d61..579e686`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 8：代码与默认 Maven 门禁完成（提交 `579e686..a5c09b8`，定向复审和 Maven 门禁复审均通过；真实外部门禁仍未执行）
- 全分支独立审查：最终 Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；代码可合并，默认 Maven 已通过，但两个真实外部门禁没有成绩，当前仍不可发布
