import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const routerSource = readFileSync(new URL('./index.ts', import.meta.url), 'utf8')
const appSource = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')

describe('路由外层页面滚动位置', () => {
  it('所有页面跳转都回到顶部，不恢复浏览器历史滚动位置', () => {
    expect(routerSource).toMatch(
      /scrollBehavior\(\)\s*\{[\s\S]*?return \{ left: 0, top: 0 \}[\s\S]*?\}/,
    )
    expect(routerSource).not.toContain('savedPosition')
  })

  it('根页面只裁剪横向溢出，不把 body 变成独立纵向滚动容器', () => {
    const bodyStyles = appSource.match(/body\s*\{(?<rules>[\s\S]*?)\}/)?.groups?.rules
    const htmlStyles = appSource.match(/html\s*\{(?<rules>[\s\S]*?)\}/)?.groups?.rules

    expect(bodyStyles).toContain('overflow-x: clip')
    expect(bodyStyles).not.toContain('overflow-x: hidden')
    expect(htmlStyles).toContain('overflow-x: clip')
    expect(htmlStyles).not.toContain('overflow-x: hidden')
  })
})
