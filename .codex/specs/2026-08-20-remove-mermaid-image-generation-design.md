# 删除 Mermaid 图片生成功能设计

## 目标

完整删除图片收集链路中的 Mermaid 流程图/架构图能力，避免本地和生产后端再规划、调度或执行 `mmdc`，同时保持内容图片、unDraw 插画、Logo 和 Vue 工程 npm 构建能力不变。

## 设计

- 删除 `MermaidDiagramTool` 与仅供该工具使用的 `ARCHITECTURE` 图片分类。
- 从 `ImageCollectionPlan` 删除 `diagramTasks` 和 `DiagramTask`，使 LangChain4j 结构化输出契约不再接受该任务类型。
- 从 `ImageCollectionService` 删除 Mermaid 依赖注入和并发调度分支。
- 从图片规划系统提示词删除架构图说明、`diagramTasks` JSON 字段和 Mermaid 语法要求。
- 从生产后端镜像删除 `@mermaid-js/mermaid-cli` 安装；保留 Node.js、npm 与 registry 配置，因为 Vue 项目构建仍依赖它们。
- 更新当前 README 与生产说明；保留 `docs/superpowers` 中的历史设计和计划，不改写历史记录。

## 验收

- 生产源码、生产配置、当前 README 和生产 Dockerfile 不再包含 `MermaidDiagramTool`、`diagramTasks`、`mermaidCode`、`mmdc` 或 `mermaid-cli`。
- 图片收集服务仍能规划并并发收集内容图片、插画和 Logo。
- Spring 上下文、图片模块测试和后端全量测试通过。
- 后端重启后健康状态为 `UP`，新请求不会再执行 `mmdc`。
