# Rerank 超时调整为 5 秒设计

## 目标

将 DashScope Rerank 的连接超时与读取超时从 3000 毫秒统一调整为 5000 毫秒，降低偶发慢响应触发 RRF 降级的概率。

## 改动范围

- `RagProperties.Rerank.timeoutMs` 默认值由 `3000L` 改为 `5000L`。
- `application.yml` 中 `rag.rerank.timeout-ms` 由 `3000` 改为 `5000`。
- 在现有配置测试中增加契约，确保 Java 默认值与 YAML 配置都保持 5000 毫秒。
- 重启本地后端，让新建的 `RagRerankService` 使用新超时。

## 保持不变

- 不修改 BM25、Embedding、Milvus Dense、RRF、Rerank 排序和降级逻辑。
- 不新增重试。
- 不修改 Embedding 的 10 秒超时、普通 Retrieval 的 2 秒超时。
- 不修改 Rerank API、模型名、候选数或文档长度限制。

## 运行语义

`RagRerankService` 当前将同一个配置值分别传给连接超时与读取超时，因此调整后语义为：

```java
factory.setConnectTimeout(Duration.ofMillis(5000));
factory.setReadTimeout(Duration.ofMillis(5000));
```

这不是严格的 5 秒请求总截止时间；连接阶段与读取阶段分别受 5 秒上限约束。

## 验证

1. 先修改契约测试并确认旧配置下失败。
2. 修改 Java 默认值和 YAML 后确认配置测试通过。
3. 运行 RAG 相关测试，确认召回和降级行为未改变。
4. 重启后端并确认健康检查返回 HTTP 200。
5. 检查工作区差异，只允许本设计范围内的文件变化。

## 风险与回退

- 风险：DashScope 卡顿时，单次 Rerank 最长等待时间会比原配置增加约 2 秒。
- 收益：3 到 5 秒之间返回的正常请求不再被提前降级。
- 回退：将 Java 默认值和 YAML 配置恢复为 3000 毫秒并重启后端。
