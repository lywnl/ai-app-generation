import { describe, expect, it } from 'vitest'
import { useGenerationPreview } from './useGenerationPreview'
describe('预览回合生命周期', () => {
  it('只有成功终态和流完成同时具备才刷新，重复收口仅一次', () => {
    const state = useGenerationPreview(); state.begin('a')
    expect(state.finish({ localTurnId: 'a', outcome: 'succeeded', status: 'streaming' })).toBe(false)
    expect(state.layout.value).toBe('workspace')
    expect(state.finish({ localTurnId: 'a', outcome: 'succeeded', status: 'done' })).toBe(true)
    expect(state.finish({ localTurnId: 'a', outcome: 'succeeded', status: 'done' })).toBe(false)
    expect(state.layout.value).toBe('preview')
  })
  it('已加载 iframe 可保留，只读收口不刷新，失败保持工作区', () => {
    const state = useGenerationPreview(); state.loaded(state.startLoad()); state.begin('a')
    expect(state.retained.value).toBe(true)
    expect(state.finish({ localTurnId: 'a', outcome: 'answered', status: 'done' })).toBe(false)
    expect(state.layout.value).toBe('preview')
    state.begin('b'); state.finish({ localTurnId: 'b', outcome: 'failed', status: 'done' })
    expect(state.layout.value).toBe('workspace'); expect(state.retained.value).toBe(true)
    state.invalidate(); expect(state.retained.value).toBe(false)
  })
  it.each(['cancelled', 'timed_out', 'system_error', 'protocol_error'] as const)('%s 不恢复预览', (outcome) => {
    const state = useGenerationPreview(); state.begin('a')
    expect(state.finish({ localTurnId: 'a', outcome, status: 'error' })).toBe(false)
    expect(state.layout.value).toBe('workspace')
  })
  it('回合前尚未完成的旧加载回调无效；重置不保留上一版', () => {
    const state = useGenerationPreview(), old = state.startLoad()
    state.begin('a'); expect(state.loaded(old)).toBe(false)
    const current = state.startLoad(); expect(state.loaded(current, true)).toBe(true)
    expect(state.crossOrigin.value).toBe(true)
    state.reset(); expect(state.available.value).toBe(false); expect(state.loaded(current)).toBe(false)
  })
  it('超时或资源失效后晚到的主文档成功不能复活上一版', () => {
    const state = useGenerationPreview(), current = state.startLoad()
    state.invalidate(); expect(state.loaded(current)).toBe(false)
  })
})
