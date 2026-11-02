# Change Review: limit-image-context

- 评审日期：2026-11-02
- Profile：standard
- Source Coverage：Pass；U1、U2 的有效要求均有规格、实现落点和验证安排。
- Pre-Apply Decision：PASS
- Validation Confidence：已核对相关实现、调用方、现有测试及四件套依赖；`openspec validate limit-image-context --strict` 通过。CLI 仅证明格式，本报告记录语义自查；业务验证在实施后执行。
- 评审者与独立性：Codex 当前执行上下文，普通自查，非独立评审。
- 核验方式：machine；单根普通文件、UTF-8 checkbox、现有 Python 3 标准库助手。
- 机器记录：`reviews/planning-review.json`，仅实际记录后生效。

## 评审范围与依据

schema 为 `spec-driven`，已包含 proposal、一个图片能力 specs、design、tasks。持续约束为用户来源、项目 AGENTS、OpenSpec 配置及本次采用的共享规则；实施参考为图片模块、测试、提示词、Facade 测试和 pom。后者允许按已授权任务修改，不按持续约束冻结。

核对了两个入口均经同一收集链路、Logo 先提交而照片先交付、描述来自素材而生成参数来自规划等事实。数量缩减是普通行为调整，standard 合理；无数据迁移、权限或跨应用持久化改动。

## Blocking findings

无。数量、顺序、去重、边界输入和外部调用授权已明确，无待用户决定的实质事项。

## Warnings

无阻塞警告。完整 URL 长度不受限，因此不能把数量限制称为固定 Token 上限；近似语义主题只通过提示引导，不额外增加模型或向量调用。已纳入 design 的取舍及交付说明。

## Change Source Coverage 检查结果

| 来源／有效要求 | proposal | specs Requirement | design | tasks | 结论与缺口 |
| --- | --- | --- | --- | --- | --- |
| U1、U2：3／6／18／1 配额 | What Changes | 图片任务按有效主题限额执行；单次搜索最多交付三张图片；汇总素材统一去重和限额 | 决策 1、2 | 1.1、1.2、2.1、3.1 | 覆盖请求前限制与提供方超量兜底 |
| U2：简短说明、URL 完整、设计保留 | What Changes、非目标 | 素材说明简短且地址完整；图片任务按有效主题限额执行 | 决策 1、3 | 1.1、2.1、2.2、3.1 | 区分生成输入与最终说明，Unicode 有明确边界 |
| U2：同步提示与兼容范围 | What Changes、非目标 | 规划提示与执行上限一致；收集保留并发及降级行为 | 决策 2、4 | 2.1、2.2、3.1 | JSON、调度、超时、取消及 MDC 均有回归 |
| D1：工程规则和真实验证 | Source Inputs、Impact | 验证所有场景 | 决策 5、Migration Plan | 全部任务及交付条件 | 仅本地 HTTP、规划／Logo 替身，无费用或重启 |

反向追踪成立：空值、空白、顺序、局部集合、复制资源及响应侧限制是保证已确认配额及兼容的必要工程选择。新能力没有重复已有正式能力；规划间数字、范围和验证一致。未把候选建议、固定节省百分比或持久化修改纳入必需任务。

## Apply handoff

任务 1.1 无前置依赖，可在基线核验后推进；1.2、2.1、2.2 依赖共享策略，3.1 依赖前三个入口完成。沿用项目离线 Maven，任务测试与本地 HTTP 组合检查均在现有授权内。必需验收映射、原始证据及正式验收报告属于交付条件，没有循环前置任务。无提交、推送、重启或归档授权扩展。
