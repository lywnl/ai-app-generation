import { parseBuildProjectToolResult, type BuildProjectToolView } from './buildProjectToolResult'

export type SessionEventType = 'delta' | 'done' | 'error'
export type ToolCallStatus = 'streaming' | 'done' | 'error'
export type GenerationStatus = 'streaming' | 'done' | 'error'
export type ContextCompressionState = 'idle' | 'compressing'
export type InternalOutputRecoveryState = 'idle' | 'recovering'
export type ToolProtocolRecoveryState = 'idle' | 'recovering'
export type IncompleteToolChainRecoveryState = 'idle' | 'recovering'

export interface ToolArgView {
  relativeFilePath?: string
  oldContent?: string
  newContent?: string
  content?: string
  [key: string]: unknown
}

export interface ToolCallView {
  id: string
  name: string
  generation: string
  provisional: boolean
  status: ToolCallStatus
  args: ToolArgView
  result?: string
  build?: BuildProjectToolView
}

export type BuildProjectDisplayState = 'streaming' | 'parsed' | 'unrecognized'
export type BuildProjectVisualState =
  | 'streaming'
  | 'success'
  | 'failed'
  | 'cancelled'
  | 'neutral'
  | 'unrecognized'

export function getBuildProjectDisplayState(
  view: Pick<ToolCallView, 'status'> & { build?: unknown },
): BuildProjectDisplayState {
  if (view.status === 'streaming') return 'streaming'
  return view.build ? 'parsed' : 'unrecognized'
}

export function getBuildProjectVisualState(
  view: Pick<ToolCallView, 'status' | 'build'>,
): BuildProjectVisualState {
  if (view.status === 'streaming') return 'streaming'
  if (!view.build) return 'unrecognized'
  if (view.build.invocationStatus === 'CANCELLED') return 'cancelled'
  if (view.build.invocationStatus !== 'COMPLETED') return 'neutral'
  if (view.build.success === true) return 'success'
  return view.build.success === false ? 'failed' : 'unrecognized'
}

export type GenerationOutcome =
  | 'pending'
  | 'answered'
  | 'succeeded'
  | 'failed'
  | 'cancelled'
  | 'timed_out'
  | 'system_error'
  | 'protocol_error'
  | 'incomplete_tool_chain'

export interface GenerationSessionSnapshot {
  appId: string
  content: string
  loading: boolean
  status: GenerationStatus
  outcome: GenerationOutcome
  contextCompression: ContextCompressionState
  internalOutputRecovery: InternalOutputRecoveryState
  toolProtocolRecovery: ToolProtocolRecoveryState
  incompleteToolChainRecovery: IncompleteToolChainRecoveryState
  errorMessage?: string
  toolCalls: Map<string, ToolCallView>
}

export function shouldRefreshGenerationPreview(
  snapshot: Pick<GenerationSessionSnapshot, 'status' | 'outcome'>,
): boolean {
  return snapshot.status === 'done' && snapshot.outcome === 'succeeded'
}

export function shouldHideCompletedReadOnlyTool(
  snapshot: Pick<GenerationSessionSnapshot, 'status' | 'outcome'>,
  toolName: string,
): boolean {
  return (
    snapshot.status === 'done' &&
    snapshot.outcome === 'answered' &&
    (toolName === 'readFile' || toolName === 'readDir')
  )
}

export function getGenerationStatusText(
  contextCompression: ContextCompressionState,
  internalOutputRecovery: InternalOutputRecoveryState,
  incompleteToolChainRecovery: IncompleteToolChainRecoveryState,
  toolProtocolRecovery: ToolProtocolRecoveryState,
  fallback: string,
): string {
  if (contextCompression === 'compressing') return '正在压缩上下文，请稍候…'
  if (internalOutputRecovery === 'recovering') return '正在恢复安全生成，请稍候…'
  if (incompleteToolChainRecovery === 'recovering') {
    return '正在继续未完成的构建流程，请稍候…'
  }
  if (toolProtocolRecovery === 'recovering') return '正在校正工具调用，请稍候…'
  return fallback
}

export function shouldShowGenerationStatus(
  loading: boolean,
  contextCompression: ContextCompressionState,
  internalOutputRecovery: InternalOutputRecoveryState,
  incompleteToolChainRecovery: IncompleteToolChainRecoveryState,
  toolProtocolRecovery: ToolProtocolRecoveryState,
  hasVisibleOutput: boolean,
): boolean {
  return (
    loading &&
    (contextCompression === 'compressing' ||
      internalOutputRecovery === 'recovering' ||
      incompleteToolChainRecovery === 'recovering' ||
      toolProtocolRecovery === 'recovering' ||
      !hasVisibleOutput)
  )
}

export interface StartGenerationSessionOptions {
  appId: string
  userMessage: string
  generationId: string
  baseURL: string
  renderMode?: 'direct' | 'throttled'
  throttleMs?: number
  expectVueTurnOutcome: boolean
}

type ContentFragment =
  | { source: 'simple_text'; text: string; committed: boolean }
  | { source: 'ai'; generation: string; text: string; committed: boolean }
  | {
      source: 'trusted_tool_display'
      generation: string
      toolRequestId: string
      stage: 'REQUESTED' | 'EXECUTED'
      text: string
      committed: boolean
    }

type Listener = (snapshot: GenerationSessionSnapshot, eventType: SessionEventType) => void
type JsonRecord = Record<string, unknown>
type RecoveryPhase = 'idle' | 'started' | 'recovered' | 'failed'
type AuxiliaryRecoveryPhase = 'idle' | 'recovering' | 'recovered' | 'failed'

