# 实施证据

- master；风险strict；规划独立评审PASS，2026-09-21T10:41:36Z start-apply通过。
- Maven工作目录为项目根；fork JVM统一使用 `-Djava.io.tmpdir=$PWD/.codex/guard-stalled-build-progress/tmp -Duser.dir=$PWD/.codex/guard-stalled-build-progress/run-root`。未启动服务、调用真实模型/数据库或改写旧计划；初始真实计划哈希见 plans-before.json。
- 运行证据均在 `.codex/guard-stalled-build-progress/`。当前未提交源码及新增文件将在最终验收绑定。

## 已验证

- 4.1：最终Maven相关28类回归529项通过，0失败/0错误/0跳过，退出码0，logs/19-full-green.log。前端目录 npm run test 全套21文件282项通过，logs/20-frontend-all.log；npm run type-check退出码0，logs/21-frontend-types.log。
- 4.2：`node .codex/guard-stalled-build-progress/verify-scope.cjs` 退出码0，证明两份正式/增量规格一致、8份真实旧计划字节哈希未变、隔离共享样本与原文件一致、git diff --check通过，logs/22-scope-final.json。变更和正式规范严格校验通过，logs/23-openspec-change.log、logs/24-openspec-specs.log。

- 1.1、2.3、3.1、3.2：诊断、词法观察、反馈恢复、终态预算及生命周期128项通过，logs/14-lifecycle.log；前端FAILED诊断/不刷新与原协议182项通过，logs/15-frontend.log。依赖阻塞条目使用被依赖文件的状态，避免自身写回把仍存在的依赖误判为新阻塞。
- 独立评审发现的反馈过早确认已修正：仅可信正文、通过完整性检查的普通响应或合法工具执行确认，伪工具正文的恢复仍携当前票据；logs/16-feedback-validity.log 70项通过。
- 独立评审发现的发布队列顺序已修正：BUILD_STALLED与构建终态一样排到统一披露队列尾；新增暂停队列用例证明最后拒绝及跳过结果先于终态。logs/17-publication-boundary.log 85项通过。日志哈希和IO已移出控制器锁，避免观测拖住取消。

- 基线：`-Dtest=AiServiceStreamingResponseHandlerTest,JsonMessageStreamHandlerTest,VueTurnFinalizerTest`，119项通过，退出码0，logs/01-baseline.log。
- 诊断首批：`-Dtest=BuildBlockDiagnosticTest,BuildProjectPlanGateTest,AppPlanStateManagerTest`，17项通过，退出码0，logs/02-diagnostic.log；新增依赖阻塞身份回归待最终重跑。
- 1.2、2.1、2.2：`-Dtest=BuildProgressStreamingTest,BuildProjectPlanGateTest,BuildProgressGuardTest`，18项通过，退出码0，logs/10-committed-observation.log。真实词法工具诊断/文件写回，结果提交后观察、去重、同批等待送达、同步模型回调、持久化失败和取消不被误报为停滞均验证。

## 失败处置

- logs/04、05、06、07、08 中的编译失败依次为新增内部枚举后遗漏Facade及测试穷举分支、跨包测试引用非公共协议助手，已补齐分支并改为既有JSON序列化入口。
- logs/09-flow-regression.log：214项中1项新测试错误断言取消不会产生终态；既有控制器会正确发布CANCELLED。改为断言唯一CANCELLED且无BUILD_STALLED，logs/10通过，未放宽生产取消逻辑。
- logs/11、12 的恢复新用例最初只发了尚未结束的伪工具正文分片，现有协议检测器要到完整响应才确认此类违规；补齐完成事件后 logs/13-recovery-green.log 8项通过，未更改原协议检测时机。
- logs/18-full-regression.log 的529项中只有既有共享JSON样本相对路径缺失1个错误。将仓库原样本复制到隔离run-root对应路径后，重跑相同集合logs/19全通过；未恢复真实user.dir、未改生产代码或放宽断言。

## 验收交接

九项任务已有证据，独立代码评审PASS；最终验收结论和指纹另存。没有真实模型、数据库、部署或运行服务验收，不自动重启、提交、推送或归档。
