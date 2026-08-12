import { describe, expect, it } from 'vitest'

import { parseBuildProjectToolResult } from './buildProjectToolResult'

const baseResult = {
  protocol: 'vue-build-tool/v1',
  invocationStatus: 'COMPLETED',
  success: true,
  attempt: 1,
  maxAttempts: 3,
  stage: 'SUCCESS',
  failureKind: null,
  timedOut: false,
  repairable: false,
  reflectionRequired: false,
  nextAction: 'STOP',
  message: '构建成功',
  errorSummary: null,
  terminateToolLoop: true,
  finalResponse: '项目已生成并构建成功。',
}

function failedResult(attempt: number) {
  const finalAttempt = attempt === 3
  return {
    ...baseResult,
    success: false,
    attempt,
    stage: 'NPM_BUILD',
    failureKind: 'CODE',
    repairable: attempt === 1,
    reflectionRequired: attempt >= 2,
    nextAction: attempt === 1 ? 'REPAIR' : attempt === 2 ? 'FINAL_DIAGNOSIS' : 'STOP',
    message: `第 ${attempt} 次构建失败`,
    errorSummary: 'TypeScript 编译失败',
    terminateToolLoop: finalAttempt,
    finalResponse: finalAttempt ? '抱歉，系统遇到了一些问题，请您稍后重试修复' : null,
  }
}

describe('parseBuildProjectToolResult', () => {
  it('解析合法的构建成功结果', () => {
    expect(parseBuildProjectToolResult(JSON.stringify(baseResult))).toEqual({
      invocationStatus: 'COMPLETED',
      success: true,
      attempt: 1,
      maxAttempts: 3,
      stage: 'SUCCESS',
      statusText: '第 1 次构建成功',
      terminal: true,
    })
  })

  it.each([
    [1, '第 1 次构建失败，正在进行最小修复', false],
    [2, '第 2 次构建失败，正在进行最终诊断', false],
    [3, '第 3 次构建失败，已停止自动修复', true],
  ])('解析第 %i 次失败结果', (attempt, statusText, terminal) => {
    expect(parseBuildProjectToolResult(failedResult(attempt))).toEqual({
      invocationStatus: 'COMPLETED',
      success: false,
      attempt,
      maxAttempts: 3,
      stage: 'NPM_BUILD',
      statusText,
      errorSummary: 'TypeScript 编译失败',
      terminal,
    })
  })

  it.each([
    {
      ...baseResult,
      invocationStatus: 'BUILD_IN_PROGRESS',
      success: null,
      attempt: null,
      stage: null,
      timedOut: null,
      nextAction: null,
      message: '当前已有构建正在执行',
      terminateToolLoop: false,
      finalResponse: null,
    },
    {
      ...baseResult,
      invocationStatus: 'REJECTED',
      success: null,
      attempt: null,
      stage: null,
      timedOut: null,
      nextAction: null,
      message: '构建请求被拒绝',
      terminateToolLoop: true,
      finalResponse: '抱歉，系统遇到了一些问题，请您稍后重试修复',
    },
  ])('保留未完成调用的 undefined success', (raw) => {
    const parsed = parseBuildProjectToolResult(raw)

    expect(parsed?.success).toBeUndefined()
    expect(parsed?.statusText).toBe(raw.message)
  })

  it('解析带阶段的取消结果', () => {
    const raw = {
      ...baseResult,
      invocationStatus: 'CANCELLED',
      success: null,
      attempt: 2,
      stage: 'NPM_BUILD',
      failureKind: null,
      timedOut: null,
      repairable: false,
      reflectionRequired: false,
      nextAction: 'STOP',
      message: '用户取消构建',
      errorSummary: null,
      terminateToolLoop: true,
      finalResponse: '抱歉，系统遇到了一些问题，请您稍后重试修复',
    }

    expect(parseBuildProjectToolResult(raw)).toMatchObject({
      invocationStatus: 'CANCELLED',
      success: undefined,
      attempt: 2,
      stage: 'NPM_BUILD',
      statusText: '用户取消构建',
      terminal: true,
    })
  })

  it.each([
    ['缺少字段', { ...baseResult, message: undefined }],
    ['字段类型错误', { ...baseResult, attempt: '1' }],
    ['未知协议', { ...baseResult, protocol: 'vue-build-tool/v2' }],
    ['maxAttempts 不是 3', { ...baseResult, maxAttempts: 4 }],
    ['未完成调用伪造失败', { ...baseResult, invocationStatus: 'REJECTED', success: false }],
    ['成功结果携带错误摘要', { ...baseResult, errorSummary: '不应存在' }],
    ['未知构建阶段', { ...failedResult(1), stage: 'UNKNOWN_STAGE' }],
    ['未知失败类型', { ...failedResult(1), failureKind: 'UNKNOWN_FAILURE' }],
  ])('拒绝%s', (_name, raw) => {
    expect(parseBuildProjectToolResult(raw)).toBeUndefined()
  })

  it('限制错误摘要长度并按普通字符串返回', () => {
    const parsed = parseBuildProjectToolResult({
      ...failedResult(1),
      errorSummary: `<script>alert('xss')</script>${'错'.repeat(2000)}`,
    })

    expect(parsed?.errorSummary).toHaveLength(1000)
    expect(parsed?.errorSummary).toContain("<script>alert('xss')</script>")
  })
})
