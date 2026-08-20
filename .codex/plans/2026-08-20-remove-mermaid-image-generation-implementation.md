# 删除 Mermaid 图片生成功能实施计划

> 按已确认的全链路删除方案执行，历史 `docs/superpowers` 记录保持不变。

## 任务清单

- [x] ✅ 新增删除契约测试，并确认现有代码因仍暴露 `diagramTasks` 和 Mermaid 提示而失败。
- [x] ✅ 删除 `MermaidDiagramTool`、`DiagramTask`、`diagramTasks`、`ARCHITECTURE` 分类和图片收集调度分支。
- [x] ✅ 删除图片规划 Prompt 中的 Mermaid 输出契约。
- [x] ✅ 删除生产镜像中的 `mermaid-cli` 安装，保留 Node/npm 构建能力。
- [x] ✅ 更新当前 README、生产说明和图片收集测试。
- [x] ✅ 运行图片模块聚焦测试、完整后端测试、差异检查和残留搜索。
- [x] ✅ 按五轴审查改动；后端重启与健康检查在提交后执行。
