import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')

describe('代码生成阶段提示文案', () => {
  it('两个现有加载区都按压缩状态切换固定文案并可被辅助技术感知', () => {
    expect(pageSource).toContain('正在生成网站...')
    expect(pageSource).toContain('正在压缩上下文，请稍候…')
    expect(pageSource).toContain('AI 正在思考...')
    expect(pageSource.match(/role="status"/g)).toHaveLength(2)
    expect(pageSource.match(/aria-live="polite"/g)).toHaveLength(2)
    expect(pageSource).not.toContain('正在构建中，请您耐心等待')
  })

  it('左侧状态行只在尚无正文和工具调用时显示', () => {
    expect(pageSource).toContain(
      'const hasVisibleOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0',
    )
    expect(pageSource).toContain('aiMessage.loading = snapshot.loading && !hasVisibleOutput')
    expect(pageSource).not.toContain('aiMessage.loading = snapshot.loading\n')
    expect(pageSource).toContain('snapshot.contextCompression')
    expect(pageSource).not.toContain(':content="generationStatusText"')
  })

  it('左侧对话区继续渲染构建工具的执行中状态', () => {
    expect(pageSource).toContain("view.name === 'buildProject'")
    expect(pageSource).toContain("getBuildProjectDisplayState(view) === 'streaming'")
    expect(pageSource).toContain('执行中')
  })
})
