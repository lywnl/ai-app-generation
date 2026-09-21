import { afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('ant-design-vue', () => ({ message: { warning: vi.fn() } }))
import request from '@/request'
import { message } from 'ant-design-vue'
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })
describe('计划请求登录失效的局部处理', () => {
  it('同一 Axios 实例仅为计划请求跳过全页导航，其他接口保持原行为', async () => {
    const location = { pathname: '/app/chat/7', href: 'http://localhost/app/chat/7' }
    vi.stubGlobal('window', { location })
    const options = { adapter: async (config: import('axios').InternalAxiosRequestConfig) => ({
      data: { code: 40100 }, status: 200, statusText: 'OK', headers: {}, config,
      request: { responseURL: 'http://localhost/api/app/7/plan' },
    }) }
    await request.get('/app/7/plan', { ...options, skipLoginRedirect: true })
    expect(location.href).toBe('http://localhost/app/chat/7')
    expect(message.warning).not.toHaveBeenCalled()
    await request.get('/app/get/vo', options)
    expect(location.href).toContain('/user/login?redirect=')
    expect(message.warning).toHaveBeenCalledOnce()
  })
})
