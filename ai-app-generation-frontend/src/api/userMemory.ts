import request from '@/request'

export const MEMORY_CATEGORIES = ['语言偏好', '视觉风格', '技术栈倾向', '体验偏好'] as const
export type MemoryCategory = (typeof MEMORY_CATEGORIES)[number]
export type MemoryStatus = 'ACTIVE' | 'CANDIDATE'
export interface MemoryVersion {
  id: string
  revision: string
}
export interface ActiveMemory extends MemoryVersion {
  content: string
}
export interface UserMemory extends MemoryVersion {
  category: MemoryCategory
  attributeName: string
  content: string
  status: MemoryStatus
  requiresConfirmation: boolean
  createTime: string
  updateTime: string
  activeMemory: ActiveMemory | null
}
export interface MemoryQuery {
  status: MemoryStatus
  category?: string
  keyword?: string
  pageNum: number
  pageSize: number
}
export interface MemoryPage {
  records: UserMemory[]
  totalRow: number
  pageNum: number
  pageSize: number
  activeCount: number
  pendingCount: number
}
export interface MemoryPreview {
  category: MemoryCategory
  attributeName: string
  activeMemory: ActiveMemory | null
}
export type MemoryMutation =
  | {
      action: 'CREATE'
      category: MemoryCategory
      attributeName: string
      content: string
      saveMode: MemoryStatus
      expectedActive: MemoryVersion | null
    }
  | { action: 'EDIT'; target: MemoryVersion; content: string }
  | { action: 'ADOPT'; target: MemoryVersion; expectedActive: MemoryVersion | null }
  | { action: 'REVOKE' | 'CANCEL'; target: MemoryVersion }
export interface MemoryMutationResult {
  changed: boolean
  targetId: string
  targetKind: 'FORMAL' | 'CANDIDATE'
}
export interface MemoryHistoryChange {
  action: 'CREATE' | 'EDIT' | 'ADOPT' | 'REVOKE' | 'CANDIDATE_CREATE' | 'CANCEL' | 'SAVED'
  category: MemoryCategory
  attributeName: string
  beforeContent: string | null
  afterContent: string | null
  targetId: string
  targetKind: 'FORMAL' | 'CANDIDATE'
  recordStatus: string
}
export interface MemoryHistoryEntry {
  id: string
  sourceType: 'CHAT' | 'MANUAL' | 'LEGACY'
  createTime: string
  changes: MemoryHistoryChange[]
}
export interface MemoryHistoryPage {
  records: MemoryHistoryEntry[]
  totalRow: number
  pageNum: number
  pageSize: number
}

export class MemoryApiError extends Error {
  constructor(
    readonly code: number,
    message?: string,
  ) {
    super(
      message ||
        (code === 40900 ? '记忆已发生变化，请刷新后重新确认' : '暂时无法完成记忆操作，请重试'),
    )
  }
  get accessLost() {
    return this.code === 40100 || this.code === 40101 || this.code === 40300
  }
  get conflict() {
    return this.code === 40900 || this.code === 40400
  }
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new MemoryApiError(50000)
  return value as Record<string, unknown>
}
function text(value: unknown): string {
  if (typeof value !== 'string') throw new MemoryApiError(50000)
  return value
}
function id(value: unknown): string {
  const result = text(value)
  if (!/^[1-9]\d{0,18}$/.test(result) || BigInt(result) > 9223372036854775807n)
    throw new MemoryApiError(50000)
  return result
}
function count(value: unknown): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value)))
    throw new MemoryApiError(50000)
  const result = Number(value)
  if (!Number.isSafeInteger(result) || result < 0) throw new MemoryApiError(50000)
  return result
}
function category(value: unknown): MemoryCategory {
  if (!MEMORY_CATEGORIES.includes(value as MemoryCategory)) throw new MemoryApiError(50000)
  return value as MemoryCategory
}
function active(value: unknown): ActiveMemory | null {
  if (value == null) return null
  const row = object(value)
  return { id: id(row.id), revision: id(row.revision), content: text(row.content) }
}
function record(value: unknown): UserMemory {
  const row = object(value)
  if (row.status !== 'ACTIVE' && row.status !== 'CANDIDATE') throw new MemoryApiError(50000)
  return {
    id: id(row.id),
    revision: id(row.revision),
    category: category(row.category),
    attributeName: text(row.attributeName),
    content: text(row.content),
    status: row.status,
    requiresConfirmation: row.requiresConfirmation === true,
    createTime: text(row.createTime),
    updateTime: text(row.updateTime),
    activeMemory: active(row.activeMemory),
  }
}
async function post(
  path: string,
  body: unknown,
  signal?: AbortSignal,
): Promise<Record<string, unknown>> {
  const response = await request.post(`/user/memory/${path}`, body, {
    signal,
    timeout: path === 'change' ? 20_000 : 10_000,
    skipLoginRedirect: true,
  })
  if (response.data?.code !== 0) {
    throw new MemoryApiError(
      response.data?.code ?? 50000,
      typeof response.data?.message === 'string' ? response.data.message : undefined,
    )
  }
  return object(response.data.data)
}

