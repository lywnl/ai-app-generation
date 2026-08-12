export interface BuildProjectToolView {
  invocationStatus: 'COMPLETED' | 'BUILD_IN_PROGRESS' | 'REJECTED' | 'CANCELLED'
  success?: boolean
  attempt?: number
  maxAttempts: number
  stage?: string
  statusText: string
  errorSummary?: string
  terminal: boolean
}

type JsonRecord = Record<string, unknown>
type InvocationStatus = BuildProjectToolView['invocationStatus']

const PROTOCOL = 'vue-build-tool/v1'
const MAX_ATTEMPTS = 3
const MAX_ERROR_SUMMARY_LENGTH = 1000
const FAILURE_RESPONSE = '抱歉，系统遇到了一些问题，请您稍后重试修复'
const SUCCESS_RESPONSE = '项目已生成并构建成功。'
const INVOCATION_STATUSES = new Set<InvocationStatus>([
  'COMPLETED',
  'BUILD_IN_PROGRESS',
  'REJECTED',
  'CANCELLED',
])
const NEXT_ACTIONS = new Set(['REPAIR', 'RETRY_BUILD', 'FINAL_DIAGNOSIS', 'STOP'])
const BUILD_STAGES = new Set(['VALIDATION', 'NPM_INSTALL', 'NPM_BUILD', 'DIST_CHECK', 'SUCCESS'])
const FAILURE_KINDS = new Set(['CODE', 'DEPENDENCY', 'INFRASTRUCTURE'])

function parseRecord(raw: unknown): JsonRecord | undefined {
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      return isRecord(parsed) ? parsed : undefined
    } catch {
      return undefined
    }
  }
  return isRecord(raw) ? raw : undefined
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isNullableBoolean(value: unknown): value is boolean | null {
  return value === null || typeof value === 'boolean'
}

function isNullableAttempt(value: unknown): value is number | null {
  return value === null || (Number.isInteger(value) && Number(value) >= 1 && Number(value) <= 3)
}

function hasCommonFields(record: JsonRecord): boolean {
  return (
    record.protocol === PROTOCOL &&
    INVOCATION_STATUSES.has(record.invocationStatus as InvocationStatus) &&
    record.maxAttempts === MAX_ATTEMPTS &&
    typeof record.message === 'string' &&
    record.message.trim().length > 0 &&
    isNullableBoolean(record.success) &&
    isNullableAttempt(record.attempt) &&
    isNullableString(record.stage) &&
    isNullableString(record.failureKind) &&
    isNullableBoolean(record.timedOut) &&
    typeof record.repairable === 'boolean' &&
    typeof record.reflectionRequired === 'boolean' &&
    isNullableString(record.nextAction) &&
    isNullableString(record.errorSummary) &&
    typeof record.terminateToolLoop === 'boolean' &&
    isNullableString(record.finalResponse)
  )
}

function isCompletedSuccess(record: JsonRecord): boolean {
  return (
    record.success === true &&
    record.stage === 'SUCCESS' &&
    record.failureKind === null &&
    record.timedOut === false &&
    record.repairable === false &&
    record.reflectionRequired === false &&
    record.nextAction === 'STOP' &&
    record.errorSummary === null &&
    record.terminateToolLoop === true &&
    record.finalResponse === SUCCESS_RESPONSE
  )
}

function expectedFailureAction(record: JsonRecord): string {
  if (record.attempt === MAX_ATTEMPTS) {
    return 'STOP'
  }
  if (record.attempt === 2) {
    return 'FINAL_DIAGNOSIS'
  }
  return record.failureKind === 'CODE' ? 'REPAIR' : 'RETRY_BUILD'
}

function isCompletedFailure(record: JsonRecord): boolean {
  if (
    record.success !== false ||
    record.stage === 'SUCCESS' ||
    typeof record.failureKind !== 'string' ||
    !FAILURE_KINDS.has(record.failureKind) ||
    typeof record.errorSummary !== 'string' ||
    !NEXT_ACTIONS.has(record.nextAction as string)
  ) {
    return false
  }
  const terminal = record.attempt === MAX_ATTEMPTS
  return (
    record.repairable === (record.attempt === 1 && record.failureKind === 'CODE') &&
    record.reflectionRequired === (Number(record.attempt) >= 2) &&
    record.nextAction === expectedFailureAction(record) &&
    record.terminateToolLoop === terminal &&
    (terminal ? record.finalResponse === FAILURE_RESPONSE : record.finalResponse === null)
  )
}

