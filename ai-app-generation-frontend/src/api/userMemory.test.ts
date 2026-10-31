import { beforeEach, describe, expect, it, vi } from 'vitest'
import request from '@/request'
import { MemoryApiError, userMemoryApi } from './userMemory'

vi.mock('@/request', () => ({ default: { post: vi.fn() } }))
const post = vi.mocked(request.post)
const row = {
  id: '9007199254740993',
  revision: '3',
  category: '语言偏好',
  attributeName: '页面默认语言',
  content: '中文',
  status: 'ACTIVE',
  requiresConfirmation: false,
  createTime: '2026-10-30T12:00:00',
  updateTime: '2026-10-30T12:00:00',
  activeMemory: null,
}
const query = { status: 'ACTIVE' as const, pageNum: 1, pageSize: 10 }
const response = (data: unknown) => ({ data: { code: 0, data } })

describe('长期记忆接口边界', () => {
  beforeEach(() => vi.clearAllMocks())
  it('大整数标识始终按字符串保留，分页总数单独解析', async () => {
    post.mockResolvedValue(
      response({
        records: [row],
        totalRow: '1',
        pageNum: 1,
        pageSize: 10,
        activeCount: '1',
        pendingCount: '0',
      }),
    )
    const page = await userMemoryApi.list(query, new AbortController().signal)
    expect(page.records[0]?.id).toBe('9007199254740993')
    expect(page.totalRow).toBe(1)
    expect(post).toHaveBeenCalledWith(
      '/user/memory/list',
      query,
      expect.objectContaining({ skipLoginRedirect: true }),
    )
  })
  it.each([9007199254740992, '9223372036854775808', '1.2'])('拒绝损坏的标识 %s', async (id) => {
    post.mockResolvedValue(
      response({
        records: [{ ...row, id }],
        totalRow: 1,
        pageNum: 1,
        pageSize: 10,
        activeCount: 1,
        pendingCount: 0,
      }),
    )
    await expect(userMemoryApi.list(query, new AbortController().signal)).rejects.toBeInstanceOf(
      MemoryApiError,
    )
  })
  it('不把历史状态记录混入管理中的当前列表', async () => {
    post.mockResolvedValue(
      response({
        records: [{ ...row, status: 'SUPERSEDED' }],
        totalRow: 1,
        pageNum: 1,
        pageSize: 10,
        activeCount: 1,
        pendingCount: 0,
      }),
    )
    await expect(userMemoryApi.list(query, new AbortController().signal)).rejects.toBeInstanceOf(
      MemoryApiError,
    )
  })
  it('保留后端冲突错误，让页面重新确认', async () => {
    post.mockResolvedValue({ data: { code: 40900, message: '记忆已变化' } })
    try {
      await userMemoryApi.change({
        action: 'REVOKE',
        requestId: 'f42802c1-8bed-4e6e-804c-c16a44e5c99f',
        target: { id: '1', revision: '1' },
      })
      expect.fail('应拒绝冲突操作')
    } catch (error) {
      expect(error).toBeInstanceOf(MemoryApiError)
      expect((error as MemoryApiError).conflict).toBe(true)
      expect((error as Error).message).toBe('记忆已变化')
    }
  })
  it('预览返回可校验的正式版本，不将内容当作目标标识', async () => {
    post.mockResolvedValue(
      response({
        category: '语言偏好',
        attributeName: '页面默认语言',
        activeMemory: { id: row.id, revision: '3', content: '中文' },
      }),
    )
    const preview = await userMemoryApi.preview(
      { category: '语言偏好', attributeName: '默认语言' },
      new AbortController().signal,
    )
    expect(preview.activeMemory).toEqual({ id: row.id, revision: '3', content: '中文' })
  })
  it('只接受完整有效的变更结果', async () => {
    post.mockResolvedValue(response({ changed: true, targetId: 123, targetKind: 'FORMAL' }))
    await expect(
      userMemoryApi.change({
        action: 'CANCEL',
        requestId: 'f42802c1-8bed-4e6e-804c-c16a44e5c99f',
        target: { id: '1', revision: '1' },
      }),
    ).rejects.toBeInstanceOf(MemoryApiError)
  })
  it('历史页保留来源以及修改前后的内容', async () => {
    post.mockResolvedValue(
      response({
        records: [
          {
            id: row.id,
            sourceType: 'MANUAL',
            createTime: row.createTime,
            changes: [
              {
                action: 'EDIT',
                category: row.category,
                attributeName: row.attributeName,
                beforeContent: '中文',
                afterContent: '日语',
                targetId: row.id,
                targetKind: 'FORMAL',
                recordStatus: 'ACTIVE',
              },
            ],
          },
        ],
        totalRow: '1',
        pageNum: 1,
        pageSize: 10,
      }),
    )
    const result = await userMemoryApi.history(1, new AbortController().signal)
    expect(result.records[0]?.changes[0]?.beforeContent).toBe('中文')
    expect(result.records[0]?.changes[0]?.afterContent).toBe('日语')
    expect(result.records[0]?.sourceType).toBe('MANUAL')
  })
})
