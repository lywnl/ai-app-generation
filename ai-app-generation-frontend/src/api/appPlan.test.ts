import { afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/request', () => ({ default: { get: vi.fn() } }))
import request from '@/request'
import { getAppPlan, PlanQueryError } from './appPlan'

afterEach(() => vi.resetAllMocks())
describe('计划查询', () => {
  it('保留长整数 ID，传递取消信号和十秒超时，接受空态', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { code: 0, data: null } })
    const signal = new AbortController().signal
    expect(await getAppPlan('9223372036854775807', signal)).toBeNull()
    expect(request.get).toHaveBeenCalledWith('/app/9223372036854775807/plan', { signal, timeout: 10000, skipLoginRedirect: true })
  })
  it('业务失败不解析为正常空态', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { code: 40101 } })
    await expect(getAppPlan('7', new AbortController().signal)).rejects.toMatchObject({ accessLost: true })
    expect(new PlanQueryError(50000).accessLost).toBe(false)
  })
  it.each(['ECONNABORTED', 'ERR_CANCELED'])('保留超时和取消拒绝 %s', async (code) => {
    vi.mocked(request.get).mockRejectedValue({ code })
    await expect(getAppPlan('7', new AbortController().signal)).rejects.toEqual({ code })
  })
})
