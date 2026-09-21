# vue-soft-replan 当前实现验收

- 日期：2026-09-21；风险等级：strict；核验方式：machine。
- 代码基线：`8b0e8891718467552682835d5b8fd7bc2bc1e996`；本轮没有业务代码修改。
- 当前规划：`reviews/change-review.md`、`reviews/independent-planning-review-current.md` 及本轮 record-plan 基线。
- 任务：37/37；9.5 本轮重开后完成实际剩余核验，任务正文和产品行为未改变。
- 独立代码复核：`reviews/independent-code-review-current.md`，PASS；基于当前提交核查原 P/E、解析热修复、物理删除以及旧 patch 未包含的新消息测试。

## 当前证据与覆盖

详细映射唯一维护于 `reports/verification-manifest.json`：10 条规格需求、39 个场景、37 项任务及 strict 跨需求边界，共 11 个核验组。新增删除范围、前端业务事件、普通取消和通用协议错误均纳入，未将调用 updatePlan 误当作真实模型自动触发偏差。

本轮实际完成：
- 比较 8b0e889 与上一轮验证差异，代码 patch SHA-256 与当时留存值完全一致；当前业务源码、依赖清单无未验证差异。
- 补核 GenerationMessageTest 当前代码与干净构建 JUnit 的两项通过记录，并由独立执行上下文复核其保留的业务断言。
- 冻结三轮实测日志及最终计划快照，防止后续日志追加改变验收输入。
- 核验七个专用类已删除、四类业务信号完整、P/E 核心和保留的其他恢复策略相对原 P/E 提交未改。
- 补齐遗漏的当前需求/场景/任务映射及源码、测试、前端、构建配置覆盖。

这些新增核验位于 `.codex/vue-soft-replan/revalidation-20260921/`。旧 JSON、报告和映射原件保存在其中的 `previous-baseline/`，不覆盖历史事实。

## 运行结果

代码输入一致性已核实，复用上一轮真实执行结果，不因切换流程重跑完整测试：

- 后端聚焦测试通过；日志 `.codex/vue-soft-replan/archive-readiness/removal-focused.log`。
- 干净全量 `bash mvnw -q clean test`：2022 项、4 失败、0 错误、22 跳过，退出码 1；原始日志及 JUnit 快照见 manifest。相关 P/E、取消、队列、工具协议、构建门禁和删除用例通过。
- 前端 `npm test`：14 文件、244 项通过；`npm run type-check`、`npm run build` 通过，构建存在 chunk 大小警告。
- 用户前端三轮真实生成：首次计划及 21 次文件写入后构建成功；追加联系我们调用 updatePlan 升到 version=2 并构建成功；页脚标记文本进入源码和构建产物并成功构建。日志未出现此前参数一致性异常。
- 本轮删除边界核验、manifest 结构检查、OpenSpec strict 校验和 `git diff --check` 通过。

四项失败是 InfrastructureCredentialConfigTest 的三个密码/ACL/模板断言和 ProductionRagDeploymentConfigTest 的 Milvus 密码映射断言。当前生产配置与 README 使用“独立密码优先、共享密码兜底”，测试仍要求旧共享密码字符串；相关文件本变更未改，用户明确暂不处理。保留真实失败记录，作为非本变更限制，不宣称全量测试通过。22 项跳过未计作已验证，未用其替代本变更必需场景。

## 限制与流程说明

- 用户实测没有刻意触发自动偏差 Replan；其确定性行为由现有工具/控制器/检测器测试覆盖。
- 字符串包裹 JSON 数组的输入兼容分支没有独立单测，列为非阻塞覆盖建议。
- 取消与删除同时发生的生产租约窗口没有在本次修改；并发测试已使用接管信号屏障核验声明的等待顺序，不据此宣称消除了所有生产竞态。
- 未做截图视觉验收、真实数据库迁移、上传或部署；没有把模拟测试称为这些外部验收。
- 追加物理删除代码先于本次规划复审。原 P/E 实施开始记录保留；本次 start-apply 只登记 2026-09-21 的继续验证阶段，不补填历史时间，不追认历史代码修改前门禁。该历史流程偏差不因今日记录而消失。

## 结论

当前必需行为、任务、适用验证和当前独立评审已满足；不存在本变更范围内未解决阻塞。结论：`Ready for Archive`。本报告需与本轮 record-verify/check-verify 绑定；未执行归档、提交或推送。
