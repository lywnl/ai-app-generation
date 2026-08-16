import { parseBuildProjectToolResult, type BuildProjectToolView } from './buildProjectToolResult'

export type SessionEventType = 'delta' | 'done' | 'error'

export type ToolCallStatus = 'streaming' | 'done' | 'error'

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
  if (view.status === 'streaming') {
    return 'streaming'
  }
  return view.build ? 'parsed' : 'unrecognized'
}

export function getBuildProjectVisualState(
  view: Pick<ToolCallView, 'status' | 'build'>,
): BuildProjectVisualState {
  if (view.status === 'streaming') {
    return 'streaming'
  }
  if (!view.build) {
    return 'unrecognized'
  }
  if (view.build.invocationStatus === 'CANCELLED') {
    return 'cancelled'
  }
  if (view.build.invocationStatus !== 'COMPLETED') {
    return 'neutral'
  }
  if (view.build.success === true) {
    return 'success'
  }
  return view.build.success === false ? 'failed' : 'unrecognized'
}

export type GenerationStatus = 'streaming' | 'done' | 'error'

export type ContextCompressionState = 'idle' | 'compressing'

export type GenerationOutcome =
  | 'pending'
  | 'succeeded'
  | 'failed'
  | 'cancelled'
  | 'timed_out'
  | 'system_error'
  | 'protocol_error'

export interface GenerationSessionSnapshot {
  appId: string
  content: string
  loading: boolean
  status: GenerationStatus
  outcome: GenerationOutcome
  contextCompression: ContextCompressionState
  errorMessage?: string
  toolCalls: Map<string, ToolCallView>
}

export function shouldRefreshGenerationPreview(
  snapshot: Pick<GenerationSessionSnapshot, 'status' | 'outcome'>,
): boolean {
  return snapshot.status === 'done' && snapshot.outcome === 'succeeded'
}

export interface StartGenerationSessionOptions {
  appId: string
  userMessage: string
  baseURL: string
  renderMode?: 'direct' | 'throttled'
  throttleMs?: number
  expectVueTurnOutcome?: boolean
}

type Listener = (snapshot: GenerationSessionSnapshot, eventType: SessionEventType) => void

interface SessionState {
  snapshot: GenerationSessionSnapshot
  listeners: Set<Listener>
  controller?: AbortController
  flushTimer?: ReturnType<typeof setTimeout>
  buffer: string
  renderMode: 'direct' | 'throttled'
  throttleMs: number
  requestId: number
  expectVueTurnOutcome: boolean
  businessErrorSeen: boolean
  semanticEventSeen: boolean
  awaitingDone: boolean
  doneSeen: boolean
}

type JsonRecord = Record<string, unknown>

const DEFAULT_THROTTLE_MS = 100
const SERVICE_UNAVAILABLE_MESSAGE = '生成服务暂时不可用，请稍后重试。'
const REQUEST_TOO_LARGE_MESSAGE = '请求内容过大，请缩短需求后重试。'
const sessions = new Map<string, SessionState>()

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
  return {
    ...snapshot,
    toolCalls,
  }
}

function getOrCreateSession(appId: string): SessionState {
  const existing = sessions.get(appId)
  if (existing) {
    return existing
  }
  const created: SessionState = {
    snapshot: createEmptySnapshot(appId),
    listeners: new Set(),
    buffer: '',
    renderMode: 'throttled',
    throttleMs: DEFAULT_THROTTLE_MS,
    requestId: 0,
    expectVueTurnOutcome: false,
    businessErrorSeen: false,
    semanticEventSeen: false,
    awaitingDone: false,
    doneSeen: false,
  }
  sessions.set(appId, created)
  return created
}

function getActiveSession(appId: string, requestId: number): SessionState | undefined {
  const session = sessions.get(appId)
  if (!session || session.requestId !== requestId) {
    return undefined
  }
  return session
}

function emit(appId: string, eventType: SessionEventType, requestId?: number): void {
  const session = sessions.get(appId)
  if (!session) {
    return
  }
  if (requestId !== undefined && session.requestId !== requestId) {
    return
  }
  const snapshot = cloneSnapshot(session.snapshot)
  session.listeners.forEach((listener) => listener(snapshot, eventType))
}

function stopSessionStream(session: SessionState): void {
  if (session.controller) {
    session.controller.abort()
    session.controller = undefined
  }
  if (session.flushTimer) {
    clearTimeout(session.flushTimer)
    session.flushTimer = undefined
  }
  session.buffer = ''
}

