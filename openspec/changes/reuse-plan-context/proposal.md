# Proposal

## Why

`fc4685e` 已消除重复完整计划快照，但仍在每次请求读盘、检查计划并按原文锚点插入独立附件。将恢复资料保存为 L0 有序窗口条目，后续直接沿用窗口顺序；检查由实际覆盖、epoch 或服务端计划定义变化触发。

## What Changes

- L0 增加独立类型 `PLAN_RESTORE`，正文、身份和顺序随 Redis 快照持久化；每应用最多一份。
- formatVersion 升为 2，兼容旧数组和 v1，成功 CAS 时升级；提供显式应用 ID 的停机降级操作。
- 服务端定义成功落盘同步标记变化，完整工具结果入窗后优先复用；普通文件/构建状态不触发恢复，不感知手动改计划文件。
- 原文、来源、摘要、证据及回合计数与恢复资料分离；协议恢复使用显式原文视图。恢复不占原文软窗口额度，但计入完整请求预算。
- 保留快照正文、所有工具原文和协议、既有反馈、冷重建范围、取消删除和截止边界。
- 非目标：不改变摘要内容、12,288 Token 原文窗口、阈值、RAG/L2、前端、API/SSE；不新增数据库字段/Redis 键，不实现重启续跑或文件监控，不消除计划自身 Token。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `vue-soft-replan`：计划恢复保存到热窗口，事件触发检查及 v1/v2 持久化兼容；替代本变更原来的独立附件约定及 `unify-context-summarization` 的逐请求完整快照条款，其他约束保留。

## Impact

涉及 L0 类型/codec/存储、来源操作、摘要材料、决策证据、终态/协议恢复、请求门禁、计划状态管理及相应测试。保持 `strict`，本轮新增持久化编码风险与回退验收。基线 `fc4685e`，原记录见 `history/fc4685e/`；旧六项完成记录仅证明历史实现。

测试复用项目 Maven/JUnit、隔离 MySQL/Redis 夹具和受控模型替身，比较上下文读盘、恢复次数、请求 Token 与 Redis 快照字节数。真实模型效果和生产部署不属于本轮验证。

## Source Inputs

| ID | 位置 | 类型／确认状态 | 采纳范围 | 替代关系 |
| --- | --- | --- | --- | --- |
| U1/U2 | `sources/user-request.md` | 用户要求／部分替代 | 计划真实来源、快照正文、进度通知、预算和生命周期 | 存放位置、编码、检查频率由 U3 替代 |
| U3 | `sources/user-request.md`；`.codex/plans/计划恢复消息进入热窗口.md` | 用户要求／已确认实施 | 热窗口持久化、事件恢复、兼容降级和本轮验证 | 同上，原报告保留 |
| D1 | `openspec/specs/vue-soft-replan/spec.md` | 正式规范／持续约束 | 计划协议、回合隔离、反馈、构建 | 上下文供给由本 delta 修订 |
| D2 | `openspec/changes/unify-context-summarization/specs/vue-soft-replan/spec.md`、`openspec/changes/unify-context-summarization/specs/context-summarization/spec.md` | 已验收规范／持续约束 | 来源、压缩、原文回收、预算、临时资料 | 仅计划供给及恢复缓存编码由本轮替代 |
| D3 | `AGENTS.md`、`.agents/shared/openspec/` 工作流/来源/评审/证据规则 | 持续约束 | 中文、授权、strict 与可核验交付 | 无 |
| C1 | `src/main/java/com/lyw/appgeneration/ai/memory/`、`service/`、`core/handler/` 与对应测试 | 实施参考 | L0、摘要/证据、请求、终态及来源边界 | 允许按任务修改 |
| C2 | `src/main/java/com/lyw/appgeneration/ai/plan/`、`ai/tools/`、`ai/AiGeneratorServiceFactory.java`、`core/AiCodeGeneratorFacade.java`、`src/main/java/dev/langchain4j/service/` 与对应测试 | 实施参考 | 同步定义标记、原文提交、协议恢复与装配 | 保留公共契约，允许内部适配 |

路径均相对项目根或变更目录。机器记录区分持续约束和允许修改的参考，不冻结待改源码。
