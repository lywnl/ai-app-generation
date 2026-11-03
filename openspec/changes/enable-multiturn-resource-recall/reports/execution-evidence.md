# 实施与验证证据

基线为 `6938c2f` 加当前工作区实现。完整命令、退出码、起止时间和源码 SHA-256 保存在各运行目录；[执行汇总](../../../../.codex/enable-multiturn-resource-recall/test-summary.json) 同时保留早期失败与当前有效结果。需求、场景及任务的详细映射统一维护在 [verification-manifest.json](verification-manifest.json)。

## 当前有效结果

| 验证范围 | 结果 | 原始记录 |
| --- | --- | --- |
| Java 综合回归：路由、策略、成功查询、RAG、图片、截止、取消、Facade、请求门禁、预算、构建与受控端到端 | 实际 35 类、536 项，失败/错误/跳过均为 0 | [regression-v2](../../../../.codex/enable-multiturn-resource-recall/regression-v2/command.json)，同目录 `junit/`、`maven.log`、`code-state.json` |
| 当前计划恢复及热窗口边界 | 2 类、17 项，失败/错误/跳过均为 0 | [memory-boundary](../../../../.codex/enable-multiturn-resource-recall/memory-boundary/command.json) |
| 恢复测试增加沙箱启用保护后的聚焦复测 | 3 项，失败/错误/跳过均为 0，替代综合回归中相同的 3 项 | [reuse-sandbox](../../../../.codex/enable-multiturn-resource-recall/reuse-sandbox/command.json) |
| 前端会话解析、余额错误、生成中状态及终态 | 116 项通过，无失败或待执行项 | [frontend](../../../../.codex/enable-multiturn-resource-recall/frontend/command.json)、同目录 `vitest.json` |
| 经用户单独授权的真实分类对照 | 240 次，旧新各 120 次，通过固定样例门槛 | [classification-command](../../../../.codex/enable-multiturn-resource-recall/classification-command.json)、[summary](../../../../.codex/enable-multiturn-resource-recall/classification/summary.json) |

Java 不重复计数共 37 类、553 项。综合回归命令中有一个不存在的 `CompressionAwareChatMemoryTest` 选择器，Maven 未对单个未匹配选择器报错；它不计入执行或通过数。已另行运行项目实际存在的 `PlanRestoreWindowTest` 和 `PlanRestoreEventGateTest` 核对对应边界。

Java 使用现有 Java 25、Maven 离线仓库及项目测试配置。`run_tests.py` 的 `--sandbox` 将测试工作目录和临时目录放入本次运行目录，并复制 `embed_text` 只读夹具，夹具也进入源码快照。`VueMultiturnResourceRecallE2ETest` 和 `VueResourceContextReuseTest` 均有沙箱启用条件；上述正式运行中均实际执行，没有用跳过代替通过。复用测试注解是 `regression-v2` 后唯一的源码变化，其余业务和测试输入未变，以 `reuse-sandbox/code-state.json` 绑定最终源码。

## 受控连续调用链

`VueMultiturnResourceRecallE2ETest` 连接真实 `AppServiceImpl → AiCodeGeneratorFacade → RAG/图片编排 → AiServices/TokenStream → UnifiedContextRequestPreparer → 计划/文件/构建工具 → JsonMessageStreamHandler/Finalizer`，在 `.codex` 沙箱实际写入工程文件和计划。

数据库接口、分类与生成模型、检索供应商、图片供应商及 npm 构建执行器是替身；没有声称真实数据库、图片供应商或 npm 构建通过。测试按顺序驱动：只读问答、首次创建失败、重试成功、调整样式、新增新闻、更换背景图。每轮都核对实际模型请求、持久终态、检索次数、完整工具批次、候选未直接进入 L0/历史、同轮清单固定及下轮替换。

`VueResourceContextReuseTest` 在工具续调、协议恢复和未完成工具链恢复三条真实请求路径上触发同步摘要；摘要模型替身只接收原始来源，候选资料不在摘要输入内。摘要后沿用同一资料列表，RAG 与图片各准备一次，计量消息与发送消息完全一致。

并发边界通过可注入时钟和闩锁验证：排队计入唯一预算，慢主题不掩盖后面已完成结果，过期后不搜索或重排，取消删除不启动主模型，队列清理且忽略中断的物理调用退出前不提前归还 RAG 许可。生产默认值另断言为初始 15 秒、后续 8 秒；没有把模拟网络耗时当作线上性能。