interface InternalRecoveryProtocolState {
  phase: RecoveryPhase
  recoveryAttempt: 0 | 1
  originalFailedGeneration?: string
  recoveryGeneration?: string
  recentRollbackGeneration?: string
  rollbackAwaitingRecovery: boolean
}

interface SessionState {
  snapshot: GenerationSessionSnapshot
  fragments: ContentFragment[]
  listeners: Set<Listener>
  controller?: AbortController
  flushTimer?: ReturnType<typeof setTimeout>
  renderMode: 'direct' | 'throttled'
  throttleMs: number
  requestId: number
  expectVueTurnOutcome: boolean
  lastSequence: number
  businessErrorSeen: boolean
  awaitingDone: boolean
  awaitingDoneAfterTurnOutcome: boolean
  doneSeen: boolean
  recovery: InternalRecoveryProtocolState
  toolProtocolRecoveryPhase: AuxiliaryRecoveryPhase
  incompleteToolChainRecoveryPhase: AuxiliaryRecoveryPhase
  generationId: string
}

const DEFAULT_THROTTLE_MS = 100
const MAX_SEQUENCE = 2_147_483_647
const MAX_CODE_POINTS = 2_147_483_647
const MAX_GENERATION = '9223372036854775807'
const STREAM_PROTOCOL = 'generation-stream/v1'
const SERVICE_UNAVAILABLE_MESSAGE = '生成服务暂时不可用，请稍后重试。'
const REQUEST_TOO_LARGE_MESSAGE = '请求内容过大，请缩短需求后重试。'
const sessions = new Map<string, SessionState>()

const OUTCOME_MAP: Record<string, Exclude<GenerationOutcome, 'pending'>> = {
  ANSWERED: 'answered',
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  TIMED_OUT: 'timed_out',
  SYSTEM_ERROR: 'system_error',
  PROTOCOL_ERROR: 'protocol_error',
  INCOMPLETE_TOOL_CHAIN: 'incomplete_tool_chain',
}

const CONTEXT_COMPRESSION_MESSAGES = {
  STARTED: '正在压缩上下文，请稍候…',
  COMPLETED: '上下文压缩完成，继续生成…',
} as const

const INTERNAL_RECOVERY_MESSAGES = {
  STARTED: '检测到生成状态异常，正在重新生成…',
  RECOVERED: '生成状态已恢复，继续处理…',
  FAILED: '生成状态异常，系统已停止本次生成，请重新发起。',
} as const

const TOOL_PROTOCOL_RECOVERY_MESSAGES = {
  STARTED: '正在校正工具调用，请稍候…',
  RECOVERED: '工具调用已校正，继续生成…',
  FAILED:
    '工具调用格式异常，系统自动校正后仍未恢复。本轮没有执行相关工具，请重新发送请求。',
} as const

const INCOMPLETE_TOOL_CHAIN_RECOVERY_MESSAGES = {
  STARTED: '正在继续未完成的构建流程，请稍候…',
  RECOVERED: '未完成的构建流程已恢复，继续生成…',
  FAILED: '模型未能继续完成真实工具执行和构建，本轮已安全停止。',
} as const

class GenerationStreamError extends Error {
  constructor(
    message: string,
    readonly outcome: 'system_error' | 'protocol_error',
  ) {
    super(message)
    this.name = 'GenerationStreamError'
  }
}

function createEmptySnapshot(appId: string): GenerationSessionSnapshot {
  return {
    appId,
    content: '',
    loading: false,
    status: 'done',
    outcome: 'pending',
    contextCompression: 'idle',
    internalOutputRecovery: 'idle',
    toolProtocolRecovery: 'idle',
    incompleteToolChainRecovery: 'idle',
    toolCalls: new Map(),
  }
}

function cloneSnapshot(snapshot: GenerationSessionSnapshot): GenerationSessionSnapshot {
  const toolCalls = new Map<string, ToolCallView>()
  snapshot.toolCalls.forEach((value, key) => {
    toolCalls.set(key, {
      ...value,
      args: { ...value.args },
      build: value.build ? { ...value.build } : undefined,
    })
  })
  return { ...snapshot, toolCalls }
}

function createProtocolState(): InternalRecoveryProtocolState {
  return { phase: 'idle', recoveryAttempt: 0, rollbackAwaitingRecovery: false }
}

function getOrCreateSession(appId: string): SessionState {
  const existing = sessions.get(appId)
  if (existing) return existing
  const created: SessionState = {
    snapshot: createEmptySnapshot(appId),
    fragments: [],
    listeners: new Set(),
    renderMode: 'throttled',
    throttleMs: DEFAULT_THROTTLE_MS,
    requestId: 0,
    expectVueTurnOutcome: false,
    lastSequence: 0,
    businessErrorSeen: false,
    awaitingDone: false,
    awaitingDoneAfterTurnOutcome: false,
    doneSeen: false,
    recovery: createProtocolState(),
    toolProtocolRecoveryPhase: 'idle',
    incompleteToolChainRecoveryPhase: 'idle',
    generationId: '',
  }
  sessions.set(appId, created)
  return created
}

function getActiveSession(appId: string, requestId: number): SessionState | undefined {
  const session = sessions.get(appId)
  return session?.requestId === requestId ? session : undefined
}

function emit(appId: string, eventType: SessionEventType, requestId?: number): void {
  const session = sessions.get(appId)
  if (!session || (requestId !== undefined && session.requestId !== requestId)) return
  const snapshot = cloneSnapshot(session.snapshot)
  session.listeners.forEach((listener) => listener(snapshot, eventType))
}

function stopSessionStream(session: SessionState): void {
  session.controller?.abort()
  session.controller = undefined
  cancelFlushTimer(session)
}

function cancelFlushTimer(session: SessionState): void {
  if (!session.flushTimer) return
  clearTimeout(session.flushTimer)
  session.flushTimer = undefined
}

