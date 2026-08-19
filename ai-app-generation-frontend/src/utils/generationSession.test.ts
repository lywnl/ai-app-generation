import { afterEach, describe, expect, it, vi } from 'vitest'

import goldenCases from '../test-fixtures/vue-build-tool-v1-cases.json'
import {
  clearGenerationSession,
  getGenerationSessionSnapshot,
  getBuildProjectDisplayState,
  getBuildProjectVisualState,
  getGenerationStatusText,
  shouldShowGenerationStatus,
  shouldRefreshGenerationPreview,
  startGenerationSession,
  subscribeGenerationSession,
  type SessionEventType,
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

function businessErrorEvent(
  kind: 'BUSINESS' | 'SYSTEM' = 'BUSINESS',
  message = '系统繁忙',
): string {
  return event(
    'business-error',
    JSON.stringify({
      protocol: 'generation-error/v1',
      kind,
      code: kind === 'BUSINESS' ? 40000 : 50000,
      message,
    }),
  )
}

function contextCompressionEvent(phase: 'STARTED' | 'COMPLETED'): string {
  return event(
    'context-compression',
    JSON.stringify({
      protocol: 'context-compression/v1',
      phase,
      message: phase === 'STARTED' ? '正在压缩上下文，请稍候…' : '上下文压缩完成，继续生成…',
    }),
  )
}

function toolProtocolRecoveryEvent(
  phase: 'STARTED' | 'RECOVERED' | 'FAILED',
  override: Record<string, unknown> = {},
): string {
  const messages = {
    STARTED: '正在校正工具调用，请稍候…',
    RECOVERED: '工具调用已校正，继续生成…',
    FAILED: '工具调用格式异常，系统自动校正后仍未恢复。本轮没有执行相关工具，请重新发送请求。',
  } as const
  return event(
    'tool-protocol-recovery',
    JSON.stringify({
      protocol: 'tool-protocol-recovery/v1',
      phase,
      message: messages[phase],
      ...override,
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
    ['压缩中且已有正文', 'compressing', 'idle', true, true],
    ['压缩中且已有工具卡', 'compressing', 'idle', true, true],
    ['恢复中且已有输出', 'idle', 'recovering', true, true],
    ['空闲且已有输出', 'idle', 'idle', true, false],
  ] as const)(
    '%s 时状态区可见性符合最高优先级',
    (_name, compression, recovery, hasVisibleOutput, expected) => {
      expect(
        shouldShowGenerationStatus(true, compression, recovery, hasVisibleOutput),
      ).toBe(expected)
      expect(getGenerationStatusText(compression, recovery, 'AI 正在思考...')).toBe(
        compression === 'compressing'
          ? '正在压缩上下文，请稍候…'
          : recovery === 'recovering'
            ? '正在校正工具调用，请稍候…'
            : 'AI 正在思考...',
      )
    },
  )

  it('默认恢复状态为 idle 且正常正文流不受影响', async () => {
    const snapshot = await runSession([
      'data: {"d":"正常正文"}\n\n',
      outcomeEvent('SUCCEEDED', true),
      event('done'),
    ])

    expect(snapshot).toMatchObject({
      content: '正常正文',
      toolProtocolRecovery: 'idle',
      status: 'done',
      outcome: 'succeeded',
    })
  })

  it('恢复开始保留后端已下发的 direct 可信正文且控制文案不进入正文', async () => {
    const appId = 'tool-recovery-direct-isolation'
    appIds.add(appId)
    const observed: Array<{ content: string; recovery: string | undefined }> = []
    subscribeGenerationSession(appId, (snapshot) => {
      observed.push({
        content: snapshot.content,
        recovery: snapshot.toolProtocolRecovery,
      })
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamResponse([
          'data: {"d":"可信前缀"}\n\n',
          toolProtocolRecoveryEvent('STARTED'),
          toolProtocolRecoveryEvent('RECOVERED'),
          outcomeEvent('SUCCEEDED', true),
          event('done'),
        ]),
      ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    expect(observed).toContainEqual({ content: '可信前缀', recovery: 'recovering' })
    expect(getGenerationSessionSnapshot(appId)).toMatchObject({
      content: '可信前缀',
      toolProtocolRecovery: 'idle',
      outcome: 'succeeded',
    })
    expect(getGenerationSessionSnapshot(appId)?.content).not.toContain('校正工具调用')
  })

  it('恢复开始把 throttled 可信缓冲固化为检查点且终态后不重复追加', async () => {
    vi.useFakeTimers()
    try {
      const appId = 'tool-recovery-throttled-isolation'
      appIds.add(appId)
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          streamResponse([
            'data: {"d":"缓冲可信前缀"}\n\n',
            toolProtocolRecoveryEvent('STARTED'),
            toolProtocolRecoveryEvent('RECOVERED'),
            outcomeEvent('SUCCEEDED', true),
            event('done'),
          ]),
        ),
      )

      startGenerationSession({
        appId,
        userMessage: '生成页面',
        baseURL: 'http://localhost/api',
        renderMode: 'throttled',
        throttleMs: 10_000,
        expectVueTurnOutcome: true,
      })

      await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
      expect(getGenerationSessionSnapshot(appId)?.content).toBe('缓冲可信前缀')
      await vi.advanceTimersByTimeAsync(10_000)
      expect(getGenerationSessionSnapshot(appId)?.content).toBe('缓冲可信前缀')
    } finally {
      vi.useRealTimers()
    }
  })

  it('恢复开始保留已经由结构化 SSE 建立的可信工具卡和可信正文', async () => {
    const appId = 'tool-recovery-preserves-tool-card'
    appIds.add(appId)
    const observed: Array<{
      recovery: string | undefined
      toolIds: string[]
    }> = []
    subscribeGenerationSession(appId, (snapshot) => {
      observed.push({
        recovery: snapshot.toolProtocolRecovery,
        toolIds: [...snapshot.toolCalls.keys()],
      })
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamResponse([
          messageEvent({
            type: 'tool_request',
            id: 'trusted-tool',
            name: 'writeFile',
            arguments: '{}',
          }),
          'data: {"d":"工具卡后的可信正文"}\n\n',
          toolProtocolRecoveryEvent('STARTED'),
          toolProtocolRecoveryEvent('FAILED'),
          outcomeEvent('FAILED'),
          event('done'),
        ]),
      ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    expect(observed).toContainEqual({
      recovery: 'recovering',
      toolIds: ['trusted-tool'],
    })
    expect(getGenerationSessionSnapshot(appId)?.toolCalls.has('trusted-tool')).toBe(true)
    expect(getGenerationSessionSnapshot(appId)?.content).toBe('工具卡后的可信正文')
  })

  it('后续 generation 退化时保留后端在 STARTED 前已下发的全部可信正文', async () => {
    const snapshot = await runSession([
      'data: {"d":"此前可信正文"}\n\n',
      messageEvent({
        type: 'tool_request',
        id: 'trusted-tool',
        name: 'readFile',
        arguments: '{"relativeFilePath":"src/App.vue"}',
      }),
      messageEvent({
        type: 'tool_executed',
        id: 'trusted-tool',
        name: 'readFile',
        result: JSON.stringify({
          protocol: 'file-tool/v1',
          operation: 'readFile',
          status: 'SUCCESS',
          relativePath: 'src/App.vue',
          changed: false,
          message: '读取成功',
          failureReason: null,
          content: '不应展示的源码',
        }),
      }),
      'data: {"d":"后续可信正文"}\n\n',
      toolProtocolRecoveryEvent('STARTED'),
      toolProtocolRecoveryEvent('FAILED'),
      outcomeEvent('PROTOCOL_ERROR'),
      event('done'),
    ])

    expect(snapshot).toMatchObject({
      content: '此前可信正文后续可信正文',
      outcome: 'protocol_error',
      toolProtocolRecovery: 'idle',
    })
    expect(snapshot?.toolCalls.has('trusted-tool')).toBe(true)
  })

  it('允许后端 STARTED 到 RECOVERED 后再次 FAILED 的合法恢复序列', async () => {
    const snapshot = await runSession([
      toolProtocolRecoveryEvent('STARTED'),
      toolProtocolRecoveryEvent('RECOVERED'),
      toolProtocolRecoveryEvent('FAILED'),
      outcomeEvent('PROTOCOL_ERROR'),
      event('done'),
    ])

    expect(snapshot).toMatchObject({
      status: 'done',
      outcome: 'protocol_error',
      errorMessage:
        '工具调用格式异常，系统自动校正后仍未恢复。本轮没有执行相关工具，请重新发送请求。',
      toolProtocolRecovery: 'idle',
    })
  })

  it.each(['RECOVERED', 'FAILED'] as const)(
    '%s 只允许从 recovering 合法回到 idle',
    async (phase) => {
      const appId = `tool-recovery-transition-${phase}`
      appIds.add(appId)
      const observed: Array<string | undefined> = []
      subscribeGenerationSession(appId, (snapshot) => {
        observed.push(snapshot.toolProtocolRecovery)
      })
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          streamResponse([
            toolProtocolRecoveryEvent('STARTED'),
            toolProtocolRecoveryEvent(phase),
            outcomeEvent('SUCCEEDED', true),
            event('done'),
          ]),
        ),
      )

      startGenerationSession({
        appId,
        userMessage: '生成页面',
        baseURL: 'http://localhost/api',
        renderMode: 'direct',
        expectVueTurnOutcome: true,
      })

      await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
      expect(observed).toContain('recovering')
      expect(observed.slice(observed.indexOf('recovering') + 1)).toContain('idle')
    },
  )

  it.each([
    ['真实正文', 'data: {"d":"可信正文"}\n\n'],
    [
      'tool_request',
      messageEvent({ type: 'tool_request', id: 'tool-request', name: 'writeFile' }),
    ],
    [
      'tool_argument',
      messageEvent({
        type: 'tool_argument',
        id: 'tool-argument',
        name: 'writeFile',
        key: 'content',
        value: '可信参数',
      }),
    ],
    [
      'tool_argument_delta',
      messageEvent({
        type: 'tool_argument_delta',
        id: 'tool-delta',
        name: 'writeFile',
        key: 'content',
        delta: '可信增量',
      }),
    ],
    [
      'tool_executed',
      messageEvent({
        type: 'tool_executed',
        id: 'tool-executed',
        name: 'writeFile',
        result: '完成',
      }),
    ],
  ])('%s 一开始就隐藏恢复提示且不删除新输出', async (_name, trustedEvent) => {
    const appId = `tool-recovery-hidden-${String(_name)}`
    appIds.add(appId)
    const observed: Array<{
      recovery: string | undefined
      content: string
      toolCount: number
    }> = []
    subscribeGenerationSession(appId, (snapshot) => {
      observed.push({
        recovery: snapshot.toolProtocolRecovery,
        content: snapshot.content,
        toolCount: snapshot.toolCalls.size,
      })
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamResponse([
          toolProtocolRecoveryEvent('STARTED'),
          trustedEvent,
          outcomeEvent('SUCCEEDED', true),
          event('done'),
        ]),
      ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    expect(observed).toContainEqual({
      recovery: 'recovering',
      content: '',
      toolCount: 0,
    })
    expect(
      observed.some(
        (item) =>
          item.recovery === 'idle' && (item.content === '可信正文' || item.toolCount === 1),
      ),
    ).toBe(true)
  })

  it.each([
    ['RECOVERED 乱序', toolProtocolRecoveryEvent('RECOVERED')],
    ['FAILED 乱序', toolProtocolRecoveryEvent('FAILED')],
    [
      '重复 STARTED',
      toolProtocolRecoveryEvent('STARTED') + toolProtocolRecoveryEvent('STARTED'),
    ],
    [
      '错误协议',
      toolProtocolRecoveryEvent('STARTED', { protocol: 'tool-protocol-recovery/v2' }),
    ],
    ['伪造文案', toolProtocolRecoveryEvent('STARTED', { message: '内部异常详情' })],
    [
      '缺字段',
      event(
        'tool-protocol-recovery',
        JSON.stringify({ protocol: 'tool-protocol-recovery/v1', phase: 'STARTED' }),
      ),
    ],
    ['额外字段', toolProtocolRecoveryEvent('STARTED', { internalReason: 'secret' })],
    ['错误 JSON', event('tool-protocol-recovery', '{not-json')],
  ])('%s 的恢复控制帧必须以 protocol_error 失败关闭', async (_name, invalidEvent) => {
    const snapshot = await runSession([invalidEvent, event('done')])

    expect(snapshot).toMatchObject({
      status: 'error',
      outcome: 'protocol_error',
      content: '',
      toolProtocolRecovery: 'idle',
    })
  })

  it('markDone 与错误终止都重置恢复状态且保留已下发可信正文', async () => {
    const doneSnapshot = await runSession([
      'data: {"d":"完成前可信正文"}\n\n',
      toolProtocolRecoveryEvent('STARTED'),
      outcomeEvent('SUCCEEDED', true),
      event('done'),
    ])
    const errorSnapshot = await runSession([
      'data: {"d":"错误前可信正文"}\n\n',
      toolProtocolRecoveryEvent('STARTED'),
      event('unexpected', '{}'),
    ])

    expect(doneSnapshot).toMatchObject({
      content: '完成前可信正文',
      status: 'done',
      toolProtocolRecovery: 'idle',
    })
    expect(errorSnapshot).toMatchObject({
      content: '错误前可信正文',
      status: 'error',
      outcome: 'protocol_error',
      toolProtocolRecovery: 'idle',
    })
  })

  it('同一 app 新请求初始化会清除上一请求的 recovering 状态', async () => {
    const appId = 'tool-recovery-new-request-reset'
    appIds.add(appId)
    let firstController: ReadableStreamDefaultController<Uint8Array> | undefined
    const firstResponse = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          firstController = controller
          controller.enqueue(encoder.encode(toolProtocolRecoveryEvent('STARTED')))
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    )
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(firstResponse)
      .mockResolvedValueOnce(
        streamResponse([
          'data: {"d":"新请求正文"}\n\n',
          outcomeEvent('SUCCEEDED', true),
          event('done'),
        ]),
      )
    vi.stubGlobal('fetch', fetchMock)

    startGenerationSession({
      appId,
      userMessage: '第一次请求',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })
    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.toolProtocolRecovery).toBe('recovering')
    })

    startGenerationSession({
      appId,
      userMessage: '第二次请求',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })
    expect(getGenerationSessionSnapshot(appId)?.toolProtocolRecovery).toBe('idle')

    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    expect(getGenerationSessionSnapshot(appId)?.content).toBe('新请求正文')
    try {
      firstController?.close()
    } catch {
      // 第二次请求会中止旧响应；已关闭控制器无需再次处理。
    }
  })

  it('统一提示派生函数保持压缩、恢复、fallback 的固定优先级', async () => {
    const module = await import('./generationSession')
    const candidate: unknown = Reflect.get(module, 'getGenerationStatusText')

    expect(candidate).toBeTypeOf('function')
    if (typeof candidate !== 'function') {
      return
    }
    expect(candidate('compressing', 'recovering', 'fallback')).toBe('正在压缩上下文，请稍候…')
    expect(candidate('idle', 'recovering', 'fallback')).toBe('正在校正工具调用，请稍候…')
    expect(candidate('idle', 'idle', 'fallback')).toBe('fallback')
  })

  it('压缩开始和完成只切换状态且不污染正文或监听事件类型', async () => {
    const appId = 'context-compression-state'
    appIds.add(appId)
    const observed: Array<{
      contextCompression: string | undefined
      content: string
      eventType: SessionEventType
    }> = []
    const unsubscribe = subscribeGenerationSession(appId, (snapshot, eventType) => {
      observed.push({
        contextCompression: snapshot.contextCompression,
        content: snapshot.content,
        eventType,
      })
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([
            contextCompressionEvent('STARTED'),
            contextCompressionEvent('COMPLETED'),
            'data: {"d":"正文"}\n\n',
            outcomeEvent('SUCCEEDED', true),
            event('done'),
          ]),
        ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('done')
    })
    expect(observed).toContainEqual({
      contextCompression: 'compressing',
      content: '',
      eventType: 'delta',
    })
    expect(observed.some((item) => item.contextCompression === 'idle')).toBe(true)
    expect(getGenerationSessionSnapshot(appId)).toMatchObject({
      contextCompression: 'idle',
      content: '正文',
      outcome: 'succeeded',
    })
    expect(getGenerationSessionSnapshot(appId)?.content).not.toContain('压缩上下文')
    expect(observed.every((item) => ['delta', 'done', 'error'].includes(item.eventType))).toBe(true)
    unsubscribe()
  })

  it.each([
    [
      '未知 protocol',
      {
        protocol: 'context-compression/v2',
        phase: 'STARTED',
        message: '正在压缩上下文，请稍候…',
      },
    ],
    [
      '未知 phase',
      {
        protocol: 'context-compression/v1',
        phase: 'FAILED',
        message: '压缩失败',
      },
    ],
    [
      '非固定安全文案',
      {
        protocol: 'context-compression/v1',
        phase: 'STARTED',
        message: '内部异常详情',
      },
    ],
  ])('压缩控制帧%s时进入 protocol_error', async (_name, payload) => {
    const snapshot = await runSession([
      event('context-compression', JSON.stringify(payload)),
      event('done'),
    ])

    expect(snapshot).toMatchObject({
      status: 'error',
      outcome: 'protocol_error',
      content: '',
    })
  })

  it('没有 STARTED 的 COMPLETED 进入 protocol_error', async () => {
    const snapshot = await runSession([contextCompressionEvent('COMPLETED'), event('done')])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('重复 STARTED 进入 protocol_error', async () => {
    const snapshot = await runSession([
      contextCompressionEvent('STARTED'),
      contextCompressionEvent('STARTED'),
      event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('资源超限工具结果会结束卡片且只保留已广播参数', async () => {
    const snapshot = await runSession([
      messageEvent({
        type: 'tool_request',
        id: 'tool-large',
        name: 'writeFile',
        arguments: null,
      }),
      messageEvent({
        type: 'tool_argument_delta',
        id: 'tool-large',
        name: 'writeFile',
        key: 'content',
        delta: '合法前缀',
      }),
      messageEvent({
        type: 'tool_executed',
        id: 'tool-large',
        name: 'writeFile',
        arguments: '{}',
        result: JSON.stringify({
          protocol: 'file-tool/v1',
          operation: 'writeFile',
          status: 'REJECTED',
          relativePath: null,
          changed: false,
          message: '工具内容超过本轮资源上限',
          failureReason: 'RESOURCE_LIMIT_EXCEEDED',
          content: null,
        }),
      }),
      outcomeEvent('SYSTEM_ERROR'),
      event('done'),
    ])

    const tool = snapshot?.toolCalls.get('tool-large')
    expect(tool?.status).toBe('done')
    expect(tool?.args.content).toBe('合法前缀')
    expect(tool?.result).toContain('RESOURCE_LIMIT_EXCEEDED')
  })

  it.each([
    ['streaming', undefined, 'streaming'],
    ['done', undefined, 'unrecognized'],
    ['done', { statusText: '构建成功' }, 'parsed'],
  ] as const)('buildProject %s 卡片展示为 %s', (status, build, expected) => {
    expect(getBuildProjectDisplayState({ status, build })).toBe(expected)
  })

  it.each([
    ['流式工具', 'streaming', undefined, 'streaming'],
    ['构建成功', 'done', { invocationStatus: 'COMPLETED', success: true }, 'success'],
    ['构建失败', 'done', { invocationStatus: 'COMPLETED', success: false }, 'failed'],
    ['构建取消', 'done', { invocationStatus: 'CANCELLED' }, 'cancelled'],
    ['构建拒绝', 'done', { invocationStatus: 'REJECTED' }, 'neutral'],
    ['已有构建', 'done', { invocationStatus: 'BUILD_IN_PROGRESS' }, 'neutral'],
    ['结果不可识别', 'done', undefined, 'unrecognized'],
  ] as const)('%s 使用 %s 视觉状态', (_name, status, build, expected) => {
    expect(
      getBuildProjectVisualState({
        status,
        build: build
          ? {
              ...build,
              maxAttempts: 3,
              statusText: '状态',
              terminateToolLoop: false,
            }
          : undefined,
      }),
    ).toBe(expected)
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

  it.each(goldenCases)('$name 结果可被页面构建卡片消费', async ({ raw, expectedView }) => {
    const snapshot = await runSession([
      messageEvent({
        type: 'tool_executed',
        id: `build-${raw.invocationStatus}-${String(raw.attempt)}`,
        name: 'buildProject',
        arguments: '{}',
        result: JSON.stringify(raw),
      }),
      outcomeEvent('FAILED'),
      event('done'),
    ])
    const tool = [...(snapshot?.toolCalls.values() ?? [])][0]
    const expectedVisualState =
      raw.invocationStatus === 'CANCELLED'
        ? 'cancelled'
        : raw.invocationStatus !== 'COMPLETED'
          ? 'neutral'
          : raw.success
            ? 'success'
            : 'failed'

    expect(tool?.build).toEqual(expectedView)
    expect(tool && getBuildProjectVisualState(tool)).toBe(expectedVisualState)
  })

  it('回合创建前 business-error 后的 done 保持 system_error', async () => {
    const snapshot = await runSession([businessErrorEvent('BUSINESS', '系统繁忙'), event('done')])

    expect(snapshot).toMatchObject({
      status: 'done',
      outcome: 'system_error',
      errorMessage: '系统繁忙',
    })
  })

  it('压缩开始后首次门禁 business-error 安全结束且不污染正文', async () => {
    const snapshot = await runSession(
      [
        contextCompressionEvent('STARTED'),
        businessErrorEvent('BUSINESS', '本轮上下文无法安全继续，生成已停止，请重试'),
        event('done'),
      ],
      false,
    )

    expect(snapshot).toMatchObject({
      content: '',
      status: 'done',
      outcome: 'system_error',
      errorMessage: '本轮上下文无法安全继续，生成已停止，请重试',
      contextCompression: 'idle',
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
    ['压缩事件', contextCompressionEvent('STARTED')],
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

  it('同一缓冲中 done 后的压缩事件必须覆盖同步清理竞态并标记协议错误', async () => {
    const appId = 'event-after-done-with-sync-clear'
    appIds.add(appId)
    let lastObserved:
      | {
          eventType: SessionEventType
          status: string
          outcome: string
        }
      | undefined
    let doneEvents = 0
    subscribeGenerationSession(appId, (snapshot, eventType) => {
      lastObserved = {
        eventType,
        status: snapshot.status,
        outcome: snapshot.outcome,
      }
      if (eventType === 'done') {
        doneEvents += 1
        clearGenerationSession(appId)
      }
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([
            outcomeEvent('SUCCEEDED', true) + event('done') + contextCompressionEvent('STARTED'),
          ]),
        ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(lastObserved?.eventType).toBe('error')
    })
    expect(lastObserved).toEqual({
      eventType: 'error',
      status: 'error',
      outcome: 'protocol_error',
    })
    expect(doneEvents).toBe(0)
  })

  it('同一缓冲中的重复 done 必须标记协议错误', async () => {
    const snapshot = await runSession([
      outcomeEvent('SUCCEEDED', true) + event('done') + event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('合法唯一 done 仅在 EOF 确认后发布一次', async () => {
    const appId = 'single-done-after-eof'
    appIds.add(appId)
    const doneSnapshots: Array<{ status: string; outcome: string }> = []
    let streamController: ReadableStreamDefaultController<Uint8Array> | undefined
    subscribeGenerationSession(appId, (snapshot, eventType) => {
      if (eventType === 'done') {
        doneSnapshots.push({ status: snapshot.status, outcome: snapshot.outcome })
      }
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              streamController = controller
              controller.enqueue(encoder.encode(outcomeEvent('SUCCEEDED', true) + event('done')))
            },
          }),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        ),
      ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.outcome).toBe('succeeded'))
    expect(getGenerationSessionSnapshot(appId)?.status).toBe('streaming')
    expect(doneSnapshots).toEqual([])

    streamController?.close()

    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    expect(doneSnapshots).toEqual([{ status: 'done', outcome: 'succeeded' }])
  })

  it('business-error 后再收到 outcome 标记为协议错误', async () => {
    const snapshot = await runSession([businessErrorEvent(), outcomeEvent('FAILED'), event('done')])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    [
      '错误协议',
      { protocol: 'vue-turn/v2', outcome: 'FAILED', message: '失败', refreshPreview: false },
    ],
    [
      '成功却不刷新',
      { protocol: 'vue-turn/v1', outcome: 'SUCCEEDED', message: '成功', refreshPreview: false },
    ],
    [
      '失败却要求刷新',
      { protocol: 'vue-turn/v1', outcome: 'FAILED', message: '失败', refreshPreview: true },
    ],
    [
      '非字符串 outcome',
      { protocol: 'vue-turn/v1', outcome: 1, message: '失败', refreshPreview: false },
    ],
    [
      '非字符串 message',
      { protocol: 'vue-turn/v1', outcome: 'FAILED', message: 123, refreshPreview: false },
    ],
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

  it('生成请求使用 POST JSON 且大整数 appId 全程保持字符串', async () => {
    const appId = '9007199254740993123'
    const userMessage = '生成内容不能进入 URL'
    appIds.add(appId)
    const fetchMock = vi
      .fn()
      .mockResolvedValue(streamResponse([outcomeEvent('SUCCEEDED', true), event('done')]))
    vi.stubGlobal('fetch', fetchMock)

    startGenerationSession({
      appId,
      userMessage,
      baseURL: 'http://localhost/api/',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('done')
    })
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost/api/app/chat/gen/code')
    expect(url).not.toContain('?')
    expect(url).not.toContain(userMessage)
    expect(request.method).toBe('POST')
    expect(request.credentials).toBe('include')
    expect(request.headers).toEqual({
      Accept: 'text/event-stream',
      'Content-Type': 'application/json; charset=UTF-8',
    })
    expect(JSON.parse(String(request.body))).toEqual({ appId, message: userMessage })
    expect(request.signal).toBeInstanceOf(AbortSignal)
  })

  it.each([
    [413, '请求内容过大，请缩短需求后重试。'],
    [502, '生成服务暂时不可用，请稍后重试。'],
  ])('HTTP %i 不读取响应正文并使用固定安全文案', async (status, safeMessage) => {
    const appId = `http-error-${status}`
    appIds.add(appId)
    const response = new Response('SECRET_PROXY_BODY', {
      status,
      statusText: 'SECRET_PROXY_STATUS',
      headers: { 'Content-Type': 'text/html' },
    })
    const getReader = vi.spyOn(response.body as ReadableStream<Uint8Array>, 'getReader')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('error')
    })
    expect(getGenerationSessionSnapshot(appId)).toMatchObject({
      outcome: 'system_error',
      errorMessage: safeMessage,
      content: '',
    })
    expect(getReader).not.toHaveBeenCalled()
  })

  it.each([undefined, 'application/json', 'text/event-streaming'])(
    'HTTP 2xx Content-Type=%s 时拒绝读取正文',
    async (contentType) => {
      const appId = `content-type-${String(contentType)}`
      appIds.add(appId)
      const headers = contentType ? { 'Content-Type': contentType } : undefined
      const response = new Response('SECRET_NON_SSE_BODY', { status: 200, headers })
      const getReader = vi.spyOn(response.body as ReadableStream<Uint8Array>, 'getReader')
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

      startGenerationSession({
        appId,
        userMessage: '生成页面',
        baseURL: 'http://localhost/api',
        expectVueTurnOutcome: true,
      })

      await vi.waitFor(() => {
        expect(getGenerationSessionSnapshot(appId)?.status).toBe('error')
      })
      expect(getGenerationSessionSnapshot(appId)).toMatchObject({
        outcome: 'system_error',
        errorMessage: '生成服务暂时不可用，请稍后重试。',
        content: '',
      })
      expect(getReader).not.toHaveBeenCalled()
    },
  )

  it.each([
    [
      '未知协议',
      { protocol: 'generation-error/v2', kind: 'BUSINESS', code: 40000, message: '失败' },
    ],
    ['缺少协议', { kind: 'BUSINESS', code: 40000, message: '失败' }],
    ['未知 kind', { protocol: 'generation-error/v1', kind: 'OTHER', code: 40000, message: '失败' }],
    ['缺少 kind', { protocol: 'generation-error/v1', code: 40000, message: '失败' }],
    [
      '非整数 code',
      { protocol: 'generation-error/v1', kind: 'BUSINESS', code: 1.5, message: '失败' },
    ],
    [
      '非安全整数 code',
      {
        protocol: 'generation-error/v1',
        kind: 'BUSINESS',
        code: 9007199254740992,
        message: '失败',
      },
    ],
    ['缺少 code', { protocol: 'generation-error/v1', kind: 'BUSINESS', message: '失败' }],
    [
      '空 message',
      { protocol: 'generation-error/v1', kind: 'BUSINESS', code: 40000, message: '  ' },
    ],
    ['缺少 message', { protocol: 'generation-error/v1', kind: 'BUSINESS', code: 40000 }],
  ])('business-error %s 时进入 protocol_error', async (_name, payload) => {
    const snapshot = await runSession([
      event('business-error', JSON.stringify(payload)),
      event('done'),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each(['BUSINESS', 'SYSTEM'] as const)(
    '合法 %s business-error 只在 done 时通知一次',
    async (kind) => {
      const appId = `preflight-${kind}`
      appIds.add(appId)
      const localEvents: SessionEventType[] = []
      const unsubscribe = subscribeGenerationSession(appId, (_snapshot, eventType) => {
        localEvents.push(eventType)
      })
      vi.stubGlobal(
        'fetch',
        vi
          .fn()
          .mockResolvedValue(
            streamResponse([
              event('heartbeat', '{"timestamp":1}'),
              businessErrorEvent(kind, '安全前置错误'),
              event('done'),
            ]),
          ),
      )

      startGenerationSession({
        appId,
        userMessage: '生成页面',
        baseURL: 'http://localhost/api',
        expectVueTurnOutcome: true,
      })

      await vi.waitFor(() => {
        expect(getGenerationSessionSnapshot(appId)?.status).toBe('done')
      })
      expect(getGenerationSessionSnapshot(appId)).toMatchObject({
        outcome: 'system_error',
        errorMessage: '安全前置错误',
      })
      expect(localEvents.filter((item) => item === 'done')).toHaveLength(1)
      expect(localEvents.filter((item) => item === 'error')).toHaveLength(0)
      expect(localEvents).not.toContain('business-error')
      unsubscribe()
    },
  )

  it.each([
    ['正文后', ['data: {"d":"正文"}\n\n', businessErrorEvent(), event('done')]],
    [
      '工具后',
      [
        messageEvent({ type: 'tool_request', id: 'tool-1', name: 'writeFile', arguments: '{}' }),
        businessErrorEvent(),
        event('done'),
      ],
    ],
    ['重复错误', [businessErrorEvent(), businessErrorEvent(), event('done')]],
    ['错误后正文', [businessErrorEvent(), 'data: {"d":"正文"}\n\n', event('done')]],
    ['错误后心跳', [businessErrorEvent(), event('heartbeat', '{"timestamp":2}'), event('done')]],
  ])('%s收到 business-error 时进入 protocol_error', async (_name, chunks) => {
    const snapshot = await runSession(chunks)

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('business-error 后 EOF 必须覆盖为 protocol_error', async () => {
    const snapshot = await runSession([businessErrorEvent()])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each(['error', 'build_status', 'unexpected-event'])(
    '未知命名事件 %s 不得退化成聊天正文',
    async (name) => {
      const snapshot = await runSession([event(name, '{"message":"机密正文"}'), event('done')])

      expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error', content: '' })
    },
  )

  it('支持 BOM、注释、多行 data、重复 event 最后值和未知字段', async () => {
    const snapshot = await runSession([
      '\uFEFF: 注释\r\nunknown: ignored\r\nevent: unexpected\r\nevent: message\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n',
      outcomeEvent('SUCCEEDED', true),
      event('done'),
    ])

    expect(snapshot).toMatchObject({
      content: '第一行\n第二行',
      status: 'done',
      outcome: 'succeeded',
    })
  })

  it('UTF-8 多字节字符跨字节 chunk 时仍完整解码', async () => {
    const appId = 'split-utf8'
    appIds.add(appId)
    const bytes = encoder.encode(
      `data: {"d":"中文😀"}\n\n${outcomeEvent('SUCCEEDED', true)}${event('done')}`,
    )
    const emojiStart = bytes.findIndex((value) => value === 0xf0)
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(bytes.slice(0, emojiStart + 2))
          controller.enqueue(bytes.slice(emojiStart + 2))
          controller.close()
        },
      }),
      { status: 200, headers: { 'Content-Type': 'TEXT/EVENT-STREAM; charset=UTF-8' } },
    )
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('done')
    })
    expect(getGenerationSessionSnapshot(appId)?.content).toBe('中文😀')
  })

  it('非法 UTF-8 使用固定 protocol_error 且不产生替换字符', async () => {
    const appId = 'invalid-utf8'
    appIds.add(appId)
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(Uint8Array.from([0x64, 0x61, 0x74, 0x61, 0x3a, 0x20, 0xc3, 0x28]))
          controller.close()
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    )
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('error')
    })
    expect(getGenerationSessionSnapshot(appId)).toMatchObject({
      outcome: 'protocol_error',
      errorMessage: '生成流包含非法 UTF-8 数据',
      content: '',
    })
    expect(getGenerationSessionSnapshot(appId)?.content).not.toContain('\uFFFD')
  })

  it('readFile 工具卡片只保留白名单元数据并强制清除正文', async () => {
    const secret = 'SECRET_READ_CONTENT_7fdd'
    const unsafeResult = JSON.stringify({
      protocol: 'file-tool/v1',
      operation: 'readFile',
      status: 'APPLIED',
      relativePath: 'src/App.vue',
      changed: false,
      message: '文件读取成功',
      failureReason: null,
      content: secret,
      extraSecret: secret,
    })
    const snapshot = await runSession([
      messageEvent({
        type: 'tool_request',
        id: 'read-1',
        name: 'readFile',
        arguments: JSON.stringify({
          relativeFilePath: 'src/App.vue',
          content: secret,
        }),
      }),
      messageEvent({
        type: 'tool_argument_delta',
        id: 'read-1',
        name: 'readFile',
        key: 'content',
        delta: secret,
      }),
      messageEvent({
        type: 'tool_executed',
        id: 'read-1',
        name: 'readFile',
        arguments: JSON.stringify({ relativeFilePath: 'src/App.vue' }),
        result: unsafeResult,
        rawContent: secret,
      }),
      outcomeEvent('SUCCEEDED', true),
      event('done'),
    ])

    const tool = snapshot?.toolCalls.get('read-1')
    expect(JSON.parse(tool?.result ?? '{}')).toEqual({
      protocol: 'file-tool/v1',
      operation: 'readFile',
      status: 'APPLIED',
      relativePath: 'src/App.vue',
      changed: false,
      message: '文件读取成功',
      failureReason: null,
      content: null,
    })
    expect(JSON.stringify(tool)).not.toContain(secret)
    expect(snapshot?.content).not.toContain(secret)
  })

  it('节流缓冲只通知一次正文变化且注销函数可重复调用', async () => {
    const appId = 'listener-idempotent'
    appIds.add(appId)
    const observedContents: string[] = []
    const listener = (snapshot: { content: string }, eventType: SessionEventType) => {
      if (eventType === 'delta') {
        observedContents.push(snapshot.content)
      }
    }
    const unsubscribe = subscribeGenerationSession(appId, listener)
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(() =>
          Promise.resolve(
            streamResponse([
              'data: {"d":"你"}\n\n',
              'data: {"d":"好"}\n\n',
              outcomeEvent('SUCCEEDED', true),
              event('done'),
            ]),
          ),
        ),
    )

    startGenerationSession({
      appId,
      userMessage: '生成页面',
      baseURL: 'http://localhost/api',
      renderMode: 'throttled',
      throttleMs: 10_000,
      expectVueTurnOutcome: true,
    })

    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('done')
    })
    expect(observedContents.filter((content) => content === '你好')).toHaveLength(1)
    const callsBeforeUnsubscribe = observedContents.length
    unsubscribe()
    unsubscribe()
    startGenerationSession({
      appId,
      userMessage: '再次生成',
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })
    await vi.waitFor(() => {
      expect(getGenerationSessionSnapshot(appId)?.status).toBe('done')
    })
    expect(observedContents).toHaveLength(callsBeforeUnsubscribe)
  })
})