function flushBuffer(appId: string, requestId: number): void {
  const session = getActiveSession(appId, requestId)
  if (!session || !session.buffer) {
    return
  }
  session.snapshot.content += session.buffer
  session.buffer = ''
  emit(appId, 'delta', requestId)
}

function queueDelta(appId: string, requestId: number, chunk: string): void {
  if (!chunk) {
    return
  }
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  if (session.renderMode === 'direct') {
    session.snapshot.content += chunk
    emit(appId, 'delta', requestId)
    return
  }
  session.buffer += chunk
  if (!session.flushTimer) {
    session.flushTimer = setTimeout(() => {
      const active = getActiveSession(appId, requestId)
      if (!active) {
        return
      }
      active.flushTimer = undefined
      flushBuffer(appId, requestId)
    }, session.throttleMs)
  }
}

function finishSession(
  appId: string,
  requestId: number,
  nextStatus: GenerationStatus,
  eventType: SessionEventType,
  errorMessage?: string,
  outcome: GenerationOutcome = 'system_error',
): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  flushBuffer(appId, requestId)
  session.snapshot.loading = false
  session.snapshot.status = nextStatus
  session.snapshot.contextCompression = 'idle'
  session.snapshot.errorMessage = errorMessage
  session.snapshot.outcome = outcome
  stopSessionStream(session)
  emit(appId, eventType, requestId)
}

function markDone(appId: string, requestId: number): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') {
    return
  }
  flushBuffer(appId, requestId)
  if (
    session.expectVueTurnOutcome &&
    session.snapshot.outcome === 'pending' &&
    !session.businessErrorSeen
  ) {
    finishSession(appId, requestId, 'error', 'error', '生成协议缺少业务终态', 'protocol_error')
    return
  }
  session.snapshot.loading = false
  session.snapshot.contextCompression = 'idle'
  if (session.snapshot.status === 'streaming') {
    session.snapshot.status = 'done'
    if (!session.expectVueTurnOutcome && session.snapshot.outcome === 'pending') {
      session.snapshot.outcome = 'succeeded'
    }
    if (session.snapshot.outcome === 'succeeded') {
      session.snapshot.errorMessage = undefined
    }
  }
  stopSessionStream(session)
  emit(appId, 'done', requestId)
}

function toStringValue(value: unknown): string | undefined {
  if (typeof value === 'string') {
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  if (value && typeof value === 'object') {
    return JSON.stringify(value)
  }
  return undefined
}

function tryParseJson(value: string): JsonRecord | undefined {
  if (!value) {
    return undefined
  }
  try {
    const parsed = JSON.parse(value) as unknown
    if (parsed && typeof parsed === 'object') {
      return parsed as JsonRecord
    }
    return undefined
  } catch {
    return undefined
  }
}

function parseArgumentObject(input: unknown): JsonRecord | undefined {
  if (typeof input === 'string') {
    return tryParseJson(input)
  }
  if (input && typeof input === 'object') {
    return input as JsonRecord
  }
  return undefined
}

function ensureToolCall(session: SessionState, id: string, name?: string): ToolCallView {
  const toolId = id || `tool-${session.snapshot.toolCalls.size + 1}`
  let view = session.snapshot.toolCalls.get(toolId)
  if (!view) {
    view = {
      id: toolId,
      name: name || 'tool',
      status: 'streaming',
      args: {},
    }
    session.snapshot.toolCalls.set(toolId, view)
  }
  if (name) {
    view.name = name
  }
  return view
}

function mergeToolArguments(view: ToolCallView, args?: JsonRecord): void {
  if (!args) {
    return
  }
  Object.entries(args).forEach(([key, value]) => {
    if (!isVisibleToolArgument(view.name, key)) {
      return
    }
    const normalized = toStringValue(value)
    view.args[key] = normalized ?? value
  })
}

function isVisibleToolArgument(toolName: string, key: string): boolean {
  if (toolName === 'readFile') {
    return key === 'relativeFilePath'
  }
  if (toolName === 'readDir') {
    return key === 'relativeDirPath'
  }
  return true
}

function handleToolRequest(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  const id = toStringValue(payload.id) || ''
  const name = toStringValue(payload.name)
  const view = ensureToolCall(session, id, name)
  view.status = 'streaming'
  mergeToolArguments(view, parseArgumentObject(payload.arguments))
  emit(appId, 'delta', requestId)
}

function handleToolArgument(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  const id = toStringValue(payload.id) || ''
  const name = toStringValue(payload.name)
  const key = toStringValue(payload.key)
  const value = toStringValue(payload.value)
  const view = ensureToolCall(session, id, name)
  view.status = 'streaming'
  if (key && value !== undefined && isVisibleToolArgument(view.name, key)) {
    view.args[key] = value
  }
  emit(appId, 'delta', requestId)
}

function handleToolArgumentDelta(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  const id = toStringValue(payload.id) || ''
  const name = toStringValue(payload.name)
  const key = toStringValue(payload.key)
  const delta = toStringValue(payload.delta) || ''
  const view = ensureToolCall(session, id, name)
  view.status = 'streaming'
  if (key && isVisibleToolArgument(view.name, key)) {
    const oldValue = typeof view.args[key] === 'string' ? (view.args[key] as string) : ''
    view.args[key] = oldValue + delta
  }
  emit(appId, 'delta', requestId)
}

function handleToolExecuted(appId: string, requestId: number, payload: JsonRecord): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  const id = toStringValue(payload.id) || ''
  const name = toStringValue(payload.name)
  const view = ensureToolCall(session, id, name)
  mergeToolArguments(view, parseArgumentObject(payload.arguments))
  const result = sanitizeToolResult(view.name, toStringValue(payload.result))
  if (result !== undefined) {
    view.result = result
    if (view.name === 'buildProject') {
      view.build = parseBuildProjectToolResult(result)
    }
  }
  view.status = 'done'
  emit(appId, 'delta', requestId)
}