function rebuildContent(session: SessionState): void {
  session.snapshot.content = session.fragments
    .filter((fragment) => fragment.committed)
    .map((fragment) => fragment.text)
    .join('')
}

function discardPendingFragments(session: SessionState): void {
  cancelFlushTimer(session)
  session.fragments = session.fragments.filter((fragment) => fragment.committed)
  rebuildContent(session)
}

function commitPendingFragments(appId: string, requestId: number, notify = true): void {
  const session = getActiveSession(appId, requestId)
  if (!session) return
  cancelFlushTimer(session)
  const changed = session.fragments.some((fragment) => !fragment.committed)
  session.fragments.forEach((fragment) => {
    fragment.committed = true
  })
  rebuildContent(session)
  if (changed && notify) emit(appId, 'delta', requestId)
}

function hideAuxiliaryRecoveryStatuses(session: SessionState): boolean {
  const changed =
    session.snapshot.toolProtocolRecovery === 'recovering' ||
    session.snapshot.incompleteToolChainRecovery === 'recovering'
  session.snapshot.toolProtocolRecovery = 'idle'
  session.snapshot.incompleteToolChainRecovery = 'idle'
  return changed
}

function appendFragment(appId: string, requestId: number, fragment: ContentFragment): void {
  if (!fragment.text) return
  const session = getActiveSession(appId, requestId)
  if (!session) return
  hideAuxiliaryRecoveryStatuses(session)
  fragment.committed = session.renderMode === 'direct'
  session.fragments.push(fragment)
  if (fragment.committed) {
    rebuildContent(session)
    emit(appId, 'delta', requestId)
    return
  }
  if (!session.flushTimer) {
    session.flushTimer = setTimeout(() => {
      commitPendingFragments(appId, requestId)
    }, session.throttleMs)
  }
}

function resetVisibleStatuses(session: SessionState): void {
  session.snapshot.contextCompression = 'idle'
  session.snapshot.internalOutputRecovery = 'idle'
  session.snapshot.toolProtocolRecovery = 'idle'
  session.snapshot.incompleteToolChainRecovery = 'idle'
}

function failSession(
  appId: string,
  requestId: number,
  message: string,
  outcome: 'system_error' | 'protocol_error',
): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') return
  discardPendingFragments(session)
  session.snapshot.loading = false
  session.snapshot.status = 'error'
  session.snapshot.outcome = outcome
  session.snapshot.errorMessage = message
  resetVisibleStatuses(session)
  stopSessionStream(session)
  emit(appId, 'error', requestId)
}

function markProtocolError(appId: string, requestId: number, message: string): void {
  failSession(appId, requestId, message, 'protocol_error')
}

function markDone(appId: string, requestId: number): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') return
  if (
    session.expectVueTurnOutcome &&
    session.snapshot.outcome === 'pending' &&
    !session.businessErrorSeen
  ) {
    discardPendingFragments(session)
    markProtocolError(appId, requestId, '生成协议缺少业务终态')
    return
  }
  if (!session.expectVueTurnOutcome && session.snapshot.outcome === 'pending') {
    session.snapshot.outcome = 'succeeded'
  }
  if (session.snapshot.outcome === 'succeeded' || session.snapshot.outcome === 'answered') {
    commitPendingFragments(appId, requestId)
  } else {
    discardPendingFragments(session)
  }
  session.snapshot.loading = false
  session.snapshot.status = 'done'
  if (session.snapshot.outcome === 'succeeded') session.snapshot.errorMessage = undefined
  resetVisibleStatuses(session)
  stopSessionStream(session)
  emit(appId, 'done', requestId)
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseRecord(data: string): JsonRecord | undefined {
  if (!data) return undefined
  try {
    const parsed = JSON.parse(data) as unknown
    return isRecord(parsed) ? parsed : undefined
  } catch {
    return undefined
  }
}

function hasExactFields(record: JsonRecord, fields: readonly string[]): boolean {
  const actual = Object.keys(record).sort()
  const expected = [...fields].sort()
  return actual.length === expected.length && actual.every((field, index) => field === expected[index])
}

function isSequence(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) >= 1 && Number(value) <= MAX_SEQUENCE
}

function acceptSequence(session: SessionState, payload: JsonRecord): boolean {
  if (!isSequence(payload.sequence) || payload.sequence !== session.lastSequence + 1) return false
  session.lastSequence = payload.sequence
  return true
}

function isGeneration(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return false
  return value.length < MAX_GENERATION.length ||
    (value.length === MAX_GENERATION.length && value <= MAX_GENERATION)
}