function completedStatusText(record: JsonRecord): string {
  if (record.success === true) {
    return `第 ${record.attempt} 次构建成功`
  }
  if (record.nextAction === 'REPAIR') {
    return '第 1 次构建失败，正在进行最小修复'
  }
  if (record.nextAction === 'RETRY_BUILD') {
    return '第 1 次构建失败，正在直接重试构建'
  }
  if (record.nextAction === 'FINAL_DIAGNOSIS') {
    return '第 2 次构建失败，正在进行最终诊断'
  }
  return `第 ${record.attempt} 次构建失败，已停止自动修复`
}

function parseCompleted(record: JsonRecord): BuildProjectToolView | undefined {
  if (
    typeof record.success !== 'boolean' ||
    typeof record.attempt !== 'number' ||
    typeof record.stage !== 'string' ||
    !BUILD_STAGES.has(record.stage) ||
    typeof record.timedOut !== 'boolean' ||
    typeof record.nextAction !== 'string' ||
    (!isCompletedSuccess(record) && !isCompletedFailure(record))
  ) {
    return undefined
  }
  return {
    invocationStatus: 'COMPLETED',
    success: record.success,
    attempt: record.attempt,
    maxAttempts: MAX_ATTEMPTS,
    stage: record.stage,
    statusText: completedStatusText(record),
    errorSummary:
      typeof record.errorSummary === 'string'
        ? record.errorSummary.slice(0, MAX_ERROR_SUMMARY_LENGTH)
        : undefined,
    terminal: record.terminateToolLoop as boolean,
  }
}

function parseCancelled(record: JsonRecord): BuildProjectToolView | undefined {
  const hasAttemptAndStage = typeof record.attempt === 'number' && typeof record.stage === 'string'
  const hasNeither = record.attempt === null && record.stage === null
  if (
    record.success !== null ||
    record.failureKind !== null ||
    record.timedOut !== null ||
    record.repairable !== false ||
    record.reflectionRequired !== false ||
    record.nextAction !== 'STOP' ||
    record.errorSummary !== null ||
    record.terminateToolLoop !== true ||
    record.finalResponse !== FAILURE_RESPONSE ||
    (hasAttemptAndStage && !BUILD_STAGES.has(record.stage as string)) ||
    (!hasAttemptAndStage && !hasNeither)
  ) {
    return undefined
  }
  return {
    invocationStatus: 'CANCELLED',
    success: undefined,
    attempt: hasAttemptAndStage ? (record.attempt as number) : undefined,
    maxAttempts: MAX_ATTEMPTS,
    stage: hasAttemptAndStage ? (record.stage as string) : undefined,
    statusText: record.message as string,
    terminal: true,
  }
}

function parseTransient(record: JsonRecord): BuildProjectToolView | undefined {
  if (
    record.success !== null ||
    record.attempt !== null ||
    record.stage !== null ||
    record.failureKind !== null ||
    record.timedOut !== null ||
    record.repairable !== false ||
    record.reflectionRequired !== false ||
    record.nextAction !== null ||
    record.errorSummary !== null
  ) {
    return undefined
  }
  const status = record.invocationStatus as 'BUILD_IN_PROGRESS' | 'REJECTED'
  if (status === 'BUILD_IN_PROGRESS' && (record.terminateToolLoop || record.finalResponse !== null)) {
    return undefined
  }
  if (
    status === 'REJECTED' &&
    (record.terminateToolLoop !== (record.finalResponse !== null) ||
      (record.terminateToolLoop && record.finalResponse !== FAILURE_RESPONSE))
  ) {
    return undefined
  }
  return {
    invocationStatus: status,
    success: undefined,
    maxAttempts: MAX_ATTEMPTS,
    statusText: record.message as string,
    terminal: record.terminateToolLoop as boolean,
  }
}

export function parseBuildProjectToolResult(raw: unknown): BuildProjectToolView | undefined {
  const record = parseRecord(raw)
  if (!record || !hasCommonFields(record)) {
    return undefined
  }
  if (record.invocationStatus === 'COMPLETED') {
    return parseCompleted(record)
  }
  if (record.invocationStatus === 'CANCELLED') {
    return parseCancelled(record)
  }
  return parseTransient(record)
}
