## MODIFIED Requirements

### Requirement: 计划工具必须使用独立受信结果协议

系统 SHALL 提供 `makePlan` 和 `updatePlan` 两个计划工具，并为其使用独立的结构化受信结果协议；系统 SHALL 保持现有文件工具的 8 字段协议、状态枚举和字段集合不变。

#### Scenario: 计划工具结果进入可信执行记录
- **WHEN** `makePlan` 或 `updatePlan` 执行成功或失败
- **THEN** 系统 SHALL 生成可被可信执行记录、转录展示和测试解析的计划工具事实

#### Scenario: 文件工具协议兼容
- **WHEN** 文件工具返回正常结果或资源限制结果
- **THEN** 系统 SHALL 继续使用现有 8 字段文件协议，且计划进度、计划版本和 Replan 状态不得作为新增字段写入该协议

#### Scenario: 只读和评测作用域
- **WHEN** 当前回合是尚未真实修改文件的 `READ_ONLY` 或评测作用域
- **THEN** 系统 SHALL 不允许其创建或修订在线计划，且评测工具集合和评测提示词不得被在线计划规则改变

### Requirement: 计划操作必须遵守作用域和取消边界

系统 SHALL 让计划工具和计划文件写入遵守当前在线工具作用域、回合租约、有效执行阶段和取消状态；初始只读回合首次真实修改后的计划衔接是兼容路径，普通修改回合仍先计划后执行。

#### Scenario: 只读回合尝试创建计划
- **WHEN** 尚无真实修改的 `READ_ONLY` 回合调用 `makePlan` 或 `updatePlan`
- **THEN** 系统 SHALL 拒绝计划变更，并不得创建或修改 `.plan.json`

#### Scenario: 真实修改后计划操作
- **WHEN** 初始只读回合已有当前租约确认的真实修改
- **THEN** 系统 SHALL 允许计划操作，直接修订非 KEEP 条目仍置为 PENDING，不自动回填此前变更；无需继续修改由模型明确 KEEP

#### Scenario: 回合取消期间写计划
- **WHEN** 当前回合已取消、租约失效或工具作用域已撤销
- **THEN** 系统 SHALL 拒绝计划写入，并不得在取消后启动后台计划保存
