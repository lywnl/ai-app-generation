import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  clearGenerationSession,
  getBuildProjectDisplayState,
  getBuildProjectVisualState,
  getGenerationSessionSnapshot,
  getGenerationStatusText,
  shouldRefreshGenerationPreview,
  shouldHideCompletedReadOnlyTool,
  shouldHideToolCall,
  shouldShowGenerationStatus,
  startGenerationSession,
  subscribeGenerationSession,
  setGenerationToolCardState,
  type SessionEventType,
  type GenerationSessionSnapshot,
} from './generationSession'

const encoder = new TextEncoder()
const appIds = new Set<string>()
const recoveryMessages = {
  STARTED: '检测到生成状态异常，正在重新生成…',
  RECOVERED: '生成状态已恢复，继续处理…',
  FAILED: '生成状态异常，系统已停止本次生成，请重新发起。',
} as const

type WireFrame = { event?: string; data: Record<string, unknown> }

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

function wire(frames: WireFrame[], lineEnding = '\n'): string {
  return frames
    .map((frame) => {
      const eventLine = frame.event ? `event: ${frame.event}${lineEnding}` : ''
      return `${eventLine}data: ${JSON.stringify(frame.data)}${lineEnding}${lineEnding}`
    })
    .join('')
}

function vueMessage(
  sequence: number,
  kind: 'ai_text' | 'structured_tool_event',
  data: string,
  generation = '1',
): WireFrame {
  return {
    data: { protocol: 'generation-stream/v1', sequence, kind, data, generation },
  }
}

function simpleMessage(sequence: number, data: string): WireFrame {
  return { data: { protocol: 'generation-stream/v1', sequence, kind: 'simple_text', data } }
}

function structuredTool(
  sequence: number,
  generation: string,
  payload: Record<string, unknown>,
): WireFrame {
  return vueMessage(sequence, 'structured_tool_event', JSON.stringify(payload), generation)
}

function trustedDisplay(
  sequence: number,
  generation: string,
  toolRequestId: string,
  stage: 'REQUESTED' | 'EXECUTED',
  text: string,
): WireFrame {
  return {
    event: 'trusted-tool-display',
    data: {
      protocol: 'trusted-tool-display/v1',
      sequence,
      generation,
      toolRequestId,
      stage,
      text,
    },
  }
}

function rollback(
  sequence: number,
  failedGeneration: string,
  codePoints: number,
  provisionalToolRequestIds: string[] = [],
): WireFrame {
  return {
    event: 'internal-output-rollback',
    data: {
      protocol: 'internal-output-rollback/v1',
      sequence,
      failedGeneration,
      codePoints,
      provisionalToolRequestIds,
    },
  }
}

function recovery(
  sequence: number,
  phase: keyof typeof recoveryMessages,
  originalFailedGeneration: string,
  recoveryGeneration: string | null,
  failedGeneration: string | null,
): WireFrame {
  return {
    event: 'internal-output-recovery',
    data: {
      protocol: 'internal-output-recovery/v1',
      sequence,
      phase,
      originalFailedGeneration,
      recoveryGeneration,
      failedGeneration,
      message: recoveryMessages[phase],
    },
  }
}

function contextCompression(sequence: number, phase: 'STARTED' | 'COMPLETED'): WireFrame {
  return {
    event: 'context-compression',
    data: {
      protocol: 'context-compression/v1',
      sequence,
      phase,
      message: phase === 'STARTED' ? '正在压缩上下文，请稍候…' : '上下文压缩完成，继续生成…',
    },
  }
}

function toolRecovery(
  sequence: number,
  eventName: 'tool-protocol-recovery' | 'incomplete-tool-chain-recovery',
  phase: 'STARTED' | 'RECOVERED' | 'FAILED',
): WireFrame {
  const messages =
    eventName === 'tool-protocol-recovery'
      ? {
          STARTED: '正在校正工具调用，请稍候…',
          RECOVERED: '工具调用已校正，继续生成…',
          FAILED:
            '工具调用格式异常，系统自动校正后仍未恢复。本轮没有执行相关工具，请重新发送请求。',
        }
      : {
          STARTED: '正在继续未完成的构建流程，请稍候…',
          RECOVERED: '未完成的构建流程已恢复，继续生成…',
          FAILED: '模型未能继续完成真实工具执行和构建，本轮已安全停止。',
        }
  return {
    event: eventName,
    data: {
      protocol:
        eventName === 'tool-protocol-recovery'
          ? 'tool-protocol-recovery/v1'
          : 'incomplete-tool-chain-recovery/v1',
      sequence,
      phase,
      message: messages[phase],
    },
  }
}

function outcome(
  sequence: number,
  value: string,
  refreshPreview = value === 'SUCCEEDED',
): WireFrame {
  return {
    event: 'turn-outcome',
    data: {
      protocol: 'vue-turn/v1',
      sequence,
      outcome: value,
      message: `回合结果：${value}`,
      refreshPreview,
    },
  }
}

function simpleOutcome(sequence: number, value: string): WireFrame {
  return {
    event: 'simple-turn-outcome',
    data: {
      protocol: 'simple-turn/v1',
      sequence,
      outcome: value,
      message: `回合结果：${value}`,
      refreshPreview: false,
    },
  }
}

function done(sequence: number): WireFrame {
  return { event: 'done', data: { protocol: 'generation-stream/v1', sequence } }
}

function businessError(message = '系统繁忙'): WireFrame {
  return {
    event: 'business-error',
    data: { protocol: 'generation-error/v1', kind: 'BUSINESS', code: 40000, message },
  }
}

function heartbeat(timestamp = 1): WireFrame {
  return { event: 'heartbeat', data: { timestamp } }
}

async function runSession(
  framesOrChunks: WireFrame[] | string[],
  options: { vue?: boolean; renderMode?: 'direct' | 'throttled'; throttleMs?: number } = {},
) {
  const appId = `app-${appIds.size + 1}`
  appIds.add(appId)
  const chunks =
    typeof framesOrChunks[0] === 'string'
      ? (framesOrChunks as string[])
      : [wire(framesOrChunks as WireFrame[])]
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse(chunks)))
  startGenerationSession({
    appId,
    userMessage: '生成页面',
    generationId: crypto.randomUUID(),
    baseURL: 'http://localhost/api',
    renderMode: options.renderMode ?? 'direct',
    throttleMs: options.throttleMs,
    expectVueTurnOutcome: options.vue ?? true,
  })
  await vi.waitFor(() => {
    expect(getGenerationSessionSnapshot(appId)?.status).not.toBe('streaming')
  })
  return getGenerationSessionSnapshot(appId)
}

afterEach(() => {
  vi.useRealTimers()
  appIds.forEach(clearGenerationSession)
  appIds.clear()
  vi.unstubAllGlobals()
})

function fileExecuted(sequence: number, name: string, id = 'file-1', generation = '1', status = 'APPLIED') {
  return structuredTool(sequence, generation, {
    type: 'tool_executed', id, name,
    arguments: JSON.stringify({ relativeFilePath: 'index.html', content: '<main>商城</main>' }),
    result: JSON.stringify({
      protocol: 'file-tool/v1', operation: name, status, relativePath: 'index.html',
      changed: status === 'APPLIED' && !['readFile', 'readDir'].includes(name),
      message: status, failureReason: null, content: null,
    }),
  })
}

