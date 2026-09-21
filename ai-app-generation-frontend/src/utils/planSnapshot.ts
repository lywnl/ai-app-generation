export const PLAN_STATUSES = ['PLANNED', 'REPLAN_PENDING', 'READY_TO_BUILD', 'BUILT'] as const
export const FILE_ACTIONS = ['CREATE', 'MODIFY', 'DELETE', 'KEEP'] as const
export const FILE_STATES = ['PENDING', 'TOUCHED', 'OUT_OF_PLAN'] as const
export interface PlanFile {
  path: string
  purpose: string
  action: typeof FILE_ACTIONS[number]
  state: typeof FILE_STATES[number]
  dependsOn: string[]
}
export interface PlanSnapshot {
  planId: string
  version: number
  status: typeof PLAN_STATUSES[number]
  summary: string
  files: PlanFile[]
  history: { version: number; reason: string | null }[]
}
export function isRelativePlanPath(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && !/[\\:\u0000-\u001f\u007f]/.test(value) &&
    value.split('/').every((part) => part.length > 0 && part !== '.' && part !== '..')
}
function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}
function positive(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0
}
export function parsePlanSnapshot(value: unknown): PlanSnapshot | null {
  if (value === null) return null
  if (!record(value) || typeof value.planId !== 'string' || !value.planId.trim() ||
    !positive(value.version) || !PLAN_STATUSES.includes(value.status as PlanSnapshot['status']) ||
    typeof value.summary !== 'string' || !value.summary.trim() || !Array.isArray(value.files) ||
    !Array.isArray(value.history) || value.history.length > 20) throw new Error('计划数据不可识别')
  const files: PlanFile[] = value.files.map((file: unknown) => {
    if (!record(file) || !isRelativePlanPath(file.path) || typeof file.purpose !== 'string' ||
      !FILE_ACTIONS.includes(file.action as PlanFile['action']) || !FILE_STATES.includes(file.state as PlanFile['state']) ||
      !Array.isArray(file.dependsOn) || !file.dependsOn.every(isRelativePlanPath)) throw new Error('计划文件数据不可识别')
    return { path: file.path, purpose: file.purpose, action: file.action as PlanFile['action'],
      state: file.state as PlanFile['state'], dependsOn: [...file.dependsOn] as string[] }
  })
  const byPath = new Map(files.map((file) => [file.path, file]))
  if (byPath.size !== files.length) throw new Error('计划路径重复')
  const visiting = new Set<string>(), visited = new Set<string>()
  function visit(path: string) {
    if (visiting.has(path)) throw new Error('计划依赖存在循环')
    if (visited.has(path)) return
    const file = byPath.get(path)
    if (!file) throw new Error('计划依赖缺失')
    visiting.add(path)
    file.dependsOn.forEach(visit)
    visiting.delete(path)
    visited.add(path)
  }
  files.forEach((file) => visit(file.path))
  const history = value.history.map((change: unknown) => {
    if (!record(change) || !positive(change.version) || (change.reason !== null && typeof change.reason !== 'string')) {
      throw new Error('计划修订数据不可识别')
    }
    return { version: change.version, reason: change.reason as string | null }
  })
  return { planId: value.planId, version: value.version, status: value.status as PlanSnapshot['status'],
    summary: value.summary, files, history }
}
