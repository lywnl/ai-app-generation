import { describe, expect, it, vi } from 'vitest'
import { useUserMemories } from './useUserMemories'
import {
  MemoryApiError,
  type MemoryMutation,
  type MemoryPage,
  type UserMemoryApi,
} from '@/api/userMemory'

vi.mock('@/request', () => ({ default: { post: vi.fn() } }))
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve()
}
const page = (content = '中文', total = 1): MemoryPage => ({
  records: [
    {
      id: '100',
      revision: '1',
      category: '语言偏好',
      attributeName: '页面默认语言',
      content,
      status: 'ACTIVE',
      requiresConfirmation: false,
      activeMemory: null,
      createTime: '2026-10-30T12:00:00',
      updateTime: '2026-10-30T12:00:00',
    },
  ],
  totalRow: total,
  pageNum: 1,
  pageSize: 10,
  activeCount: total,
  pendingCount: 0,
})
function api() {
  return {
    list: vi.fn().mockResolvedValue(page()),
    change: vi.fn().mockResolvedValue({ changed: true, targetId: '101', targetKind: 'FORMAL' }),
    history: vi.fn().mockResolvedValue({ records: [], totalRow: 0, pageNum: 1, pageSize: 10 }),
    preview: vi.fn(),
  } satisfies UserMemoryApi
}
const edit: MemoryMutation = {
  action: 'EDIT',
  target: { id: '100', revision: '1' },
  content: '日语',
}

describe('长期记忆页面状态', () => {
  it('切换筛选后丢弃晚到结果，不能把旧标签内容带回来', async () => {
    const old = deferred<MemoryPage>(),
      latest = deferred<MemoryPage>(),
      service = api()
    service.list.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const state = useUserMemories(service)
    state.setUser('7')
    void state.setQuery({ category: '视觉风格' })
    expect(service.list.mock.calls[0]![1].aborted).toBe(true)
    latest.resolve(page('蓝色'))
    await flush()
    old.resolve(page('旧内容'))
    await flush()
    expect(state.records.value[0]?.content).toBe('蓝色')
  })
  it('网络失败后的原操作重试复用请求标识', async () => {
    const service = api(),
      state = useUserMemories(service)
    state.setUser('7')
    await flush()
    service.change.mockRejectedValueOnce(new Error('模拟超时'))
    await expect(state.change(edit)).rejects.toThrow('模拟超时')
    await state.change(edit)
    expect(service.change.mock.calls[0]![0].requestId).toBe(
      service.change.mock.calls[1]![0].requestId,
    )
  })
  it('内容改动后的重试使用新标识', async () => {
    const service = api(),
      state = useUserMemories(service)
    state.setUser('7')
    await flush()
    service.change.mockRejectedValueOnce(new Error('模拟超时'))
    await expect(state.change(edit)).rejects.toThrow()
    await state.change({ ...edit, content: '英文' })
    expect(service.change.mock.calls[0]![0].requestId).not.toBe(
      service.change.mock.calls[1]![0].requestId,
    )
  })
  it('连续点击只发送一次写入，成功前不乐观改写列表', async () => {
    const pending = deferred<{ changed: boolean; targetId: string; targetKind: 'FORMAL' }>(),
      service = api()
    service.change.mockReturnValueOnce(pending.promise)
    const state = useUserMemories(service)
    state.setUser('7')
    await flush()
    const first = state.change(edit)
    expect(await state.change(edit)).toBeNull()
    expect(service.change).toHaveBeenCalledTimes(1)
    expect(state.records.value[0]?.content).toBe('中文')
    pending.resolve({ changed: true, targetId: '101', targetKind: 'FORMAL' })
    await first
    expect(state.busy.value).toBe(false)
  })
  it('版本冲突只刷新，不自动重试覆盖新值', async () => {
    const service = api(),
      state = useUserMemories(service)
    state.setUser('7')
    await flush()
    service.change.mockRejectedValueOnce(new MemoryApiError(40900))
    service.list.mockResolvedValueOnce(page('英文'))
    await expect(state.change(edit)).rejects.toBeInstanceOf(MemoryApiError)
    expect(service.change).toHaveBeenCalledTimes(1)
    expect(state.records.value[0]?.content).toBe('英文')
  })
  it('登录失效立即清空记忆和历史，停止继续读取', async () => {
    const service = api(),
      state = useUserMemories(service)
    state.setUser('7')
    await flush()
    service.list.mockRejectedValueOnce(new MemoryApiError(40100))
    await state.reload()
    expect(state.records.value).toEqual([])
    expect(state.history.value).toEqual([])
    expect(state.accessLost.value).toBe(true)
    await state.reload()
    expect(service.list).toHaveBeenCalledTimes(2)
  })
  it('切换账号时旧请求的内容和写入结果都不能进入新账号', async () => {
    const pending = deferred<{ changed: boolean; targetId: string; targetKind: 'FORMAL' }>(),
      service = api()
    service.change.mockReturnValueOnce(pending.promise)
    const state = useUserMemories(service)
    state.setUser('7')
    await flush()
    const write = state.change(edit)
    service.list.mockResolvedValueOnce(page('第二用户'))
    state.setUser('8')
    await flush()
    pending.resolve({ changed: true, targetId: '101', targetKind: 'FORMAL' })
    expect(await write).toBeNull()
    expect(state.records.value[0]?.content).toBe('第二用户')
  })
  it('撤销最后一页记录后回到有效页码', async () => {
    const service = api()
    service.list
      .mockResolvedValueOnce(page('第一页', 11))
      .mockResolvedValueOnce({ ...page('空页', 10), records: [] })
      .mockResolvedValueOnce(page('回到第一页', 10))
    const state = useUserMemories(service)
    state.setUser('7')
    await flush()
    await state.setQuery({ pageNum: 2 })
    expect(state.query.pageNum).toBe(1)
    expect(state.records.value[0]?.content).toBe('回到第一页')
  })
  it('新增候选成功后切到待确认，清除旧搜索条件', async () => {
    const service = api(),
      state = useUserMemories(service)
    state.setUser('7')
    await flush()
    state.query.keyword = '其他内容'
    await state.change({
      action: 'CREATE',
      category: '语言偏好',
      attributeName: '页面默认语言',
      content: '日语',
      saveMode: 'CANDIDATE',
      expectedActive: null,
    })
    expect(state.query.status).toBe('CANDIDATE')
    expect(state.query.keyword).toBe('')
  })
  it('卸载页面后忽略迟到的查询结果', async () => {
    const pending = deferred<MemoryPage>(),
      service = api()
    service.list.mockReturnValueOnce(pending.promise)
    const state = useUserMemories(service)
    state.setUser('7')
    state.dispose()
    pending.resolve(page('迟到内容'))
    await flush()
    expect(state.records.value).toEqual([])
  })
})
