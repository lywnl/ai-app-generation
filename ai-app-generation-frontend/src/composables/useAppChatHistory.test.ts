import { describe, expect, it, vi } from 'vitest'
import { useAppChatHistory } from './useAppChatHistory'

const response = (records: API.ChatHistory[] = []) => ({ data: { code: 0, data: { records } } })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

describe('展示历史的分页和生命周期', () => {
  it('空历史是已加载状态，能够与页面预览独立展示', async () => {
    const onPage = vi.fn()
    const history = useAppChatHistory(vi.fn().mockResolvedValue(response()), onPage)
    expect(await history.load('7')).toBe(true)
    expect(history.loaded.value).toBe(true)
    expect(history.hasMore.value).toBe(false)
    expect(history.error.value).toBe('')
    expect(onPage).toHaveBeenCalledWith([], false)
  })

  it('保留大整数应用 ID 以及原有分页大小和时间游标', async () => {
    const records = Array.from({ length: 10 }, (_, index) => ({ id: 10 - index, createTime: `time-${index}` }))
    const fetchPage = vi.fn().mockResolvedValueOnce(response(records)).mockResolvedValueOnce(response([{ id: 0 }]))
    const onPage = vi.fn()
    const history = useAppChatHistory(fetchPage, onPage)
    await history.load('475079696219832320')
    expect(history.hasMore.value).toBe(true)
    await history.load('475079696219832320', true)
    expect(fetchPage).toHaveBeenNthCalledWith(1, { appId: '475079696219832320', pageSize: 10 })
    expect(fetchPage).toHaveBeenNthCalledWith(2, { appId: '475079696219832320', pageSize: 10, lastCreateTime: 'time-9' })
    expect(onPage).toHaveBeenLastCalledWith([{ id: 0 }], true)
    expect(history.hasMore.value).toBe(false)
  })

  it('业务拒绝展示错误且不被视为成功加载的空应用', async () => {
    const onPage = vi.fn()
    const fetchPage = vi.fn().mockResolvedValueOnce({ data: { code: 40101, message: '该应用尚未部署' } })
      .mockResolvedValueOnce(response([{ id: 1, message: '历史' }]))
    const history = useAppChatHistory(fetchPage, onPage)
    expect(await history.load('7')).toBe(false)
    expect(history.loaded.value).toBe(false)
    expect(history.error.value).toBe('该应用尚未部署')
    expect(onPage).not.toHaveBeenCalled()
    expect(await history.retry()).toBe(true)
    expect(history.error.value).toBe('')
    expect(history.loaded.value).toBe(true)
  })

  it('加载更多失败后重试同一游标，不覆盖已有消息', async () => {
    const records = Array.from({ length: 10 }, (_, id) => ({ id, createTime: 'cursor' }))
    const fetchPage = vi.fn().mockResolvedValueOnce(response(records))
      .mockRejectedValueOnce(new Error('network request details'))
      .mockResolvedValueOnce(response([{ id: 11 }]))
    const onPage = vi.fn()
    const history = useAppChatHistory(fetchPage, onPage)
    await history.load('7')
    await history.load('7', true)
    expect(history.error.value).toBe('加载对话历史失败，请重试')
    expect(onPage).toHaveBeenCalledTimes(1)
    expect(history.loaded.value).toBe(true)
    await history.retry()
    expect(fetchPage.mock.calls[2]).toEqual(fetchPage.mock.calls[1])
    expect(onPage).toHaveBeenLastCalledWith([{ id: 11 }], true)
  })

  it('加载中不重复查询', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    const fetchPage = vi.fn().mockReturnValue(pending.promise)
    const history = useAppChatHistory(fetchPage, vi.fn())
    const first = history.load('7')
    expect(history.loading.value).toBe(true)
    expect(await history.load('7')).toBe(false)
    pending.resolve(response())
    await first
    expect(fetchPage).toHaveBeenCalledTimes(1)
    expect(history.loading.value).toBe(false)
  })

  it.each(['success', 'business-error', 'network-error'])(
    '切换应用或账号后，旧请求的 %s 不改变新状态', async (kind) => {
      const old = deferred<{ data: API.BaseResponsePageChatHistory }>()
      const next = deferred<{ data: API.BaseResponsePageChatHistory }>()
      const fetchPage = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise)
      const onPage = vi.fn()
      const history = useAppChatHistory(fetchPage, onPage)
      const first = history.load('7')
      history.reset()
      const second = history.load('8')
      if (kind === 'network-error') old.reject(new Error('old failure'))
      else old.resolve(kind === 'success' ? response([{ id: 7 }]) : { data: { code: 40101, message: '旧错误' } })
      await first
      expect(history.loading.value).toBe(true)
      expect(history.error.value).toBe('')
      expect(onPage).not.toHaveBeenCalled()
      next.resolve(response([{ id: 8 }]))
      await second
      expect(onPage).toHaveBeenCalledExactlyOnceWith([{ id: 8 }], false)
    },
  )

  it('重置后清理游标和重试目标，不重新请求旧账号的数据', async () => {
    const fetchPage = vi.fn().mockResolvedValue(response(Array.from({ length: 10 }, () => ({ createTime: 'old' }))))
    const history = useAppChatHistory(fetchPage, vi.fn())
    await history.load('7')
    history.reset()
    expect(await history.retry()).toBe(false)
    expect(history.hasMore.value).toBe(false)
    expect(history.loaded.value).toBe(false)
    await history.load('8', true)
    expect(fetchPage).toHaveBeenLastCalledWith({ appId: '8', pageSize: 10 })
  })
})
