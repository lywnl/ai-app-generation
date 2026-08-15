import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')

describe('代码生成外层消息容器吸底', () => {
  it('使用即时滚动，避免程序化平滑滚动被误判为用户上滑', () => {
    const messagesContainerStyles = pageSource.match(
      /\.messages-container\s*\{(?<rules>[\s\S]*?)\}/,
    )?.groups?.rules

    expect(messagesContainerStyles).toBeDefined()
    expect(messagesContainerStyles).toContain('overflow-y: auto')
    expect(messagesContainerStyles).toContain('scroll-behavior: auto')
    expect(messagesContainerStyles).not.toContain('scroll-behavior: smooth')
  })

  it('用户离开底部时暂停吸底，回到底部阈值内时恢复', () => {
    expect(pageSource).toContain('stickBottom.value = distance <= 32')
    expect(pageSource).toContain("addEventListener('scroll', handleMessagesScroll")
    expect(pageSource).toContain("removeEventListener('scroll', handleMessagesScroll)")
    expect(pageSource).toMatch(
      /if \(!stickBottom\.value\) return[\s\S]*?el\.scrollTop = el\.scrollHeight/,
    )
  })
})
