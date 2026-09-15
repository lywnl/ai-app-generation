import { webcrypto } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createUuid } from './uuid'

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

afterEach(() => vi.unstubAllGlobals())

describe('HTTP 兼容 UUID', () => {
  it('支持原生 randomUUID 时保留 Crypto 调用上下文', () => {
    const crypto = {
      randomUUID: vi.fn(function (this: unknown) {
        expect(this).toBe(crypto)
        return '00000000-0000-4000-8000-000000000001'
      }),
      getRandomValues: vi.fn(),
    }
    vi.stubGlobal('crypto', crypto)
    expect(createUuid()).toBe('00000000-0000-4000-8000-000000000001')
    expect(crypto.getRandomValues).not.toHaveBeenCalled()
  })

  it.each([
    [0, '00000000-0000-4000-8000-000000000000'],
    [255, 'ffffffff-ffff-4fff-bfff-ffffffffffff'],
  ])('HTTP 回退正确设置版本与变体位：%i', (value, expected) => {
    const crypto = {
      getRandomValues: vi.fn(function (this: unknown, bytes: Uint8Array) {
        expect(this).toBe(crypto)
        expect(bytes.byteLength).toBe(16)
        return bytes.fill(value)
      }),
    }
    vi.stubGlobal('crypto', crypto)
    expect(createUuid()).toBe(expected)
    expect(crypto.getRandomValues).toHaveBeenCalledTimes(1)
  })

  it('缺少 randomUUID 时每次使用安全随机数产生合法的不同 ID', () => {
    vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) })
    const ids = Array.from({ length: 100 }, () => createUuid())
    expect(ids.every(id => uuidV4.test(id))).toBe(true)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it.each([undefined, {}])('完全不支持 Web Crypto 时明确失败', crypto => {
    vi.stubGlobal('crypto', crypto)
    expect(() => createUuid()).toThrow('当前浏览器不支持安全随机数生成')
  })
})
