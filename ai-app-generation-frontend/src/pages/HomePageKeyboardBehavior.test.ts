import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const homeSource = readFileSync(new URL('./HomePage.vue', import.meta.url), 'utf8')

describe('首页主输入框键盘行为', () => {
  it('为主输入框绑定键盘提交处理器', () => {
    expect(homeSource).toContain('@keydown="handlePromptKeydown"')
  })

  it('普通 Enter 阻止换行并提交，Shift + Enter 保留换行', () => {
    expect(homeSource).toMatch(
      /const handlePromptKeydown = \(event: KeyboardEvent\) => \{[\s\S]*?if \(event\.key !== 'Enter' \|\| event\.shiftKey\) \{[\s\S]*?return[\s\S]*?\}[\s\S]*?event\.preventDefault\(\)[\s\S]*?createApp\(\)[\s\S]*?\}/,
    )
  })
})
