# Vue RAG 全分支终审 finding 修复报告

## 结论

- 实现提交：`7fa30d2c93577ca1f87ebc693993acfcfaa9134f`（`修复: 收紧Vue构建安全与质量门禁并发边界`）。
- 本轮终审提出的 Critical/Important 代码 finding 已全部关闭，没有剩余 Critical 或 Important。
- 最新完整 Maven：406 项，0 failure、0 error、7 skipped，`BUILD SUCCESS`。
- 五个策展骨架显式真实构建：6 项，0 failure、0 error、0 skipped，`BUILD SUCCESS`；其中 1 项校验固定来源，5 项分别真实执行 npm 安装与可信 Vite 构建。
- 三个需要真实模型或数据库条件的门禁继续默认关闭；本轮没有访问外部凭据，也没有生成或宣称真实 RAG 指标、正式摄取结果或十条首次生成 10/10 成绩。

## 根因与修复

### 1. 模型生成工程可在宿主权限下执行任意构建代码

根因不是单一 `npm run build` 命令，而是构建进程同时信任了宿主环境、模型生成脚本、依赖解析输入、项目配置、预存 `node_modules` 和项目可影响的 PATH。仅使用 `--ignore-scripts` 不能阻止恶意 build 脚本、Vite/PostCSS 配置、npm alias、Git/文件/URL 依赖或伪造的 Vite CLI。

修复：

- 子进程环境只保留清洗后的 PATH，移除所有秘密、任意宿主变量、相对 PATH 项及项目目录内 PATH 项。
- package.json 的 build 固定为 `vite build`；依赖名限制为当前 Vue 计划白名单，版本只接受受控普通 semver。
- npm 启动前拒绝 lockfile、shrinkwrap、项目 `.npmrc`、预存 `node_modules` 以及 overrides/workspaces/额外依赖图字段。
- 安装使用 `npm install --ignore-scripts --package-lock=false --no-audit --no-fund`。
- 不执行模型脚本；直接以绝对路径运行安装后 Vite CLI，并使用项目根内临时生成的可信 Vite 配置，固定 Vue 插件、相对 base 与空 PostCSS 插件配置；成功或失败后均清理临时配置。

涉及文件：

- `src/main/java/com/lyw/appgeneration/core/builder/ProcessCommandExecutor.java`
- `src/main/java/com/lyw/appgeneration/core/builder/VueProjectBuilder.java`
- `src/test/java/com/lyw/appgeneration/core/builder/ProcessCommandExecutorTest.java`
- `src/test/java/com/lyw/appgeneration/core/builder/VueProjectBuilderTest.java`

### 2. 报告生命周期和 appId 使用进程内或 exists-then-use 协议

根因是单次原子写只能防止文件撕裂，不能让“旧报告失效 → 高成本操作 → 最终/失败报告”成为一个不可交错的事务；进程内 `AtomicLong` 与 `Files.exists()` 也不能阻止不同 JVM 同时领取同一源码/事实路径。

修复：

- 报告目标先以真实父目录规范化，符号链接别名映射到同一锁键。
- 每个报告使用 JVM 内可中断 `ReentrantLock`，再持有同目录跨进程 `FileLock`，锁覆盖完整报告生命周期。
- 锁等待中断时恢复中断标记；业务异常或 Error 保持为主异常，失败报告写入异常追加为 suppressed。
- appId 在调用模型前同时以 `Files.createDirectory` 原子领取源码目录和事实目录；碰撞方重试。
- 事实复制要求预留目录仍为空且复制不覆盖；失败只清理空占位，不删除部分生成事实。
- 线程竞争、符号链接别名、两个真实 JVM 报告竞争和两个真实 JVM appId 竞争均有回归测试。

涉及文件：

- `src/test/java/com/lyw/appgeneration/rag/eval/EvaluationReportLifecycle.java`
- `src/test/java/com/lyw/appgeneration/rag/eval/AtomicEvaluationReportWriterTest.java`
- `src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildEvaluator.java`
- `src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildEvaluatorTest.java`

### 3. 目录依赖契约不完整，空依赖 Map 可变且可为 null

根因是目录加载只分别校验 dependencies/devDependencies，没有校验跨字段同名依赖冲突；Jackson 缺失或 null 字段也原样留在模型中，使下游承担 null 与可变状态。

修复：跨字段同名依赖版本不一致时携带来源相对路径、依赖名和两侧版本 fail-closed；同版本允许；两张依赖表统一归一化为不可变非 null Map。

涉及文件：

