import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')
const cardSource = readFileSync(new URL('../../components/ToolOperationCard.vue', import.meta.url), 'utf8')

describe('代码生成阶段提示文案', () => {
  it('两个加载区共用五级恢复提示派生函数并可被辅助技术感知', () => {
    expect(pageSource).toContain('正在生成网站...')
    expect(pageSource).toContain('AI 正在思考...')
    expect(pageSource.match(/role="status"/g)).toHaveLength(2)
    expect(pageSource.match(/aria-live="polite"/g)).toHaveLength(2)
    expect(pageSource.match(/getGenerationStatusText\(/g)).toHaveLength(2)
    expect(pageSource.match(/internalOutputRecovery/g)?.length).toBeGreaterThanOrEqual(8)
  })

  it('普通思考仅在无输出时显示但压缩和四类恢复状态始终优先可见', () => {
    expect(pageSource).toContain(
      'const hasVisibleOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0',
    )
    expect(pageSource).toContain('aiMessage.loading = shouldShowGenerationStatus(')
    expect(pageSource).toContain('snapshot.internalOutputRecovery')
    expect(pageSource).toContain('snapshot.contextCompression')
    expect(pageSource).toContain('snapshot.toolProtocolRecovery')
    expect(pageSource).toContain('snapshot.incompleteToolChainRecovery')
    expect(pageSource).not.toContain('aiMessage.loading = snapshot.loading\n')
  })

  it('全局 ref、Message、快照克隆和重置同步内部输出恢复状态', () => {
    expect(pageSource).toContain('type InternalOutputRecoveryState')
    expect(pageSource).toContain('internalOutputRecovery?: InternalOutputRecoveryState')
    expect(pageSource).toContain(
      "const internalOutputRecovery = ref<InternalOutputRecoveryState>('idle')",
    )
    expect(pageSource).toContain('internalOutputRecovery.value = snapshot.internalOutputRecovery')
    expect(pageSource).toContain('aiMessage.internalOutputRecovery = snapshot.internalOutputRecovery')
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
