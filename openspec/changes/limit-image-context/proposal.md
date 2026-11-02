# Proposal

## Why

图片收集目前每个搜索任务请求 12 张，规划和汇总均无数量上限，导致大量候选图片反复占用主模型上下文。通过限制实际请求、去重和缩短素材说明，控制候选规模并保留可使用的完整 URL。

## What Changes

- 搜索限定为最多 6 个有效、不同关键词任务，每个任务最多 3 张结果。
- 搜索图按完整 URL 去重，最终最多 18 张；Logo 最多 1 个有效任务、1 张结果。
- 最终素材描述归一空白、最多 80 个 Unicode 码点；Logo 的生成需求保留完整。
- 同步规划提示词，让模型合并相同或近似主题，按需返回空数组，不凑满数量。
- 在工具、收集服务和最终文本构造入口执行必要边界限制，覆盖超量返回及异常数据。

非目标：不改变临时资料生命周期、8K 近期窗口、前端、公共 API、规划 JSON 字段、持久化格式及 Logo 轮询协议；不承诺固定 Token 上限或节省比例。

## Capabilities

### New Capabilities

- `image-material-collection`：图片素材任务筛选、结果配额、文本格式和部分失败交付行为。

### Modified Capabilities

无。现有正式能力未覆盖图片素材收集。

## Impact

- 后端 `ai/image` 的搜索工具、收集服务、素材构造器及其测试。
- `prompt/image-collection-plan-system-prompt.txt`。
- 新增内部无状态策略，复用当前 Java 25、Maven、JUnit/Mockito 和 HTTP 客户端，无新增依赖或配置项。
- 风险等级 `standard`：改变数量和文本约定，但不涉及迁移、权限或公共破坏性协议。

## Source Inputs

| ID | 位置 | 类型／确认状态 | 采纳范围 | 替代关系 |
| --- | --- | --- | --- | --- |
| U1 | [用户来源 U1](sources/user-request.md#u1减少图片素材占用) | 用户需求／已确认 | 减少图片内容与条数 | U2 具体化 |
| U2 | [用户来源 U2](sources/user-request.md#u2实施已讨论方案) | 实施授权／已确认 | 3／6／18／1／80 及本轮边界 | 无 |
| D1 | 项目 `AGENTS.md`、共享工作流规则 | 持续约束 | 中文、授权、证据和工作区规则 | 无 |
| C1 | `src/main/java/com/lyw/appgeneration/ai/image/`、对应测试和规划提示词 | 已核实事实／实施参考 | 现有协议、并发与降级行为 | 允许按任务修改 |