function openFileSession(renderMode: 'direct' | 'throttled') {
  const appId = `file-session-${appIds.size + 1}`
  appIds.add(appId)
  let stream!: ReadableStreamDefaultController<Uint8Array>
  const response = new Response(new ReadableStream<Uint8Array>({
    start(controller) { stream = controller },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  startGenerationSession({
    appId, userMessage: '生成页面', generationId: crypto.randomUUID(),
    baseURL: 'http://localhost/api', renderMode, throttleMs: 10_000,
    expectVueTurnOutcome: true,
  })
  return {
    appId,
    push: (...frames: WireFrame[]) => stream.enqueue(encoder.encode(wire(frames))),
    close: () => stream.close(),
    snapshot: () => getGenerationSessionSnapshot(appId)!,
  }
}

describe('文件工具卡片原位置更新', () => {
  const tools = ['writeFile', 'modifyFile', 'readFile', 'readDir', 'deleteFile']

  describe.each(['direct', 'throttled'] as const)('%s 模式', (renderMode) => {
    it.each(tools)('%s 完成正文提交前后保留同一工具块', async (name) => {
      vi.useFakeTimers()
      const session = openFileSession(renderMode)
      const observed: GenerationSessionSnapshot[] = []
      const unsubscribe = subscribeGenerationSession(session.appId, (snapshot) => observed.push(snapshot))
      session.push(
        structuredTool(1, '1', { type: 'tool_request', id: 'file-1', name }),
        trustedDisplay(2, '1', 'file-1', 'REQUESTED', '准备文件'),
      )
      await vi.waitFor(() => expect(session.snapshot().toolCalls.has('file-1')).toBe(true))
      expect(session.snapshot().toolCalls.get('file-1')?.executedDisplayCommitted).toBe(false)
      const initialBlock = session.snapshot().displayBlocks?.[0]
      expect(initialBlock).toMatchObject({ kind: 'tool', toolRequestId: 'file-1', generation: '1' })
      expect(shouldHideToolCall(session.snapshot(), session.snapshot().toolCalls.get('file-1')!)).toBe(false)

      session.push(fileExecuted(3, name))
      await vi.waitFor(() => expect(session.snapshot().toolCalls.get('file-1')?.status).toBe('done'))
      expect(shouldHideToolCall(session.snapshot(), session.snapshot().toolCalls.get('file-1')!)).toBe(false)
      session.push(trustedDisplay(4, '1', 'file-1', 'EXECUTED', '文件操作已完成'))
      if (renderMode === 'throttled') {
        await vi.advanceTimersByTimeAsync(100)
        expect(session.snapshot().content).not.toContain('文件操作已完成')
        expect(session.snapshot().toolCalls.get('file-1')?.executedDisplayCommitted).toBe(false)
        await vi.advanceTimersByTimeAsync(10_000)
      }
      await vi.waitFor(() => expect(session.snapshot().content).toContain('文件操作已完成'))
      expect(shouldHideToolCall(session.snapshot(), session.snapshot().toolCalls.get('file-1')!)).toBe(false)
      expect(session.snapshot().displayBlocks).toEqual([initialBlock])
      expect(session.snapshot().toolCalls.get('file-1')?.executedDisplayText).toBe('文件操作已完成')
      for (const snapshot of observed) {
        const view = snapshot.toolCalls.get('file-1')
        if (view) expect(view.executedDisplayCommitted).toBe(snapshot.content.includes('文件操作已完成'))
      }
      unsubscribe()
      const resumed: GenerationSessionSnapshot[] = []
      const unsubscribeAgain = subscribeGenerationSession(session.appId, (snapshot) => resumed.push(snapshot))
      expect(resumed.at(-1)?.toolCalls.get('file-1')?.executedDisplayCommitted).toBe(true)
      session.push(outcome(5, 'SUCCEEDED'), done(6))
      session.close()
      await vi.waitFor(() => expect(session.snapshot().status).toBe('done'))
      unsubscribeAgain()
    })
  })

  it('同一文件不同调用各自交接且保留两条操作正文', async () => {
    const session = openFileSession('direct')
    session.push(
      fileExecuted(1, 'writeFile', 'first'),
      trustedDisplay(2, '1', 'first', 'EXECUTED', '第一次写 index.html\n'),
      fileExecuted(3, 'writeFile', 'second', '2'),
    )
    await vi.waitFor(() => expect(session.snapshot().toolCalls.has('second')).toBe(true))
    expect(session.snapshot().toolCalls.get('first')?.executedDisplayCommitted).toBe(true)
    expect(session.snapshot().toolCalls.get('second')?.executedDisplayCommitted).toBe(false)
    session.push(
      trustedDisplay(4, '2', 'second', 'EXECUTED', '第二次写 index.html\n'),
      outcome(5, 'SUCCEEDED'), done(6),
    )
    session.close()
    await vi.waitFor(() => expect(session.snapshot().status).toBe('done'))
    expect(session.snapshot().content.match(/index.html/g)).toHaveLength(2)
    expect(session.snapshot().toolCalls.size).toBe(2)
    expect(session.snapshot().toolCalls.get('second')?.executedDisplayCommitted).toBe(true)
  })

  it.each(['NO_CHANGE', 'FAILED', 'REJECTED', 'CANCELLED'])('%s 结果正文保留原状态', async (status) => {
    const snapshot = await runSession([
      fileExecuted(1, 'writeFile', 'file-1', '1', status),
      trustedDisplay(2, '1', 'file-1', 'EXECUTED', `文件结果：${status}`),
      outcome(3, 'FAILED', false), done(4),
    ])
    expect(snapshot?.content).toContain(status)
    expect(shouldHideToolCall(snapshot!, snapshot!.toolCalls.get('file-1')!)).toBe(false)
    expect(snapshot?.toolCalls.get('file-1')?.result).toContain(status)
  })

  it.each(['FAILED', 'CANCELLED', 'TIMED_OUT', 'disconnect'])('%s 丢弃未显示正文时卡片仍保留', async (terminal) => {
    const frames = [fileExecuted(1, 'writeFile'), trustedDisplay(2, '1', 'file-1', 'EXECUTED', '未显示正文')]
    if (terminal !== 'disconnect') frames.push(outcome(3, terminal, false), done(4))
    const snapshot = await runSession(frames, { renderMode: 'throttled', throttleMs: 10_000 })
    expect(snapshot?.content).not.toContain('未显示正文')
    expect(snapshot?.toolCalls.get('file-1')?.executedDisplayCommitted).toBe(false)
    expect(shouldHideToolCall(snapshot!, snapshot!.toolCalls.get('file-1')!)).toBe(false)
    expect(snapshot?.displayBlocks).toHaveLength(1)
    expect(snapshot?.toolCalls.get('file-1')?.executedDisplayText).toBe('')
  })

  it.each([['other-id', '1'], ['file-1', '2']])('错误调用来源 %s/%s 不得隐藏卡片', async (id, generation) => {
    const snapshot = await runSession([
      fileExecuted(1, 'writeFile'), trustedDisplay(2, generation, id, 'EXECUTED', '错误来源'),
    ])
    expect(snapshot?.outcome).toBe('protocol_error')
    expect(snapshot?.toolCalls.get('file-1')?.executedDisplayCommitted).toBe(false)
    expect(snapshot?.toolCalls.get('file-1')?.executedDisplayText).toBe('')
  })

  it('回滚临时工具时保留已执行工具及其完成正文标记', async () => {
    const snapshot = await runSession([
      fileExecuted(1, 'writeFile'),
      trustedDisplay(2, '1', 'file-1', 'EXECUTED', '已执行文件'),
      structuredTool(3, '2', { type: 'tool_request', id: 'temp', name: 'writeFile' }),
      trustedDisplay(4, '2', 'temp', 'REQUESTED', '临时文件'),
      rollback(5, '2', 0, ['temp']), recovery(6, 'FAILED', '2', null, '2'),
      outcome(7, 'PROTOCOL_ERROR', false), done(8),
    ])
    expect(snapshot?.toolCalls.has('temp')).toBe(false)
    expect(snapshot?.content).toContain('已执行文件')
    expect(snapshot?.content).not.toContain('临时文件')
    expect(snapshot?.toolCalls.get('file-1')?.executedDisplayCommitted).toBe(true)
  })

  it.each(['direct', 'throttled'] as const)('%s 模式下 Skill 加载后保留原卡片', async (renderMode) => {
    vi.useFakeTimers()
    const session = openFileSession(renderMode)
    session.push(structuredTool(1, '1', {
      type: 'tool_executed', id: 'skill-1', name: 'readSkill',
      arguments: '{"skillName":"vue-frontend-design"}',
      result: JSON.stringify({
        protocol: 'skill-tool/v1', operation: 'readSkill', status: 'APPLIED',
        skillName: 'vue-frontend-design', message: 'Skill 正文已加载',
        failureReason: null, content: null,
      }),
    }))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.get('skill-1')?.status).toBe('done'))
    expect(shouldHideToolCall(session.snapshot(), session.snapshot().toolCalls.get('skill-1')!)).toBe(false)
    session.push(trustedDisplay(2, '1', 'skill-1', 'EXECUTED', '读取 Skill vue-frontend-design（已加载）'))
    if (renderMode === 'throttled') {
      await vi.advanceTimersByTimeAsync(100)
      expect(shouldHideToolCall(session.snapshot(), session.snapshot().toolCalls.get('skill-1')!)).toBe(false)
      await vi.advanceTimersByTimeAsync(10_000)
    }
    await vi.waitFor(() => expect(session.snapshot().content).toContain('已加载'))
    expect(shouldHideToolCall(session.snapshot(), session.snapshot().toolCalls.get('skill-1')!)).toBe(false)
    expect(session.snapshot().toolCalls.get('skill-1')?.args.skillName).toBe('vue-frontend-design')
    session.push(outcome(3, 'SUCCEEDED'), done(4))
    session.close()
    await vi.waitFor(() => expect(session.snapshot().status).toBe('done'))
  })

  it('构建工具不受隐藏规则影响', () => {
    expect(shouldHideToolCall({ status: 'done', outcome: 'succeeded' }, {
      name: 'buildProject', status: 'done', executedDisplayCommitted: true,
    })).toBe(false)
  })
})

describe('实时展示块', () => {
  describe.each(['direct', 'throttled'] as const)('%s 参数流', (renderMode) => {
    it.each([
      ['writeFile', 'content'], ['modifyFile', 'oldContent'], ['modifyFile', 'newContent'],
    ])('%s.%s 每段增量立即更新同一个工具块', async (name, key) => {
      const session = openFileSession(renderMode)
      session.push(structuredTool(1, '1', { type: 'tool_request', id: 'stream', name }))
      await vi.waitFor(() => expect(session.snapshot().toolCalls.has('stream')).toBe(true))
      const block = session.snapshot().displayBlocks?.[0]
      session.push(structuredTool(2, '1', { type: 'tool_argument_delta', id: 'stream', name, key, delta: 'const title = ' }))
      await vi.waitFor(() => expect(session.snapshot().toolCalls.get('stream')?.args[key]).toBe('const title = '))
      const firstSnapshot = session.snapshot()
      session.push(structuredTool(3, '1', { type: 'tool_argument_delta', id: 'stream', name, key, delta: '"中文"\n' }))
      await vi.waitFor(() => expect(session.snapshot().toolCalls.get('stream')?.args[key]).toBe('const title = "中文"\n'))
      expect(session.snapshot().displayBlocks).toEqual([block])
      expect(firstSnapshot.toolCalls.get('stream')?.args[key]).toBe('const title = ')
      expect(session.snapshot().toolCalls.get('stream')?.status).toBe('streaming')
    })
  })

  it('普通 AI 文字即使包含工具标记和代码围栏也仍是文字块', async () => {
    const content = '[工具调用] 写入文件 index.html\n```html\n示例代码\n```'
    const snapshot = await runSession([vueMessage(1, 'ai_text', content), outcome(2, 'ANSWERED', false), done(3)])
    expect(snapshot?.displayBlocks).toMatchObject([{ kind: 'markdown', text: content }])
    expect(snapshot?.toolCalls.size).toBe(0)
  })

  it('只读问答结束沿用隐藏规则，保留模型回答，Skill 不跟随隐藏', async () => {
    const snapshot = await runSession([
      fileExecuted(1, 'readFile'), trustedDisplay(2, '1', 'file-1', 'EXECUTED', '读取结果'),
      structuredTool(3, '1', { type: 'tool_request', id: 'skill', name: 'readSkill' }),
      vueMessage(4, 'ai_text', '这是回答'), outcome(5, 'ANSWERED', false), done(6),
    ])
    expect(snapshot?.displayBlocks).toMatchObject([
      { kind: 'tool', toolRequestId: 'skill' }, { kind: 'markdown', text: '这是回答' },
    ])
    expect(snapshot?.toolCalls.has('file-1')).toBe(true)
  })

  it('按事件顺序交错文字与工具，相邻文字合并且不把工具正文重复渲染', async () => {
    const snapshot = await runSession([
      vueMessage(1, 'ai_text', '准备'), vueMessage(2, 'ai_text', '修改'),
      structuredTool(3, '1', { type: 'tool_request', id: 'one', name: 'writeFile' }),
      trustedDisplay(4, '1', 'one', 'REQUESTED', '选择工具'),
      vueMessage(5, 'ai_text', '继续说明'),
      fileExecuted(6, 'writeFile', 'one'),
      trustedDisplay(7, '1', 'one', 'EXECUTED', '第一份代码'),
      fileExecuted(8, 'writeFile', 'two'),
      trustedDisplay(9, '1', 'two', 'EXECUTED', '第二份代码'),
      outcome(10, 'SUCCEEDED'), done(11),
    ])
    expect(snapshot?.displayBlocks).toMatchObject([
      { kind: 'markdown', text: '准备修改' },
      { kind: 'tool', toolRequestId: 'one' },
      { kind: 'markdown', text: '继续说明' },
      { kind: 'tool', toolRequestId: 'two' },
    ])
    expect(snapshot?.content).toContain('第一份代码')
    expect(snapshot?.toolCalls.get('two')?.executedDisplayText).toBe('第二份代码')
  })

  it('节流文字晚提交不会改变已占位工具的键或展开状态，重新订阅保留状态', async () => {
    vi.useFakeTimers()
    const session = openFileSession('throttled')
    session.push(vueMessage(1, 'ai_text', '稍后显示'), fileExecuted(2, 'writeFile'))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.size).toBe(1))
    const key = session.snapshot().displayBlocks![0]!.key
    setGenerationToolCardState(session.appId, key, { expanded: true, codeVersion: 'before' })
    await vi.advanceTimersByTimeAsync(10_000)
    expect(session.snapshot().displayBlocks?.[1]?.key).toBe(key)
    let resumed: GenerationSessionSnapshot | undefined
    const off = subscribeGenerationSession(session.appId, (value) => { resumed = value })
    expect(resumed?.toolCardStates[key]).toEqual({ expanded: true, codeVersion: 'before' })
    resumed!.toolCardStates[key]!.expanded = false
    expect(session.snapshot().toolCardStates[key]?.expanded).toBe(true)
    off()
  })

  it('回滚删除临时工具块及展开状态，保留已执行块', async () => {
    const session = openFileSession('direct')
    session.push(fileExecuted(1, 'writeFile'),
      structuredTool(2, '1', { type: 'tool_request', id: 'temp', name: 'modifyFile' }))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.size).toBe(2))
    const key = session.snapshot().displayBlocks![1]!.key
    setGenerationToolCardState(session.appId, key, { expanded: true, codeVersion: 'after' })
    session.push(rollback(3, '1', 0, ['temp']), recovery(4, 'FAILED', '1', null, '1'))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.size).toBe(1))
    expect(session.snapshot().displayBlocks).toHaveLength(1)
    expect(session.snapshot().toolCardStates[key]).toBeUndefined()
  })

  it('修改版本按调用隔离，重新订阅保留选择且快照不共享状态', async () => {
    const session = openFileSession('direct')
    session.push(fileExecuted(1, 'modifyFile', 'first'), fileExecuted(2, 'modifyFile', 'second'))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.size).toBe(2))
    const first = session.snapshot().displayBlocks?.[0]?.key
    const second = session.snapshot().displayBlocks?.[1]?.key
    if (!first || !second) throw new Error('缺少工具引用')
    setGenerationToolCardState(session.appId, first, {
      expanded: true, codeVersion: 'before',
    })
    let resumed: GenerationSessionSnapshot | undefined
    const off = subscribeGenerationSession(session.appId, (value) => { resumed = value })
    expect(resumed?.toolCardStates[first]).toEqual({ expanded: true, codeVersion: 'before' })
    expect(resumed?.toolCardStates[second]).toBeUndefined()
    const state = resumed?.toolCardStates[first]
    if (!state) throw new Error('缺少恢复状态')
    state.codeVersion = 'after'
    expect(session.snapshot().toolCardStates[first]?.codeVersion).toBe('before')
    off()
  })

  it('失败丢弃待提交完成正文后仍有工具记录，但没有泄漏待显示详情', async () => {
    const snapshot = await runSession([
      fileExecuted(1, 'writeFile'), trustedDisplay(2, '1', 'file-1', 'EXECUTED', '待显示代码'),
      outcome(3, 'FAILED', false), done(4),
    ], { renderMode: 'throttled', throttleMs: 10_000 })
    expect(snapshot?.displayBlocks).toHaveLength(1)
    expect(snapshot?.toolCalls.get('file-1')?.executedDisplayText).toBe('')
  })

  it('普通消息不启用展示块，构建工具归入卡片而不重复显示正文', async () => {
    const simple = await runSession([simpleMessage(1, '普通文本'), done(2)], { vue: false })
    expect(simple?.displayBlocks).toBeUndefined()
    const build = await runSession([
      structuredTool(1, '1', { type: 'tool_request', id: 'build', name: 'buildProject' }),
      trustedDisplay(2, '1', 'build', 'REQUESTED', '开始构建'),
      outcome(3, 'FAILED', false), done(4),
    ])
    expect(build?.displayBlocks).toMatchObject([{ kind: 'tool', toolRequestId: 'build' }])
    expect(build?.content).toBe('开始构建')
  })

  it('构建完成后保持同一个工具块和手动收起状态', async () => {
    const session = openFileSession('direct')
    session.push(structuredTool(1, '1', { type: 'tool_request', id: 'build', name: 'buildProject' }),
      trustedDisplay(2, '1', 'build', 'REQUESTED', '[选择工具] 构建项目'))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.size).toBe(1))
    const block = session.snapshot().displayBlocks![0]!
    setGenerationToolCardState(session.appId, block.key, { expanded: false, codeVersion: 'after' })
    session.push(structuredTool(3, '1', {
      type: 'tool_executed', id: 'build', name: 'buildProject', arguments: '{}', result: '{}',
    }), trustedDisplay(4, '1', 'build', 'EXECUTED', '构建结果说明'))
    await vi.waitFor(() => expect(session.snapshot().toolCalls.get('build')?.executedDisplayCommitted).toBe(true))
    expect(session.snapshot().displayBlocks).toEqual([block])
    expect(session.snapshot().toolCardStates[block.key]?.expanded).toBe(false)
    expect(session.snapshot().toolCalls.get('build')?.executedDisplayText).toBe('构建结果说明')
  })
})