function compareGeneration(left: string, right: string): number {
  return left.length === right.length ? left.localeCompare(right) : left.length - right.length
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function parseArgumentObject(input: string): JsonRecord | undefined {
  return parseRecord(input)
}

function displayArgument(value: unknown): unknown {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return value === null ? null : JSON.stringify(value)
}

function isVisibleToolArgument(toolName: string, key: string): boolean {
  if (toolName === 'readFile') return key === 'relativeFilePath'
  if (toolName === 'readDir') return key === 'relativeDirPath'
  return true
}

function mergeToolArguments(view: ToolCallView, args?: JsonRecord): void {
  if (!args) return
  Object.entries(args).forEach(([key, value]) => {
    if (isVisibleToolArgument(view.name, key)) view.args[key] = displayArgument(value)
  })
}

function validateToolPayload(payload: JsonRecord): boolean {
  if (payload.type === 'tool_request') {
    return hasExactFields(payload, ['type', 'id', 'name']) &&
      isNonEmptyString(payload.id) && isNonEmptyString(payload.name)
  }
  if (payload.type === 'tool_argument') {
    return hasExactFields(payload, ['type', 'id', 'name', 'key', 'value']) &&
      isNonEmptyString(payload.id) && isNonEmptyString(payload.name) &&
      isNonEmptyString(payload.key) && typeof payload.value === 'string'
  }
  if (payload.type === 'tool_argument_delta') {
    return hasExactFields(payload, ['type', 'id', 'name', 'key', 'delta']) &&
      isNonEmptyString(payload.id) && isNonEmptyString(payload.name) &&
      isNonEmptyString(payload.key) && typeof payload.delta === 'string'
  }
  if (payload.type === 'tool_executed') {
    return hasExactFields(payload, ['type', 'id', 'name', 'arguments', 'result']) &&
      isNonEmptyString(payload.id) && isNonEmptyString(payload.name) &&
      typeof payload.arguments === 'string' && typeof payload.result === 'string'
  }
  return false
}

function matchingToolCall(
  session: SessionState,
  id: string,
  name: string,
  generation: string,
): ToolCallView | undefined {
  const view = session.snapshot.toolCalls.get(id)
  return view?.name === name && view.generation === generation ? view : undefined
}

function sanitizeToolResult(toolName: string, result: string): string | undefined {
  if (toolName !== 'readFile' && toolName !== 'readDir') return result
  const parsed = parseRecord(result)
  if (
    !parsed || parsed.protocol !== 'file-tool/v1' || parsed.operation !== toolName ||
    typeof parsed.status !== 'string' ||
    (parsed.relativePath !== null && typeof parsed.relativePath !== 'string') ||
    parsed.changed !== false || !isNonEmptyString(parsed.message) ||
    (parsed.failureReason !== null && typeof parsed.failureReason !== 'string')
  ) return undefined
  return JSON.stringify({
    protocol: parsed.protocol,
    operation: parsed.operation,
    status: parsed.status,
    relativePath: parsed.relativePath,
    changed: parsed.changed,
    message: parsed.message,
    failureReason: parsed.failureReason,
    content: null,
  })
}

function handleStructuredTool(
  appId: string,
  requestId: number,
  generation: string,
  data: string,
): void {
  const session = getActiveSession(appId, requestId)
  const payload = parseRecord(data)
  if (!session || !payload || !validateToolPayload(payload)) {
    markProtocolError(appId, requestId, '结构化工具事件不合法')
    return
  }
  const id = payload.id as string
  const name = payload.name as string
  if (payload.type === 'tool_request') {
    if (session.snapshot.toolCalls.has(id)) {
      markProtocolError(appId, requestId, '工具请求 ID 重复')
      return
    }
    session.snapshot.toolCalls.set(id, {
      id, name, generation, provisional: true, status: 'streaming', args: {},
    })
  } else if (payload.type === 'tool_executed') {
    const existing = session.snapshot.toolCalls.get(id)
    if (existing && !matchingToolCall(session, id, name, generation)) {
      markProtocolError(appId, requestId, '工具执行来源不一致')
      return
    }
    const view = existing ?? {
      id, name, generation, provisional: true, status: 'streaming' as const, args: {},
    }
    mergeToolArguments(view, parseArgumentObject(payload.arguments as string))
    const result = sanitizeToolResult(name, payload.result as string)
    if (result !== undefined) {
      view.result = result
      if (name === 'buildProject') view.build = parseBuildProjectToolResult(result)
    }
    view.provisional = false
    view.status = 'done'
    session.snapshot.toolCalls.set(id, view)
  } else {
    const view = matchingToolCall(session, id, name, generation)
    if (!view || !view.provisional) {
      markProtocolError(appId, requestId, '工具参数缺少匹配的临时请求')
      return
    }
    const key = payload.key as string
    if (isVisibleToolArgument(name, key)) {
      if (payload.type === 'tool_argument') view.args[key] = payload.value as string
      else {
        const previous = typeof view.args[key] === 'string' ? view.args[key] : ''
        view.args[key] = previous + (payload.delta as string)
      }
    }
  }
  hideAuxiliaryRecoveryStatuses(session)
  emit(appId, 'delta', requestId)
}

function handleMessage(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  if (!session) return
  const kind = payload.kind
  const vueKind = kind === 'ai_text' || kind === 'structured_tool_event'
  const expectedFields = vueKind
    ? ['protocol', 'sequence', 'kind', 'data', 'generation']
    : ['protocol', 'sequence', 'kind', 'data']
  if (
    payload.protocol !== STREAM_PROTOCOL || !hasExactFields(payload, expectedFields) ||
    typeof payload.data !== 'string' ||
    (session.expectVueTurnOutcome ? !vueKind : kind !== 'simple_text') ||
    (vueKind && !isGeneration(payload.generation))
  ) {
    markProtocolError(appId, requestId, '生成正文 envelope 不合法')
    return
  }
  if (vueKind && !canAcceptGeneration(session, payload.generation as string)) {
    markProtocolError(appId, requestId, '恢复阶段收到不合法的生成代内容')
    return
  }
  if (kind === 'simple_text') {
    appendFragment(appId, requestId, { source: 'simple_text', text: payload.data, committed: false })
  } else if (kind === 'ai_text') {
    appendFragment(appId, requestId, {
      source: 'ai', generation: payload.generation as string, text: payload.data, committed: false,
    })
  } else {
    handleStructuredTool(appId, requestId, payload.generation as string, payload.data)
  }
}

function canAcceptGeneration(session: SessionState, generation: string): boolean {
  if (!session.recovery.recentRollbackGeneration) return true
  return !session.recovery.rollbackAwaitingRecovery &&
    session.recovery.phase === 'recovered' &&
    session.recovery.recoveryGeneration !== undefined &&
    compareGeneration(generation, session.recovery.recoveryGeneration) >= 0
}

function handleTrustedDisplay(appId: string, requestId: number, payload: JsonRecord): void {
  if (
    payload.protocol !== 'trusted-tool-display/v1' ||
    !hasExactFields(payload, ['protocol', 'sequence', 'generation', 'toolRequestId', 'stage', 'text']) ||
    !isGeneration(payload.generation) || !isNonEmptyString(payload.toolRequestId) ||
    (payload.stage !== 'REQUESTED' && payload.stage !== 'EXECUTED') ||
    !isNonEmptyString(payload.text)
  ) {
    markProtocolError(appId, requestId, '可信工具展示协议不合法')
    return
  }
  const session = getActiveSession(appId, requestId)
  if (!session) return
  if (!canAcceptGeneration(session, payload.generation)) {
    markProtocolError(appId, requestId, '恢复阶段收到不合法的可信工具展示')
    return
  }
  const view = matchingToolCall(
    session, payload.toolRequestId, session.snapshot.toolCalls.get(payload.toolRequestId)?.name ?? '',
    payload.generation,
  )
  if (!view || (payload.stage === 'REQUESTED' ? !view.provisional : view.provisional)) {
    markProtocolError(appId, requestId, '可信工具展示缺少对应工具事实')
    return
  }
  appendFragment(appId, requestId, {
    source: 'trusted_tool_display',
    generation: payload.generation,
    toolRequestId: payload.toolRequestId,
    stage: payload.stage,
    text: payload.text,
    committed: false,
  })
}

function rollbackAiFragments(session: SessionState, generation: string, codePoints: number): boolean {
  const available = session.fragments
    .filter((fragment) => fragment.source === 'ai' && fragment.generation === generation)
    .reduce((sum, fragment) => sum + Array.from(fragment.text).length, 0)
  if (codePoints > available) return false
  let remaining = codePoints
  for (let index = session.fragments.length - 1; index >= 0 && remaining > 0; index -= 1) {
    const fragment = session.fragments[index]
    if (fragment.source !== 'ai' || fragment.generation !== generation) continue
    const points = Array.from(fragment.text)
    const removed = Math.min(remaining, points.length)
    fragment.text = points.slice(0, points.length - removed).join('')
    remaining -= removed
  }
  session.fragments = session.fragments.filter((fragment) => fragment.text.length > 0)
  return true
}

function handleRollback(appId: string, requestId: number, payload: JsonRecord): void {
  const ids = payload.provisionalToolRequestIds
  if (
    payload.protocol !== 'internal-output-rollback/v1' ||
    !hasExactFields(payload, ['protocol', 'sequence', 'failedGeneration', 'codePoints', 'provisionalToolRequestIds']) ||
    !isGeneration(payload.failedGeneration) || !Number.isInteger(payload.codePoints) ||
    Number(payload.codePoints) < 0 || Number(payload.codePoints) > MAX_CODE_POINTS ||
    !Array.isArray(ids) || ids.some((id) => !isNonEmptyString(id)) ||
    new Set(ids).size !== ids.length
  ) {
    markProtocolError(appId, requestId, '内部输出回滚协议不合法')
    return
  }
  const session = getActiveSession(appId, requestId)
  if (!session) return
  if (session.recovery.rollbackAwaitingRecovery) {
    markProtocolError(appId, requestId, '前一内部输出回滚尚未完成状态转换')
    return
  }
  if (
    session.recovery.recoveryAttempt === 1 &&
    session.recovery.phase !== 'started' &&
    session.recovery.phase !== 'recovered'
  ) {
    markProtocolError(appId, requestId, '内部输出回滚次数超限')
    return
  }
  if (
    (session.recovery.phase === 'started' || session.recovery.phase === 'recovered') &&
    session.recovery.recoveryGeneration !== undefined &&
    compareGeneration(payload.failedGeneration, session.recovery.recoveryGeneration) < 0
  ) {
    markProtocolError(appId, requestId, '内部输出回滚代次早于恢复下界')
    return
  }
  for (const id of ids as string[]) {
    const view = session.snapshot.toolCalls.get(id)
    if (view && view.generation !== payload.failedGeneration) {
      markProtocolError(appId, requestId, '回滚工具来源代次冲突')
      return
    }
  }
  cancelFlushTimer(session)
  if (!rollbackAiFragments(session, payload.failedGeneration, payload.codePoints as number)) {
    markProtocolError(appId, requestId, '回滚正文码点数超过已接收范围')
    return
  }
  const removableIds = new Set<string>()
  for (const id of ids as string[]) {
    const view = session.snapshot.toolCalls.get(id)
    if (view?.provisional) {
      removableIds.add(id)
      session.snapshot.toolCalls.delete(id)
    }
  }
  session.fragments = session.fragments.filter(
    (fragment) =>
      fragment.source !== 'trusted_tool_display' ||
      fragment.stage !== 'REQUESTED' ||
      fragment.generation !== payload.failedGeneration ||
      !removableIds.has(fragment.toolRequestId),
  )
  session.recovery.recentRollbackGeneration = payload.failedGeneration
  session.recovery.rollbackAwaitingRecovery = true
  rebuildContent(session)
  emit(appId, 'delta', requestId)
}

function handleInternalRecovery(appId: string, requestId: number, payload: JsonRecord): void {
  if (
    payload.protocol !== 'internal-output-recovery/v1' ||
    !hasExactFields(payload, [
      'protocol', 'sequence', 'phase', 'originalFailedGeneration',
      'recoveryGeneration', 'failedGeneration', 'message',
    ]) ||
    (payload.phase !== 'STARTED' && payload.phase !== 'RECOVERED' && payload.phase !== 'FAILED') ||
    payload.message !== INTERNAL_RECOVERY_MESSAGES[payload.phase] ||
    !isGeneration(payload.originalFailedGeneration) ||
    (payload.recoveryGeneration !== null && !isGeneration(payload.recoveryGeneration)) ||
    (payload.failedGeneration !== null && !isGeneration(payload.failedGeneration))
  ) {
    markProtocolError(appId, requestId, '内部输出恢复协议不合法')
    return
  }
  const session = getActiveSession(appId, requestId)
  if (!session) return
  const state = session.recovery
  const original = payload.originalFailedGeneration
  const recoveryGeneration = payload.recoveryGeneration
  const failedGeneration = payload.failedGeneration
  if (payload.phase === 'STARTED') {
    const valid = state.recoveryAttempt === 0 && state.phase === 'idle' &&
      state.recentRollbackGeneration === original && isGeneration(recoveryGeneration) &&
      failedGeneration === null && compareGeneration(recoveryGeneration, original) > 0
    if (!valid) {
      markProtocolError(appId, requestId, '内部输出恢复 STARTED 转移不合法')
      return
    }
    state.phase = 'started'
    state.recoveryAttempt = 1
    state.originalFailedGeneration = original
    state.recoveryGeneration = recoveryGeneration
    state.rollbackAwaitingRecovery = false
    session.snapshot.internalOutputRecovery = 'recovering'
  } else if (payload.phase === 'RECOVERED') {
    const valid = state.phase === 'started' && state.originalFailedGeneration === original &&
      state.recoveryGeneration === recoveryGeneration && failedGeneration === null &&
      !state.rollbackAwaitingRecovery
    if (!valid) {
      markProtocolError(appId, requestId, '内部输出恢复 RECOVERED 转移不合法')
      return
    }
    state.phase = 'recovered'
    session.snapshot.internalOutputRecovery = 'idle'
  } else {
    const failedBeforeStart = state.recoveryAttempt === 0 && state.phase === 'idle' &&
      state.recentRollbackGeneration === original && recoveryGeneration === null &&
      failedGeneration === original && state.rollbackAwaitingRecovery
    const failedAfterStart = state.recoveryAttempt === 1 &&
      (state.phase === 'started' || state.phase === 'recovered') &&
      state.originalFailedGeneration === original && state.recoveryGeneration === recoveryGeneration &&
      (state.phase === 'started'
        ? failedGeneration === recoveryGeneration
        : failedGeneration === state.recentRollbackGeneration && state.rollbackAwaitingRecovery)
    if (!failedBeforeStart && !failedAfterStart) {
      markProtocolError(appId, requestId, '内部输出恢复 FAILED 转移不合法')
      return
    }
    state.phase = 'failed'
    state.rollbackAwaitingRecovery = false
    discardPendingFragments(session)
    session.snapshot.internalOutputRecovery = 'idle'
  }
  emit(appId, 'delta', requestId)
}

function handleContextCompression(appId: string, requestId: number, payload: JsonRecord): void {
  const phase = payload.phase
  const session = getActiveSession(appId, requestId)
  const validPhase = phase === 'STARTED' || phase === 'COMPLETED'
  const validTransition = session &&
    (phase === 'STARTED'
      ? session.snapshot.contextCompression === 'idle'
      : session.snapshot.contextCompression === 'compressing')
  if (
    !session || payload.protocol !== 'context-compression/v1' ||
    !hasExactFields(payload, ['protocol', 'sequence', 'phase', 'message']) || !validPhase ||
    payload.message !== CONTEXT_COMPRESSION_MESSAGES[phase] || !validTransition
  ) {
    markProtocolError(appId, requestId, '上下文压缩协议不合法')
    return
  }
  session.snapshot.contextCompression = phase === 'STARTED' ? 'compressing' : 'idle'
  emit(appId, 'delta', requestId)
}

function handleAuxiliaryRecovery(
  appId: string,
  requestId: number,
  payload: JsonRecord,
  kind: 'tool' | 'incomplete',
): void {
  const session = getActiveSession(appId, requestId)
  const messages = kind === 'tool'
    ? TOOL_PROTOCOL_RECOVERY_MESSAGES : INCOMPLETE_TOOL_CHAIN_RECOVERY_MESSAGES
  const protocol = kind === 'tool'
    ? 'tool-protocol-recovery/v1' : 'incomplete-tool-chain-recovery/v1'
  const phase = payload.phase
  const validPhase = phase === 'STARTED' || phase === 'RECOVERED' || phase === 'FAILED'
  const current = session
    ? kind === 'tool' ? session.toolProtocolRecoveryPhase : session.incompleteToolChainRecoveryPhase
    : 'idle'
  const validTransition =
    (phase === 'STARTED' && current === 'idle') ||
    (phase === 'RECOVERED' && current === 'recovering') ||
    (phase === 'FAILED' && (current === 'recovering' || current === 'recovered'))
  if (
    !session || payload.protocol !== protocol ||
    !hasExactFields(payload, ['protocol', 'sequence', 'phase', 'message']) || !validPhase ||
    payload.message !== messages[phase] || !validTransition
  ) {
    markProtocolError(appId, requestId, '工具恢复协议不合法')
    return
  }
  const nextPhase: AuxiliaryRecoveryPhase =
    phase === 'STARTED' ? 'recovering' : phase === 'RECOVERED' ? 'recovered' : 'failed'
  if (kind === 'tool') {
    session.toolProtocolRecoveryPhase = nextPhase
    session.snapshot.toolProtocolRecovery = phase === 'STARTED' ? 'recovering' : 'idle'
    if (phase === 'FAILED') session.snapshot.errorMessage = messages.FAILED
  } else {
    session.incompleteToolChainRecoveryPhase = nextPhase
    session.snapshot.incompleteToolChainRecovery = phase === 'STARTED' ? 'recovering' : 'idle'
  }
  emit(appId, 'delta', requestId)
}

function handleTurnOutcome(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  const outcome = typeof payload.outcome === 'string' ? OUTCOME_MAP[payload.outcome] : undefined
  const expectedProtocol = session?.expectVueTurnOutcome
    ? 'vue-turn/v1' : 'simple-turn/v1'
  if (
    !session || payload.protocol !== expectedProtocol ||
    !hasExactFields(payload, ['protocol', 'sequence', 'outcome', 'message', 'refreshPreview']) ||
    !outcome || !isNonEmptyString(payload.message) || typeof payload.refreshPreview !== 'boolean' ||
    session.snapshot.outcome !== 'pending' ||
    ((outcome === 'answered' || outcome === 'succeeded') &&
      !canAcceptSuccessfulOutcome(session)) ||
    (outcome === 'succeeded' ? payload.refreshPreview !== true : payload.refreshPreview !== false)
  ) {
    markProtocolError(appId, requestId, '生成业务终态协议不合法')
    return
  }
  session.snapshot.outcome = outcome
  session.awaitingDone = true
  session.awaitingDoneAfterTurnOutcome = true
  if (outcome !== 'succeeded' && outcome !== 'answered') {
    discardPendingFragments(session)
  }
  if (outcome !== 'succeeded' && session.toolProtocolRecoveryPhase !== 'failed') {
    session.snapshot.errorMessage = payload.message
  }
  emit(appId, 'delta', requestId)
}

function canAcceptSuccessfulOutcome(session: SessionState): boolean {
  if (!session.recovery.recentRollbackGeneration) return true
  return session.recovery.phase === 'recovered' &&
    !session.recovery.rollbackAwaitingRecovery
}

function handleBusinessError(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  if (
    !session || !hasExactFields(payload, ['protocol', 'kind', 'code', 'message']) ||
    payload.protocol !== 'generation-error/v1' ||
    (payload.kind !== 'BUSINESS' && payload.kind !== 'SYSTEM') ||
    !Number.isSafeInteger(payload.code) || Number(payload.code) <= 0 ||
    !isNonEmptyString(payload.message) ||
    session.businessErrorSeen || session.awaitingDone || session.snapshot.outcome !== 'pending'
  ) {
    markProtocolError(appId, requestId, '生成错误协议不合法')
    return
  }
  session.businessErrorSeen = true
  session.awaitingDone = true
  session.awaitingDoneAfterTurnOutcome = false
  discardPendingFragments(session)
  session.snapshot.outcome = 'system_error'
  session.snapshot.errorMessage = payload.message
}

function handleSseEvent(appId: string, requestId: number, event: string, data: string): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') return
  const eventName = event || 'message'
  if (session.doneSeen) {
    markProtocolError(appId, requestId, '生成结束事件后收到意外事件')
    return
  }
  if (
    session.awaitingDone &&
    eventName !== 'done' &&
    !(session.awaitingDoneAfterTurnOutcome && eventName === 'heartbeat')
  ) {
    markProtocolError(appId, requestId, '生成终态后收到意外事件')
    return
  }
  const payload = parseRecord(data)
  if (!payload) {
    markProtocolError(appId, requestId, '生成事件 JSON 不合法')
    return
  }
  if (eventName === 'heartbeat') {
    if (!hasExactFields(payload, ['timestamp']) || !Number.isSafeInteger(payload.timestamp)) {
      markProtocolError(appId, requestId, '生成心跳协议不合法')
    }
    return
  }
  if (eventName === 'business-error') {
    handleBusinessError(appId, requestId, payload)
    return
  }
  if (!acceptSequence(session, payload)) {
    markProtocolError(appId, requestId, '生成事件序号不连续')
    return
  }
  if (eventName === 'done') {
    if (!hasExactFields(payload, ['protocol', 'sequence']) || payload.protocol !== STREAM_PROTOCOL) {
      markProtocolError(appId, requestId, '生成结束协议不合法')
      return
    }
    session.doneSeen = true
    return
  }
  if (eventName === 'message') {
    handleMessage(appId, requestId, payload)
    return
  }
  if (!session.expectVueTurnOutcome && eventName !== 'context-compression' &&
    eventName !== 'simple-turn-outcome') {
    markProtocolError(appId, requestId, '普通生成流包含 Vue 专用事件')
    return
  }
  switch (eventName) {
    case 'trusted-tool-display':
      handleTrustedDisplay(appId, requestId, payload)
      break
    case 'internal-output-rollback':
      handleRollback(appId, requestId, payload)
      break
    case 'internal-output-recovery':
      handleInternalRecovery(appId, requestId, payload)
      break
    case 'context-compression':
      handleContextCompression(appId, requestId, payload)
      break
    case 'tool-protocol-recovery':
      handleAuxiliaryRecovery(appId, requestId, payload, 'tool')
      break
    case 'incomplete-tool-chain-recovery':
      handleAuxiliaryRecovery(appId, requestId, payload, 'incomplete')
      break
    case 'turn-outcome':
      handleTurnOutcome(appId, requestId, payload)
      break
    case 'simple-turn-outcome':
      handleTurnOutcome(appId, requestId, payload)
      break
    default:
      markProtocolError(appId, requestId, '生成流包含未知命名事件')
  }
}

