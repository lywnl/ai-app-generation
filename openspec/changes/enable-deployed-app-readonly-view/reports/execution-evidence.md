# U3 执行证据

本轮有效需求为“精选且已成功部署”。所有当前原始记录位于 `.codex/enable-deployed-app-readonly-view/featured-revision/`，U2 原规划、实现和验收的原字节副本位于 `.codex/enable-deployed-app-readonly-view/baselines/deployed-only/`，不将旧范围的测试结果计入本轮。

| 检查 | 当前记录 | 结果 |
| --- | --- | --- |
| Java／MVC | backend-final/command.json、junit/ | 10 个套件，136 项通过，零失败／错误／跳过 |
| 前端回归 | frontend-final/command.json、vitest.json | 13 个文件，201 项通过，无跳过 |
| Vue／TypeScript | type-check/command.json | 退出码 0 |
| 实际生产构建 | production-build/command.json | 退出码 0，显式 `/api`，产物留在 dist |
| 浏览器 | browser/observations.json、同名 .txt、requests.jsonl | 20 个当前界面观测，合成模型响应驱动一次作者修改完成 |
| 最终检查 | verification-check/command.json、verification-summary.json | 测试、构建与当前源码一致；124 条普通访客请求无写操作、计划、草稿和无资格发布页面访问 |

## 新条件覆盖

- 精选且已部署可读；只部署、只精选、二者都不满足均拒绝历史，查看页不创建预览 iframe。
- null、0、1、98、100 等非精选优先级不能获得共享资格；取消精选或撤销部署记录后，下一页被服务端拒绝。
- 真实 MVC 仍验证共享历史不含内部记忆值，六类写操作拒绝普通非作者；作者和管理员保留原权限。
- 浏览器实测已部署但未精选应用：普通访客看到明确拒绝提示；作者仍能发送合成修改并刷新草稿；管理员仍能读取历史和发布页。管理员未部署应用的草稿预览亦保留。
- 符合条件后的分页、页面交互、空历史、历史失败重试、预览失败重试、三种生成类型、等待和切换应用均重新通过浏览器验证。
- `browser/deployed-not-featured.jpg`、`featured-deployed.jpg`、`owner-generated.jpg` 为当前 JPEG 截图；`console.json` 为空。

## 保留和边界

`generationSession.test.ts` 的内容、暂存及未暂存差异与原保护记录完全一致；U2 的 21 个原始快照文件通过哈希核对。本轮没有新增付费调用、数据库迁移、提交、推送或生产发布，隔离夹具服务和临时浏览器页已关闭。

没有失败或跳过的当前必需测试。权限判断、序列化和前端实际行为使用真实代码；数据库、登录身份、发布页面及模型响应为替身，不称为生产验证。跨源 iframe 观测限制保留。
