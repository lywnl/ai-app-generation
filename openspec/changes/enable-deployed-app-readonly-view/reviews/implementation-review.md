# Implementation Review: enable-deployed-app-readonly-view

- 评审日期：2026-11-03
- Profile：strict
- Code Review Decision：PASS
- 评审者与独立性：`/root/deployed_view_planning_review`；未编写本变更业务代码或测试，本轮仅编写本报告。
- 基线：Git `09c0284` 加 U3 当前未提交实现；本次已对照 `featured-revision` 最终执行记录的源码 SHA-256 核对当前文件，未发现差异。U2 原报告和实现保存在 `baselines/deployed-only/`，不再作为不限精选的有效结论。
- 范围：独立复核 U3 对后端精选及部署联合授权、前端发布预览门槛、拒绝文案和对应测试的修改；未变化的展示隔离、写权限和异步加载实现沿用已完成的独立审查，并核对本轮回归。
- 限制：这是独立代码质量评审，不登记验收，不将本地合成环境称为生产验证。

## 审查范围

业务实现：

- `src/main/java/com/lyw/appgeneration/service/impl/ChatHistoryServiceImpl.java`
- `ai-app-generation-frontend/src/pages/app/AppChatPage.vue`
- `ai-app-generation-frontend/src/utils/appViewAccess.ts`
- `ai-app-generation-frontend/src/composables/useAppChatHistory.ts`

测试及验证实现：

- `ChatHistoryReadonlyAccessTest`、`AppReadonlyHttpContractTest`
- `appViewAccess.test.ts`、`useAppChatHistory.test.ts`、`AppChatPageReadonlyBehavior.test.ts`
- `AppChatPageGeneratingStatus.test.ts` 的定位范围调整
- `ai-app-generation-frontend/tests/fixtures/deployed-app-view-server.mjs`

本轮将受影响实现、测试及浏览器夹具与 U2 原字节基线逐文件比较；未变化的历史控制器、应用写接口、计划授权、Long 序列化、生成会话绑定和预览状态机复用前次核查。U3 未改动这些权限和生命周期实现。

## Findings

无阻塞性缺陷，未发现需要实施修复的代码问题。

## 核对结论

### 部署共享资格与输出边界

历史读取先校验应用 ID、分页大小及有效登录身份，每页重新读取当前应用。作者／管理员以外的用户必须同时满足 `AppConstant.GOOD_APP_PRIORITY.equals(priority)`、非空白 `deployKey` 和非空 `deployedTime`。现有精选常量为整数 99；null、0、1、98、100 等优先级均不能取得共享资格。`view=1` 不参与授权，不存在或已删除的应用仍被拒绝。查询继续按应用 ID 和既有时间游标限定，不引入跨应用拼接或权限缓存。

三条件使用 AND 而非 OR。新增测试分别验证精选且已部署成功、只部署／只精选拒绝，以及首个分页成功后取消精选或移除部署时间时下一页拒绝；拒绝发生在历史查询之前。作者与管理员在未精选、未部署时仍走原权限分支。

普通非作者通过 `Page.map` 获得仅含展示字段的新记录。`memoryMessage`、`memoryOutcome`、`decisionEvidence` 和内部删除标记没有复制到共享对象；作者／管理员保留原始响应行为。服务测试验证原记录及分页结果不被修改，HTTP 测试经过真实控制器、服务和 Jackson 转换，验证内部值没有出现在响应中。此结论是“内部值不外发”，不将 null 字段与彻底不存在的 JSON 属性混为一谈。

### 修改权限及作者兼容

前端只对有效且匹配的作者 ID 展示输入、部署、源码下载和可视化编辑入口。缺失 ID、非法 ID 与不安全的数字 ID 不会相等为作者；正常雪花 ID 字符串符合现有后端 Long 序列化方式。

生成、初始消息、停止、会话恢复、下载、部署和可视化编辑处理函数均有身份守卫；管理员仅保留已有应用详情管理能力。非作者初始化不读取本机作者生成快照，不创建计划请求，不自动发送初始提示词。作者仍沿用计划、生成会话和草稿预览，`view=1` 没有改变作者能力。

后端生成、取消、部署、下载、普通更新和删除的原授权没有放宽。新增真实 MVC 越权测试覆盖六类入口，证明拒绝发生在模型、下载、取消回调和相关写操作之前；既有部署、删除、下载、计划与 SSE 回归也已通过。

### 发布预览和迟到状态

