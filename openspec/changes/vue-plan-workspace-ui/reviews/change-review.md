# 规划评审

- 变更：`vue-plan-workspace-ui`
- 风险等级：`strict`，维持原登记；涉及应用权限、跨应用隔离、计划文件读取与删除并发。
- 结论：`PASS`，原 B1 已经用户确认范围并完成规划修订；仅表示规划可实施，不代表代码缺陷已修复或测试通过。
- 核验方式：`machine`；已确认 Python 3.14.7、单根目录、普通规划文件及非空 UTF-8 checkbox 任务清单，未发现变更目录中的符号链接。按当前 PASS 登记 `record-plan` 并立即 `check-plan`；不执行 `start-apply`。
- 聚合评审者：当前执行上下文 `/root`，也是此前规划作者；本报告不冒充独立评审。
- 独立评审者：子代理 `/root/planning_review`，未撰写本变更规划；当前结论见 [独立复评](independent-planning-review-current.md)，原 [BLOCK 报告](independent-planning-review.md) 保留作为历史，不复用其旧指纹。
- 基线：Git HEAD `3a84e6d9397a54c7d8dcf4a71311212b9f44962b` 与静态入口修订后的磁盘规划；七份文件实际 SHA-256 见当前独立报告，最终机器快照由 `planning-review.json` 记录。Git HEAD 不替代未跟踪文件的内容基线。

## 检查范围与结果

范围依据为 [proposal](../proposal.md)、三个 [specs](../specs/)、[design](../design.md)、[tasks](../tasks.md) 和 [.openspec.yaml](../.openspec.yaml)。没有外部需求文档，来源为本任务用户需求及 A/A/A、上一版预览 A、静态入口防护纳入本期的明确选择；Demo 不作为协议事实。未启动服务、未请求真实用户数据、未调用模型，未运行业务测试或宣称实现验收通过。

| 检查面 | 结果与依据 |
| --- | --- |
| Schema 与产物 | `spec-driven` 的 proposal、specs、design、tasks 齐备；`openspec validate vue-plan-workspace-ui --strict` 成功，仅说明格式范围有效 |
| 需求与来源 | 用户页面内展示、仅所有者查询、失败/取消保留工作区、只读不刷新、上一版失效降级、现有数据原因提示均已承接 |
| 状态真实性 | TOUCHED 与构建成功分离；工具结果状态与计划级状态分离；缺失原因、刷新后历史不完整均未伪造 |
| 查询同步 | design Decisions 4、5 与 tasks 3、4 覆盖同版本进度、响应过期、请求串行合并及用户/应用/回合隔离 |
| 只读与删除 | design Decisions 2、3 与 tasks 1、2 明确读取不创建目录、不接管回合，生命周期许可内复核应用；Decisions 8 补足静态旁路保护 |
| 任务依赖 | 25 项均未勾选，依赖可排序；新增 2.5 → 2.6 纳入 7.1，形成读取/静态防护与接口共同验收；无验收循环前置 |
| 验收安排 | tasks 7 覆盖 Maven、Vitest、构建及桌面/移动端浏览器验证；2.5、2.6 消费真实临时文件响应体核验 GET/HEAD、延迟打开和正常匿名资源；明确替身与真实模型边界 |
| 风险与回退 | strict 适用；不改 P/E 和构建存储、不做迁移；静态防护与接口一起交付，前端回退不撤销静态计划保护 |

## 阻塞项

当前无未解决的规划阻塞。下文保留 B1 的发现及闭合记录，不表示现有业务代码中的旁路已修复。

### B1：静态资源入口绕过计划查询的所有者权限（规划已闭合）

