import { isRelativePlanPath, type PlanSnapshot } from './planSnapshot'
import { PLAN_OBSERVATION_TOOLS, type GenerationStatus, type ToolCallView } from './generationSession'
import { getToolOperationResult, TOOL_OPERATION_LABELS } from './toolOperationDisplay'

export function toolFilePath(view: ToolCallView): string | undefined {
  const argument = view.args.relativeFilePath ?? view.args.relativeDirPath
  if (isRelativePlanPath(argument)) return argument
  if (view.result) {
    try {
      const result = JSON.parse(view.result)
      if (result.protocol === 'file-tool/v1' && result.operation === view.name &&
        getToolOperationResult(view, 'done').status !== 'unknown' && isRelativePlanPath(result.relativePath)) return result.relativePath
    } catch { /* 结果不可识别时不关联文件。 */ }
  }
}
export function presentPlan(plan: PlanSnapshot, tools: ToolCallView[], active: boolean) {
  const planned = plan.files.filter((file) => file.state !== 'OUT_OF_PLAN' && file.action !== 'KEEP')
  const files = plan.files.map((file) => ({ ...file,
    waitingFor: file.dependsOn.filter((path) => {
      const dependency = plan.files.find((item) => item.path === path)
      return dependency?.action !== 'KEEP' && dependency?.state !== 'TOUCHED'
    }),
    executing: active && tools.some((tool) => tool.status === 'streaming' && toolFilePath(tool) === file.path),
  }))
  return { files, total: planned.length, touched: planned.filter((file) => file.state === 'TOUCHED').length,
    outside: files.filter((file) => file.state === 'OUT_OF_PLAN'),
    planned: files.filter((file) => file.state !== 'OUT_OF_PLAN') }
}
export function executionEntries(tools: ToolCallView[], status: GenerationStatus) {
  return tools.filter((view) => PLAN_OBSERVATION_TOOLS.has(view.name)).map((view) => ({
    id: view.id, label: TOOL_OPERATION_LABELS[view.name] ?? view.name,
    path: toolFilePath(view), result: getToolOperationResult(view, status),
  }))
}