function consumeSseBlock(block: string, onEvent: (event: string, data: string) => void): void {
  const text = block.replace(/^\uFEFF/, '')
  if (!text) return
  let event = 'message'
  let hasData = false
  const dataLines: string[] = []
  text.split('\n').forEach((rawLine) => {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (!line || line.startsWith(':')) return
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    const rawValue = separator < 0 ? '' : line.slice(separator + 1)
    const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue
    if (field === 'event') event = value || 'message'
    if (field === 'data') {
      hasData = true
      dataLines.push(value)
    }
  })
  if (hasData) onEvent(event, dataLines.join('\n'))
}

function consumeSseBuffer(
  buffer: string,
  onEvent: (event: string, data: string) => void,
  flush: boolean,
): string {
  let working = buffer
  let separator = /\r\n\r\n|\n\n|\r\r/.exec(working)
  while (separator?.index !== undefined) {
    consumeSseBlock(working.slice(0, separator.index), onEvent)
    working = working.slice(separator.index + separator[0].length)
    separator = /\r\n\r\n|\n\n|\r\r/.exec(working)
  }
  if (flush && working.trim()) {
    consumeSseBlock(working, onEvent)
    return ''
  }
  return working
}

function normalizeBaseURL(baseURL: string): string {
  const trimmed = baseURL.trim()
  if (/^https?:\/\//i.test(trimmed)) return trimmed.replace(/\/+$/, '')
  const normalizedPath = trimmed.startsWith('/') ? trimmed : `/${trimmed}`
  return `${window.location.origin}${normalizedPath}`.replace(/\/+$/, '')
}

function isEventStreamResponse(response: Response): boolean {
  const contentType = response.headers.get('Content-Type')
  return contentType?.split(';', 1)[0]?.trim().toLowerCase() === 'text/event-stream'
}

function decodeUtf8(decoder: TextDecoder, value?: Uint8Array, stream = false): string {
  try {
    return decoder.decode(value, { stream })
  } catch (error) {
    if (error instanceof TypeError) {
      throw new GenerationStreamError('生成流包含非法 UTF-8 数据', 'protocol_error')
    }
    throw error
  }
}

async function startSseStream(
  appId: string,
  requestId: number,
  userMessage: string,
  generationId: string,
  baseURL: string,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`${normalizeBaseURL(baseURL)}/app/chat/gen/code`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ appId, message: userMessage, generationId }),
    signal,
  })
  if (!response.ok) {
    throw new GenerationStreamError(
      response.status === 413 ? REQUEST_TOO_LARGE_MESSAGE : SERVICE_UNAVAILABLE_MESSAGE,
      'system_error',
    )
  }
  if (!isEventStreamResponse(response) || !response.body) {
    throw new GenerationStreamError(SERVICE_UNAVAILABLE_MESSAGE, 'system_error')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decodeUtf8(decoder, value, true)
    buffer = consumeSseBuffer(buffer, (event, data) => handleSseEvent(appId, requestId, event, data), false)
  }
  buffer += decodeUtf8(decoder)
  consumeSseBuffer(buffer, (event, data) => handleSseEvent(appId, requestId, event, data), true)
  const session = getActiveSession(appId, requestId)
  if (session?.snapshot.status === 'streaming') {
    if (session.doneSeen) markDone(appId, requestId)
    else markProtocolError(appId, requestId, '生成流意外结束')
  }
}

