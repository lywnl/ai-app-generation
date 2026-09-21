import { describe, expect, it } from 'vitest'
import type { ToolCallView } from './generationSession'
import { getToolOperationCode, getToolOperationResult, getToolOperationTarget } from './toolOperationDisplay'
import { parseBuildProjectToolResult } from './buildProjectToolResult'
import buildCases from '../test-fixtures/vue-build-tool-v1-cases.json'

function tool(name = 'writeFile', status = 'APPLIED', overrides: Record<string, unknown> = {}): ToolCallView {
  return {
    id: 'one', name, generation: '1', provisional: false, status: 'done',
    executedDisplayCommitted: false, args: { relativeFilePath: 'src/App.vue' },
    result: JSON.stringify({
      protocol: name === 'readSkill' ? 'skill-tool/v1' : 'file-tool/v1',
      operation: name, status, message: '真实结果', failureReason: null, content: null,
      ...(name === 'readSkill' ? { skillName: 'vue-design' } : {
        relativePath: 'src/App.vue', changed: status === 'APPLIED' && ['writeFile', 'modifyFile', 'deleteFile'].includes(name),
      }),
      ...overrides,
    }),
  }
}

describe('工具结果展示', () => {
  it.each(buildCases)('构建 $name 复用严格解析结果，不伪装构建成功', ({ raw, expectedView }) => {
    const view = { ...tool('buildProject'), result: JSON.stringify(raw), build: parseBuildProjectToolResult(raw) }
    const result = getToolOperationResult(view, 'done')
    if (!expectedView) {
      expect(result.label).toBe('结果未确认')
      return
    }
    expect(result.message).toBe(expectedView.statusText)
    if (expectedView.invocationStatus === 'CANCELLED') expect(result.label).toBe('已取消')
    else if (expectedView.invocationStatus === 'REJECTED') expect(result.label).toBe('已拒绝')
    else if (expectedView.invocationStatus === 'BUILD_IN_PROGRESS') expect(result.label).toBe('构建进行中')
    else expect(result.label).toBe(expectedView.success ? '已完成' : '失败')
  })

  it('构建工具显示当前项目，执行中无结果时不报失败', () => {
    const view = { ...tool('buildProject'), status: 'streaming' as const, args: {} }
    expect(getToolOperationTarget(view)).toBe('当前项目')
    expect(getToolOperationResult(view, 'streaming').label).toBe('执行中')
    expect(getToolOperationResult(view, 'done').label).toBe('结果未确认')
    expect(getToolOperationCode(view)).toEqual({})
  })
  it.each(['writeFile', 'modifyFile', 'readFile', 'readDir', 'deleteFile', 'readSkill'])('%s 从真实协议确认完成', (name) => {
    expect(getToolOperationResult(tool(name), 'done')).toMatchObject({ label: '已完成', message: '真实结果' })
  })
  it.each([
    ['NO_CHANGE', '无变化'], ['REJECTED', '已拒绝'], ['NOT_FOUND', '未找到'],
    ['CANCELLED', '已取消'], ['FAILED', '失败'],
  ])('%s 保留真实状态', (status, label) => {
    expect(getToolOperationResult(tool('modifyFile', status), 'done').label).toBe(label)
  })
  it.each([
    { protocol: 'other' }, { operation: 'deleteFile' }, { status: 'SUCCESS' },
    { changed: false }, { message: '' }, { content: '<secret>' },
  ])('不接受不可信或自相矛盾的结果 %j', (overrides) => {
    expect(getToolOperationResult(tool('writeFile', 'APPLIED', overrides), 'done').label).toBe('结果未确认')
  })
  it('调用结束不等于成功，断流也不推断工具失败或取消', () => {
    const view = { ...tool(), result: '非法 JSON' }
    expect(getToolOperationResult(view, 'done').label).toBe('结果未确认')
    view.status = 'streaming'
    expect(getToolOperationResult(view, 'streaming').label).toBe('执行中')
    expect(getToolOperationResult(view, 'error').label).toBe('结果未确认')
  })
  it('不接受 Skill 原文或不支持的状态', () => {
    expect(getToolOperationResult(tool('readSkill', 'APPLIED', { content: '隐藏正文' }), 'done').label).toBe('结果未确认')
    expect(getToolOperationResult(tool('readSkill', 'NO_CHANGE'), 'done').label).toBe('结果未确认')
  })
  it('计划版本冲突不得被文件工具协议接受', () => {
    expect(getToolOperationResult(tool('modifyFile', 'CONFLICT'), 'done').status).toBe('unknown')
  })
  it('目录目标使用 relativeDirPath', () => {
    expect(getToolOperationTarget({ ...tool('readDir'), args: { relativeDirPath: 'src/components' } })).toBe('src/components')
  })
})

describe('工具代码预览', () => {
  it('没有代码时保留空值供组件按生成状态显示空态', () => {
    expect(getToolOperationCode({ ...tool(), args: {} }).after).toBeUndefined()
    expect(getToolOperationCode({ ...tool(), args: { content: '' } }).after).toBe('')
  })
  it('已提交的空文件不能回退到旧参数', () => {
    const view = { ...tool(), executedDisplayCommitted: true, args: { content: '旧参数' },
      executedDisplayText: '```html\n```' }
    expect(getToolOperationCode(view).after).toBe('')
  })
  it('长 URL、中文、缩进和换行保留原文，不在数据层插入换行或截断', () => {
    const content = `\t  const url = "https://example.com/${'long-path'.repeat(300)}"\n  const title = "中文标题"\n`
    const view = { ...tool(), args: { content } }
    expect(getToolOperationCode(view).after).toBe(content)
    expect(getToolOperationCode({ ...view, executedDisplayCommitted: true,
      executedDisplayText: `\`\`\`js\n${content}\`\`\`` }).after).toBe(content)
  })
  it('完成正文未提交时仅显示流式参数', () => {
    const view = { ...tool(), args: { content: '流式代码' }, executedDisplayText: '```html\n尚未提交\n```' }
    expect(getToolOperationCode(view).after).toBe('流式代码')
  })
  it('用 Markdown 解析器读取包含反引号的完整围栏，完成后替换参数而非追加', () => {
    const view = { ...tool(), args: { content: '部分' }, executedDisplayCommitted: true,
      executedDisplayText: '完成\n````html\n<div>```</div>\n````\n' }
    expect(getToolOperationCode(view)).toEqual({ after: '<div>```</div>\n' })
  })
  it('修改前后各自取一个代码块，失败时仍可查看本次提交参数', () => {
    const view = { ...tool('modifyFile'), executedDisplayCommitted: true,
      args: { oldContent: '旧参数', newContent: '新参数' },
      executedDisplayText: '修改前\n```\n旧正文\n```\n修改后\n```\n新正文\n```' }
    expect(getToolOperationCode(view)).toEqual({ before: '旧正文\n', after: '新正文\n' })
    view.executedDisplayText = '无变化'
    expect(getToolOperationCode(view)).toEqual({ before: '旧参数', after: '新参数' })
  })
  it.each(['readFile', 'readDir', 'readSkill', 'deleteFile'])('%s 不渲染读取或删除的内容载荷', (name) => {
    expect(getToolOperationCode({ ...tool(name), args: { content: '不可展示' } })).toEqual({})
  })
})