- `src/main/java/com/lyw/appgeneration/service/rag/catalog/TemplateCatalog.java`
- `src/test/java/com/lyw/appgeneration/service/rag/catalog/TemplateCatalogTest.java`

### 4. 同一门禁轮次多次读取宿主环境

根因是环境模型与门禁入口各自调用 `System.getenv()`，环境就绪判断、PGVector 目标、模型凭据和 Spring 强制属性可能来自不同时间点的宿主状态。

修复：摄取、检索、生成三个入口各只执行一次 `Map.copyOf(System.getenv())`，所有派生对象和 Spring 强制属性均消费同一不可变快照；环境模型不再提供自行回读宿主环境的入口。新增源码结构测试守住该约束。

涉及文件：

- `src/test/java/com/lyw/appgeneration/rag/VueQualityGateEnvironmentSnapshotTest.java`
- `src/test/java/com/lyw/appgeneration/rag/ingest/VueIngestionEnvironment.java`
- `src/test/java/com/lyw/appgeneration/rag/ingest/VueKnowledgeIngestionQualityGateTest.java`
- `src/test/java/com/lyw/appgeneration/rag/vue/VueEvaluationEnvironment.java`
- `src/test/java/com/lyw/appgeneration/rag/vue/VueRetrievalQualityGateTest.java`
- `src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildEnvironment.java`
- `src/test/java/com/lyw/appgeneration/rag/build/VueGenerationBuildQualityGateTest.java`

## TDD 证据

### RED

- 构建执行边界首轮：19 项中 3 failure，分别证明环境秘密泄漏、仍执行 npm build、恶意 build 脚本未提前拒绝。
- 依赖解析补强：35 项中 16 failure，证明 lockfile/`.npmrc`、非 registry semver、依赖图字段、缺少 `--package-lock=false` 与固定 PostCSS 配置尚未受控。
- 最终构建安全复核：37 项中 2 failure，证明预存 `node_modules` 与危险 PATH 项仍可进入执行边界。
- 报告生命周期：6 项中 3 failure，证明同 JVM operation 重叠、等待锁中断语义缺失、两个真实 JVM 生命周期交错；符号链接别名补充 RED 为 7 项中 1 error，错误为 `OverlappingFileLockException`。
- appId 原子领取：7 项中 1 failure，两个并发评测器实际拿到同一候选 ID；双真实 JVM测试随后固定该协议。
- 目录契约有效 RED：52 项中 5 failure，均为 null/缺失 Map 未归一化或跨字段冲突未拒绝。更早一次 6 failure 包含一条测试期望错误，不计为有效产品 RED。
- 环境快照：1 项中 1 failure，聚合证明三个环境模型仍自行读取宿主环境。

### GREEN

- 最新构建器定向：37 项，0 failure、0 error、0 skipped。
- 报告生命周期与 appId 并发组合：15 项，0 failure、0 error、0 skipped。
- 目录与检索相关组合：83 项，0 failure、0 error、0 skipped。
- 环境快照与三个门禁相关组合：30 项，0 failure、0 error、0 skipped。
- 全套相关定向：205 项，0 failure、0 error、1 skipped；跳过项是默认关闭的真实骨架动态构建。
- 最新显式五骨架：6 项，0 failure、0 error、0 skipped。
- 最新完整 Maven：406 项，0 failure、0 error、7 skipped；Spring Boot 上下文实际启动成功。
- `git diff --check` 与暂存区 `git diff --cached --check` 均通过。
- 三个门禁入口各且仅各有一次 `Map.copyOf(System.getenv())`；对应环境模型没有 `System.getenv()`。

## 剩余风险与发布边界

- 没有剩余 Critical/Important 代码 finding。
- npm registry、宿主安装的 npm/node 二进制及其传递依赖仍属于外部信任边界；本修复禁止模型指定非 registry 源和项目侧解析覆盖，但没有引入由仓库维护者审核的完整离线依赖快照。`--package-lock=false` 是为了拒绝模型提供的锁文件，不等于传递依赖完全可复现。
- 报告锁文件会作为同目录持久协调文件保留；这是跨 JVM 协议的一部分，不是临时文件泄漏。
- 正式 23 条 `text-embedding-v4` 摄取和物理核验、30 条真实 Hybrid/Dense 检索指标、十条首次真实生成 10/10 均未执行，因此发布状态仍为不可发布；默认 Maven 成功不能替代这些外部门禁。
- 本轮未 push、未 merge、未切换分支；`.codex/sdd/progress.md` 与 `.codex/sdd/whole-branch-review.md` 属于主代理未提交内容，未被暂存或纳入实现提交。
