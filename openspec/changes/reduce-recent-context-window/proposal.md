# Proposal

## Why

用户已选择缩小压缩后保留的近期原文，降低该部分请求上下文占用。8K 仍为完整批次选择的软目标，不承诺整轮调用总 Token 或重复读取次数下降。

## What Changes

- 将共享近期原文软目标从 12,288 调整为 8,192 Token，同步默认配置与固定值启动校验。
- 保留当前用户原话、最新完整批次及未完成批次保护；仍仅在可靠摘要覆盖后回收原文。
- 同步预算相关断言、说明与真实模型夹具的预算记录；补充 8K 边界验证。
- 请求阈值、摘要内容与上限、估算系数、计划恢复、RAG/L2/图片生命周期、存储格式、公共 API 均保持。

## Capabilities

### New Capabilities

- `context-summarization`：沿用待归档 `unify-context-summarization` 的能力路径，仅登记近期原文预算的后续约定。该能力尚未出现在正式 `openspec/specs/` 中，不另建近义能力。

### Modified Capabilities

无已归档能力需要修改。

## Impact

- 影响 `MemoryTokenProperties`、`application.yml` 和使用该共享配置的窗口选择器；不改变选择算法或持久化协议。
- 风险等级 standard：可观察预算调整，不新增隔离、事务、迁移或公共破坏性协议。
- 新旧程序与其配置须配套使用；生效需要后续重新加载后端。本轮不改运行中服务。
- 旧变更中的 12K 结论保留为历史；将来同步 `context-summarization` 正式规范时合并本次 8K 决定，不能以较早记录覆盖新值。

## Source Inputs

| ID | 位置 | 类型／确认状态 | 采纳范围 | 替代关系 |
| --- | --- | --- | --- | --- |
| U1、U2 | `sources/user-request.md` | 用户需求／已确认 | 8,192 原文软目标及实施范围 | 替代旧版本 12,288 数值 |
| D1 | `AGENTS.md`、`.agents/shared/openspec/workflow-policy.md` | 工程规则／持续约束 | 中文、工作区、规划与验收证据 | 无 |
| D2 | `../unify-context-summarization/specs/context-summarization/spec.md` 的近期原文与完整请求要求 | 已实现约定／部分被替代 | 保护批次、可靠覆盖及完整请求门禁 | 仅原文目标改为 8,192 |
| C1 | `MemoryTokenProperties.java`、`SummaryWindowSelector.java`、相关测试与 `application.yml` | 已核实事实／实施参考 | 固定值校验、窗口边界、预算验证 | 允许按任务修改 |
