# 独立规划评审

- 变更：`vue-plan-workspace-ui`
- 风险等级：`strict`
- 结论：`BLOCK`
- 评审身份：独立子代理执行上下文 `/root/planning_review`；未撰写本变更 proposal、specs、design 或 tasks，也未修改其正文及业务代码。
- 独立性说明：主评审提供了静态资源入口及刷新初始化的检查线索，本执行上下文直接读取规划和源码后独立核验结论；未把主评审陈述当作验证证据。
- 方法：静态核对本变更全部规划、共享检查表与相关调用链。未启动服务、请求真实用户数据、执行真实模型调用或运行实现验收。

## 阻塞项

### B1：计划所有者权限未覆盖现有静态原文件访问通道

- 规划位置：`specs/vue-plan-read-api/spec.md:7`、`:28` 要求仅登录所有者读取有限视图；`design.md:38`、`:46` 仅设计新增查询接口的鉴权与返回范围，`tasks.md:9` 开始的接口任务及联合验证未覆盖已有静态通道。
- 代码事实：`src/main/java/com/lyw/appgeneration/controller/StaticResourceController.java:30` 接受任意 `deployKey` 下的资源路径，`:49` 直接拼接生成根路径，`:56` 返回文件资源。该入口没有登录、所有者或内部计划文件过滤。`PlanStoragePathResolver.projectRoot/planPath` 将计划保存在同一生成根的 `vue_project_<appId>/.plan.json`。现有 `AuthInterceptor` 只拦截带 `AuthCheck` 的方法，而静态入口没有该注解。
- 影响：即使新增 `GET /api/app/{appId}/plan` 正确校验，已知应用 ID 的访问者仍可构造 `/api/static/vue_project_<appId>/.plan.json` 走现有文件返回链路，绕过所有者检查并取得原始计划字段。该结论为代码可达性分析，未进行线上 HTTP 探测，不宣称已观察到实际数据泄漏。
- 修正条件：先由用户确认将阻断静态入口读取计划旁车文件纳入本期范围，再同步 proposal、specs、design、tasks。至少覆盖 `.plan.json`、计划原子写入临时文件及能够落到这些文件的路径绕过，保留正常网页与资源访问；补充未登录、其他用户、直接路径及绕过路径的拒绝测试和 HTML/JS/CSS 正常访问回归。不得简单给整个公开网站预览统一加所有者权限，也不得仅增加 DTO 权限测试后宣称核心边界已闭合。
- 此项涉及已承诺的核心权限和受限视图，不以接受风险替代修正；修订后需重新评审当前规划基线。

## 非阻塞提醒

1. 刷新初始化必须纳入预览状态改造。`AppChatPage.vue:597` 起的活动、终态和无会话分支会在消息数不少于两条时调用 `updatePreview()`，而该方法始终指向同一个当前 `dist` 地址。设计 Decisions 1、7 与任务 6.2、6.4、7.3 已规定不冒充上一版及刷新降级；实施时必须处理这些初始化调用，不能仅把模板改为 `v-show`。浏览器用例应包含失败后硬刷新和活动回合离页后返回。
2. `generationSession.ts` 中观察计数的结果去重应局限于新增展示/查询触发，不暗中重写既有 SSE 协议校验或将同一工具 ID 的不同结果等同为成功。现规划已要求保持严格校验；按任务 3.2、3.3 验证。
3. 现有工具事件与偏差落盘时序并不保证每个瞬时状态可见。设计 Decisions 4、5 和 Risks 已如实接受此限制，符合用户选择 A，本报告不要求新增 SSE、轮询或完整偏差历史。

## 已核对的可实施部分

- 查询 DTO 使用同一不可变计划快照，历史裁剪及不返回活动回合字段有对应任务；新增只读入口规避原 `load()` 创建目录副作用。
- 查询先校验所有者，再获取 `AppDataLifecycleFence` 许可并复核应用，能够与当前删除关门机制组合；任务安排了两种到达顺序、异常释放和生成并发测试。
- 以用户、应用、本地回合及观察序号隔离计划查询，串行合并并丢弃过期响应，能处理计划版本不变但文件进度变化的现状。
- 失败、取消、只读结束及成功业务终态具有区分；不把 `TOUCHED` 或工具完成认作构建验证通过。
- 23 个必需任务覆盖读取、接口、前端同步、展示、布局与联合回归，依赖可排序；正式验收与独立代码评审位于交付条件，不构成任务循环。
- 风险等级保持 `strict` 合理。现有源码、测试和配置应登记为允许实施修改的 reference；工程规则与确认需求才是持续约束 source。

## 规划基线

以下为独立评审读取的文件 SHA-256；不代表已登记可实施的机器 PASS 基线。规划变更后本报告不能作为新版本的通过结论。

| 文件（相对变更目录） | SHA-256 |
| --- | --- |
| `.openspec.yaml` | `5cf6601fe096d831e331a1b658ceaf873829eb9ca3710c8de55976e560a83141` |
| `proposal.md` | `28021748af6e7d16dfa0516e837a07f3a75c2a8cc91c8924efc3085083103cb8` |
| `design.md` | `cbd24bed10bfb691509480b20154ed2c7797c24efa4c83a47dbc35e7d5c9ed1c` |
| `tasks.md` | `c8b9a14752e135cb41e08bb6fbfa54fbdc80ccca67633bfa6e388b2051fd3b01` |
| `specs/vue-plan-workspace-ui/spec.md` | `cfcd4386d07e7be9d5e7c6181a222330936ad39c85e60fe7a6ace78cbadb7bf4` |
| `specs/vue-plan-read-api/spec.md` | `473196b2649a40124b4ecf5706e0f19b87287fdcdf98cff4ada3a855e925e625` |
| `specs/vue-soft-replan/spec.md` | `4708dcbcf24bac5868a606bd563573753fbbcfd7f1cf8e70c7d6262070b413dc` |

## 后续交接

先解决 B1 的范围与规划修订，再复评；本轮不得记录 `record-plan` PASS 或进入业务实施。前端浏览器样例验证、后端并发与权限测试以及实际代码质量仍属于后续实施验收，本报告没有将其宣称为已通过。