function sanitizeToolResult(toolName: string, result: string | undefined): string | undefined {
  if (!result || (toolName !== 'readFile' && toolName !== 'readDir')) {
    return result
  }
  const parsed = tryParseJson(result)
  if (
    !parsed ||
    parsed.protocol !== 'file-tool/v1' ||
    parsed.operation !== toolName ||
    typeof parsed.status !== 'string' ||
    (parsed.relativePath !== null && typeof parsed.relativePath !== 'string') ||
    parsed.changed !== false ||
    typeof parsed.message !== 'string' ||
    !parsed.message.trim() ||
    (parsed.failureReason !== null && typeof parsed.failureReason !== 'string')
  ) {
    return undefined
  }
  const safe = {
    protocol: parsed.protocol,
    operation: parsed.operation,
    status: parsed.status,
    relativePath: parsed.relativePath,
    changed: parsed.changed,
    message: parsed.message,
    failureReason: parsed.failureReason,
    content: null,
  }
  return JSON.stringify(safe)
}

function handleTypedMessage(appId: string, requestId: number, payload: JsonRecord): void {
  const type = (toStringValue(payload.type) || '').toLowerCase()
  switch (type) {
    case 'ai_response': {
      const data = toStringValue(payload.data)
      if (data) {
        queueDelta(appId, requestId, data)
      }
      return
    }
    case 'tool_request':
      handleToolRequest(appId, requestId, payload)
      return
    case 'tool_argument':
      handleToolArgument(appId, requestId, payload)
      return
    case 'tool_argument_delta':
      handleToolArgumentDelta(appId, requestId, payload)
      return
    case 'tool_executed':
      handleToolExecuted(appId, requestId, payload)
      return
    default:
      return
  }
}

function handleMessageData(appId: string, requestId: number, data: string): void {
  if (!data) {
    return
  }
  const outer = tryParseJson(data)
  const wrapped = outer && typeof outer.d === 'string' ? outer.d : data
  if (!wrapped) {
    return
  }
  const inner = tryParseJson(wrapped)
  if (inner && typeof inner.type === 'string') {
    handleTypedMessage(appId, requestId, inner)
    return
  }
  queueDelta(appId, requestId, wrapped)
}

const OUTCOME_MAP: Record<string, Exclude<GenerationOutcome, 'pending'>> = {
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  TIMED_OUT: 'timed_out',
  SYSTEM_ERROR: 'system_error',
  PROTOCOL_ERROR: 'protocol_error',
}

function markProtocolError(appId: string, requestId: number, message: string): void {
  finishSession(appId, requestId, 'error', 'error', message, 'protocol_error')
}

const CONTEXT_COMPRESSION_MESSAGES = {
  STARTED: '正在压缩上下文，请稍候…',
  COMPLETED: '上下文压缩完成，继续生成…',
} as const

