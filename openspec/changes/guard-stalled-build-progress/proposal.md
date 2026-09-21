## Why

构建前拒绝不消耗真实构建次数，模型可能重复请求而没有实际进展。既有 Replan 去重及 fail-open 不能替代无进展识别和统一收口。

## What Changes

- 类型化门禁诊断，在现有 message 中说明阻塞与下一步。
- 回合级保护器仅观察已可靠提交的工具事实；第一次说明、第二次安排纠偏、有效响应证明送达后再次无进展则结束本轮。
- 计划阻塞和代码失败后无修改拒绝共用保护，复用真实构建会话的计数及变更序号。
- 内部 BUILD_STALLED 经现有终态通道映射 FAILED，保留文件和计划，不刷新预览。

## Capabilities

### New Capabilities

- `vue-build-progress-guard`：结构化构建阻塞、进展识别、反馈送达和唯一终态。

### Modified Capabilities

- `vue-soft-replan`：区分软纠偏与独立停滞终止；构建依赖拒绝改为经过保护器，不立即制造新计划偏差。

## Impact

计划状态管理器、构建工具、词法工具作用域、在线工具执行包装、回合上下文、流式请求控制器/响应处理器、终态投影和预算，以及相关测试。无外部需求文件；依据用户已确认计划与仓库代码，采用 spec-driven/strict/machine。
