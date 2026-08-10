# 任务 5：Vue 混合检索实施计划

## 目标

在不改变 HTML、MULTI_FILE 旧检索行为的前提下，为 Vue 工程新增 Dense、RRF 和骨架/片段双链混合检索，严格遵守 `.codex/sdd/task-5-brief.md` 的固定参数和降级矩阵。

## 文件职责

- `service/rag/retrieval/DenseRetriever.java`：构造双 metadata filter 的 PGVector 请求，过滤坏 metadata，并在通道内聚合父文档。
- `service/rag/retrieval/RrfFusionService.java`：执行只依赖 rank 的 RRF 纯计算，以目录质量分和文档 ID 完成稳定同分排序。
- `service/rag/model/VueRagContext.java`：不可变承载骨架、功能片段、目录版本和降级状态。
- `service/rag/VueHybridRetrievalService.java`：分别编排骨架链与片段链，调用召回、融合、重排和兼容性选择，处理目录/通道/重排降级。
- `service/rag/RagRerankService.java`：保留旧 `RetrievedSnippet` 接口，增加 Vue 父文档重排接口和无源码文档文本构造。
- `service/rag/RagRetrievalService.java`：保留旧方法实现，仅委托新增 `retrieveVueProject`。

## TDD 任务

1. 先新增 `DenseRetrieverTest`：锁定 VUE_PROJECT store、Top10、minScore、目录版本与文档类型双过滤、坏 metadata 跳过、父文档最高分聚合和稳定排序；确认缺类导致 RED 后写最小实现并确认 GREEN。
2. 先新增 `RrfFusionServiceTest`：锁定 `1/(60+rank)` 精确值、交集提升、单路、重复父文档、质量同分和 ID tie-break、Top15；确认 RED 后写纯计算实现并确认 GREEN。
3. 先新增 `VueHybridRetrievalServiceTest`：锁定双链 TopK、Rerank Top3/Top8、最终 1+4、无源码文本、兼容性后续选择及完整降级矩阵；确认 RED 后实现编排、上下文和 Rerank 最小兼容扩展。
4. 补 `RagRetrievalServiceTest` 锁定 HTML/MULTI_FILE 旧链行为和 Vue 新入口委托。
5. 运行任务 1-5 目标测试与既定纯单元回归，执行 `git diff --check`；加载代码审查和完成验证技能，自审后写报告并提交。

## 已知环境

- 仓库 `mvnw` 无执行权限，命令统一使用 `bash mvnw`。
- 系统 JDK 为 17，而项目声明 Java 25；在已忽略的工作树 `.codex/runtime/jdk25` 使用 Temurin 25，不修改系统环境或用户目录。
