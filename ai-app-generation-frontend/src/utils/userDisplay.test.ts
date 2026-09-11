import { describe, expect, it } from 'vitest'
import { formatUserDisplayName } from './userDisplay'

describe('统一用户展示名称', () => {
  it('原样展示后端持久化的八位数字，包括前导零', () => {
    expect(formatUserDisplayName('用户_00123456')).toBe('用户_00123456')
    expect(formatUserDisplayName('用户_87654321')).toBe('用户_87654321')
  })

  it('缺失或旧名称只提供中性占位，不临时生成另一份身份', () => {
    for (const name of [undefined, '', '无名', '官方', '用户_1234567', '用户_123456789']) {
      expect(formatUserDisplayName(name)).toBe('用户')
    }
  })
})