本次合成 E2E 的本地预算示例：初始 RAG 303 Token、18 张图片清单 608 Token；后续功能参考 198 Token、6 张图片清单 263 Token；只读和纯样式轮两类资料均为 0。每轮资料准备日志同时记录数量和等待耗时。数字来自测试数据和本地估算器，不是模型供应商计费值，也不表示真实图片 URL 的固定成本。

## 真实分类对照

授权、输入哈希及两次零调用启动失败见 [classification-authorization.md](classification-authorization.md)。`deepseek-flash`、temperature=0、maxTokens=256、无重试，60 条固定合成输入旧新提示词各两轮，实际 240 次且未补跑。

| 指标 | 旧提示词 | 联合提示词 |
| --- | ---: | ---: |
| READ_ONLY 误判 | 0 | 0 |
| MUTATION_REQUIRED 误判 | 0 | 0 |
| 非法输出 | 0 | 0 |
| 平均耗时 | 732.625 ms | 772.192 ms |
| P95 耗时 | 1069 ms | 1030 ms |

新版 RAG 标记错误、图片标记错误及关键否定新增误判均为 0。该结果仅适用于固定 60 条样例和当次模型响应，不泛化为所有线上表达的准确率承诺。样例、旧/新提示词与运行器输入哈希仍和授权记录一致；早期冻结的 `classification-build` 仅证明这次分类评测，不冒充最终业务字节码。

## 失败与修复记录

所有非零运行保留，不计为通过。后续运行重新验证了相关修复，未降低验收条件。

| 原记录 | 失败及处置 | 有效后续证据 |
| --- | --- | --- |
| `t2-policy` | 内部 `CommittedVueTurn` 构造签名和策略测试夹具未同步 | `t2-policy-fixed`、`regression-v2` |
| `t5-executor-base`、`t6-facade` | 任务组/内部 API 接线期间编译未完成 | `t5-executor-core`、`t5-wiring-compile`、`regression-v2` |
| `t6-facade-fixed`、`t6-facade-v3` | 当前 Mockito 配置不能模拟 final reader，及取消分支的多余 stub | 改用真实安全 reader/移除无效 stub；`t5-image-cancel`、`regression-v2` |
| `t7-java-e2e`、`t7-java-e2e-v2`、`t7-composed` | 连续夹具最初缺当前持久用户边界，后续仍有请求/轮次断言失败 | 完成真实服务、终态与请求装配；`t7-composed-v2`、`regression-v2` |
| `dense-compat` | 沙箱缺 `embed_text`，以及线程名兼容断言失败 | 复制夹具并纳入哈希、恢复原线程名前缀；`regression-v2` |
| `regression` | 同一夹具缺失及新增指标未同步接口白名单 | 补齐夹具和低基数指标契约/采样验证；`regression-v2` |
| 独立 CR-1、CR-2 | 计划初始化空目录被当成旧工程；兼容读取未复用完整依赖契约 | 安全识别完全空目录、复用固定版本与依赖解析约束；`review-fixes`、`regression-v2` |
| 在线 Dense-only 复核 | 在线部分结果必须尊重关闭混合检索的配置 | `OnlineSelection` 按开关选 Dense 顺序；生产夹具测试在 `regression-v2` 通过 |

每项原始命令位于 `.codex/enable-multiturn-resource-recall/<记录名>/command.json`；对应日志和 JUnit 与命令同目录，全部被执行汇总索引。失败日志中的预期故障注入和模拟供应商错误不被描述为真实服务故障。

## 边界与保护项

- 原有 `generationSession.test.ts` 内容与暂存状态保持不变，`.DS_Store` 未处理。最终保护核对见 `final-checks.json`。
- 没有数据库迁移、新 Redis 字段或候选结果持久化；L0/L1 协议、8,192 Token 窗口和计划恢复热窗口保持原行为。
- 共享工程类型枚举路由通过真实本机 HTTP 替身测试回归；未运行需要额外付费的真实工程类型分类集成测试。非 Vue 生成及余额提示在 Java 和前端回归范围内。
- 除已授权的分类对照，未调用真实 RAG、搜索图片或 Logo 服务。未提交、推送、重启或部署，当前运行服务尚未加载本轮源码。
- 回退需整体撤销联合提示词、解析器和资料策略，避免新旧格式错配；归档与运行环境切换仍按各自授权执行。
