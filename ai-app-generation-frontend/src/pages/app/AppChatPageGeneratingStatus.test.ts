import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')

describe('代码生成阶段提示文案', () => {
  it('两个加载区共用恢复提示派生函数并可被辅助技术感知', () => {
    expect(pageSource).toContain('正在生成网站...')
    expect(pageSource).toContain('AI 正在思考...')
    expect(pageSource.match(/role="status"/g)).toHaveLength(2)
    expect(pageSource.match(/aria-live="polite"/g)).toHaveLength(2)
    expect(pageSource).toContain('getGenerationStatusText')
    expect(pageSource.match(/getGenerationStatusText\(/g)).toHaveLength(2)
    expect(pageSource).not.toContain("contextCompression === 'compressing'")
    expect(pageSource).not.toContain('正在构建中，请您耐心等待')
  })

  it('普通思考仅在无输出时显示但压缩和恢复状态始终优先可见', () => {
    expect(pageSource).toContain(
      'const hasVisibleOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0',
    )
    expect(pageSource).toContain('aiMessage.loading = shouldShowGenerationStatus(')
    expect(pageSource).toContain(
      `aiMessage.loading = shouldShowGenerationStatus(
    snapshot.loading,
    snapshot.contextCompression,
    snapshot.toolProtocolRecovery,
    hasVisibleOutput,
  )`,
    )
    expect(pageSource).not.toContain('aiMessage.loading = snapshot.loading\n')
    expect(pageSource).toContain('snapshot.contextCompression')
    expect(pageSource).toContain('snapshot.toolProtocolRecovery')
    expect(pageSource).not.toContain(':content="generationStatusText"')
  })

  it('全局 ref、Message 与会话消息同步同一恢复状态', () => {
    expect(pageSource).toContain('type ToolProtocolRecoveryState')
    expect(pageSource).toContain("toolProtocolRecovery?: ToolProtocolRecoveryState")
    expect(pageSource).toContain(
      "const toolProtocolRecovery = ref<ToolProtocolRecoveryState>('idle')",
    )
    expect(pageSource).toContain(
      'toolProtocolRecovery.value = snapshot.toolProtocolRecovery',
    )
    expect(pageSource).toContain(
      'aiMessage.toolProtocolRecovery = snapshot.toolProtocolRecovery',
    )
  })

  it('左侧对话区继续渲染构建工具的执行中状态', () => {
    expect(pageSource).toContain("view.name === 'buildProject'")
    expect(pageSource).toContain("getBuildProjectDisplayState(view) === 'streaming'")
    expect(pageSource).toContain('执行中')
  })
})
