# 任务 4 实施报告

## 实现结果

- 新增纯增量 `ToolProtocolRecoveryDetector`，只依赖 Jackson 与 JDK，不依赖模型、ChatMemory、SSE 或 Vue。
- 明确返回 `Text`、`Buffering`、`Duplicate` 三类状态。
- 首个完整候选隔离；相同规范指纹的第二个连续候选触发终态 `Duplicate`，两个伪工具块均不下发。
- 不同候选采用滚动窗口：释放旧候选并保留新候选；正文打断旧候选时原样释放旧候选与正文，正文后的新候选仍可参与后续判定。
- 真实结构化工具调用到达后释放暂存内容并永久关闭正文检测；Duplicate 已形成后，迟到 structured 通知和后续正文均不能逆转或泄漏污染。
- 严格识别注册工具、对象根节点、完整 JSON；拒绝未知工具、残缺 JSON、重复键、非对象根节点和无效语法。
- 指纹递归排序对象键，保持数组顺序、字符串/布尔/null 语义和数字原始词法，区分 `1`、`1.0`、`1e0`。

## TDD 证据

### RED

命令：

```bash
export JAVA_HOME="$PWD/.codex/runtime/jdk-25.0.4+7/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
bash mvnw -Dtest='ToolProtocolRecoveryDetectorTest' test \
  2>&1 | tee .codex/verification/task-4-red.log
```

结果：使用 Java 25，测试编译因 `ToolProtocolRecoveryDetector` 尚不存在而失败；属于目标 API 缺失的有效 RED，不是环境错误。

实现过程中又用新增测试发现并修复两项真实缺口：

- Jackson 树模型会合并 `1.0` 与 `1e0` 的词法差异，改为流式 Token 规范化后保持数字原文。
- Duplicate 后迟到 structured 通知一度会逆转终态，现固定保持 `Duplicate`。

### GREEN

命令：

```bash
bash mvnw -Dtest='ToolProtocolRecoveryDetectorTest' test \
  2>&1 | tee .codex/verification/task-4-green-final.log
git diff --check
```

结果：`12` 项测试，`0` failure、`0` error、`0` skipped，`BUILD SUCCESS`；`git diff --check` 通过。

## 独立审查

- 规格符合性：通过。
- 代码质量：通过，Approved。
- Critical：0。
- Important：0。
- Minor：0。

## 修改文件

- `src/main/java/dev/langchain4j/service/ToolProtocolRecoveryDetector.java`
- `src/test/java/dev/langchain4j/service/ToolProtocolRecoveryDetectorTest.java`
- `.codex/plans/2026-08-18-memory-projection-tool-protocol-recovery-implementation.md`
- `.codex/sdd/task-4-report.md`

## 风险与后置项

- 本任务只提供检测器；generation 撤销、一次恢复和二次熔断分别由后续任务 5、6 接入。
- 生产适配层与真实模型稳定性探针留在任务 9，Chrome 行为验收留在任务 10。
