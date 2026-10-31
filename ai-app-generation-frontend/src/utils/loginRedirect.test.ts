import { describe, expect, it } from 'vitest'
import { loginRedirect } from './loginRedirect'

describe('登录后返回站内页面', () => {
  it('保留长期记忆页面路径', () => expect(loginRedirect('/user/memory')).toBe('/user/memory'))
  it.each([
    undefined,
    null,
    [' /user/memory'],
    'https://example.com',
    '//example.com',
    '/\\example.com',
    '/\nexample.com',
  ])('拒绝非站内目标 %s', (value) => {
    expect(loginRedirect(value)).toBe('/')
  })
})
