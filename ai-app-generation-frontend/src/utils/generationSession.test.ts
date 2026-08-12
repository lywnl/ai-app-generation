import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  clearGenerationSession,
  getGenerationSessionSnapshot,
  getBuildProjectDisplayState,
  shouldRefreshGenerationPreview,
  startGenerationSession,
} from './generationSession'

const encoder = new TextEncoder()
const appIds = new Set<string>()

function streamResponse(chunks: string[]): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
        controller.close()
      },
    }),
    { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
  )
}

function event(name: string, data = ''): string {
  return `event: ${name}\ndata: ${data}\n\n`
}

function messageEvent(payload: unknown): string {
  return `data: ${JSON.stringify({ d: JSON.stringify(payload) })}\n\n`
}

function outcomeEvent(outcome: string, refreshPreview = false): string {
  return event(
    'turn-outcome',
    JSON.stringify({
      protocol: 'vue-turn/v1',
      outcome,
      message: `回合结果：${outcome}`,
      refreshPreview,
    }),
  )
}

async function runSession(chunks: string[], expectVueTurnOutcome = true) {
  const appId = `app-${appIds.size + 1}`
  appIds.add(appId)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse(chunks)))

  startGenerationSession({
    appId,
    userMessage: '生成页面',
    baseURL: 'http://localhost/api',
    renderMode: 'direct',
    expectVueTurnOutcome,
  })

  await vi.waitFor(() => {
    expect(getGenerationSessionSnapshot(appId)?.status).not.toBe('streaming')
  })
  return getGenerationSessionSnapshot(appId)
}

afterEach(() => {
  appIds.forEach(clearGenerationSession)
  appIds.clear()
  vi.unstubAllGlobals()
})

describe('generationSession Vue SSE 状态机', () => {
  it.each([
    ['streaming', undefined, 'streaming'],
    ['done', undefined, 'unrecognized'],
    ['done', { statusText: '构建成功' }, 'parsed'],
  ] as const)('buildProject %s 卡片展示为 %s', (status, build, expected) => {
    expect(getBuildProjectDisplayState({ status, build })).toBe(expected)
  })

  it.each([
    ['done + succeeded', 'done', 'succeeded', true],
    ['streaming + succeeded', 'streaming', 'succeeded', false],
    ['done + failed', 'done', 'failed', false],
    ['error + succeeded', 'error', 'succeeded', false],
  ] as const)('%s 的预览刷新判断为 %s', (_name, status, outcome, expected) => {
    expect(shouldRefreshGenerationPreview({ status, outcome })).toBe(expected)
  })

  it('兼容 CRLF、跨 chunk、heartbeat 和无尾随空行', async () => {
    const complete = [
      ': heartbeat\r\n\r\n',
      event('heartbeat', '{"timestamp":1}').replace(/\n/g, '\r\n'),
      'data: {"d":"你',
      '好"}\r\n\r\n',
      outcomeEvent('SUCCEEDED', true).replace(/\n/g, '\r\n'),
      'event: done\r\ndata:',
    ]

    const snapshot = await runSession(complete)

    expect(snapshot).toMatchObject({
      content: '你好',
      status: 'done',
      outcome: 'succeeded',
      loading: false,
    })
  })

  it('只有 SUCCEEDED outcome 后收到 done 才成功', async () => {
    const snapshot = await runSession([outcomeEvent('SUCCEEDED', true), event('done')])

    expect(snapshot).toMatchObject({ status: 'done', outcome: 'succeeded' })
  })

  it('解析 buildProject 结果但不让它决定整轮成功', async () => {
    const buildResult = {
      protocol: 'vue-build-tool/v1',
      invocationStatus: 'COMPLETED',
      success: true,
      attempt: 1,
      maxAttempts: 3,
      stage: 'SUCCESS',
      failureKind: null,
      timedOut: false,
      repairable: false,
      reflectionRequired: false,
      nextAction: 'STOP',
      message: '构建成功',
      errorSummary: null,
      terminateToolLoop: true,
      finalResponse: '项目已生成并构建成功。',
    }
    const toolExecuted = messageEvent({
      type: 'tool_executed',
      id: '9007199254740993123',
      name: 'buildProject',
      arguments: '{}',
      result: JSON.stringify(buildResult),
    })

    const snapshot = await runSession([toolExecuted, event('done')])

    expect(snapshot?.toolCalls.get('9007199254740993123')).toMatchObject({
      id: '9007199254740993123',
      result: JSON.stringify(buildResult),
      build: { success: true, statusText: '第 1 次构建成功' },
    })
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('回合创建前 business-error 后的 done 保持 system_error', async () => {
    const snapshot = await runSession([
      event('business-error', JSON.stringify({ error: true, message: '系统繁忙' })),
      event('done'),
    ])

    expect(snapshot).toMatchObject({
      status: 'done',
      outcome: 'system_error',
      errorMessage: '系统繁忙',
    })
  })

  it('意外 EOF 标记为协议错误', async () => {
    const snapshot = await runSession([outcomeEvent('SUCCEEDED', true)])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('非 Vue 生成流缺少显式 done 时也标记为协议错误', async () => {
    const snapshot = await runSession(['data: {"d":"正文"}\n\n'], false)

    expect(snapshot).toMatchObject({
      content: '正文',
      status: 'error',
      outcome: 'protocol_error',
    })
  })

  it('两个 turn-outcome 标记为协议错误', async () => {
    const snapshot = await runSession([
      outcomeEvent('FAILED'),
      outcomeEvent('FAILED'),
      event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['正文', 'data: {"d":"不应接受"}\n\n'],
    [
      '工具事件',
      messageEvent({
        type: 'tool_executed',
        id: 'tool-after-outcome',
        name: 'writeFile',
        arguments: '{}',
        result: 'ok',
      }),
    ],
    ['错误事件', event('error', JSON.stringify({ message: '迟到错误' }))],
  ])('turn-outcome 后收到%s标记为协议错误', async (_name, unexpectedEvent) => {
    const snapshot = await runSession([
      outcomeEvent('SUCCEEDED', true),
      unexpectedEvent,
      event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    expect(snapshot?.content).not.toContain('不应接受')
    expect(snapshot?.toolCalls.has('tool-after-outcome')).toBe(false)
  })

  it('business-error 后再收到 outcome 标记为协议错误', async () => {
    const snapshot = await runSession([
      event('business-error', JSON.stringify({ error: true, message: '系统繁忙' })),
      outcomeEvent('FAILED'),
      event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['错误协议', { protocol: 'vue-turn/v2', outcome: 'FAILED', message: '失败', refreshPreview: false }],
    ['成功却不刷新', { protocol: 'vue-turn/v1', outcome: 'SUCCEEDED', message: '成功', refreshPreview: false }],
    ['失败却要求刷新', { protocol: 'vue-turn/v1', outcome: 'FAILED', message: '失败', refreshPreview: true }],
  ])('%s 的 outcome 标记为协议错误', async (_name, rawOutcome) => {
    const snapshot = await runSession([
      event('turn-outcome', JSON.stringify(rawOutcome)),
      event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['FAILED', 'failed'],
    ['CANCELLED', 'cancelled'],
    ['TIMED_OUT', 'timed_out'],
    ['SYSTEM_ERROR', 'system_error'],
    ['PROTOCOL_ERROR', 'protocol_error'],
  ])('映射 %s 业务终态', async (wireOutcome, expectedOutcome) => {
    const snapshot = await runSession([outcomeEvent(wireOutcome), event('done')])

    expect(snapshot).toMatchObject({ status: 'done', outcome: expectedOutcome })
  })
})
