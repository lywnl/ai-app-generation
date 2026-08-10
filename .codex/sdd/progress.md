# Vue RAG 混合检索执行进度

## 全分支最终审查修复

- 已修正任务 7 的旧链解释：`rag.hybrid.enabled=false` 只关闭 BM25、RRF、Rerank，不再回到旧 Vue `id/title/category/code` metadata 与通用 `assemble`；生产改用新版 Dense-only、当前目录父文档回查和 `assembleVueProject`。
- `rag.enabled` 现为 Vue 普通生成与真实生成评测的优先总开关；Service 层同步防御，不允许 Facade 之外的生产调用绕过。
- 跨层验收已串联同一 InMemory 存储、真实当前摄取、真实 Dense、父文档与 Hybrid 关闭 Facade，证明旧 schema/旧目录版本不可见、当前完整源码可用。
- 验证：指定覆盖 52/52，纯单元 265/265，均为 0 failure、0 error、0 skipped；`git diff --check` 通过。

## 修复后全范围验证

- 任务 1～8 目标回归：176 项，0 failure、0 error、1 skipped；跳过项为默认门控的真实骨架联网构建。
- 纯单元回归：266 项，0 failure、0 error、0 skipped。
- 完整 `mvn test`：278 项，0 failure、10 error、2 skipped，未通过；错误集合与修复前基线一致：8 个 Spring 上下文测试因缺少 `DEEPSEEK_API_KEY`，2 个 `JsonMessageStreamHandlerTest` 因既有测试未注入 `ToolMessageCollapser`。
- 真实检索门禁报告：状态“未执行”，缺少 `DASHSCOPE_API_KEY`、`SPRING_DATASOURCE_PASSWORD`，未取得 Hit@1、Recall@4 或 Dense 相对退化指标。
- 十条真实生成构建报告：状态“未执行”，缺少 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`、`SPRING_DATASOURCE_PASSWORD`，未取得 10/10 构建结果。
- `git diff --check`：通过；未推送远程。

- 任务 1：完成（提交 `f32d576..ac2bca9`，独立审查通过）
- 任务 2：完成（提交 `ac2bca9..8aead1b`，独立审查通过）
- 任务 3：完成（提交 `8aead1b..e4ce5fe`，独立审查通过）
- 任务 4：完成（提交 `e4ce5fe..b2a2db6`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 5：完成（提交 `b2a2db6..ede5a53`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 6：完成（提交 `ede5a53..12c5d61`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 7：完成（提交 `12c5d61..579e686`，最终复审 Spec Compliance 与 Task quality 均通过，无遗留问题）
- 任务 8：代码实现完成（提交 `579e686..b2be84e`，定向复审通过；真实外部门禁仍未执行）
- 全分支独立审查：最终 Spec Compliance 与 Task quality 均通过，Critical/Important/Minor 均为 0；代码可合并，但完整 Maven 与两个真实外部门禁未通过，当前不可发布
