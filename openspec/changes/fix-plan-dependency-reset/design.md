## Context

见 proposal 的起因。AppPlanStateManager 的 resetAffectedStates 将反向 BFS 的结果作为状态重置集；UpdatePlanTool 合并 add/remove/modify 后调用它。现有依赖校验、计划原子写入及取消/租约提交能力可以复用。

## Goals / Non-Goals

**Goals:** 分离直接修订状态与间接影响提示，保留现有所有提交边界。
**Non-Goals:** 不迁移真实计划，不修复循环、不改构建算法、不扩展前端及协议。

## Decisions

1. 将状态方法明确命名为 `resetExplicitlyChangedStates`，只处理显式 add/modify 路径的非 KEEP 条目。其他 PlanFile 原样保留；KEEP 和显式 OUT_OF_PLAN 纳入计划的处理保留当前补丁合并及直接重置语义，不趁机修改其他状态规则。
2. 新增 `indirectlyAffectedPaths`，只分析已通过校验的新图：反向 BFS 由 add/modify/remove 路径启动，遍历途中仍通过被显式修改的节点，输出排除所有显式路径的其余依赖者。结果去重，按新计划顺序输出；删除引用若未同步修改，先被校验拒绝。分析不修改 PlanFile。
3. UpdatePlanTool 的顺序固定为补丁合并 → 完整依赖校验 → 直接状态重置 → 检查提示构造 → 原有原子提交 → acknowledgePlanUpdate → 成功结果。所有检查在提交前，提示仅经成功结果返回。保留只读/评测拒绝、版本CAS、取消栅栏、历史20条限制和计划级 PLANNED。
4. 提示在 UpdatePlanTool 中用常量20限制路径数，超出追加“另有 N 个受影响文件”；路径以已有 PlanFile 规范化结果为准，不从任意模型文本抽取。通过 PlanToolResult.applied 的消息重载返回，旧重载继续使用“计划已保存”。无间接路径时不附加提示。计划协议的8个字段和稳定展示摘要保持不变；提示用于指导模型，不新增用户面板展示，原有工具事件仍可携带完整 message。
5. 该提示不是实际偏差，不调用 ReplanContext.observe；不会增加反馈次数或设置 REPLAN_PENDING。原有 acknowledgePlanUpdate 仍处理已有偏差。当前计划的可信 TOUCHED 可继承，但不等于兼容性已验证；编译和行为验证仍承担各自责任。

## Risks / Trade-offs

- 模型可能忽略间接影响 → 提示具体路径，并允许后续显式修订；不声称本期消除模型遗漏或无进展循环。
- 历史 PENDING 无法可靠区分成因 → 不自动迁移，真实旧计划保持原样。
- 正式规格原文与修复规则不同 → 用户已明确要求同步规格，在规划阶段同步并作为本变更持续约束绑定。归档历史和上一期报告保留；其旧指纹不再证明当前新语义，不能伪称旧验收持续有效。

## Validation Strategy

扩展状态单测、PlanToolTest 集成及 PlanToolProtocolSupportTest 往返；新增隔离的路由/入口重现测试，以真实文件工具成功写入触发计划状态，并使用真实 beforeBuild 检查门禁，不运行 npm 或真实模型。查询、Replan、前端解析和工具展示回归。测试 root、JVM tmpdir、日志全部位于 `.codex/fix-plan-dependency-reset/`，不得改写已有真实 `.plan.json`。既有文件工具测试通过 fork JVM 的 user.dir 指向独立目录隔离项目输出，测试前核对行为可行性；无法隔离时不运行会覆盖真实项目的用例。

## Migration Plan

无数据迁移、无服务重启。修复经评审验证后作为单个交付单元，不重写归档、不自动归档或推送。回退仅回退本次源码和规格，不能重写生成计划。
