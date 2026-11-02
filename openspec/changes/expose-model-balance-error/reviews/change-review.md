# Change Review: expose-model-balance-error

- 评审日期：2026-11-02；Profile：standard。
- Source Coverage：Pass；U1 展示目标及 U2 DeepSeek 范围均已落实。
- Pre-Apply Decision：PASS。
- Validation Confidence：相关错误转换、SDK 默认映射器、SSE 编码和前端展示已读取；四件套及 `openspec validate expose-model-balance-error --strict` 校验通过。尚未以规划校验代替业务测试。
- 评审者与独立性：Codex 当前执行上下文普通自查，非独立评审。
- 核验方式：machine；单根、普通 UTF-8 文件、现有 Python 标准库助手。
- 机器记录：`reviews/planning-review.json`，实际记录后生效。

## 评审范围与依据

schema 为 spec-driven。持续约束包含来源、AGENTS、OpenSpec 配置和采用的共享规则；异常、生成收尾、AppService、SSE 与前端会话／页面代码和测试为允许按任务修改的实施参考。

范围是增加已知余额故障的固定提示，继续使用现有字段、错误码与失败枚举。没有放开异常原文透传、权限或持久化隔离，standard 合理；此前图片变更保持独立。

## Blocking findings

无。用户已确认 DeepSeek；暂无原始日志，但可用项目实际 SDK 的 HTTP 402 及真实包装行为验证，未把线上复现设为未经授权的付费测试。

## Warnings

无阻塞警告。丢失 cause 或没有明确余额依据时只能保留通用提示；单独的限流／配额字样不作为余额证据。已登记为设计取舍，防止错误归因。

## Change Source Coverage 检查结果

| 来源／有效要求 | proposal | specs | design | tasks | 结论 |
| --- | --- | --- | --- | --- | --- |
| U1、U2：DeepSeek 余额不足明确展示 | What Changes | 明确识别模型余额不足；前置和普通接口显示余额提示 | 决策 1、2 | 1.1、3.1 | 覆盖 SDK 包装、前置及 JSON |
| U1：生成中和前端可见 | What Changes | 生成中余额失败保持一致终态；前端展示后端明确提示 | 决策 2、3 | 2.1、3.1 | 新历史与前端一致，不误报成功 |
| D1：范围、脱敏、真实验证 | 非目标、Source Inputs | 未知错误与伪余额文本等场景 | 决策 1、4 | 全部 | 保持协议与失败优先级，有反例和原始证据安排 |

正向覆盖与反向追踪均成立。因果链遍历上限、防循环和明确证据匹配是可靠分类的必要工程选择；安全常量、可信原因与终态预留是支持一致失败显示的必要配套。未扩展为余额管理、真实扣费、历史回写或压缩机制调整。

## Apply handoff

先完成 1.1，再接入 2.1，最后执行跨入口及前端回归 3.1。错误码和现有 SYSTEM 失败类别保持兼容，只细化展示；取消、协议、构建等既有终态分支优先于普通异常分类。通过 Java 与 Vitest 替身测试验证，不自动重启或提交。验收报告与指纹为交付条件，无循环任务。