- 优先级：P1；与独立报告 B1 合并为同一根因。
- 原规划位置：`specs/vue-plan-read-api/spec.md:7`、`:28` 及旧 design Decisions 2、旧 tasks 2/7.1，仅保护新增查询接口，未覆盖原始计划文件的另一条访问通道。
- 实现事实：[StaticResourceController.java](../../../../src/main/java/com/lyw/appgeneration/controller/StaticResourceController.java) 第 30 行接受静态资源请求，第 49 行直接拼接生成根及请求路径，第 56 行返回文件；未校验所有者或拒绝计划文件。`AuthInterceptor` 仅匹配 `@AuthCheck`，静态入口没有此注解。计划真实位置为同根 `vue_project_<appId>/.plan.json`。
- 可达路径：`/api/static/vue_project_<appId>/.plan.json`。按当前代码，该路径在文件存在时可绕过新 DTO 接口并返回原始计划；这是静态代码结论，未进行 HTTP 泄漏探测。
- 影响：新增接口即使正确拒绝非所有者，也不能支撑“只有所有者读取计划、只返回有限用户视图”的整体权限目标。文件工具和下载包排除 `.plan.json` 不能替代静态接口的检查。
- 修正条件：经用户确认，将静态读取计划旁车的阻断纳入本期；同步 proposal、specs、design、tasks，覆盖 `.plan.json`、`.plan-*.tmp` 和能够指向这些文件的路径绕过，保持正常页面与 HTML/JS/CSS/图片资源访问。补充直接访问、越权/未登录及绕过拒绝的真实控制器测试和正常预览回归。不得仅对整个公开预览加所有者限制来扩大行为变化，也不能仅按原始 URL 字符串匹配而遗漏归一化或链接旁路。
- 用户决定：明确同意“将这项静态入口防护纳入本期”；不是接受缺口不修复。
- 闭合证据：元数据与 proposal 已登记范围/来源，查询规格新增“静态资源不得提供计划旁车文件”及四个场景，design Decisions 8 明确一次路径解码、目录边界、链接拒绝和实际 Resource 打开时重新检查，tasks 2.5/2.6/7.1/7.4 安排实现及验证。旧 `FileSystemResource` 的延迟绝对路径重开不再作为方案。
- 独立复评结论：当前规划 PASS；GET/HEAD 句柄关闭、路径替换和正常资源访问仍须实施后验证。

## 警告及处置

- **预览初始化不能遗漏。** 当前 `AppChatPage.vue:597` 起在已有消息时调用 `updatePreview()`，包括无会话和活动会话分支。design Decisions 1、7 及 tasks 6.2、6.4、7.3 已覆盖不可冒充上一版；实施须同时处理初始化，不能只改模板为 `v-show`。该项已有验收安排，不新增产品决策。
- **去重不能弱化协议。** tasks 3.2 的工具 ID 去重用于新增观察计数/展示，不能把相同 ID 的冲突结果误判成安全重复，或改写原有严格 SSE 校验；用结果去重及协议回归测试核对。
- **瞬时状态与历史有限是已接受的边界。** 工具事件可能早于偏差落盘；用户已选择不增加轮询和专用 SSE，design 已记录下一事件/手动核对与最终查询。不以此重新要求完整偏差历史或宣称精确事件重放。
- **浏览器失败识别有限。** 同源资源错误和跨源不可核验应分别测试，iframe load 不等于健康；已由预览 A 和 tasks 6.4 接受降级，不恢复版本存储方案 B。

## 约束与实施参考

当前通过基线按下列类别登记；实际输入路径以机器记录 `planning-review.json` 为准，参考代码可按获准任务修改，不能把阅读行为视为冻结实现。

| 类别 | 输入及处理 |
| --- | --- |
| `--plan` | `.openspec.yaml`、proposal、specs 目录、design、tasks；包含已确认用户决定，任务正文及验收要求不能在实施中静默修改 |
| `--source` | `AGENTS.md`、`openspec/config.yaml`、共享 workflow-policy/review-checklist/verification-evidence 及采用的技能规则；正式 `openspec/specs/vue-soft-replan/spec.md` 作为基线约束，本变更声明的展示增量按其范围处理 |
| `--reference` | `AppChatPage.vue`、`generationSession.ts`、工具展示、预览编辑、API/登录状态模块及相关前端测试配置；`AppController`、计划状态/路径读取、查询所依赖的应用读取与生命周期模块、相关后端测试与构建清单。它们可按获准任务修改，不登记为冻结源码 |
| B1 新增参考 | `StaticResourceController` 已纳入获准范围；实施新建的路径读取及测试文件须在实现验收中补入实际代码清单 |
| 报告 | 本聚合报告和独立报告一起绑定；独立报告的哈希表仅记录评审对象，不能充当机器通过记录 |

## 实施交接

当前可交接 `openspec-apply-change`，但本轮未获得业务实施授权，故不执行实施入口。以 `record-plan --profile strict --independent ...` 登记当前规划及报告后立即 `check-plan`；后续实施需明确授权和真实 `start-apply`，规划格式通过不替代这些门槛。

既定 P/E、工具协议和构建存储约束保持不变，新增静态防护使任务数从 23 增至 25，风险等级仍为 strict。权限、删除竞态、过期响应、终态、静态读取和预览浏览器测试均为后续实施验收要求，本报告未将其标为已执行。