function handleContextCompression(appId: string, requestId: number, data: string): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') {
    return
  }
  const payload = tryParseJson(data)
  const phase = payload?.phase
  const validPhase = phase === 'STARTED' || phase === 'COMPLETED'
  const expectedMessage = validPhase ? CONTEXT_COMPRESSION_MESSAGES[phase] : undefined
  const validTransition =
    phase === 'STARTED'
      ? session.snapshot.contextCompression === 'idle'
      : phase === 'COMPLETED' && session.snapshot.contextCompression === 'compressing'
  if (
    payload?.protocol !== 'context-compression/v1' ||
    !validPhase ||
    payload.message !== expectedMessage ||
    !validTransition
  ) {
    markProtocolError(appId, requestId, '上下文压缩协议不合法')
    return
  }
  session.snapshot.contextCompression = phase === 'STARTED' ? 'compressing' : 'idle'
  emit(appId, 'delta', requestId)
}

function parseBusinessError(data: string): string | undefined {
  const payload = tryParseJson(data)
  if (
    payload?.protocol !== 'generation-error/v1' ||
    (payload.kind !== 'BUSINESS' && payload.kind !== 'SYSTEM') ||
    !Number.isSafeInteger(payload.code) ||
    typeof payload.message !== 'string' ||
    !payload.message.trim()
  ) {
    return undefined
  }
  return payload.message
}

function handleBusinessError(appId: string, requestId: number, data: string): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') {
    return
  }
  const errorMessage = parseBusinessError(data)
  if (!errorMessage) {
    markProtocolError(appId, requestId, '生成前置错误协议不合法')
    return
  }
  if (
    session.semanticEventSeen ||
    session.snapshot.outcome !== 'pending' ||
    session.businessErrorSeen
  ) {
    markProtocolError(appId, requestId, '生成协议包含冲突的前置错误')
    return
  }
  session.businessErrorSeen = true
  session.awaitingDone = true
  session.snapshot.outcome = 'system_error'
  session.snapshot.errorMessage = errorMessage
}

function handleTurnOutcome(appId: string, requestId: number, data: string): void {
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') {
    return
  }
  session.semanticEventSeen = true
  const payload = tryParseJson(data)
  const wireOutcome = typeof payload?.outcome === 'string' ? payload.outcome : undefined
  const outcome = wireOutcome ? OUTCOME_MAP[wireOutcome] : undefined
  const message = typeof payload?.message === 'string' ? payload.message : undefined
  const refreshPreview = payload?.refreshPreview
  if (
    session.businessErrorSeen ||
    session.snapshot.outcome !== 'pending' ||
    payload?.protocol !== 'vue-turn/v1' ||
    !outcome ||
    !message?.trim() ||
    typeof refreshPreview !== 'boolean' ||
    (outcome === 'succeeded' ? refreshPreview !== true : refreshPreview !== false)
  ) {
    markProtocolError(appId, requestId, '生成业务终态协议不合法')
    return
  }
  session.snapshot.outcome = outcome
  session.awaitingDone = true
  if (outcome !== 'succeeded') {
    session.snapshot.errorMessage = message
  }
  emit(appId, 'delta', requestId)
}

function handleSseEvent(appId: string, requestId: number, event: string, data: string): void {
  const eventName = event || 'message'
  const session = getActiveSession(appId, requestId)
  if (!session || session.snapshot.status !== 'streaming') {
    return
  }
  if (session.doneSeen) {
    markProtocolError(appId, requestId, '生成结束事件后收到意外事件')
    return
  }
  if (eventName === 'done') {
    session.doneSeen = true
    return
  }
  if (session.awaitingDone) {
    markProtocolError(appId, requestId, '生成业务终态后收到意外事件')
    return
  }
  if (eventName === 'heartbeat') {
    return
  }
  if (eventName === 'context-compression') {
    handleContextCompression(appId, requestId, data)
    return
  }
  if (eventName === 'turn-outcome') {
    handleTurnOutcome(appId, requestId, data)
    return
  }
  if (eventName === 'business-error') {
    handleBusinessError(appId, requestId, data)
    return
  }
  if (eventName !== 'message') {
    session.semanticEventSeen = true
    markProtocolError(appId, requestId, '生成流包含未知命名事件')
    return
  }
  session.semanticEventSeen = true
  handleMessageData(appId, requestId, data)
}

function consumeSseBlock(block: string, onEvent: (event: string, data: string) => void): void {
  const text = block.replace(/^\uFEFF/, '')
  if (!text) {
    return
  }
  let event = 'message'
  let hasDataLine = false
  const dataLines: string[] = []
  text.split('\n').forEach((rawLine) => {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (!line || line.startsWith(':')) {
      return
    }
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    const rawValue = separator < 0 ? '' : line.slice(separator + 1)
    const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue
    if (field === 'event') {
      event = value || 'message'
      return
    }
    if (field === 'data') {
      hasDataLine = true
      dataLines.push(value)
    }
  })
  if (!hasDataLine && event === 'message') {
    return
  }
  onEvent(event, dataLines.join('\n'))
}

