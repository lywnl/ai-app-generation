# Change Review: reduce-recent-context-window

- 评审日期：2026-11-02。
- Profile：standard；预算行为调整，不涉及新存储协议、隔离或事务设计。
- Source Coverage：Pass；U1、U2 已确认，未采纳的图片优化建议未进入任务。
- Pre-Apply Decision：PASS。
- Validation Confidence：已核对实际默认值、YAML、窗口选择器及相邻测试；`openspec validate reduce-recent-context-window --strict` 通过。此阶段未执行业务测试。
- 评审者与独立性：当前执行上下文普通自查，非独立评审；standard 无独立评审要求。
- 核验方式：machine，单根普通 UTF-8 文件；记录为 `reviews/planning-review.json`。

## 评审范围与依据

实际 schema 为 spec-driven，proposal、单份 specs、design 和 tasks 齐备。持续约束为来源目录、AGENTS、工作流及既有基础规格中明确继续有效的保护与门禁语义；允许修改的实现参考包括预算配置、相邻测试、过时注释及夹具预算记录。旧记录里的 12K 不作为本次持续约束。

完整批次算法无需变更，8K 不改变最新批次例外、用户原话保护和可靠覆盖后回收。YAML 与类默认值必须一起修改，现有严格初始化校验不能绕过。实施前分支为 master、HEAD 为 cd32ab7；无关 `.DS_Store` 保留。

## Blocking findings

无。数值、范围、实施授权及本地验证入口均明确。

## Warnings

- 缩小窗口可能增加重读，不能把窗口节省量当作整轮成本收益；已在用户讨论和 design 记录，用户 U2 仍明确选择 8K，不重复请求决定。
- `context-summarization` 基础规格尚未归档，本次复用相同能力路径；将来归档必须合并基础条目和最新 8K 决定。当前不执行归档，该交付后续条件不阻塞本轮代码调整。
- 当前后端运行独立字节码副本，本轮只修改和验证工作区；生效需要后续重新加载配套程序与配置。

## Change Source Coverage 检查结果

| 来源／有效要求 | proposal | specs | design | tasks | 结论与缺口 |
| --- | --- | --- | --- | --- | --- |
| U1、U2：8,192 软目标 | What Changes | 近期原文必须跨回合按 Token 保留；两个精确边界场景 | Decisions 1、2 | 1.1、1.2 | 覆盖，无缺口 |
| U2：配置、校验和相关验证 | Impact | 近期原文预算配置必须一致；两项启动场景 | Decisions 1、3、4 | 1.1、1.2、2.1 | 覆盖，无缺口 |
| D2：批次、原话、覆盖与完整请求门禁 | What Changes | 近期原文要求其余场景；预算配置描述 | Decisions 2、4 | 1.2、2.1 | 保留原语义，仅替代 12K 数值 |
| D1：范围、证据和操作边界 | Impact、Source Inputs | 无额外业务要求 | Decisions 4、5、Migration Plan | 全部及交付条件 | 无迁移、真实模型或服务操作 |

反向追踪成立：新增测试与配置一致性检查是落实 U1/U2 的必要验证，没有新增通知、摘要内容或缓存功能。任务依赖为 1.1 → 1.2 → 2.1，验收指纹不设为进入验收前的任务，无循环依赖。八个场景均安排配置、窗口或现有组合回归验证。

## Apply handoff

实施范围按 tasks，先修改配套预算并完成边界测试，再执行组合回归。使用现有 Maven 离线设置、项目内缓存和临时目录；原始日志、JUnit 与代码状态记录到 `.codex/reduce-recent-context-window/`。真实模型夹具只编译，不调用付费服务。无未决需求。
