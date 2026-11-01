# Proposal

## Why

Vue 在线请求目前在完整计划工具结果之外，每次又追加完整磁盘计划快照，重复消耗输入预算，并把较早的执行状态不断移动到新工具事实之后。改为检查实际可见来源、复用完整计划并在缺失时恢复，使计划持续可见，同时保留真实执行顺序。

## What Changes

- 每次只读核实当前磁盘计划；优先使用实际保留历史中的匹配成功计划工具结果，其次复用运行期恢复附件，最后按需生成附件。
- 在应用对应的 `CompressionAwareChatMemory` 中保留一份不可变附件；绑定计划定义、L0 epoch、实际摘要覆盖边界及完整来源单元后的固定位置。
- 普通文件状态变化继续由工具结果及服务端工程状态承接；恢复附件明确表示恢复时快照，每个文件仅一行。定义变化、可靠覆盖推进及来源重建按规则重新恢复。
- 最终消息先冻结再计量，所有在线请求入口共享相同规则；取消、删除及迟到结果继续受现有门禁约束。
- 非目标：不改变 L1 内容规则、12,288 Token 原文软窗口、压缩阈值、RAG／图片生命周期、计划面板、API、SSE 或任何工具协议；不删减、重写原始工具消息，不新增状态通知、持久字段或后台服务。

## Capabilities

### New Capabilities

无。按需恢复属于已有 Vue 计划上下文能力。

### Modified Capabilities

- `vue-soft-replan`：修订“在线变更回合必须维护结构化计划”的请求供给方式，增加计划可见性、运行期恢复附件与请求预算／生命周期约束。承接尚未归档的 `unify-context-summarization` delta，本变更获准替代其中每请求额外刷新完整计划的条款，其他约束及旧验收记录保留。

## Impact

- 后端改动集中于请求准备、分层投影和 `CompressionAwareChatMemory` 内部状态；需要严格识别 `plan-tool/v1` 的成功完整结果。
- 计划读写、文件触达更新、构建闸门及流式编排继续使用既有实现，仅用于集成及兼容验证。基线为 `dd51e25`。
- 风险等级为用户指定的 `strict`。重点核验来源可信度、压缩后完整性、附件时序、跨应用隔离和取消／删除竞态；不涉及数据迁移或公共契约破坏。
- 受控端到端使用本地模型替身与隔离工作目录，验证实际 Java 请求链、工具落盘及构建闸门；记录同输入 Token 对比，不预设收益比例。真实线上模型与现有业务数据不属于本轮验证前提。

## Source Inputs

| ID | 位置 | 类型／确认状态 | 采纳范围 | 替代关系 |
| --- | --- | --- | --- | --- |
| U1 | `sources/user-request.md` U1；`.codex/plans/计划上下文按需恢复与复用.md` | 用户计划／已确认 | 目标、五项实现安排、验收及兼容边界 | 替代 D2 中重复快照规则 |
| U2 | `sources/user-request.md` U2 | 用户授权／明确实施 | 保存、独立变更、strict 评审、实施与验收 | 替代原“本轮仅输出计划”阶段限制 |
| D1 | `openspec/specs/vue-soft-replan/spec.md` | 正式规范／持续约束 | 计划协议、工具与回合隔离、反馈及构建行为 | 请求上下文条款由本变更修订 |
| D2 | `openspec/changes/unify-context-summarization/specs/vue-soft-replan/spec.md`、`specs/context-summarization/spec.md` | 已验收未归档规范／持续约束 | 来源、压缩、预算、状态与临时资料规则 | 只替代每请求额外完整快照与同版本状态重建要求；预算包含实际选择的有效计划材料 |
| D3 | `AGENTS.md`、`.agents/shared/openspec/` 工作流、来源、评审及证据规则 | 工程规则／持续约束 | 中文、授权范围、strict 独立评审及可核验交付 | 无 |
| C1 | `src/main/java/com/lyw/appgeneration/ai/memory/`、对应测试 | 已核实事实／实施参考 | 请求门禁、L0 来源、投影、冻结与运行期记忆；允许按任务修改 | 不冻结待改代码 |
| C2 | `src/main/java/com/lyw/appgeneration/ai/plan/`、`src/main/java/com/lyw/appgeneration/ai/tools/`、`src/main/java/com/lyw/appgeneration/ai/AiGeneratorServiceFactory.java`、`src/main/java/com/lyw/appgeneration/core/handler/VueTurnContext.java`、`src/main/java/dev/langchain4j/service/AiServiceStreamingResponseHandler.java` | 已核实事实／实施参考 | 只读计划、计划定义及现有工具事实／缓存／取消链路 | 保留公开契约，允许必要内部适配 |

以上路径除变更内文件外均相对于项目根。机器基线区分持续约束 `source` 与允许实施的 `reference`，不把代码变化误判为需求变化。
