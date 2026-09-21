import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearGenerationSession, getGenerationSessionSnapshot, startGenerationSession, PLAN_OBSERVATION_TOOLS } from './generationSession'
afterEach(() => { clearGenerationSession('7'); vi.unstubAllGlobals() })
describe('计划观察计数', () => {
  it('八类工具成功失败均推进一次，重复结果、参数和正文不重复推进', async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new ReadableStream({ start(c) { controller = c } }), {
      headers: { 'Content-Type': 'text/event-stream' },
    })))
    startGenerationSession({ appId: '7', userMessage: '构建', generationId: 'local-turn', baseURL: 'http://localhost/api', expectVueTurnOutcome: true, renderMode: 'direct' })
    let sequence = 0
    const write = (payload: unknown) => controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ protocol: 'generation-stream/v1', sequence: ++sequence, kind: 'structured_tool_event', generation: '1', data: JSON.stringify(payload) })}\n\n`))
    for (const name of PLAN_OBSERVATION_TOOLS) {
      const payload = { type: 'tool_executed', id: name, name, arguments: '{}', result: '{"status":"FAILED"}' }
      write(payload); write(payload)
    }
    write({ type: 'tool_request', id: 'extra', name: 'writeFile' })
    write({ type: 'tool_argument', id: 'extra', name: 'writeFile', key: 'relativeFilePath', value: 'src/App.vue' })
    await vi.waitFor(() => expect(getGenerationSessionSnapshot('7')?.planObservation).toBe(8))
    const snapshot = getGenerationSessionSnapshot('7')!
    expect(snapshot.localTurnId).toBe('local-turn')
    expect(snapshot.toolCalls.size).toBe(9)
    snapshot.toolCalls.clear()
    expect(getGenerationSessionSnapshot('7')?.toolCalls.size).toBe(9)
    controller.close()
    await vi.waitFor(() => expect(getGenerationSessionSnapshot('7')?.status).toBe('error'))
    expect(getGenerationSessionSnapshot('7')?.planObservation).toBe(9)
    clearGenerationSession('7'); expect(getGenerationSessionSnapshot('7')).toBeUndefined()
  })
})
