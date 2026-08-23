import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')

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

  it('左侧对话区继续渲染构建工具的执行中状态', () => {
    expect(pageSource).toContain("view.name === 'buildProject'")
    expect(pageSource).toContain("getBuildProjectDisplayState(view) === 'streaming'")
    expect(pageSource).toContain('执行中')
  })
})
