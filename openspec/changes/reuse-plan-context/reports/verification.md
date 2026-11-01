# Verification: reuse-plan-context

- 日期：2026-11-01
- Profile：strict；核验方式：machine
- 结论：**Ready for Archive**。本轮范围内的实现、6 项任务、34 条需求／场景／任务映射与必需验证均完成，无未解决阻塞。
- 规划基线：`dd51e25` 上的本变更规划及 `reviews/planning-review.json`；已在首次编码前通过 `record-plan`、`check-plan`、`start-apply`。本轮只更新任务 checkbox，规划内容与来源未变；最终 `check-plan` 仍通过。
- 代码基线：当前未提交实现，5 个后端文件与 4 个测试文件。`regression-01/code-state.json` 覆盖本轮运行时 793 个项目源码／配置／测试文件（包含未跟踪文件）；最终重新枚举、比较后无新增或内容差异。工作区原有 `.DS_Store` 保留。

## 实现结果

`PlanContext` 根据实际可见的完整工具批次识别成功计划，比较身份、版本、目标及文件定义。`CompressionAwareChatMemory` 保存一份独立运行期附件；`TrustedTurnRequestViewProjector` 在固定来源边界插入它，后续事实保持在其后。`UnifiedContextRequestPreparer` 保留每次只读核实磁盘计划，在最终消息冻结及计量后，通过现有取消／终态／删除边界提交短内存状态。

普通文件触达和构建进度不重建附件，沿用真实工具结果、工程状态和已有反馈。可靠覆盖推进或 epoch／定义变化后重新检查并按需恢复；仅终态登记增加摘要修订号不触发重建。原始工具参数、结果与配对完整保留，附件未进入 L0、L1 新材料或持久编码。

详细的 **3 项 Requirement、25 项 Scenario、6 项任务 → 实现 → 测试节点 → 本轮原始证据** 统一维护于 [verification-manifest.json](verification-manifest.json)，本报告不重复整张映射。

## 实际验证

| 验证 | 结果 | 原始证据 |
| --- | --- | --- |
| 最终聚焦及相关回归 | 34 个测试类，**484 项通过，0 失败、0 错误、0 跳过** | `.codex/reuse-plan-context/regression-01/command.json`、`maven.log`、`junit/` |
| 受控端到端单独运行 | 1 项通过；最终回归再次包含此项 | `.codex/reuse-plan-context/e2e-03/command.json`、`junit/` |
| 真实 Java 链的请求、文件及计划核对 | 9 次实际 `ChatRequest`，2 次连续同步压缩，3 个真实文件写入，一次构建进程替身调用；最终 v2 `BUILT` | `.codex/reuse-plan-context/regression-01/tmp/plan-context-e2e/` 下的 `pipeline.log`、9 份 `request-*.json`、`summary-inputs.json`、`source-final.json`、`final-plan.json` |
| OpenSpec 严格校验、规划基线与差异格式 | 均退出 0；旧统一摘要变更、提示词、预算参数、计划协议及 L0 编码没有差异 | `.codex/reuse-plan-context/final-checks/commands.json`、`1.log`～`4.log` |
| 测试后代码新鲜度 | 793 个登记文件无变化、无新增 | `.codex/reuse-plan-context/final-checks/code-freshness.json` |

命令、工作目录、起止时间、退出码、代码状态和原始日志路径已保存于对应 `command.json`，并由结构化清单索引。环境为本机 Java 25.0.4.1、Maven 3.10.0，使用项目已有离线仓库与配置。最终回归的文件工具工作目录及 Java 临时目录均位于 `.codex/reuse-plan-context/regression-01/`；受控链另开独立 JVM 和隔离工程目录。

端到端保留生产的 12,288 Token 软窗口、49,152／57,344／65,536 阈值，以隔离诊断文件的真实读取结果制造压缩条件。完整计划成功结果可见时额外附件为 0；压缩移除计划后每次只有 1 份，普通续写正文不变，新覆盖后恢复最新磁盘进度。实际发送消息与估算消息及工具定义逐项相同，两次摘要输入均没有恢复附件。

### 相同输入的 Token 对比

对捕获请求保留相同原文、工具定义和临时资料，分别计算旧的额外完整快照规则与新请求；使用现有保守估算器，不声称服务商精确计费 Token。

| 固定样例 | 旧规则 | 新请求 | 新请求额外附件 |
| --- | ---: | ---: | ---: |
| 创建计划后的请求 | 1,969 | 1,621 | 0 |
| 第一次文件写入后的请求 | 2,127 | 1,780 | 0 |
| 修订计划后的请求 | 2,461 | 2,061 | 0 |
| 首次压缩后的恢复请求 | 33,357 | 33,355 | 1 |

完整 9 次比较见 `regression-01/tmp/plan-context-e2e/token-comparison.json`。收益来自省去历史中已有计划之外的副本；需要恢复的请求仍承担完整计划成本，不据固定样例推断线上节省比例。

## 独立评审与失败处置

[规划评审](../reviews/change-review.md) 和 [独立代码评审](../reviews/implementation-review.md) 均为 PASS，评审者为未撰写相应规划／代码的 `/root/plan_context_review`。

代码评审发现的 IR1（P2）已关闭：旧版摘要 `summaryRevision=0` 仍可能按完整历史游标隐藏旧计划，而初版判定只共享了新版可靠裁剪。已将该过滤统一放进 `visibleSource()`，新增“旧版零修订摘要隐藏的成功计划也必须按实际可见历史恢复”回归；最终 484 项验证包括此修复。

保留所有中间失败记录，未冒充通过：`gate-01` 的一个跨回合测试在 `when` 配置时触发旧 stub，改为 `doAnswer` 后通过；`e2e-01` 为新测试导入及构造器装配错误，`e2e-02` 为对工程状态文字的断言不符。均已修正并由 `e2e-03`／`regression-01` 验证。没有未运行、跳过或以降低验收标准绕过的必需项。

## 交付边界

- 受控端到端使用真实 Java 流式编排、请求门禁、摘要服务／草稿引擎／来源选择／覆盖规划、计划与文件工具及构建闸门。模型、数据库／Redis 适配器和 npm 构建进程使用本地替身；构建替身核对三个真实文件后才生成测试产物。这不等于真实模型效果、外部存储或 npm 构建验证，本次约定也不要求这些外部验证。
- 未改公共 API、SSE、工具协议、数据库／Redis 结构、摘要提示词、预算参数、RAG 生命周期或前端；未修改旧 `unify-context-summarization` 的评审／验收材料。无需迁移，代码回退即可恢复旧请求组装规则。
- 当前业务服务未重启，代码未提交、推送或部署。本轮未归档；后续规范同步须先承接旧统一摘要变更，再应用本变更的替代条款，避免旧 delta 覆盖新规则。
- 原始证据保存在本机项目 `.codex/reuse-plan-context/`，被本报告引用的日志及请求文件应保留；不保证仅克隆 Git 仓库即可复核这些本地材料。

本报告与独立评审定稿后，使用 `evidence_manifest.py` 检查映射，再由 `record-verify`／`check-verify` 绑定当前代码、报告和原始证据。机器记录位于 `reports/verification.json`，不代替上述语义评审和真实运行证据。
