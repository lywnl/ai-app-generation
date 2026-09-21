import { describe, expect, it } from 'vitest'
import { parsePlanSnapshot } from './planSnapshot'

export const planFixture = () => ({
  planId: 'p1', version: 1, status: 'PLANNED', summary: '企业官网',
  files: [{ path: 'src/App.vue', purpose: '首页', action: 'MODIFY', state: 'PENDING', dependsOn: [] }],
  history: [{ version: 1, reason: '创建计划' }],
})
describe('计划快照', () => {
  it('接受空态和合法快照，并复制数据', () => {
    expect(parsePlanSnapshot(null)).toBeNull()
    const source = planFixture(), plan = parsePlanSnapshot(source)!
    source.files[0]!.path = 'other'
    expect(plan.files[0]!.path).toBe('src/App.vue')
  })
  it.each(['../a', '/a', 'a\\b', 'a//b', 'C:/a', 'a/./b'])('拒绝不安全路径 %s', (path) => {
    const plan = planFixture(); plan.files[0]!.path = path
    expect(() => parsePlanSnapshot(plan)).toThrow()
  })
  it('拒绝未知状态、依赖缺失、循环和重复路径', () => {
    expect(() => parsePlanSnapshot({ ...planFixture(), status: 'DONE' })).toThrow()
    const plan = planFixture()
    expect(() => parsePlanSnapshot({ ...plan, files: [...plan.files, ...plan.files] })).toThrow()
    expect(() => parsePlanSnapshot({ ...plan, files: [{ ...plan.files[0], dependsOn: ['missing'] }] })).toThrow()
    expect(() => parsePlanSnapshot({ ...plan, files: [{ ...plan.files[0], dependsOn: ['src/App.vue'] }] })).toThrow()
  })
})
