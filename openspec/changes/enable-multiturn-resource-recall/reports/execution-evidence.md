# U5 实施与验证证据

本次为 2026-11-06 继续完成的 Vue RAG 数量统一与取消兼容筛选，范围是 tasks 第 8 节。Git 基线为 `b8b164c348a95dbebaf0455d66eed3e26a65dc59` 加当前未提交修改。详细映射以 [verification-manifest.json](verification-manifest.json) 为准，实际执行数与源码核对见 [汇总](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-verification/summary.json)。

## 本次执行结果

| 范围 | 实际执行与结果 | 原始记录 |
| --- | --- | --- |
| 核心回归 | 18 类、205 项，失败/错误/跳过均为 0 | [vue-rag-selection-core](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-core/command.json) |
| 沙箱组合回归 | E2E 1 项、资料复用 3 项、Facade 59 项，共 63 项；失败/错误/跳过均为 0 | [vue-rag-selection-composed](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-composed/command.json) |
| 持续边界补测 | 成功终态查询 2 项、AppService Vue 回合 38 项、资源策略 3 项，共 43 项；失败/错误/跳过均为 0 | [vue-rag-selection-boundaries](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-boundaries/command.json) |
| 前端会话回归 | 两个文件、116 项，失败/待执行/todo 均为 0 | [vue-rag-selection-frontend](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-frontend/command.json) |

Java 合计 24 个不同测试类、311 项，不重复累计初轮排错结果。各运行目录保留命令、退出码、UTC 起止时间、`code-state.json`、原始日志及 JUnit/Vitest 报告。Java 25 使用现有 Maven 离线验证脚本；Node 24 使用现有前端脚本，未安装新依赖。

组合测试显式传入 `--sandbox`，实际工作目录和临时文件位于本次 `.codex` 目录；已逐类核对执行数量，未将目录条件导致的跳过算作通过。三组 Java 源码快照与当前登记源码一致；前端本次快照包含的 94 个文件也与当前文件匹配。

## 实现与行为覆盖

- `RagConstants` 集中 8/10/1/4/2；Dense 请求对象和 BM25 经真实 `MilvusBm25SearchClient` 构造的 `SearchReq` 验证搜索上限与显式参数透传，父文档不足不补搜。两路各 8 个不重合父模板验证 RRF 默认保留 10 个，重复父模板、空结果、k=60 和稳定排序继续核验。
- 本机 HTTP 重排测试捕获实际 `top_n`，覆盖 1/4/2 与候选不足。普通、在线、后续、生产及离线 Dense-only 按排名选择；技术栈或依赖不一致仍可入选，类型、目录版本、父文档回查及重排响应合法性继续检查。在线正常、关闭/失败重排、BM25/Dense 失败和截止前部分交付均核对阶段上限。
- 真实临时目录覆盖缺失、空目录、部分文件、子目录、任意清单内容、锁文件、悬空内部链接、工程及祖先链接、普通文件替代目录、POSIX 权限拒绝、截止取消及真实计划初始化。Reader 不读取清单；已有工程无骨架候选时继续现状，构建器仍拒绝违反依赖契约的工程。
- 真实 Java 组合调用连接 AppService、Facade、检索编排、AiServices/请求门禁、文件与计划工具、终态处理。六回合覆盖只读、首次失败、重试成功、样式、新闻、背景；工具续调、同步摘要和协议恢复复用同一资料，下轮替换清单，不直接把候选写入 L0/L1/历史。

## 失败、评审与历史复用

初轮 [vue-rag-selection-resume-check](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-resume-check/command.json) 实际 84 项、1 项失败：RRF 默认测试仍断言旧值 15，实际值已为 10。修复测试以两路候选验证真实截断行为，后续核心回归通过；保留原非零记录，不把它计为通过。

[独立代码评审](../reviews/implementation-review.md) 为 PASS。评审者未编写业务代码、测试或规划；CR-U5-1 指出的 README 初始/后续调用数量、每轮资源策略和不可用目录说明已修正并复核关闭。

旧报告与原指纹保存在 [历史变更副本](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-before/change/)，旧 [执行证据](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-before/change/reports/execution-evidence.md) 保持原字节。未变化模块的本地结果按相关输入核对复用；旧快照之外的 `ChatHistoryServiceImpl` 和前端变化来自既有其他提交，本轮已对受影响的成功资格、资源策略和会话行为补测，未修改这些业务文件。

原 240 次真实分类对照的输入、路由/解析代码和运行器仍与历史快照一致，只保留其固定样例与当次模型结论，本轮没有重新调用分类或检索供应商。旧兼容读取和筛选要求由 U5 替代，不以旧兼容测试证明新行为。

## 边界与交付

本次数据库、生成/分类/摘要模型、RAG/图片供应商及 npm 执行器使用替身；重排 HTTP 为本机环回服务，文件和计划真实写入测试沙箱。没有真实 Milvus/DashScope 召回质量、供应商时延或 npm 构建的新增实测结论。

公共 HTTP/SSE、模板数据、实际构建依赖约束和已交付多轮策略保持原契约。没有提交、推送、部署、重启、数据库迁移或归档；回退范围仅是 U5 的 RAG 数量、选择和目录识别修订。原始证据仍被验收引用，需要保留。
