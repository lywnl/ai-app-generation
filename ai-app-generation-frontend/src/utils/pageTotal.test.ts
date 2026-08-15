import { describe, expect, it } from 'vitest'

import { normalizePageTotal } from './pageTotal'

describe('分页总数归一化', () => {
  it.each([
    ['数字字符串', '2', 2],
    ['数字', 4, 4],
    ['零', '0', 0],
    ['空值', undefined, 0],
    ['负数', '-1', 0],
    ['小数', '1.5', 0],
    ['非数字', 'unknown', 0],
    ['布尔值', true, 0],
    ['数组', ['2'], 0],
  ])('%s 转换为 Ant Design Vue 可接受的数字', (_name, raw, expected) => {
    expect(normalizePageTotal(raw)).toBe(expected)
  })
})
