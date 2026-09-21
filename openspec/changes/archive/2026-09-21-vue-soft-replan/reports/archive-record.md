# OpenSpec 归档记录

- 状态：`archived`（归档完成）
- 风险等级：`strict`
- 核验方式：`machine`
- active_change_path：`openspec/changes/vue-soft-replan`
- archived_change_path：`openspec/changes/archive/2026-09-21-vue-soft-replan`
- 检查点：`archive-checkpoint/journal.json`

## 原验收依据

以下路径保留归档前含义；归档后读取时仅将活动路径前缀映射为归档路径，原记录不重写。

- `openspec/changes/vue-soft-replan/reviews/planning-review.json`：`ef0cd883248b62583947c8683e1a62013c39c8b3436d4d90882d71ff70b56be1`
- `openspec/changes/vue-soft-replan/reports/verification.json`：`435f400558edeffbebdc19bc63d0110970e648a89a71c5bf058ebc78bd82edda`

## 核验结果

已复核原记录哈希、原记录覆盖的输入、变更文件和预登记规范内容。实际命令、工作目录、退出码、时间及输出见检查点 commands。

归档后已确认活动目录不存在、归档目录存在、正式规范及归档校验通过，且 openspec list 不再包含该活动变更。

归档核验不等于重新执行业务验收，不证明评审语义或独立身份；不执行提交、推送或部署。