export function startGenerationSession(options: StartGenerationSessionOptions): void {
  if (typeof options.expectVueTurnOutcome !== 'boolean') {
    throw new Error('生成会话类型未确定')
  }
  if (!options.generationId) throw new Error('生成任务 ID 不能为空')
  const { appId, userMessage, baseURL, renderMode = 'throttled' } = options
  const throttleMs =
    typeof options.throttleMs === 'number' && options.throttleMs > 0
      ? options.throttleMs : DEFAULT_THROTTLE_MS
  const session = getOrCreateSession(appId)
  stopSessionStream(session)
  session.requestId += 1
  const requestId = session.requestId
  session.fragments = []
  session.generationId = options.generationId
  session.renderMode = renderMode
  session.throttleMs = throttleMs
  session.expectVueTurnOutcome = options.expectVueTurnOutcome
  session.lastSequence = 0
  session.businessErrorSeen = false
  session.awaitingDone = false
  session.awaitingDoneAfterTurnOutcome = false
  session.doneSeen = false
  session.recovery = createProtocolState()
  session.toolProtocolRecoveryPhase = 'idle'
  session.incompleteToolChainRecoveryPhase = 'idle'
  session.snapshot = {
    appId,
    content: '',
    loading: true,
    status: 'streaming',
    outcome: 'pending',
    contextCompression: 'idle',
    internalOutputRecovery: 'idle',
    toolProtocolRecovery: 'idle',
    incompleteToolChainRecovery: 'idle',
    toolCalls: new Map(),
  }
  emit(appId, 'delta', requestId)
  const controller = new AbortController()
  session.controller = controller
  void startSseStream(appId, requestId, userMessage, options.generationId,
    baseURL, controller.signal).catch((error: unknown) => {
    if (controller.signal.aborted) return
    const streamError = error instanceof GenerationStreamError
      ? error : new GenerationStreamError(SERVICE_UNAVAILABLE_MESSAGE, 'system_error')
    failSession(appId, requestId, streamError.message, streamError.outcome)
  })
}

export function getActiveGenerationId(appId: string): string | undefined {
  const session = sessions.get(appId)
  return session?.snapshot.status === 'streaming' && session.generationId
    ? session.generationId : undefined
}

export function subscribeGenerationSession(appId: string, listener: Listener): () => void {
  const session = getOrCreateSession(appId)
  session.listeners.add(listener)
  listener(cloneSnapshot(session.snapshot), 'delta')
  return () => sessions.get(appId)?.listeners.delete(listener)
}

export function getGenerationSessionSnapshot(appId: string): GenerationSessionSnapshot | undefined {
  const session = sessions.get(appId)
  return session ? cloneSnapshot(session.snapshot) : undefined
}

export function clearGenerationSession(appId: string): void {
  const session = sessions.get(appId)
  if (!session) return
  stopSessionStream(session)
  sessions.delete(appId)
}