普通非作者在应用信息到达后先检查 `priority === 99 && isAppDeployed(app)`，与后端整数精选值及部署完成条件一致。条件不满足时预览选择函数返回空值，真实页面初始化不会调用预览加载，也不会回退到草稿；页面给出“仅精选且已部署”的说明。符合资格时先设置发布地址，再等待历史；历史零条、一条、失败或等待不阻断合法预览。

管理员在进入精选检查前保留例外：已部署但未精选时仍选择发布地址，未部署时保留原草稿预览；符合资格但发布地址缺失时不自动回退草稿。作者继续沿用自己的工作区链路。初始化与预览重试都复用同一地址选择函数，没有重试绕过精选条件的独立分支。

`useAppChatHistory` 将加载、空记录、错误和重试分开；失败不会推进游标，加载更多重试保持对应请求。reset 清除游标和重试目标，并递增 epoch，旧成功、业务错误和网络错误均不能写入新状态。

页面同时以 pageEpoch 隔离应用信息及历史后的续接，账号 ID／角色或应用变化时解除会话并重置状态。初始发送与普通发送在 nextTick 后再次检查身份和 epoch。iframe 的 load/error 路径检查当前元素及加载标识；切换时中止探测并清理定时器，避免旧预览覆盖新页面。非作者不初始化可视化编辑器，也不处理选中元素消息。

### 可维护性与范围

权限与地址选择辅助函数、历史加载 composable 保持小范围，未新增依赖、数据库字段或分享接口。U3 前端使用现有 AppVO 的 numeric priority；后端复用既有精选常量，不改变优先级的持久化语义。页面函数测试执行从实际 SFC 提取的实现，新增未精选已部署时不调用预览、会话或计划的断言；浏览器组合验收仍需由本轮实际构建补足模板和事件绑定。

原生成阶段提示测试改为定位带 `aria-live="polite"` 的两个生成区域，避免新历史状态区改变全页 `role="status"` 总数而产生误报，原有生成提示断言仍在。

已对 `.codex/enable-deployed-app-readonly-view/protected-state.json` 核对：`generationSession.test.ts` 的内容哈希、暂存差异和未暂存差异全部保持原状。

## 已核对的执行证据

本轮原始目录均位于 `.codex/enable-deployed-app-readonly-view/featured-revision/`；不将旧 U2 通过数量或截图直接作为新范围证据：

| 证据 | 实际结果 | 核查范围 |
| --- | --- | --- |
| `backend-final/command.json`、`junit/` | 136 项通过；失败、错误、跳过均为 0 | 新联合资格、优先级组合、取消精选后的分页拒绝、HTTP 只部署／只精选拒绝、展示序列化、六类越权写入口及原历史、静态资源、计划、部署、删除、下载、SSE |
| `frontend-final/command.json`、`vitest.json` | 201 项通过，零失败和跳过 | 精选与部署组合、管理员例外、真实页面函数、异步隔离及原前端回归 |
| `type-check/command.json` | 退出码 0 | 当前 Vue／TypeScript 类型检查 |
| `production-build/command.json` | 退出码 0 | `/api` 配置下的 U3 实际生产构建 |

浏览器夹具已增加“已部署但未精选”与“未精选且未部署”场景，现有正向场景按 priority 99 提供数据，历史模拟接口同样采用联合条件并保留作者／管理员例外。本评审完成时，本轮浏览器组合场景仍由主执行者验证和汇总；本报告未将其预先记为完成，也没有使用旧 U2 的非精选可见截图冒充 U3 验收。

本轮执行 `git diff --check` 退出码为 0。读取 U2 基线与当前文件的 `git diff --no-index` 返回 1 表示确有预期代码差异，不是测试失败。通过数量仅来自 U3 当前完整测试记录，不叠加旧轮次或重复回归。

## 已知限制与验收交接

- 跨源 iframe 的全部 HTTP／资源状态受浏览器同源策略限制；实现和规划均没有承诺突破该限制。
- 部署字段表示部署完成记录，不代表发布资源一直可用；资源失败由独立预览提示与重试处理，不放宽权限或回退草稿。
- 没有改变既有静态资源接口的公开策略，也没有执行生产部署或真实业务库验证；本变更收紧的是历史服务授权及查看页的预览加载资格，不承诺阻断所有直接发布 URL。
- 当前 U3 代码质量可通过；最终验收仍需主执行者核对新联合条件各组合的真实浏览器证据及完整需求／场景映射与当前代码一致，再登记验收，不以本报告或旧 U2 记录替代该步骤。
