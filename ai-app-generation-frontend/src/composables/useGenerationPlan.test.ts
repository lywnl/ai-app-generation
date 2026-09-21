import { describe, expect, it, vi } from 'vitest'
import { useGenerationPlan } from './useGenerationPlan'
import { PlanQueryError } from '@/api/appPlan'
import type { PlanSnapshot } from '@/utils/planSnapshot'
vi.mock('@/request', () => ({ default: { get: vi.fn() } }))

const context = { userId: '1', appId: '7', turnId: 'turn1' }
const plan = (state: 'PENDING' | 'TOUCHED' = 'PENDING'): PlanSnapshot => ({
  planId: 'p', version: 1, status: 'PLANNED', summary: '首页', history: [],
  files: [{ path: 'src/App.vue', action: 'MODIFY', purpose: '首页', state, dependsOn: [] }],
})
function deferred() {
  let resolve!: (value: PlanSnapshot | null) => void, reject!: (error: unknown) => void
  const promise = new Promise<PlanSnapshot | null>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
async function flush() { for (let i = 0; i < 5; i++) await Promise.resolve() }

describe('计划串行同步', () => {
  it('合并初始触发；在途事件丢弃旧结果并只发一次后续查询', async () => {
    const first = deferred(), second = deferred()
    const query = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const state = useGenerationPlan(query)
    state.setContext(context); state.setContext(context); await flush()
    expect(query).toHaveBeenCalledTimes(1)
    state.observe(1); state.observe(2); state.observe(3)
    first.resolve(plan()); await flush()
    expect(state.snapshot.value).toBeNull(); expect(query).toHaveBeenCalledTimes(2)
    second.resolve(plan('TOUCHED')); await flush()
    expect(state.snapshot.value?.files[0]?.state).toBe('TOUCHED')
    expect(state.status.value).toBe('ready')
  })
  it('同版本进度与修订回退按观察接受，不依赖版本大小', async () => {
    const query = vi.fn().mockResolvedValueOnce(plan('TOUCHED')).mockResolvedValueOnce(plan())
    const state = useGenerationPlan(query)
    state.setContext(context); await flush(); state.observe(1); await flush()
    expect(state.snapshot.value?.files[0]?.state).toBe('PENDING')
  })
  it.each(['userId', 'appId', 'turnId'] as const)('切换 %s 取消旧请求并拒绝晚到响应', async (key) => {
    const old = deferred(), fresh = deferred()
    const query = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const state = useGenerationPlan(query)
    state.setContext(context); await flush()
    state.setContext({ ...context, [key]: '9' }); await flush()
    expect(query.mock.calls[0]![1].aborted).toBe(true)
    fresh.resolve(plan('TOUCHED')); await flush(); old.resolve(plan()); await flush()
    expect(state.snapshot.value?.files[0]?.state).toBe('TOUCHED')
  })
  it('普通失败保留旧快照，无定时重试；手动重试可恢复', async () => {
    const query = vi.fn().mockResolvedValueOnce(plan()).mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce(null)
    const state = useGenerationPlan(query)
    state.setContext(context); await flush(); state.observe(1); await flush()
    expect(state.status.value).toBe('stale'); expect(state.snapshot.value).not.toBeNull()
    await flush(); expect(query).toHaveBeenCalledTimes(2)
    state.retry(); await flush(); expect(state.status.value).toBe('empty')
  })
  it('失败期间有新事件仍执行合并查询，终态无需会话缓存', async () => {
    const old = deferred()
    const query = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(plan('TOUCHED'))
    const state = useGenerationPlan(query)
    state.setContext(context); await flush(); state.observe(8)
    old.reject(new Error('network')); await flush()
    expect(query).toHaveBeenCalledTimes(2); expect(state.status.value).toBe('ready')
  })
  it('权限丢失清空并停请求；卸载取消，晚到无效', async () => {
    const pending = deferred()
    const query = vi.fn().mockResolvedValueOnce(plan()).mockRejectedValueOnce(new PlanQueryError(40101)).mockReturnValueOnce(pending.promise)
    const state = useGenerationPlan(query)
    state.setContext(context); await flush(); state.observe(1); await flush()
    expect(state.snapshot.value).toBeNull(); state.retry(); state.observe(2); await flush()
    expect(query).toHaveBeenCalledTimes(2)
    state.setContext({ ...context, turnId: 'next' }); await flush(); state.dispose()
    pending.resolve(plan()); await flush()
    expect(state.status.value).toBe('idle'); expect(state.snapshot.value).toBeNull()
  })
})
