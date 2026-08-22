# Vue Hybrid 检索默认开启设计

## 背景

当前 Vue 首次生成在 `rag.enabled=true`、`rag.hybrid.enabled=false` 时仍会使用 Milvus，但只执行 Dense-only 向量召回；BM25、RRF 融合和 Rerank 均被跳过。

项目原先把 Hybrid 作为灰度能力，要求满足以下真实门禁后才能启用：

1. 23 条正式数据摄取与物理核验通过。
2. 30 条真实检索质量门禁通过。
3. 10 条首次生成构建达到 10/10。

上述门禁已全部通过，因此可以把 Hybrid 从灰度关闭状态切换为全环境默认开启。

## 目标

- Java 配置对象、本地配置和生产 Compose 默认值统一为 `true`。
- Vue 首次生成默认执行 `BM25 + Milvus Dense + RRF + Rerank`。
- 保留 `RAG_HYBRID_ENABLED=false`，用于显式关闭 Hybrid 和故障回退。
- 不改变 `rag.enabled` 总开关、Milvus 连接、Embedding、摄取、召回参数或 Prompt 拼装协议。

## 配置语义

| 配置 | 行为 |
|---|---|
| `rag.enabled=false` | 完全关闭 RAG，不执行 Dense-only 或 Hybrid |
| `rag.enabled=true` 且 `rag.hybrid.enabled=false` | 仅执行 Milvus Dense-only 召回 |
| `rag.enabled=true` 且 `rag.hybrid.enabled=true` | 执行 BM25、Milvus Dense、RRF 和可配置的 Rerank |

本次只修改最后一个配置的默认选择，不删除 Dense-only 路线。

## 修改范围

1. `RagProperties.Hybrid.enabled` 默认值改为 `true`。
2. `application.yml` 中 `rag.hybrid.enabled` 改为 `true`。
3. 生产 Compose 中 `RAG_HYBRID_ENABLED` 的环境变量回退值改为 `true`。
4. 生产环境变量示例同步改为 `true`。
5. README 与生产部署说明改为“默认开启，可显式关闭”。
6. 配置与部署契约测试同步验证新的默认语义和回退方式。

## 不修改范围

- 不修改 `VueHybridRetrievalService` 的召回算法。
- 不修改 Milvus Collection、Schema、索引、向量维度和一致性等级。
- 不修改 BM25、RRF、Rerank 的实现与参数。
- 不修改普通 HTML、多文件生成的 RAG 路线。
- 不删除 Dense-only 生产入口和离线评测入口。

## 运行流程

Vue 首次生成时，`AiCodeGeneratorFacade` 继续读取 `ragProperties.getHybrid().isEnabled()`：

1. 默认值为 `true` 时调用 `retrieveVueProject`。
2. 分别在项目骨架池和功能片段池执行 BM25 与 Milvus Dense 召回。
3. 通过 RRF 融合排序。
4. Rerank 开启时调用 DashScope 精排；失败则按现有逻辑降级为 RRF 顺序。
5. 选择一个兼容骨架和最多四个兼容功能片段，拼装到生成 Prompt。

## 风险与回退

- Hybrid 比 Dense-only 多一次本地 BM25、RRF 计算和 DashScope Rerank，延迟和外部调用成本会增加。
- BM25、Dense 或 Rerank 单通道失败时，继续使用现有降级逻辑，不改变生成可用性策略。
- 如生产表现异常，设置 `RAG_HYBRID_ENABLED=false` 并重启后端，即可恢复 Dense-only，不需要回滚代码或迁移数据。

## 验证方案

1. 先修改契约测试，使其期望 Hybrid 默认开启，并验证测试按预期失败。
2. 修改生产配置与文档，使配置测试通过。
3. 运行 `RagPropertiesTest`、`ProductionRagDeploymentConfigTest`、`AiCodeGeneratorFacadeTest` 和 `VueHybridRetrievalServiceTest`。
4. 重启本地后端，不设置 Hybrid 覆盖变量。
5. 发起一次 Vue 首次生成，验证指标同时出现 BM25、Dense、RRF、Rerank，并确认降级计数为零或能解释的现有回退。
6. 验证前后端健康、Git 差异和 UTF-8 文本。
