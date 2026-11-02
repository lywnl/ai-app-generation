# 图片素材限额验收

- 日期：2026-11-02；Profile：standard；核验方式：machine。
- 规划基线：`reviews/planning-review.json`，持续约束有效且已在首次业务修改前登记实施开始。
- 代码基线：`511edc1` 加本变更工作区修改；本次回归的 `code-state.json` 覆盖已跟踪及新增源码，运行后比对无变化。
- 结论：**Ready for Archive**。5 项任务已完成，无阻塞；未执行归档、提交或重启。

## 实现与覆盖

详细映射统一维护在 [verification-manifest.json](verification-manifest.json)：6 项 Requirement、11 项 Scenario、5 项 Task 均对应实现、测试文件与本次原始证据，共 22 条。核对了双向覆盖，不仅依赖清单结构校验。

实现范围为图片配额策略、搜索工具、收集服务、素材构造器和规划提示词。搜索任务归一空白并去重后最多 6 个，实际 HTTP 请求 `per_page=3&page=1`；响应侧、服务侧及文本入口执行适用配额，汇总最多 18 张搜索图和 1 张 Logo。描述最多 80 个 Unicode 码点，完整 URL 和 Logo 生成设计保留。

自查确认：去重集合为调用内局部状态；资源筛选不修改传入对象；JSON 字段和工具签名兼容；既有线程池、逐结果等待、Logo 优先提交、取消信号和 MDC 保留。未改动资料生命周期、记忆窗口、前端及存储格式。评审为当前执行上下文的普通自查，standard 无独立评审要求。

## 实际验证

最终回归命令：

```text
python3 -B .codex/limit-image-context/run_tests.py regression 'ImageCollection*Test,ImageSearchToolTest,Logo*Test,MermaidImageGenerationRemovalContractTest,AiCodeGeneratorFacadeTest' --sandbox
```

退出码 0，**140 项测试通过，失败 0、错误 0、跳过 0**，包含图片模块 82 项与 Facade 58 项。编译和测试使用现有 Java 25、Maven 离线仓库与项目本地 settings；临时目录和测试工作目录位于 `.codex/limit-image-context/regression/`。

受控组合样例故意让规划返回超量主题、Logo，并让本机提供方每个关键词返回 12 张：实际仅执行 6 次 HTTP 搜索，每次请求 3 张，最终交付 18 张搜索图和 1 张 Logo；所有长说明均为 80 码点，URL 参数完整。另一个样例注入无效 HTTP 响应与 Logo 失败，仍交付去重后的 5 张搜索图，第二次调用得到同样结果。

`git diff --check` 和 `openspec validate limit-image-context --strict` 均以退出码 0 通过。实施期的策略、搜索、收集聚焦测试也通过，最终回归已重新覆盖当前实现；详细命令、时刻和退出码见清单。

原始证据：

- [.codex/limit-image-context/regression/command.json](../../../../.codex/limit-image-context/regression/command.json)：真实 Maven 命令、时间和退出码。
- [.codex/limit-image-context/regression/summary.json](../../../../.codex/limit-image-context/regression/summary.json)：12 个测试类、140 项用例及代码新鲜度比对。
- [.codex/limit-image-context/regression/maven.log](../../../../.codex/limit-image-context/regression/maven.log) 与同目录 `junit/`：原始日志、XML 和测试明细。
- [.codex/limit-image-context/final-checks.json](../../../../.codex/limit-image-context/final-checks.json)：Git、OpenSpec 校验与工作区状态。

## 异常、限制与交付

日志中的 JSON 解析错误、规划异常、Logo 失败和超时来自故障注入；对应断言全部通过。JDK 对 Lombok 的 Unsafe 及 Mockito 动态 agent 给出既有兼容警告，未造成编译或测试失败。

未调用真实 Pexels、Logo 服务、规划模型或主模型，也未做在线生产效果验收；本次 HTTP 证据来自回环地址替身。数量和描述长度受控不等于固定 Token 上限，URL 长度仍取决于提供方。

正在运行的后端使用已有字节码，新逻辑需后续重启加载；已有回合中的素材不被回写。无迁移，回退本轮源码和提示词即可恢复旧规则。无关 `.DS_Store` 保留原样。
