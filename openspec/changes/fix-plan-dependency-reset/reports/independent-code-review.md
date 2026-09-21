# 独立代码评审

- 变更：`fix-plan-dependency-reset`
- 风险等级：`strict`
- 结论：`PASS`
- 评审者：独立执行上下文 `/root/planning_review`；未编写受评源码、测试或规划正文，基于当前磁盘差异和已有执行证据独立复核。
- 范围：三个后端实现文件、三个邻近测试文件、正式规格同步及本次规划。未评估或修复模型循环收口、历史真实计划、前端新功能或实际运行服务。

## 发现

未发现阻塞本次交付的实现缺陷或兼容性回归。

## 核对结果

- `AppPlanStateManager.resetExplicitlyChangedStates` 只重置显式路径的非 KEEP 条目，其他记录直接保留；未把 PENDING 或 OUT_OF_PLAN 提升为已完成。`indirectlyAffectedPaths` 保持反向遍历，输出过滤种子且按计划顺序排列，能穿过显式中间节点。其调用发生在完整图校验之后。
- `UpdatePlanTool` 先合并并校验新图，再重置、生成提示并沿用原有版本 CAS 与租约提交；移除的路径不在新文件列表中，不会被重新加入。提交成功后的 acknowledge 仍是原流程，没有为检查提示新增 Replan 观察、阻断或计数。
- 成功提示最多列出 20 个路径并记录剩余数量，无间接影响时保留默认“计划已保存”。其目的为指导模型，不新增面板展示；原始 message 仍随现有工具结果传输。正式规格与当前设计在该边界上的澄清见 `planning-clarification-review.md`。
- `PlanToolResult.applied` 保留原签名并委托新重载，现有八个字段和状态枚举不变；消息仍经过记录构造器的非空校验。`makePlan` 和旧调用无需修改。
- 新增 router/main 重现测试真实调用文件写入工具及 `beforeBuild`，同时确认写入前门禁拒绝、写入后通过、main 字节和修改时间均不变、租约只记录一次实际变更。与纯粹断言内部集合不同，该用例覆盖原误重置引起的不必要写入链路。
- 状态测试覆盖三节点、各种状态、KEEP、空种子、多个显式节点、稳定排序与去重；工具测试覆盖提示截断、非法图拒绝不改字节、合法删除与重写依赖、作用域内取消，以及新增文件待处理。协议测试覆盖自定义多行消息往返、字段集合和默认文案。
- 当前 Git 差异未修改构建门禁算法、Replan 检测、SSE、前端或运行环境。构建、取消和版本的原有保护未被删减；没有历史计划迁移或自动修复路径。

## 已核验的证据

本执行上下文读取下列实际日志和 Surefire 属性；未重复运行相同测试，也不将实施者摘要单独视为测试通过证据。

| 证据（相对 `.codex/fix-plan-dependency-reset/`） | 核验结果 |
| --- | --- |
| `logs/03-state-protocol.log` | 14 项通过，Maven BUILD SUCCESS |
| `logs/04-tool-gate.log` | 16 项通过，Maven BUILD SUCCESS |
| `logs/05-backend-regression.log` | 54 项，失败/错误/跳过均为 0，BUILD SUCCESS；包含预期异常场景日志，不等同测试失败 |
| `logs/06-frontend-regression.log` | 6 个文件、169 项通过 |
| `plans-before.json` 与当前计划文件 | 独立只读计算清单内 7 份计划的当前 SHA-256，全部与实施前一致 |
| 当前 `target/surefire-reports/TEST-com.lyw.appgeneration.ai.tools.PlanToolTest.xml` | JVM `user.dir` 与 `java.io.tmpdir` 均落在本次 `.codex` 隔离目录 |

`git diff --check` 无输出、退出成功。旧真实计划仅做哈希校验，不输出正文，不进行修复；复核没有访问工作区外用户文件。

## 评审实现基线

路径位于项目根；SHA-256 是本次读取的内容指纹。

| 文件 | SHA-256 |
| --- | --- |
| `src/main/java/com/lyw/appgeneration/ai/plan/AppPlanStateManager.java` | `a5c4f7c742a40f34394cda5072659bab0662086fd464f7c28a419cd2684de29b` |
| `src/main/java/com/lyw/appgeneration/ai/tools/UpdatePlanTool.java` | `28131f0e3ca5b04ef79d27593cbb8062b84b2d40971e3382f55bcb7015e6c688` |
| `src/main/java/com/lyw/appgeneration/ai/tools/PlanToolResult.java` | `a33329a38ae25cc8e4215085d762e8c0a969d17e604db12291dc969363ca4122` |
| `src/test/java/com/lyw/appgeneration/ai/plan/AppPlanStateManagerTest.java` | `b6bd553ccb0c798e968d1956d1fc6e7b8e231bbe1106f14e8c0c680111ec4bfd` |
| `src/test/java/com/lyw/appgeneration/ai/tools/PlanToolTest.java` | `81d3624fbe5c6656ad69761463f3a351ff577ed7e62b0749585ff413924e489c` |
| `src/test/java/com/lyw/appgeneration/ai/tools/PlanToolProtocolSupportTest.java` | `03c79d5b45bc4953185b4ea0b297887d4c8e1a29e529ee8df79e22098532e47c` |
| `openspec/specs/vue-soft-replan/spec.md` | `998cafcc3d07cd314d5dcde5b43ac1be84229f49430423634a9586503a257a84` |

## 验收边界

本结论不等于真实模型端到端验收，也不证明生成代码在实际 npm 构建或用户行为场景下兼容。间接提示可能被模型忽略，历史错误 PENDING 仍需后续正常修订；这些为已确认的本期边界。本评审未修改业务、规划或既有评审记录，不授权自动归档、推送、重启或真实模型调用。
