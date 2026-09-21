import { describe, expect, it } from 'vitest'
import { parsePlanSnapshot } from './planSnapshot'
import { presentPlan, executionEntries, toolFilePath } from './planPresentation'
import { getToolOperationResult } from './toolOperationDisplay'
import type { ToolCallView } from './generationSession'
const tool = (name: string, result?: string): ToolCallView => ({ id: name, generation: '1', name, status: 'done', provisional: false, executedDisplayCommitted: false, args: {}, result })
describe('计划展示', () => {
  it('区分 KEEP、计划外及触达进度；依赖等待仅来自快照', () => {
    const plan = parsePlanSnapshot({ planId: 'p', version: 1, summary: '目标', status: 'PLANNED', history: [], files: [
      { path: 'a', purpose: '', action: 'KEEP', state: 'PENDING', dependsOn: [] },
      { path: 'b', purpose: '', action: 'MODIFY', state: 'TOUCHED', dependsOn: ['a'] },
      { path: 'c', purpose: '', action: 'CREATE', state: 'PENDING', dependsOn: ['b'] },
      { path: 'd', purpose: '', action: 'CREATE', state: 'PENDING', dependsOn: ['c'] },
      { path: 'extra', purpose: '', action: 'MODIFY', state: 'OUT_OF_PLAN', dependsOn: [] },
    ] })!
    const view = presentPlan(plan, [], false)
    expect([view.touched, view.total, view.outside.length]).toEqual([1, 3, 1])
    expect(view.files[1]!.waitingFor).toEqual([]); expect(view.files[3]!.waitingFor).toEqual(['c'])
    expect(presentPlan({ ...plan, files: [] }, [], false).total).toBe(0)
  })
  it('只有活动回合和合法路径才能显示正在执行', () => {
    const view = { ...tool('writeFile'), status: 'streaming' as const, args: { relativeFilePath: '../secret' } }
    expect(toolFilePath(view)).toBeUndefined()
    view.args.relativeFilePath = 'src/App.vue'
    expect(toolFilePath(view)).toBe('src/App.vue')
    expect(executionEntries([view], 'done')[0]!.result.status).toBe('unknown')
  })
  it.each(['APPLIED', 'REJECTED', 'CONFLICT', 'FAILED'])('计划工具 %s 不等于计划整体状态', (status) => {
    const view = tool('updatePlan', JSON.stringify({ protocol: 'plan-tool/v1', operation: 'updatePlan', status,
      planId: 'p', version: 2, summary: '调整', files: [], message: '内部细节不直出' }))
    expect(getToolOperationResult(view, 'done').status).toBe(status)
    expect(getToolOperationResult(view, 'done').message).not.toContain('内部细节')
    if (status === 'APPLIED') expect(getToolOperationResult(view, 'done').plan).toMatchObject({ version: 2, summary: '调整', files: [] })
    else expect(getToolOperationResult(view, 'done').plan).toBeUndefined()
    expect(toolFilePath(view)).toBeUndefined()
    expect(executionEntries([view], 'done')[0]!.label).toBe('调整计划')
  })
})
