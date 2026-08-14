import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const controllerSource = readFileSync(new URL('./appController.ts', import.meta.url), 'utf8')
const typingsSource = readFileSync(new URL('./typings.d.ts', import.meta.url), 'utf8')

describe('生成接口静态契约', () => {
  it('只保留 POST body 与字符串 appId 定义', () => {
    expect(controllerSource).toContain('POST /app/chat/gen/code')
    expect(controllerSource).toContain('body: API.AppChatGenerateRequest')
    expect(controllerSource).toContain("method: 'POST'")
    expect(controllerSource).toContain('data: body')
    expect(controllerSource).not.toContain('GET /app/chat/gen/code')
    expect(controllerSource).not.toContain('chatToGenCodeParams')

    expect(typingsSource).toMatch(
      /type AppChatGenerateRequest = \{\s*appId: string\s*message: string\s*\}/,
    )
    expect(typingsSource).not.toContain('type chatToGenCodeParams')
  })
})