describe('generationSession generation-stream/v1 状态机', () => {
  it('普通模板取消使用独立终态协议且不刷新预览', async () => {
    const snapshot = await runSession(
      [simpleMessage(1, '半截代码'), simpleOutcome(2, 'CANCELLED'), done(3)],
      { vue: false },
    )
    expect(snapshot).toMatchObject({ status: 'done', outcome: 'cancelled' })
    expect(shouldRefreshGenerationPreview(snapshot!)).toBe(false)
  })

  it.each([
    ['streaming', 'answered', 'readFile', false],
    ['streaming', 'answered', 'readDir', false],
    ['done', 'answered', 'readFile', true],
    ['done', 'answered', 'readDir', true],
    ['done', 'succeeded', 'modifyFile', false],
    ['done', 'succeeded', 'buildProject', false],
    ['done', 'failed', 'readFile', false],
    ['done', 'system_error', 'readFile', false],
  ] as const)(
    '%s + %s + %s 的只读工具卡片隐藏判断为 %s',
    (status, outcome, toolName, expected) => {
      expect(shouldHideCompletedReadOnlyTool({ status, outcome }, toolName)).toBe(expected)
    },
  )

  it.each(['direct', 'throttled'] as const)('%s 模式按 generation 回滚响应开头的正文', async (renderMode) => {
    const snapshot = await runSession(
      [
        vueMessage(1, 'ai_text', '错误前缀', '1'),
        rollback(2, '1', 4),
        recovery(3, 'STARTED', '1', '2', null),
        recovery(4, 'RECOVERED', '1', '2', null),
        vueMessage(5, 'ai_text', '安全正文', '2'),
        outcome(6, 'ANSWERED', false),
        done(7),
      ],
      { renderMode, throttleMs: 10_000 },
    )

    expect(snapshot).toMatchObject({
      content: '安全正文',
      status: 'done',
      outcome: 'answered',
      internalOutputRecovery: 'idle',
    })
  })

  it('只撤销普通正文之后的失败代 AI 片段并保留其他来源', async () => {
    const snapshot = await runSession([
      vueMessage(1, 'ai_text', '保留', '1'),
      structuredTool(2, '1', {
        type: 'tool_executed',
        id: 'tool-1',
        name: 'writeFile',
        arguments: '{}',
        result: '完成',
      }),
      trustedDisplay(3, '1', 'tool-1', 'EXECUTED', '工具已执行'),
      vueMessage(4, 'ai_text', '删除', '2'),
      rollback(5, '2', 2),
      recovery(6, 'STARTED', '2', '3', null),
      recovery(7, 'FAILED', '2', '3', '3'),
      outcome(8, 'PROTOCOL_ERROR', false),
      done(9),
    ])

    expect(snapshot?.content).toBe('保留工具已执行')
  })

  it('readSkill 工具卡片只显示名称并丢弃正文', async () => {
    const snapshot = await runSession([
      structuredTool(1, '1', {
        type: 'tool_request', id: 'skill-1', name: 'readSkill',
      }),
      structuredTool(2, '1', {
        type: 'tool_executed', id: 'skill-1', name: 'readSkill',
        arguments: '{"skillName":"vue-frontend-design"}',
        result: JSON.stringify({
          protocol: 'skill-tool/v1', operation: 'readSkill', status: 'APPLIED',
          skillName: 'vue-frontend-design', message: 'Skill 正文已加载',
          failureReason: null, content: null,
        }),
      }),
      outcome(3, 'ANSWERED', false),
      done(4),
    ])

    const card = snapshot?.toolCalls.get('skill-1')
    expect(card?.args.skillName).toBe('vue-frontend-design')
    expect(card?.result).toContain('"content":null')
    expect(card?.result).not.toContain('SKILL.md')
  })

  it('按 Unicode 码点回滚 emoji 且 codePoints=0 合法', async () => {
    const emoji = await runSession([
      vueMessage(1, 'ai_text', '保留😀泄漏', '1'),
      rollback(2, '1', 3),
      recovery(3, 'STARTED', '1', '2', null),
      recovery(4, 'FAILED', '1', '2', '2'),
      outcome(5, 'PROTOCOL_ERROR', false),
      done(6),
    ])
    const zero = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'FAILED', '1', '2', '2'),
      outcome(4, 'PROTOCOL_ERROR', false),
      done(5),
    ])

    expect(emoji?.content).toBe('保留')
    expect(zero).toMatchObject({ outcome: 'protocol_error', content: '' })
  })

  it('节流中未显示的失败片段被回滚且旧 timer 在 FAILED 后不补发', async () => {
    const snapshot = await runSession(
      [
        vueMessage(1, 'ai_text', '待删除', '1'),
        rollback(2, '1', 3),
        recovery(3, 'FAILED', '1', null, '1'),
        outcome(4, 'PROTOCOL_ERROR', false),
        done(5),
      ],
      { renderMode: 'throttled', throttleMs: 10_000 },
    )
    expect(snapshot?.content).toBe('')
  })

  it('内部 FAILED 丢弃其他代尚未展示的节流片段且不让 done 补发', async () => {
    const snapshot = await runSession(
      [
        vueMessage(1, 'ai_text', '尚未展示的安全前缀', '1'),
        rollback(2, '2', 0),
        recovery(3, 'STARTED', '2', '3', null),
        recovery(4, 'FAILED', '2', '3', '3'),
        outcome(5, 'PROTOCOL_ERROR', false),
        done(6),
      ],
      { renderMode: 'throttled', throttleMs: 10_000 },
    )

    expect(snapshot).toMatchObject({ content: '', status: 'done', outcome: 'protocol_error' })
  })

  it('回滚删除指定代 provisional 工具卡和 REQUESTED 展示但保留已执行事实', async () => {
    const snapshot = await runSession([
      structuredTool(1, '1', { type: 'tool_request', id: 'temp', name: 'writeFile' }),
      trustedDisplay(2, '1', 'temp', 'REQUESTED', '准备写入'),
      structuredTool(3, '1', { type: 'tool_request', id: 'done', name: 'buildProject' }),
      trustedDisplay(4, '1', 'done', 'REQUESTED', '准备构建'),
      structuredTool(5, '1', {
        type: 'tool_executed',
        id: 'done',
        name: 'buildProject',
        arguments: '{}',
        result: '完成',
      }),
      trustedDisplay(6, '1', 'done', 'EXECUTED', '构建完成'),
      rollback(7, '1', 0, ['temp', 'done', 'unknown']),
      recovery(8, 'FAILED', '1', null, '1'),
      outcome(9, 'PROTOCOL_ERROR', false),
      done(10),
    ])

    expect(snapshot?.toolCalls.has('temp')).toBe(false)
    expect(snapshot?.toolCalls.get('done')).toMatchObject({
      generation: '1',
      provisional: false,
      status: 'done',
    })
    expect(snapshot?.content).toBe('准备构建构建完成')
  })

  it('参数事件必须命中相同 id、generation 和工具名', async () => {
    const snapshot = await runSession([
      structuredTool(1, '1', { type: 'tool_request', id: 'tool-1', name: 'writeFile' }),
      structuredTool(2, '2', {
        type: 'tool_argument_delta',
        id: 'tool-1',
        name: 'writeFile',
        key: 'content',
        delta: '越代',
      }),
      done(3),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    expect(snapshot?.toolCalls.get('tool-1')?.args.content).toBeUndefined()
  })

  it('complete-only provider 可直接建立已执行工具卡', async () => {
    const snapshot = await runSession([
      structuredTool(1, '9', {
        type: 'tool_executed',
        id: 'tool-9',
        name: 'writeFile',
        arguments: '{"relativeFilePath":"src/App.vue"}',
        result: '完成',
      }),
      outcome(2, 'SUCCEEDED'),
      done(3),
    ])

    expect(snapshot?.toolCalls.get('tool-9')).toMatchObject({
      generation: '9',
      provisional: false,
      status: 'done',
      args: { relativeFilePath: 'src/App.vue' },
    })
  })

  it('严格接受第一次恢复成功和恢复代再次泄漏后失败', async () => {
    const success = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      outcome(4, 'ANSWERED', false),
      done(5),
    ])
    const secondLeak = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      vueMessage(4, 'ai_text', '再次泄漏', '2'),
      rollback(5, '2', 4),
      recovery(6, 'FAILED', '1', '2', '2'),
      outcome(7, 'PROTOCOL_ERROR', false),
      done(8),
    ])

    expect(success).toMatchObject({ status: 'done', outcome: 'answered' })
    expect(secondLeak).toMatchObject({ content: '', status: 'done', outcome: 'protocol_error' })
  })

  it('恢复成功后接受恢复下界及后续代次的正文、工具事件和可信展示', async () => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      structuredTool(4, '2', {
        type: 'tool_executed',
        id: 'tool-g2',
        name: 'writeFile',
        arguments: '{}',
        result: 'g2 已执行',
      }),
      trustedDisplay(5, '2', 'tool-g2', 'EXECUTED', 'g2 工具完成'),
      vueMessage(6, 'ai_text', 'g3 正文', '3'),
      structuredTool(7, '3', {
        type: 'tool_request',
        id: 'tool-g3',
        name: 'writeFile',
      }),
      trustedDisplay(8, '3', 'tool-g3', 'REQUESTED', 'g3 请求工具'),
      structuredTool(9, '3', {
        type: 'tool_executed',
        id: 'tool-g3',
        name: 'writeFile',
        arguments: '{}',
        result: 'g3 已执行',
      }),
      trustedDisplay(10, '3', 'tool-g3', 'EXECUTED', 'g3 工具完成'),
      vueMessage(11, 'ai_text', 'g4 正文', '4'),
      structuredTool(12, '4', {
        type: 'tool_request',
        id: 'tool-g4',
        name: 'buildProject',
      }),
      trustedDisplay(13, '4', 'tool-g4', 'REQUESTED', 'g4 请求构建'),
      structuredTool(14, '4', {
        type: 'tool_executed',
        id: 'tool-g4',
        name: 'buildProject',
        arguments: '{}',
        result: 'g4 已执行',
      }),
      trustedDisplay(15, '4', 'tool-g4', 'EXECUTED', 'g4 构建完成'),
      outcome(16, 'ANSWERED', false),
      done(17),
    ])

    expect(snapshot).toMatchObject({
      status: 'done',
      outcome: 'answered',
      content:
        'g2 工具完成g3 正文g3 请求工具g3 工具完成g4 正文g4 请求构建g4 构建完成',
    })
    expect(snapshot?.toolCalls.get('tool-g2')).toMatchObject({
      generation: '2',
      provisional: false,
    })
    expect(snapshot?.toolCalls.get('tool-g3')).toMatchObject({
      generation: '3',
      provisional: false,
    })
    expect(snapshot?.toolCalls.get('tool-g4')).toMatchObject({
      generation: '4',
      provisional: false,
    })
  })

  it('恢复成功后拒绝小于恢复下界的迟到旧代', async () => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      vueMessage(4, 'ai_text', '不得接收的 g1 正文', '1'),
      done(5),
    ])

    expect(snapshot).toMatchObject({
      status: 'error',
      outcome: 'protocol_error',
      content: '',
    })
  })

  it('后续代次再次泄漏时按对应 rollback 和原恢复链 FAILED 收口', async () => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      vueMessage(4, 'ai_text', 'g2 安全正文', '2'),
      vueMessage(5, 'ai_text', 'g3 泄漏', '3'),
      rollback(6, '3', 5),
      recovery(7, 'FAILED', '1', '2', '3'),
      outcome(8, 'PROTOCOL_ERROR', false),
      done(9),
    ])

    expect(snapshot).toMatchObject({
      status: 'done',
      outcome: 'protocol_error',
      content: 'g2 安全正文',
      internalOutputRecovery: 'idle',
    })
  })

  it.each([
    [
      '恢复启动前失败',
      [rollback(1, '1', 0), recovery(2, 'FAILED', '1', null, '1'), outcome(3, 'PROTOCOL_ERROR', false), done(4)],
    ],
    [
      '恢复代同步或异步失败',
      [
        rollback(1, '1', 0),
        recovery(2, 'STARTED', '1', '2', null),
        recovery(3, 'FAILED', '1', '2', '2'),
        outcome(4, 'PROTOCOL_ERROR', false),
        done(5),
      ],
    ],
  ] as const)('%s 只关闭恢复提示并等待后端终态', async (_name, frames) => {
    const snapshot = await runSession([...frames])
    expect(snapshot).toMatchObject({
      status: 'done',
      internalOutputRecovery: 'idle',
      outcome: 'protocol_error',
    })
  })

  it('同一恢复阶段只接受一个 rollback', async () => {
    const beforeStart = await runSession([
      rollback(1, '1', 0),
      rollback(2, '1', 0),
      recovery(3, 'STARTED', '1', '2', null),
      recovery(4, 'RECOVERED', '1', '2', null),
      outcome(5, 'ANSWERED', false),
      done(6),
    ])
    const afterRecovered = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      rollback(4, '2', 0),
      rollback(5, '3', 0),
      outcome(6, 'ANSWERED', false),
      done(7),
    ])

    expect(beforeStart).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    expect(afterRecovered).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('恢复成功后的 rollback 不得回退到恢复下界之前的旧代', async () => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      rollback(4, '1', 0),
      recovery(5, 'FAILED', '1', '2', '1'),
      outcome(6, 'PROTOCOL_ERROR', false),
      done(7),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('STARTED 后 RECOVERED 前的恢复代泄漏允许一次 rollback 后失败', async () => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      rollback(3, '2', 0),
      recovery(4, 'FAILED', '1', '2', '2'),
      outcome(5, 'PROTOCOL_ERROR', false),
      done(6),
    ])

    expect(snapshot).toMatchObject({
      status: 'done',
      outcome: 'protocol_error',
      content: '',
    })
  })

  it('STARTED 后已有待处理 rollback 时拒绝 RECOVERED', async () => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      rollback(3, '2', 0),
      recovery(4, 'RECOVERED', '1', '2', null),
      outcome(5, 'ANSWERED', false),
      done(6),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['AI 正文', vueMessage(7, 'ai_text', '不得接收的 g4 正文', '4')],
    [
      '结构化工具事件',
      structuredTool(7, '4', {
        type: 'tool_request',
        id: 'tool-after-rollback',
        name: 'writeFile',
      }),
    ],
    [
      '可信工具展示',
      trustedDisplay(7, '3', 'executed-before-rollback', 'EXECUTED', '不得重复展示'),
    ],
  ] as const)('恢复分支 rollback 后 FAILED 前拒绝%s', async (_name, unexpectedFrame) => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      structuredTool(4, '3', {
        type: 'tool_executed',
        id: 'executed-before-rollback',
        name: 'writeFile',
        arguments: '{}',
        result: '已执行',
      }),
      vueMessage(5, 'ai_text', 'g3 泄漏', '3'),
      rollback(6, '3', 5),
      unexpectedFrame,
      done(8),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error', content: '' })
    expect(snapshot?.toolCalls.has('tool-after-rollback')).toBe(false)
  })

  it.each([
    ['ANSWERED', false],
    ['SUCCEEDED', true],
  ] as const)('待处理 rollback 时拒绝成功终态 %s', async (wireOutcome, refreshPreview) => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      rollback(4, '2', 0),
      outcome(5, wireOutcome, refreshPreview),
      done(6),
    ])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['started', 'ANSWERED', false],
    ['started', 'SUCCEEDED', true],
    ['failed', 'ANSWERED', false],
    ['failed', 'SUCCEEDED', true],
  ] as const)(
    '内部恢复处于 %s 时拒绝成功终态 %s',
    async (recoveryState, wireOutcome, refreshPreview) => {
      const recoveryFrames =
        recoveryState === 'started'
          ? [recovery(2, 'STARTED', '1', '2', null)]
          : [
              recovery(2, 'STARTED', '1', '2', null),
              recovery(3, 'FAILED', '1', '2', '2'),
            ]
      const outcomeSequence = recoveryFrames.length + 2
      const snapshot = await runSession([
        rollback(1, '1', 0),
        ...recoveryFrames,
        outcome(outcomeSequence, wireOutcome, refreshPreview),
        done(outcomeSequence + 1),
      ])

      expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    },
  )

  it.each([
    ['rollback 等待态', 'CANCELLED', 'cancelled'],
    ['rollback 等待态', 'TIMED_OUT', 'timed_out'],
    ['started', 'CANCELLED', 'cancelled'],
    ['started', 'TIMED_OUT', 'timed_out'],
  ] as const)(
    '内部恢复处于 %s 时允许失败终态 %s 接管',
    async (recoveryState, wireOutcome, expectedOutcome) => {
      const recoveryFrames =
        recoveryState === 'started' ? [recovery(2, 'STARTED', '1', '2', null)] : []
      const outcomeSequence = recoveryFrames.length + 2
      const snapshot = await runSession([
        rollback(1, '1', 0),
        ...recoveryFrames,
        outcome(outcomeSequence, wireOutcome, false),
        done(outcomeSequence + 1),
      ])

      expect(snapshot).toMatchObject({ status: 'done', outcome: expectedOutcome })
    },
  )

  it.each([
    ['重复 STARTED', [rollback(1, '1', 0), recovery(2, 'STARTED', '1', '2', null), recovery(3, 'STARTED', '1', '3', null)]],
    ['无 rollback 的 RECOVERED', [recovery(1, 'RECOVERED', '1', '2', null)]],
    ['字段关系冲突', [rollback(1, '1', 0), recovery(2, 'STARTED', '2', '3', null)]],
  ] as const)('%s 被拒绝', async (_name, frames) => {
    const snapshot = await runSession([...frames, done(frames.length + 1)])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['正文', vueMessage(3, 'ai_text', '过早正文', '2')],
    [
      '工具',
      structuredTool(3, '2', { type: 'tool_request', id: 'early-tool', name: 'writeFile' }),
    ],
  ] as const)('STARTED 后 RECOVERED 前收到恢复代%s时拒绝', async (_name, frame) => {
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      frame,
      done(4),
    ])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    expect(snapshot?.content).toBe('')
    expect(snapshot?.toolCalls.has('early-tool')).toBe(false)
  })

  it('FAILED 和本地协议错误不撤销未获 rollback 授权的安全正文', async () => {
    const failed = await runSession([
      vueMessage(1, 'ai_text', '安全正文', '1'),
      rollback(2, '1', 0),
      recovery(3, 'FAILED', '1', null, '1'),
      outcome(4, 'PROTOCOL_ERROR', false),
      done(5),
    ])
    const invalid = await runSession([
      vueMessage(1, 'ai_text', '仍应保留', '1'),
      { event: 'turn-outcome', data: { protocol: 'vue-turn/v1', sequence: 2 } },
      done(3),
    ])

    expect(failed?.content).toBe('安全正文')
    expect(invalid?.content).toBe('仍应保留')
  })

  it.each([
    ['Vue 接受 simple_text', [simpleMessage(1, '错误')], true],
    ['普通链接受 ai_text', [vueMessage(1, 'ai_text', '错误')], false],
    ['旧 d 包装', [{ data: { d: JSON.stringify({ type: 'ai_response', data: '错误' }) } }], true],
  ] as const)('%s 时进入 protocol_error', async (_name, frames, vue) => {
    const snapshot = await runSession([...frames, done(2)], { vue })
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error', content: '' })
  })

  it.each([
    ['message 多余字段', { ...vueMessage(1, 'ai_text', '正文').data, extra: true }],
    ['message 宽松 sequence', { ...vueMessage(1, 'ai_text', '正文').data, sequence: '1' }],
    ['message 数字 generation', { ...vueMessage(1, 'ai_text', '正文').data, generation: 1 }],
    ['message 缺 generation', { protocol: 'generation-stream/v1', sequence: 1, kind: 'ai_text', data: '正文' }],
    ['控制帧多余字段', { ...rollback(1, '1', 0).data, extra: true }],
  ])('%s 被严格拒绝', async (name, data) => {
    const eventName = name === '控制帧多余字段' ? 'internal-output-rollback' : undefined
    const snapshot = await runSession([{ event: eventName, data }, done(2)])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['内层 generation', { type: 'tool_request', id: '1', name: 'writeFile', generation: '1' }],
    ['内层多余字段', { type: 'tool_request', id: '1', name: 'writeFile', extra: true }],
    ['非字符串 id', { type: 'tool_request', id: 1, name: 'writeFile' }],
    ['非法工具类型', { type: 'ai_response', data: '伪正文' }],
  ])('%s 的结构化工具对象被拒绝', async (_name, payload) => {
    const snapshot = await runSession([structuredTool(1, '1', payload), done(2)])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['重复', [vueMessage(1, 'ai_text', '一'), vueMessage(1, 'ai_text', '二')]],
    ['跳号', [vueMessage(1, 'ai_text', '一'), vueMessage(3, 'ai_text', '二')]],
    ['倒序', [vueMessage(2, 'ai_text', '一')]],
    ['零值', [vueMessage(0, 'ai_text', '一')]],
    ['超过 int 上限', [vueMessage(2_147_483_648, 'ai_text', '一')]],
  ] as const)('sequence %s 时拒绝后续帧', async (_name, frames) => {
    const lastSequence = frames.at(-1)!.data.sequence as number
    const snapshot = await runSession([...frames, done(lastSequence + 1)])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('heartbeat 不编号且不改变业务序号', async () => {
    const snapshot = await runSession([
      heartbeat(123),
      vueMessage(1, 'ai_text', '正文'),
      heartbeat(456),
      outcome(2, 'ANSWERED', false),
      done(3),
    ])
    expect(snapshot).toMatchObject({ content: '正文', status: 'done', outcome: 'answered' })
  })

  it.each([
    ['ANSWERED', 'answered'],
    ['PROTOCOL_ERROR', 'protocol_error'],
  ] as const)(
    'turn-outcome %s 后允许无序号 heartbeat 穿插再由连续 done 闭合',
    async (wireOutcome, expectedOutcome) => {
      const snapshot = await runSession([
        vueMessage(1, 'ai_text', '已确认正文'),
        outcome(2, wireOutcome, false),
        heartbeat(789),
        done(3),
      ])

      expect(snapshot).toMatchObject({
        content: '已确认正文',
        status: 'done',
        outcome: expectedOutcome,
      })
    },
  )

  it('business-error 后 heartbeat 仍被拒绝且不能伪装正常 done', async () => {
    const snapshot = await runSession([businessError('前置失败'), heartbeat(789), done(1)])

    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['首部错误', [businessError('首部失败'), done(1)]],
    ['已有业务帧后的错误', [contextCompression(1, 'STARTED'), businessError('中途失败'), done(2)]],
  ] as const)('%s 后 done 延续当前序号', async (_name, frames) => {
    const snapshot = await runSession([...frames], { vue: false })
    expect(snapshot).toMatchObject({ status: 'done', outcome: 'system_error' })
  })

  it('business-error 丢弃尚未展示的节流正文且 done 不再补发', async () => {
    const snapshot = await runSession(
      [simpleMessage(1, '尚未展示'), businessError(), done(2)],
      { vue: false, renderMode: 'throttled', throttleMs: 10_000 },
    )

    expect(snapshot).toMatchObject({ content: '', status: 'done', outcome: 'system_error' })
  })

  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])(
    'business-error 非法错误码 %s 被拒绝',
    async (code) => {
      const frame = businessError()
      frame.data.code = code
      const snapshot = await runSession([frame, done(1)], { vue: false })
      expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    },
  )

  it('business-error 后业务帧被拒绝且安全正文不被猜测撤销', async () => {
    const snapshot = await runSession([
      vueMessage(1, 'ai_text', '保留'),
      businessError(),
      vueMessage(2, 'ai_text', '迟到'),
      done(3),
    ])
    expect(snapshot).toMatchObject({ content: '保留', status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['generation 前导零', vueMessage(1, 'ai_text', '正文', '01')],
    ['generation 超过 long 上限', vueMessage(1, 'ai_text', '正文', '9223372036854775808')],
    ['codePoints 小数', rollback(1, '1', 1.5)],
    ['codePoints 超过 int 上限', rollback(1, '1', 2_147_483_648)],
    ['rollback 重复 id', rollback(1, '1', 0, ['a', 'a'])],
    ['rollback 空 id', rollback(1, '1', 0, [''])],
  ] as const)('%s 被有界校验拒绝', async (_name, frame) => {
    const snapshot = await runSession([frame, done(2)])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('rollback 超过失败代现有 AI 码点数时保留原正文并协议失败', async () => {
    const snapshot = await runSession([
      vueMessage(1, 'ai_text', '安全', '1'),
      rollback(2, '1', 3),
      done(3),
    ])
    expect(snapshot).toMatchObject({ content: '安全', status: 'error', outcome: 'protocol_error' })
  })

  it('已知 provisional id generation 冲突时协议失败而不删除工具卡', async () => {
    const snapshot = await runSession([
      structuredTool(1, '1', { type: 'tool_request', id: 'tool-1', name: 'writeFile' }),
      rollback(2, '2', 0, ['tool-1']),
      done(3),
    ])
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    expect(snapshot?.toolCalls.has('tool-1')).toBe(true)
  })

  it('上下文压缩、内部恢复、未完成工具链、工具恢复和普通提示优先级固定', () => {
    expect(getGenerationStatusText('compressing', 'recovering', 'recovering', 'recovering', '普通')).toBe(
      '正在压缩上下文，请稍候…',
    )
    expect(getGenerationStatusText('idle', 'recovering', 'recovering', 'recovering', '普通')).toBe(
      '正在恢复安全生成，请稍候…',
    )
    expect(getGenerationStatusText('idle', 'idle', 'recovering', 'recovering', '普通')).toBe(
      '正在继续未完成的构建流程，请稍候…',
    )
    expect(getGenerationStatusText('idle', 'idle', 'idle', 'recovering', '普通')).toBe(
      '正在校正工具调用，请稍候…',
    )
  })

  it('恢复状态参与可见性判断且新一轮会重置', async () => {
    expect(shouldShowGenerationStatus(true, 'idle', 'recovering', 'idle', 'idle', true)).toBe(true)
    const snapshot = await runSession([
      rollback(1, '1', 0),
      recovery(2, 'STARTED', '1', '2', null),
      recovery(3, 'RECOVERED', '1', '2', null),
      outcome(4, 'ANSWERED', false),
      done(5),
    ])
    expect(snapshot?.internalOutputRecovery).toBe('idle')
  })

  it('普通链只接受 simple_text、上下文压缩、business-error 与 done', async () => {
    const snapshot = await runSession(
      [simpleMessage(1, '你好'), contextCompression(2, 'STARTED'), contextCompression(3, 'COMPLETED'), done(4)],
      { vue: false, renderMode: 'throttled' },
    )
    expect(snapshot).toMatchObject({ content: '你好', outcome: 'succeeded', status: 'done' })
  })

  it('工具协议恢复只属于 Vue 会话', async () => {
    const snapshot = await runSession([toolRecovery(1, 'tool-protocol-recovery', 'STARTED'), done(2)], {
      vue: false,
    })
    expect(snapshot).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('turn-outcome 与 done 都只能出现一次且必须连续编号', async () => {
    const duplicateOutcome = await runSession([outcome(1, 'ANSWERED', false), outcome(2, 'ANSWERED', false), done(3)])
    const duplicateDone = await runSession([outcome(1, 'ANSWERED', false), done(2), done(3)])
    expect(duplicateOutcome).toMatchObject({ status: 'error', outcome: 'protocol_error' })
    expect(duplicateDone).toMatchObject({ status: 'error', outcome: 'protocol_error' })
  })

  it('失败 turn-outcome 丢弃尚未展示的节流正文且 done 不再补发', async () => {
    const snapshot = await runSession(
      [vueMessage(1, 'ai_text', '尚未展示'), outcome(2, 'PROTOCOL_ERROR', false), done(3)],
      { renderMode: 'throttled', throttleMs: 10_000 },
    )

    expect(snapshot).toMatchObject({ content: '', status: 'done', outcome: 'protocol_error' })
  })

  it('缺少 turn-outcome 的 done 不得提交尚未展示的节流正文', async () => {
    const snapshot = await runSession(
      [vueMessage(1, 'ai_text', '尚未展示'), done(2)],
      { renderMode: 'throttled', throttleMs: 10_000 },
    )

    expect(snapshot).toMatchObject({ content: '', status: 'error', outcome: 'protocol_error' })
  })

  it.each([
    ['ANSWERED', 'answered', false],
    ['SUCCEEDED', 'succeeded', true],
  ] as const)('%s 终态映射正确且预览刷新为 %s', async (value, expected, refresh) => {
    const snapshot = await runSession([outcome(1, value, refresh), done(2)])
    expect(snapshot).toMatchObject({ status: 'done', outcome: expected })
    expect(shouldRefreshGenerationPreview(snapshot!)).toBe(refresh)
  })

  it('请求体中的大整数 appId 全程保持字符串', async () => {
    const appId = '9007199254740993123'
    appIds.add(appId)
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([wire([outcome(1, 'ANSWERED', false), done(2)])]))
    vi.stubGlobal('fetch', fetchMock)
    startGenerationSession({
      appId,
      userMessage: '生成页面',
      generationId: crypto.randomUUID(),
      baseURL: 'http://localhost/api/',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })
    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost/api/app/chat/gen/code')
    expect(JSON.parse(String(request.body))).toMatchObject({ appId, message: '生成页面' })
    expect(JSON.parse(String(request.body)).generationId).toMatch(
      /^[0-9a-f-]{36}$/,
    )
  })

  it('未提供会话类型时拒绝发起请求', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    expect(() =>
      startGenerationSession({
        appId: 'unknown-type',
        userMessage: '生成页面',
        generationId: crypto.randomUUID(),
        baseURL: 'http://localhost/api',
      } as never),
    ).toThrow('生成会话类型未确定')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([
    ['HTTP 413', new Response('SECRET', { status: 413 }), '请求内容过大，请缩短需求后重试。'],
    ['非 SSE', new Response('SECRET', { status: 200, headers: { 'Content-Type': 'application/json' } }), '生成服务暂时不可用，请稍后重试。'],
  ])('%s 使用固定安全文案', async (_name, response, message) => {
    const appId = `safe-error-${appIds.size}`
    appIds.add(appId)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    startGenerationSession({
      appId,
      userMessage: '生成页面',
      generationId: crypto.randomUUID(),
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })
    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('error'))
    expect(getGenerationSessionSnapshot(appId)).toMatchObject({
      outcome: 'system_error',
      errorMessage: message,
      content: '',
    })
  })

  it('非法 UTF-8 按协议失败且不产生替换字符', async () => {
    const appId = 'invalid-utf8'
    appIds.add(appId)
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(Uint8Array.of(0xc3, 0x28))
          controller.close()
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    )
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    startGenerationSession({
      appId,
      userMessage: '生成页面',
      generationId: crypto.randomUUID(),
      baseURL: 'http://localhost/api',
      renderMode: 'direct',
      expectVueTurnOutcome: true,
    })
    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('error'))
    expect(getGenerationSessionSnapshot(appId)).toMatchObject({
      outcome: 'protocol_error',
      errorMessage: '生成流包含非法 UTF-8 数据',
    })
  })

  it('SSE 分块、CRLF、heartbeat 和无尾随空行均可解析', async () => {
    const complete = wire(
      [heartbeat(), vueMessage(1, 'ai_text', '你好'), outcome(2, 'ANSWERED', false), done(3)],
      '\r\n',
    )
    const split = complete.indexOf('你好') + 1
    const snapshot = await runSession([complete.slice(0, split), complete.slice(split).trimEnd()])
    expect(snapshot).toMatchObject({ content: '你好', status: 'done', outcome: 'answered' })
  })

  it('节流结束只提交一次正文且注销订阅可重复调用', async () => {
    const appId = 'listener-idempotent'
    appIds.add(appId)
    const observed: string[] = []
    const listener = (snapshot: { content: string }, eventType: SessionEventType) => {
      if (eventType === 'delta') observed.push(snapshot.content)
    }
    const unsubscribe = subscribeGenerationSession(appId, listener)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamResponse([
          wire([
            vueMessage(1, 'ai_text', '你'),
            vueMessage(2, 'ai_text', '好'),
            outcome(3, 'ANSWERED', false),
            done(4),
          ]),
        ]),
      ),
    )
    startGenerationSession({
      appId,
      userMessage: '生成页面',
      generationId: crypto.randomUUID(),
      baseURL: 'http://localhost/api',
      renderMode: 'throttled',
      throttleMs: 10_000,
      expectVueTurnOutcome: true,
    })
    await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
    expect(observed.filter((content) => content === '你好')).toHaveLength(1)
    unsubscribe()
    unsubscribe()
  })

  it.each([
    ['streaming', undefined, 'streaming'],
    ['done', undefined, 'unrecognized'],
    ['done', { statusText: '构建成功' }, 'parsed'],
  ] as const)('构建卡片展示状态为 %s', (status, build, expected) => {
    expect(getBuildProjectDisplayState({ status, build })).toBe(expected)
  })

  it.each([
    ['streaming', undefined, 'streaming'],
    ['done', { invocationStatus: 'COMPLETED', success: true }, 'success'],
    ['done', { invocationStatus: 'COMPLETED', success: false }, 'failed'],
    ['done', { invocationStatus: 'CANCELLED' }, 'cancelled'],
  ] as const)('构建视觉状态为 %s', (status, build, expected) => {
    expect(
      getBuildProjectVisualState({
        status,
        build: build
          ? { ...build, maxAttempts: 3, statusText: '状态', terminateToolLoop: false }
          : undefined,
      }),
    ).toBe(expected)
  })
})
