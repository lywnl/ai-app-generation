import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'

import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearGenerationSession,
  getGenerationSessionSnapshot,
  startGenerationSession,
  subscribeGenerationSession,
} from '../../utils/generationSession'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')
const cardSource = readFileSync(new URL('../../components/ToolOperationCard.vue', import.meta.url), 'utf8')
const activeTestSessions = new Set<string>()

afterEach(() => {
  activeTestSessions.forEach(clearGenerationSession)
  activeTestSessions.clear()
  vi.unstubAllGlobals()
})

/** 执行页面原有提示函数与订阅回调，只替换 DOM 操作和提示组件以观察调用。 */
function attachActualPageListener(appId: string, showError: (text: string) => void) {
  const script = parse(pageSource).descriptor.scriptSetup!.content
  const source = ts.createSourceFile('AppChatPage.ts', script, ts.ScriptTarget.ESNext, true, ts.ScriptKind.TS)
  const names = new Set(['outcomeMessage', 'attachSessionListener'])
  const declarations = source.statements.filter(
    (statement) => ts.isVariableStatement(statement) && statement.declarationList.declarations.some(
      (declaration) => names.has(declaration.name.getText(source)),
    ),
  )
  expect(declarations).toHaveLength(2)
  const executable = ts.transpileModule(declarations.map((node) => node.getText(source)).join('\n'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText
  const detachSession: { value?: () => void } = {}
  const attach = runInNewContext(`${executable}\nattachSessionListener`, {
    detachSession,
    activeSessionAppId: { value: appId },
    subscribeGenerationSession,
    applySessionSnapshot: vi.fn(),
    scrollToBottom: vi.fn(),
    finalizeGeneration: vi.fn(),
    message: { error: showError },
  }) as (target: string) => void
  attach(appId)
  return () => detachSession.value?.()
}

describe('代码生成阶段提示文案', () => {
  it.each(['business-error', 'turn-outcome'] as const)(
    '%s 余额提示经过真实会话和页面回调后调用错误弹窗',
    async (event) => {
      const appId = `balance-page-${event}`
      activeTestSessions.add(appId)
      const text = '模型服务余额不足，请充值后重试。'
      const payload = event === 'business-error'
        ? { protocol: 'generation-error/v1', kind: 'SYSTEM', code: 50000, message: text }
        : { protocol: 'vue-turn/v1', sequence: 1, outcome: 'SYSTEM_ERROR', message: text, refreshPreview: false }
      const wire = `event: ${event}\ndata: ${JSON.stringify(payload)}\n\n` +
        `event: done\ndata: ${JSON.stringify({ protocol: 'generation-stream/v1', sequence: event === 'business-error' ? 1 : 2 })}\n\n`
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(wire, {
        headers: { 'Content-Type': 'text/event-stream' },
      })))
      const showError = vi.fn()
      const detach = attachActualPageListener(appId, showError)
      try {
        startGenerationSession({
          appId, userMessage: '测试错误显示', generationId: crypto.randomUUID(),
          baseURL: 'http://localhost/api', expectVueTurnOutcome: true,
        })
        await vi.waitFor(() => expect(getGenerationSessionSnapshot(appId)?.status).toBe('done'))
        expect(showError).toHaveBeenCalledExactlyOnceWith(text)
        expect(getGenerationSessionSnapshot(appId)).toMatchObject({
          loading: false, outcome: 'system_error', errorMessage: text,
        })
      } finally {
        detach()
      }
    },
  )

  it('两个加载区共用四级阶段提示派生函数并可被辅助技术感知', () => {
    expect(pageSource).toContain('正在生成网站...')
    expect(pageSource).toContain('AI 正在思考...')
    const generationRegions = [...pageSource.matchAll(/<div\b[^>]*aria-live="polite"[^>]*>/g)]
    expect(generationRegions).toHaveLength(2)
    for (const [region] of generationRegions) expect(region).toContain('role="status"')
    expect(pageSource.match(/getGenerationStatusText\(/g)).toHaveLength(2)
  })

  it('普通思考仅在无输出时显示但压缩和两类恢复状态始终优先可见', () => {
    expect(pageSource).toContain(
      'const hasVisibleOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0',
    )
    expect(pageSource).toContain('aiMessage.loading = shouldShowGenerationStatus(')
    expect(pageSource).toContain('snapshot.contextCompression')
    expect(pageSource).toContain('snapshot.toolProtocolRecovery')
    expect(pageSource).toContain('snapshot.incompleteToolChainRecovery')
    expect(pageSource).not.toContain('aiMessage.loading = snapshot.loading\n')
  })

  it('应用类型未加载或不受支持时拒绝启动生成', () => {
    expect(pageSource).toContain('const resolveVueSessionType = (): boolean | undefined =>')
    expect(pageSource).toContain("message.error('应用类型尚未加载或不受支持')")
    expect(pageSource).toContain('messages.value.splice(aiMessageIndex, 1)')
    expect(pageSource).toContain('expectVueTurnOutcome')
  })

  it('构建工具使用统一卡片并保留阶段和错误摘要', () => {
    expect(cardSource).toContain("view.name === 'buildProject' && view.build")
    expect(cardSource).toContain('buildStageLabel')
    expect(cardSource).toContain('view.build.errorSummary')
    expect(cardSource).toContain("result.status === 'streaming'")
  })

  it('取消终态只结束生成，不重复弹出错误提示', () => {
    expect(pageSource).toContain("snapshot.outcome !== 'cancelled'")
    expect(pageSource).toContain("snapshot.outcome !== 'succeeded'")
    expect(pageSource).toContain("snapshot.outcome !== 'answered'")
    expect(pageSource).toContain('message.error(outcomeMessage(snapshot.outcome, snapshot.errorMessage))')
  })

  it('实时消息使用有序工具块，历史正文和构建面板保留原入口', () => {
    expect(pageSource).toContain('!shouldHideToolCall(snapshot, view)')
    expect(pageSource).toContain('aiMessage.displayBlocks = snapshot.displayBlocks')
    expect(pageSource).toContain('ToolOperationCard')
    expect(pageSource).toContain('v-for="block in message.displayBlocks"')
    expect(pageSource).toContain(':key="message.id"')
    expect(pageSource).toContain('setGenerationToolCardState(')
    expect(pageSource).toContain('!isOperationTool(view.name)')
    expect(pageSource).toContain('updatePreview(true)')
  })
})