export const userMemoryApi = {
  async list(query: MemoryQuery, signal: AbortSignal): Promise<MemoryPage> {
    const data = await post('list', query, signal)
    if (!Array.isArray(data.records)) throw new MemoryApiError(50000)
    return {
      records: data.records.map(record),
      totalRow: count(data.totalRow),
      pageNum: count(data.pageNum),
      pageSize: count(data.pageSize),
      activeCount: count(data.activeCount),
      pendingCount: count(data.pendingCount),
    }
  },
  async preview(
    value: { category: MemoryCategory; attributeName: string },
    signal: AbortSignal,
  ): Promise<MemoryPreview> {
    const data = await post('preview', value, signal)
    return {
      category: category(data.category),
      attributeName: text(data.attributeName),
      activeMemory: active(data.activeMemory),
    }
  },
  async change(value: MemoryMutation & { requestId: string }): Promise<MemoryMutationResult> {
    const data = await post('change', value)
    if (
      typeof data.changed !== 'boolean' ||
      (data.targetKind !== 'FORMAL' && data.targetKind !== 'CANDIDATE')
    )
      throw new MemoryApiError(50000)
    return { changed: data.changed, targetId: id(data.targetId), targetKind: data.targetKind }
  },
  async history(pageNum: number, signal: AbortSignal): Promise<MemoryHistoryPage> {
    const data = await post('history', { pageNum, pageSize: 10 }, signal)
    if (!Array.isArray(data.records)) throw new MemoryApiError(50000)
    const records = data.records.map((value) => {
      const entry = object(value)
      if (
        !['CHAT', 'MANUAL', 'LEGACY'].includes(String(entry.sourceType)) ||
        !Array.isArray(entry.changes)
      )
        throw new MemoryApiError(50000)
      const changes = entry.changes.map((value) => {
        const change = object(value)
        if (
          !['CREATE', 'EDIT', 'ADOPT', 'REVOKE', 'CANDIDATE_CREATE', 'CANCEL', 'SAVED'].includes(
            String(change.action),
          ) ||
          !['FORMAL', 'CANDIDATE'].includes(String(change.targetKind))
        )
          throw new MemoryApiError(50000)
        return {
          action: change.action,
          category: category(change.category),
          attributeName: text(change.attributeName),
          beforeContent: change.beforeContent == null ? null : text(change.beforeContent),
          afterContent: change.afterContent == null ? null : text(change.afterContent),
          targetId: id(change.targetId),
          targetKind: change.targetKind,
          recordStatus: text(change.recordStatus),
        } as MemoryHistoryChange
      })
      return {
        id: id(entry.id),
        sourceType: entry.sourceType,
        createTime: text(entry.createTime),
        changes,
      } as MemoryHistoryEntry
    })
    return {
      records,
      totalRow: count(data.totalRow),
      pageNum: count(data.pageNum),
      pageSize: count(data.pageSize),
    }
  },
}

export type UserMemoryApi = typeof userMemoryApi
