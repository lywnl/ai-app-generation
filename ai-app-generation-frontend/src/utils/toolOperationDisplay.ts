import MarkdownIt from 'markdown-it'
import type { GenerationStatus, ToolCallView } from './generationSession'
import { parsePlanSnapshot, type PlanSnapshot } from './planSnapshot'

const markdown = new MarkdownIt({ html: false })
const STATUS_LABELS = {
  APPLIED: '已完成', NO_CHANGE: '无变化', REJECTED: '已拒绝',
  NOT_FOUND: '未找到', CANCELLED: '已取消', FAILED: '失败',
  CONFLICT: '版本冲突',
} as const
type OperationStatus = keyof typeof STATUS_LABELS
const FILE_TOOL_STATUSES = new Set(['APPLIED', 'NO_CHANGE', 'REJECTED', 'NOT_FOUND', 'CANCELLED', 'FAILED'])

export const TOOL_OPERATION_LABELS: Record<string, string> = {
  writeFile: '写入文件', modifyFile: '修改文件', readFile: '读取文件',
  readDir: '读取目录', deleteFile: '删除文件', readSkill: '读取 Skill', buildProject: '构建项目',
  makePlan: '创建计划', updatePlan: '调整计划',
}

export interface ToolOperationResult {
  status: OperationStatus | 'BUILD_IN_PROGRESS' | 'streaming' | 'unknown'
  label: string
  message: string
  plan?: Pick<PlanSnapshot, 'version' | 'summary' | 'files'>
}

function parseResult(view: ToolCallView): Record<string, unknown> | undefined {
  if (!view.result) return undefined
  try {
    const value: unknown = JSON.parse(view.result)
    if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
    const result = value as Record<string, unknown>
    if (result.operation !== view.name || typeof result.message !== 'string' || !result.message.trim() ||
      result.content !== null || (result.failureReason !== null && typeof result.failureReason !== 'string')) return undefined
    return result
  } catch {
    return undefined
  }
}

function hasValidStatus(view: ToolCallView, result: Record<string, unknown>): boolean {
  const status = result.status
  if (typeof status !== 'string' || !Object.prototype.hasOwnProperty.call(STATUS_LABELS, status)) return false
  if (view.name === 'readSkill') {
    return result.protocol === 'skill-tool/v1' &&
      ['APPLIED', 'NOT_FOUND', 'REJECTED', 'FAILED'].includes(status) &&
      typeof result.skillName === 'string' && result.skillName.trim().length > 0 &&
      (view.args.skillName === undefined || view.args.skillName === result.skillName)
  }
  if (result.protocol !== 'file-tool/v1' || !FILE_TOOL_STATUSES.has(status) || !Object.prototype.hasOwnProperty.call(TOOL_OPERATION_LABELS, view.name)) return false
  if (result.relativePath !== null && typeof result.relativePath !== 'string') return false
  const expectedPath = view.args.relativeFilePath ?? view.args.relativeDirPath
  if (result.relativePath !== null && expectedPath !== undefined && result.relativePath !== expectedPath) return false
  if (status === 'NO_CHANGE' && !['writeFile', 'modifyFile'].includes(view.name)) return false
  if (status === 'NOT_FOUND' && view.name === 'writeFile') return false
  const changed = status === 'APPLIED' && ['writeFile', 'modifyFile', 'deleteFile'].includes(view.name)
  return result.changed === changed
}

export function getToolOperationResult(view: ToolCallView, sessionStatus: GenerationStatus): ToolOperationResult {
  if (view.status === 'streaming' && sessionStatus === 'streaming') {
    return { status: 'streaming', label: '执行中', message: '等待工具返回结果' }
  }
  if (view.name === 'buildProject') return getBuildResult(view)
  if (view.name === 'makePlan' || view.name === 'updatePlan') return getPlanResult(view)
  const result = view.status === 'done' ? parseResult(view) : undefined
  if (!result || !hasValidStatus(view, result)) {
    return { status: 'unknown', label: '结果未确认', message: '未收到可确认的工具执行结果' }
  }
  const status = result.status as OperationStatus
  return { status, label: STATUS_LABELS[status], message: result.message as string }
}

function getPlanResult(view: ToolCallView): ToolOperationResult {
  const unknown: ToolOperationResult = { status: 'unknown', label: '结果未确认', message: '未收到可确认的计划结果' }
  if (view.status !== 'done' || !view.result) return unknown
  try {
    const value = JSON.parse(view.result)
    if (!value || value.protocol !== 'plan-tool/v1' || value.operation !== view.name ||
      !['APPLIED', 'REJECTED', 'CONFLICT', 'FAILED'].includes(value.status) ||
      typeof value.message !== 'string' || !value.message.trim() || !Array.isArray(value.files) ||
      Object.keys(value).sort().join(',') !== 'files,message,operation,planId,protocol,status,summary,version') return unknown
    if (value.status === 'APPLIED' && (typeof value.planId !== 'string' || !value.planId.trim() ||
      !Number.isSafeInteger(value.version) || value.version < 1 || typeof value.summary !== 'string' || !value.summary.trim())) return unknown
    const status = value.status as OperationStatus
    const labels = { APPLIED: '计划已保存', REJECTED: '计划未接受', CONFLICT: '计划版本已变化', FAILED: '计划保存失败' }
    const plan = status === 'APPLIED' ? parsePlanSnapshot({
      planId: value.planId, version: value.version, summary: value.summary,
      status: 'PLANNED', files: value.files, history: [],
    }) : null
    return { status, label: STATUS_LABELS[status], message: labels[status as keyof typeof labels],
      ...(plan ? { plan: { version: plan.version, summary: plan.summary, files: plan.files } } : {}) }
  } catch { return unknown }
}

function getBuildResult(view: ToolCallView): ToolOperationResult {
  const build = view.status === 'done' ? view.build : undefined
  if (!build) {
    return { status: 'unknown', label: '结果未确认', message: '未收到可确认的构建结果' }
  }
  if (build.invocationStatus === 'BUILD_IN_PROGRESS') {
    return { status: 'BUILD_IN_PROGRESS', label: '构建进行中', message: build.statusText }
  }
  const status = build.invocationStatus === 'COMPLETED'
    ? (build.success ? 'APPLIED' : 'FAILED') : build.invocationStatus
  return { status, label: STATUS_LABELS[status], message: build.statusText }
}

export function getToolOperationTarget(view: ToolCallView): string {
  if (view.name === 'buildProject') return '当前项目'
  if (view.name === 'makePlan' || view.name === 'updatePlan') return '当前计划'
  const path = view.args.relativeFilePath ?? view.args.relativeDirPath ?? view.args.skillName
  return typeof path === 'string' && path.length > 0 ? path : '等待目标信息'
}

export function getToolOperationCode(view: ToolCallView): { before?: string; after?: string } {
  if (view.name !== 'writeFile' && view.name !== 'modifyFile') return {}
  const blocks = view.executedDisplayCommitted && view.executedDisplayText
    ? markdown.parse(view.executedDisplayText, {}).filter((token) => token.type === 'fence') : []
  if (view.name === 'writeFile') {
    return { after: blocks[0]?.content ?? textArgument(view.args.content) }
  }
  return {
    before: blocks[0]?.content ?? textArgument(view.args.oldContent),
    after: blocks[1]?.content ?? textArgument(view.args.newContent),
  }
}

function textArgument(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}
