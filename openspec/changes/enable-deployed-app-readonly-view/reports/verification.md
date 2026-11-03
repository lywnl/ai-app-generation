# Verification: enable-deployed-app-readonly-view

- 日期：2026-11-03。
- Profile：strict；核验方式：machine。
- 结论：Ready for Archive。
- 当前来源：U1 与 U3；U2 的“不限精选”范围已被收紧，其原记录保留在 baselines/deployed-only。
- 基线：Git `09c0284` 加当前未提交实现；U3 已重新完成独立规划复评、record-plan 和首次修改前 start-apply。六项任务均完成。

## 核验结果

普通非作者只有在 `priority == 99`、有效 `deployKey`、成功 `deployedTime` 同时成立时，才能查看历史与查看页内的发布预览。后端逐页授权，前端初始化和重试使用同一资格判断；仅满足任一条件不会开放。作者和管理员保持原权限，共享展示不带内部记忆内容，也不授予写权限。

完整 Requirement／Scenario／Task 映射见 [verification-manifest.json](verification-manifest.json)，实际记录见 [execution-evidence.md](execution-evidence.md)。本轮已核对全部五项需求及场景，不复用旧 U2 的非精选可见结果证明新范围。

- Java：136 项通过。
- 前端：201 项通过，类型检查和生产构建通过。
- 浏览器：20 个当前观测，包含资格四种组合和作者／管理员例外。124 条普通访客请求无受保护写接口、计划、草稿或无资格发布预览请求。
- 独立代码复评：[implementation-review.md](../reviews/implementation-review.md)，PASS，无待修复发现。
- 无关修改与原历史基线哈希核对通过，`git diff --check` 通过，测试服务已清理。

## 交付边界

本地实现与验收完成，尚未提交、推送或部署云端。实际 Java/MVC 与构建前端使用本机合成身份、存储和模型响应，未进行生产或真实业务数据库验证。直接发布 URL 与静态资源的原有公开策略不在本变更范围内，跨源 iframe 观测限制保留。

无数据库迁移。回退需整体恢复本轮前后端联合资格，不能只回退一侧；旧 U2 宽范围仅为历史参考，不能当成用户当前授权。后续正式归档或发布仍需明确指令。
