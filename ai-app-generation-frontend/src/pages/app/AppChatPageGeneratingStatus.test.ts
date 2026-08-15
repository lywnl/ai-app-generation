import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')

describe('代码生成阶段提示文案', () => {
  it('右侧预览区在整个生成回合保持统一文案', () => {
    expect(pageSource).toContain('<p>正在生成网站...</p>')
    expect(pageSource).not.toContain('正在构建中，请您耐心等待')
    expect(pageSource).not.toContain('generatingStatusText')
  })

  it('左侧对话区继续渲染构建工具的执行中状态', () => {
    expect(pageSource).toContain("view.name === 'buildProject'")
    expect(pageSource).toContain("getBuildProjectDisplayState(view) === 'streaming'")
    expect(pageSource).toContain('执行中')
  })
})
