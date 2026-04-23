export type SessionEventType = 'delta' | 'done' | 'error' | 'business-error'

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
}

export type GenerationStatus = 'streaming' | 'done' | 'error'

export interface GenerationSessionSnapshot {
  appId: string
  content: string
  loading: boolean
  status: GenerationStatus
  errorMessage?: string
  toolCalls: Map<string, ToolCallView>
}

export interface StartGenerationSessionOptions {
  appId: string
  userMessage: string
  baseURL: string
  renderMode?: 'direct' | 'throttled'
  throttleMs?: number
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
}

type JsonRecord = Record<string, unknown>

const DEFAULT_THROTTLE_MS = 100
const sessions = new Map<string, SessionState>()

function createEmptySnapshot(appId: string): GenerationSessionSnapshot {
  return {
    appId,
    content: '',
    loading: false,
    status: 'done',
    toolCalls: new Map(),
  }
}

function cloneSnapshot(snapshot: GenerationSessionSnapshot): GenerationSessionSnapshot {
  const toolCalls = new Map<string, ToolCallView>()
  snapshot.toolCalls.forEach((value, key) => {
    toolCalls.set(key, {
      ...value,
      args: { ...value.args },
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
): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  flushBuffer(appId, requestId)
  session.snapshot.loading = false
  session.snapshot.status = nextStatus
  session.snapshot.errorMessage = errorMessage
  stopSessionStream(session)
  emit(appId, eventType, requestId)
}

function markDone(appId: string, requestId: number): void {
  const session = getActiveSession(appId, requestId)
  if (!session) {
    return
  }
  flushBuffer(appId, requestId)
  session.snapshot.loading = false
  if (session.snapshot.status === 'streaming') {
    session.snapshot.status = 'done'
    session.snapshot.errorMessage = undefined
  }
  stopSessionStream(session)
  emit(appId, 'done', requestId)
}

function readErrorMessage(data: unknown): string {
  if (!data || typeof data !== 'object') {
    return '生成失败'
  }
  const record = data as JsonRecord
  if (typeof record.message === 'string' && record.message.trim()) {
    return record.message
  }
  return '生成失败'
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
    const normalized = toStringValue(value)
    view.args[key] = normalized ?? value
  })
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
  if (key && value !== undefined) {
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
  if (key) {
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
  view.status = 'done'
  emit(appId, 'delta', requestId)
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
  if (outer && outer.error === true) {
    const errorMessage = readErrorMessage(outer)
    finishSession(appId, requestId, 'error', 'business-error', errorMessage)
    return
  }
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

function handleSseEvent(appId: string, requestId: number, event: string, data: string): void {
  const eventName = event || 'message'
  if (eventName === 'done') {
    markDone(appId, requestId)
    return
  }
  if (eventName === 'business-error') {
    const payload = tryParseJson(data)
    const errorMessage = readErrorMessage(payload)
    finishSession(appId, requestId, 'error', 'business-error', errorMessage)
    return
  }
  if (eventName === 'error') {
    const payload = tryParseJson(data)
    const errorMessage = readErrorMessage(payload)
    finishSession(appId, requestId, 'error', 'error', errorMessage)
    return
  }
  handleMessageData(appId, requestId, data)
}

function consumeSseBlock(block: string, onEvent: (event: string, data: string) => void): void {
  const text = block.trim()
  if (!text) {
    return
  }
  let event = 'message'
  let hasDataLine = false
  const dataLines: string[] = []
  text.split('\n').forEach((rawLine) => {
    const line = rawLine.trimEnd()
    if (!line || line.startsWith(':')) {
      return
    }
    if (line.startsWith('event:')) {
      event = line.slice(6).trim() || 'message'
      return
    }
    if (line.startsWith('data:')) {
      hasDataLine = true
      dataLines.push(line.slice(5).trimStart())
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
  let splitIndex = working.indexOf('\n\n')
  while (splitIndex >= 0) {
    const block = working.slice(0, splitIndex)
    working = working.slice(splitIndex + 2)
    consumeSseBlock(block, onEvent)
    splitIndex = working.indexOf('\n\n')
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

function buildSseUrl(baseURL: string, appId: string, userMessage: string): string {
  const endpoint = `${normalizeBaseURL(baseURL)}/app/chat/gen/code`
  const url = new URL(endpoint)
  url.searchParams.set('appId', appId)
  url.searchParams.set('message', userMessage)
  return url.toString()
}

async function startSseStream(
  appId: string,
  requestId: number,
  userMessage: string,
  baseURL: string,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(buildSseUrl(baseURL, appId, userMessage), {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'text/event-stream',
    },
    signal,
  })
  if (!response.ok || !response.body) {
    throw new Error(`请求失败: ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '')
    buffer = consumeSseBuffer(buffer, (event, data) => handleSseEvent(appId, requestId, event, data), false)
  }
  buffer += decoder.decode().replace(/\r/g, '')
  consumeSseBuffer(buffer, (event, data) => handleSseEvent(appId, requestId, event, data), true)

  const session = getActiveSession(appId, requestId)
  if (session && session.snapshot.status === 'streaming') {
    markDone(appId, requestId)
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
  session.snapshot = {
    appId,
    content: '',
    loading: true,
    status: 'streaming',
    toolCalls: new Map(),
  }
  emit(appId, 'delta', requestId)

  const controller = new AbortController()
  session.controller = controller
  void startSseStream(appId, requestId, userMessage, baseURL, controller.signal).catch((error: unknown) => {
    if (controller.signal.aborted) {
      return
    }
    const message = error instanceof Error ? error.message : '生成失败'
    finishSession(appId, requestId, 'error', 'error', message || '生成失败')
  })
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
