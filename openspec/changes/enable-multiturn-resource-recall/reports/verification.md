# U5 实现验收：enable-multiturn-resource-recall

结论：**Ready for Archive**。本次批准的 Vue RAG 修订（tasks 8.1—8.5）已完成，未解决阻塞为 0。结论针对当前本地实现与下述验证范围，不代表已经归档或发布。

## 基线与实现

- Profile：`strict`；schema：`spec-driven`。Git HEAD 为 `b8b164c348a95dbebaf0455d66eed3e26a65dc59`，验收包含当前未提交修改。
- [规划评审](../reviews/change-review.md) 为 PASS；原 U5 实施开始记录 `2026-11-05T16:15:14.358681+00:00` 保留，本次恢复实施及验收均核对持续约束。未重置开始时间、未修改任务正文，仅根据真实证据完成第 8 节 checkbox。
- 两通道各最多搜索 8 个子块，RRF 每池最多保留 10 个父模板；重排与最终选取共用骨架 1、首轮功能 4、后续功能 2 的上限，候选不足不补搜、不凑数。
- RAG 已移除框架、语言、构建工具、依赖版本及候选依赖白名单筛选。工程状态仅由安全目录的缺失/空/非空/不可用识别，不读取 `package.json`；原目录版本、文档类型、ID/响应校验、截止取消、已有工程保护和实际构建依赖契约继续有效。

## 证据与覆盖

详细需求/场景/任务 → 实现 → 测试 → 原始证据映射以 [verification-manifest.json](verification-manifest.json) 的 57 条记录为准，覆盖当前 10 项 Requirement、24 项 Scenario 和 23 项任务。实际命令、运行范围、失败处置和复用依据见 [execution-evidence.md](execution-evidence.md)，汇总和源码一致性检查保存在 [summary.json](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-verification/summary.json)。

本次 Java 实际执行 **24 类、311 项**：核心 205、沙箱组合 63、持续边界 43，失败/错误/跳过均为 0。前端会话回归 **116 项**通过，无失败、待执行或 todo。首次失败重试、仅功能池、候选跨回合替换、同轮续调复用、数据合法性、各种降级及真实目录安全边界均有对应验证。

初轮 84 项中的旧 RRF 断言失败被保留并由当前通过证据替代，没有通过修改验收要求解决失败。所有新增 Java 和前端运行的登记源码与当前字节一致。README 仅在测试后作阶段语义澄清，已由独立评审复核；文案没有改变业务实现。

未变化模块复用原本地证据并核对相关输入；旧源码快照中涉及 U5 或已提交历史服务变化的部分均以新测试覆盖，前端因旧快照之后已有变化而补测。原第 1—7 节保留历史完成事实，原清单兼容读取、兼容筛选和旧数量已明确由 U5 替代。旧真实分类评测只保留当次模型与固定样例结论，不宣称本轮重新验证模型效果。

## 独立评审与静态检查

[独立代码质量评审](../reviews/implementation-review.md) 为 **PASS**，评审者 `/root/vue_rag_implementation_review` 未编写本轮代码、测试或规划。CR-U5-1 的 README 阶段与目录语义问题已修正、复核关闭，无开放业务代码阻塞。

`git diff --check`、OpenSpec strict 校验、持续规划约束检查、任务完成状态及结构化验收清单均通过；原始检查结果见 [checks.json](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-verification/checks.json) 与 [manifest-check.json](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-verification/manifest-check.json)。验收指纹保存在 `reports/verification.json`，由标准库助手绑定当前代码、测试、构建输入、报告、独立评审与原始证据。

## 限制与交付

本次是本地逻辑与受控组合验收。数据库、模型、检索/图片供应商和 npm 执行器使用替身，重排 HTTP 仅为环回服务；没有真实 Milvus/DashScope 检索质量、费用或耗时改善的实测结论。

未提交、推送、部署、重启、迁移或归档。回退只撤销 U5 的检索数量、筛选及目录识别修订，不撤销已交付的多轮路由和图片策略。原规划、评审、验收及证据保存在 [历史副本](../../../../.codex/enable-multiturn-resource-recall/vue-rag-selection-before/change/)，保持原字节；本次原始运行证据同样需要保留。
