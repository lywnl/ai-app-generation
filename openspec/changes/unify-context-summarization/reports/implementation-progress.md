# 实施证据索引

本文件记录实施事实，不表示整个变更已验收。规划仍以有效的 strict 基线为准，详细任务／需求映射逐步维护在 `verification-manifest.json`。

| 任务 | 实现与验证 | 原始证据 |
| --- | --- | --- |
| 1.1 | 摘要实体、组合覆盖及旧构造兼容、召回格式／Token 校验、内部证据 JSON 隔离；5 个聚焦测试类共 16 项通过，无跳过 | `.codex/unify-context-summarization/runs/001-contracts/command.json`、`maven.log`、JUnit XML；同目录 `code-state.json` 记录已提交及未提交源码状态 |
| 1.2 | 两份初始化结构与条件加列迁移一致；10 项迁移契约测试通过，无跳过，尚未连接真实数据库。 | `.codex/unify-context-summarization/runs/002-migration-contract/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.1 | 实际用户记录 ID 在提交门内绑定并传至请求边界；109 项回合、服务与 Facade 回归通过。 | `.codex/unify-context-summarization/runs/003-turn-identity/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.2 | 版本封装及旧数组兼容、原始工具 JSON、Unicode、未知读取下界和空窗口依赖；12 项测试通过。 | `.codex/unify-context-summarization/runs/004-l0-codec/command.json`、原始日志、JUnit 与代码内容哈希 |
| 1.3 | 真实 MySQL 8.0.40 的 3 项测试通过，无跳过；初始化、旧库升级及重入逐字段保持数据，随机库已清理，DDL 与 DML 失败边界已验证。 | `.codex/unify-context-summarization/runs/005-mysql-migration/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.3 | 同锁追加、系统替换、关闭、覆盖裁剪及恢复保留依赖；92 项聚焦回归通过。首次测试辅助类编译问题已在本次修复，原失败日志保留于 006-source-operations。 | `.codex/unify-context-summarization/runs/007-source-operations/command.json`、原始日志、JUnit 与代码内容哈希 |
| 2.4 | 共享纯工具配对校验，按后端边界判断结束，保留 AI 中途说明；25 项解析及回归测试通过。 | `.codex/unify-context-summarization/runs/008-source-parser/command.json`、原始日志、JUnit 与代码内容哈希 |
| 3.1 | 复用同一转录和可信事实，按实际来源提交记录代次，计划理由先于展示消费采集；166 项测试通过。 | `.codex/unify-context-summarization/runs/009-decision-sources/command.json`、原始日志、JUnit 与代码内容哈希 |
| 3.2 | 用户记录独立 JSON 合并、原文不可改写、实施与验证分离、重复及迟到更新幂等；8 项聚焦测试通过。 | `.codex/unify-context-summarization/runs/010-decision-evidence/command.json`、原始日志、JUnit 与代码内容哈希 |
| 3.3 | 完整批次与采集边界、保存成功及截止共同约束准备凭据，同边界重试幂等；12 项测试通过。首次跨包测试工厂调用问题已修复，失败日志保留于 011-evidence-preparation。 | `.codex/unify-context-summarization/runs/012-evidence-preparation/command.json`、原始日志、JUnit 与代码内容哈希 |
| 3.4 | 分页 API 与反序列化隔离证据，决策指令不覆盖可信失败或进入 L2，归属与删除回填受控；47 项测试通过。 | `.codex/unify-context-summarization/runs/013-evidence-isolation/command.json`、原始日志、JUnit 与代码内容哈希 |
| 4.1 | 显式输入与来源单元合并，分页补齐历史缺口，后端投影按记录 ID 去重且模型同文不提升权限；20 项测试通过。 | `.codex/unify-context-summarization/runs/015-summary-sources/command.json`、原始日志、JUnit 与代码内容哈希 |
| 4.2 | 按 12,288 Token 软目标从最新完整单元回选，保护当前原话、最新超目标批次及未完成批次；11 项测试通过，选择没有存储副作用。 | `.codex/unify-context-summarization/runs/016-summary-window/command.json`、原始日志、JUnit 与代码内容哈希 |
| 4.3 | 统一草稿共用完整单元分块和 3,072 Token 收敛器，输入包含请求封装，旧游标零摘要复用；26 项测试通过，017 的测试重载歧义已修复。 | `.codex/unify-context-summarization/runs/018-unified-draft/command.json`、原始日志、JUnit 与代码内容哈希 |
| 4.4 | 三次只合并新增批次，结束后前沿保持直到剩余材料与终态完整覆盖，证据修订单独增量；37 项组合测试通过。 | `.codex/unify-context-summarization/runs/020-unified-material-coverage/command.json`、原始日志、JUnit 与代码内容哈希 |
| 5.1 | 应用级任务共用、等待后仅合并未覆盖来源、同一绝对截止及执行器退避已通过 192 项回归；旧提示引用与分块测试断言已修正，原 021 失败记录保留。 | `.codex/unify-context-summarization/runs/023-summary-coordination-delta/command.json`、原始日志、JUnit 与代码内容哈希 |
| 5.2 | 摘要短事务按修订提交全部字段，来源及证据锁内复核，失败元数据仅更新局部列；140 项本地事务、竞争及回合回归通过，真实存储证据留给 5.5。 | `.codex/unify-context-summarization/runs/025-unified-persistence/command.json`、原始日志、JUnit 与代码内容哈希 |
| 5.3 | 可靠提交后按来源 CAS 同时回收原文与推进读取下界，确认丢失先重读，Redis 失败仅重试回收；36 项测试通过，覆盖晚到追加、epoch、截止与取消。 | `.codex/unify-context-summarization/runs/026-unified-reclamation/command.json`、原始日志、JUnit 与代码内容哈希 |
| 5.4 | 每次以 REQUIRES_NEW/READ_COMMITTED 读取完整摘要再原子读 L0，按版本下界整对重试；新版完整快照缓存与新旧失效均已接入，115 项回归通过。 | `.codex/unify-context-summarization/runs/028-compatible-snapshots/command.json`、原始日志、JUnit 与代码内容哈希 |
| 7.1 | 请求边界绑定只读计划接口，固定资料移除旧计划，说明补充动作依赖并区分触达与验证；105 项回归通过，测试临时目录固定到项目内。 | `.codex/unify-context-summarization/runs/033-latest-plan/command.json`、原始日志、JUnit 与代码内容哈希 |
| 5.5 | 已授权的 MySQL 8.0.40 与 Redis 真实组合共 7 项通过，无跳过；真实 r1/r2 回收交错、RR 外层隔离、回滚、修订与唯一键冲突、COMMIT 确认丢失、Redis Lua 截止及重试均已验证，每项随机库和 3 个键清理成功。 | `.codex/unify-context-summarization/runs/035-real-storage/command.json`、原始日志、JUnit 与代码内容哈希 |
| 6.1 | 终态赢家在短事务中合并最终证据、保存 AI 可信结果并仅登记前沿结束；132 项本地回归及 9 项真实存储测试通过，真实故障证明证据、AI 行与摘要终态同步回滚，未增加模型调用。 | `.codex/unify-context-summarization/runs/038-terminal-real-storage/command.json`、原始日志、JUnit 与代码内容哈希 |
| 6.2 | 合法完整回合保留原文并以稳定 ID 附加后端终态，错误分支更换 epoch 保持原恢复语义，收尾不再触发 L1；138 项回归及 10 项真实存储测试通过，L2 仍单次使用稳定 AI ID。 | `.codex/unify-context-summarization/runs/040-source-retention/command.json`、原始日志、JUnit 与代码内容哈希 |
| 6.3 | 26 项聚焦测试通过；请求投影只消费相容快照，保留原始工具批次，将带持久身份的后端终态去重汇入状态说明；完整旧副本不受之后的回收影响，分层组装不再隐式读活动 L0/L1。实际模型调用链由后续 7.2 接线。 | `.codex/unify-context-summarization/runs/042-trusted-request-view/command.json`、原始日志、JUnit 与代码内容哈希 |
| 7.2 | 124 项测试通过；初始、续调和两类恢复经过真实请求编排及统一门禁，计划每次刷新且资料各一份，最终发送使用同次完整估算。同步重组、相容副本、读失败、截止及取消已覆盖；修复终态已认领但回调票据仍开放时的迟到放行。存储及模型仍为本地替身。 | `.codex/unify-context-summarization/runs/046-unified-request-snapshot/command.json`、原始日志、JUnit 与代码内容哈希 |
| 7.3 | 249 项回归通过；生产三个阈值与共享截止、已覆盖大 L0 不重复摘要、最新大批次及固定资料超限、真实失败受控停止均已验证。删除旧检查点投影、结果类型、专用状态和专用重建；原检查点专项测试由统一摘要来源／门禁场景替换，保留非 Vue 完整历史兼容路径。 | `.codex/unify-context-summarization/runs/050-unified-admission/command.json`、原始日志、JUnit 与代码内容哈希 |
| 7.4 | 118 项测试通过；完整请求捕获验证 RAG、图片 URL／路径和 Skill 目录各一份，同步等待后不叠加、不再检索；下一用户回合保留正常历史而不继承临时资料。构建及升级反馈仍由有效模型响应确认，准备、失败和取消均不提前送达；资料与反馈不写入 L0。 | `.codex/unify-context-summarization/runs/051-transient-lifecycle/command.json`、原始日志、JUnit 与代码内容哈希 |
| 7.5 | 142 项计划／构建／只读升级／恢复组合测试通过；实际模型请求链验证同版本 REPLAN_PENDING、单份偏差反馈及修订后的刷新，既有权限、绑定次数、真实构建判定、恢复代次和取消规则保持。此项为本地可控输出回归，不替代真实模型长任务验收。 | `.codex/unify-context-summarization/runs/052-plan-guard-recovery/command.json`、原始日志、JUnit 与代码内容哈希 |
| 8.1 | 冷重建与隔离 87 项本地回归、12 项真实存储组合通过；新 epoch 与实际摘要修订一次写入，旧数组必须可靠对齐，旧序号不裁新原文；孤立用户按旧记录补写终态且与前沿登记同事务，保留已有证据和磁盘修改事实，不自动续跑工具。 | `.codex/unify-context-summarization/runs/058-cold-isolation-real-storage/command.json`、原始日志、JUnit 与代码内容哈希 |
| 8.2 | 删除及访问边界回归通过；新增冷重建读取、历史来源回填与孤立终态登记受同一删除栅栏保护，删除后不回填，其他应用保持可写。真实 Redis 验证新旧摘要缓存键均清理，独立证据和 L2 的原有归属及隔离测试保持通过。 | `.codex/unify-context-summarization/runs/057-cold-isolation/command.json`、原始日志、JUnit 与代码内容哈希 |
| 8.3 | 158 项回归通过；观测增加原文保留量、旧结果去重、配对重读和摘要复用类型，真实模型调用单独计数。统一及兼容路径都仅在确认持久提交后计摘要成功；复用、失败和旁路指标异常不虚增模型成功，也不影响门禁结果。 | `.codex/unify-context-summarization/runs/061-summary-observability/command.json`、原始日志、JUnit 与代码内容哈希 |
| 8.4 | 已对照新旧数据夹具及真实存储结果补齐旧数组对齐、读取下界、缓存键、孤立回合修复、单实例发布和分阶段回退说明，git diff --check 通过。不重跑已经完成的迁移，不把未知来源填零或清空新摘要／证据作为回退办法。 | `.codex/unify-context-summarization/runs/062-runtime-docs/command.json`、原始日志、JUnit 与代码内容哈希 |

## 环境边界

任务 1.3 已获用户明确授权并在 `127.0.0.1:3406` 的现有 MySQL 8.0.40 容器执行；只新建并清理本次随机测试库，没有修改既有业务库，容器原已运行。

任务 5.5 已获用户明确授权，在相同 MySQL 服务和 `127.0.0.1:6379` 的 Redis 数据库 0 执行 7 项真实存储组合测试；每项随机 MySQL 库及随机测试应用的 3 个 Redis 键均已验证清理。失败注入使用真实 SQL／COMMIT／Lua 之后的可控异常或暂停，存储没有使用替身；摘要模型为可控输出，不计作真实模型验收。

用户已追加授权本目标内的 Docker 操作，无需重复确认。`ENVIRONMENT_NOTE`：后续在线跨回合集成与真实模型长任务尚未执行，对应任务保持未完成；现有存储及本地替身测试不替代这些验收。