function consumeSseBuffer(
  buffer: string,
  onEvent: (event: string, data: string) => void,
  flush: boolean,
): string {
  let working = buffer
  let separator = /\r\n\r\n|\n\n|\r\r/.exec(working)
  while (separator?.index !== undefined) {
    const block = working.slice(0, separator.index)
    working = working.slice(separator.index + separator[0].length)
    consumeSseBlock(block, onEvent)
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
  if (/^https?:\/\//i.test(trimmed)) {
    return trimmed.replace(/\/+$/, '')
  }
  const normalizedPath = trimmed.startsWith('/') ? trimmed : `/${trimmed}`
  return `${window.location.origin}${normalizedPath}`.replace(/\/+$/, '')
}

function buildGenerationUrl(baseURL: string): string {
  return `${normalizeBaseURL(baseURL)}/app/chat/gen/code`
}

function isEventStreamResponse(response: Response): boolean {
  const contentType = response.headers.get('Content-Type')
  if (!contentType) {
    return false
  }
  return contentType.split(';', 1)[0]?.trim().toLowerCase() === 'text/event-stream'
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
  baseURL: string,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(buildGenerationUrl(baseURL), {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json; charset=UTF-8',
    },
    body: JSON.stringify({ appId, message: userMessage }),
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
    if (done) {
      break
    }
    buffer += decodeUtf8(decoder, value, true)
    buffer = consumeSseBuffer(
      buffer,
      (event, data) => handleSseEvent(appId, requestId, event, data),
      false,
    )
  }
  buffer += decodeUtf8(decoder)
  consumeSseBuffer(buffer, (event, data) => handleSseEvent(appId, requestId, event, data), true)

  const session = getActiveSession(appId, requestId)
  if (session && session.snapshot.status === 'streaming') {
    if (session.doneSeen) {
      markDone(appId, requestId)
    } else {
      markProtocolError(appId, requestId, '生成流意外结束')
    }
  }
}

export function startGenerationSession(options: StartGenerationSessionOptions): void {
  const { appId, userMessage, baseURL, renderMode = 'throttled' } = options
  const throttleMs =
    typeof options.throttleMs === 'number' && options.throttleMs > 0
      ? options.throttleMs
      : DEFAULT_THROTTLE_MS

  const session = getOrCreateSession(appId)
  stopSessionStream(session)
  session.requestId += 1
  const requestId = session.requestId
  session.renderMode = renderMode
  session.throttleMs = throttleMs
  session.expectVueTurnOutcome = options.expectVueTurnOutcome === true
  session.businessErrorSeen = false
  session.semanticEventSeen = false
  session.awaitingDone = false
  session.doneSeen = false
  session.snapshot = {
    appId,
    content: '',
    loading: true,
    status: 'streaming',
    outcome: 'pending',
    contextCompression: 'idle',
    toolCalls: new Map(),
  }
  emit(appId, 'delta', requestId)

  const controller = new AbortController()
  session.controller = controller
  void startSseStream(appId, requestId, userMessage, baseURL, controller.signal).catch(
    (error: unknown) => {
      if (controller.signal.aborted) {
        return
      }
      const streamError =
        error instanceof GenerationStreamError
          ? error
          : new GenerationStreamError(SERVICE_UNAVAILABLE_MESSAGE, 'system_error')
      finishSession(appId, requestId, 'error', 'error', streamError.message, streamError.outcome)
    },
  )
}

export function subscribeGenerationSession(appId: string, listener: Listener): () => void {
  const session = getOrCreateSession(appId)
  session.listeners.add(listener)
  listener(cloneSnapshot(session.snapshot), 'delta')

  return () => {
    const current = sessions.get(appId)
    if (!current) {
      return
    }
    current.listeners.delete(listener)
  }
}

export function getGenerationSessionSnapshot(appId: string): GenerationSessionSnapshot | undefined {
  const session = sessions.get(appId)
  if (!session) {
    return undefined
  }
  return cloneSnapshot(session.snapshot)
}

export function clearGenerationSession(appId: string): void {
  const session = sessions.get(appId)
  if (!session) {
    return
  }
  stopSessionStream(session)
  sessions.delete(appId)
}
