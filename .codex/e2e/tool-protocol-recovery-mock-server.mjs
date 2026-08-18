import http from 'node:http'

const HOST = '127.0.0.1'
const PORT = Number.parseInt(process.env.RECOVERY_E2E_PORT ?? '9027', 10)
const APP_ID = '447200000000000001'
const USER_ID = '447200000000000002'

const FIXED = {
  started: '正在校正工具调用，请稍候…',
  recovered: '工具调用已校正，继续生成…',
  failed: '工具调用格式异常，系统自动校正后仍未恢复。本轮没有执行相关工具，请重新发送请求。',
  compressionStarted: '正在压缩上下文，请稍候…',
  compressionCompleted: '上下文压缩完成，继续生成…',
}

const state = {
  scenario: 'normal',
  requestCount: 0,
  activeResponse: undefined,
  activeStep: 'idle',
  terminalCount: 0,
  previewRefreshEligible: false,
}

function cors(contentType = 'application/json; charset=UTF-8') {
  return {
    'Access-Control-Allow-Origin': 'http://127.0.0.1:5174',
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Headers': 'Content-Type, Accept',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Content-Type': contentType,
  }
}

function json(response, payload, status = 200) {
  response.writeHead(status, cors())
  response.end(JSON.stringify(payload))
}

function sse(response, event, data) {
  response.write(`event: ${event}\n`)
  response.write(`data: ${JSON.stringify(data)}\n\n`)
}

function recovery(response, phase) {
  const key = phase.toLowerCase()
  sse(response, 'tool-protocol-recovery', {
    protocol: 'tool-protocol-recovery/v1',
    phase,
    message: FIXED[key],
  })
}

function compression(response, phase) {
  sse(response, 'context-compression', {
    protocol: 'context-compression/v1',
    phase,
    message: phase === 'STARTED'
      ? FIXED.compressionStarted
      : FIXED.compressionCompleted,
  })
}

function message(response, payload) {
  sse(response, 'message', { d: JSON.stringify(payload) })
}

function toolSequence(response) {
  message(response, {
    type: 'tool_request', id: 'call-trusted-1', name: 'readDir',
    arguments: { relativeDirPath: 'src' },
  })
  message(response, {
    type: 'tool_executed', id: 'call-trusted-1', name: 'readDir',
    arguments: { relativeDirPath: 'src' },
    result: JSON.stringify({
      protocol: 'file-tool/v1', operation: 'readDir', status: 'SUCCEEDED',
      relativePath: 'src', changed: false, message: '目录读取成功',
      failureReason: null, content: 'App.vue',
    }),
  })
}

function terminal(response, outcome, text, refreshPreview) {
  if (text) {
    message(response, { type: 'ai_response', data: text })
  }
  sse(response, 'turn-outcome', {
    protocol: 'vue-turn/v1', outcome,
    message: outcome === 'SUCCEEDED' ? '生成成功' : FIXED.failed,
    refreshPreview,
  })
  response.write('event: done\ndata: done\n\n')
  response.end()
  state.terminalCount += 1
  state.previewRefreshEligible = refreshPreview
  state.activeResponse = undefined
  state.activeStep = 'idle'
}

function beginGeneration(response) {
  response.writeHead(200, {
    ...cors('text/event-stream; charset=UTF-8'),
    'Cache-Control': 'no-cache', Connection: 'keep-alive',
  })
  state.requestCount += 1
  state.activeResponse = response
  state.activeStep = 'started'
  if (state.scenario === 'normal') {
    message(response, { type: 'ai_response', data: '受控正常输出已经开始。' })
    toolSequence(response)
    terminal(response, 'SUCCEEDED', '受控正常生成完成。', true)
  } else if (state.scenario === 'recovery_success') {
    message(response, { type: 'ai_response', data: '临时伪工具前缀，应被 STARTED 清除。' })
    recovery(response, 'STARTED')
  } else if (state.scenario === 'recovery_failure') {
    message(response, { type: 'ai_response', data: '第二代前的污染正文，应被 STARTED 清除。' })
    recovery(response, 'STARTED')
  } else if (state.scenario === 'compression_overlap') {
    recovery(response, 'STARTED')
    compression(response, 'STARTED')
  } else if (state.scenario === 'trusted_checkpoint_recovered_failed') {
    message(response, { type: 'ai_response', data: '此前可信正文。' })
    toolSequence(response)
    message(response, { type: 'ai_response', data: '退化代临时正文，不应残留。' })
    recovery(response, 'STARTED')
  }
  response.on('close', () => {
    if (state.activeResponse === response) {
      state.activeResponse = undefined
      state.activeStep = 'idle'
    }
  })
}

function advance(response) {
  const stream = state.activeResponse
  if (!stream) {
    json(response, { code: 1, message: '没有活动生成流' }, 409)
    return
  }
  if (state.scenario === 'recovery_success' && state.activeStep === 'started') {
    recovery(stream, 'RECOVERED')
    toolSequence(stream)
    terminal(stream, 'SUCCEEDED', '校正后只保留可信输出。', true)
  } else if (state.scenario === 'recovery_failure' && state.activeStep === 'started') {
    recovery(stream, 'FAILED')
    terminal(stream, 'PROTOCOL_ERROR', '', false)
  } else if (state.scenario === 'compression_overlap' && state.activeStep === 'started') {
    compression(stream, 'COMPLETED')
    state.activeStep = 'compression-completed'
  } else if (state.scenario === 'compression_overlap'
      && state.activeStep === 'compression-completed') {
    recovery(stream, 'RECOVERED')
    message(stream, { type: 'ai_response', data: '重叠状态恢复后的可信正文。' })
    terminal(stream, 'SUCCEEDED', '', true)
  } else if (state.scenario === 'trusted_checkpoint_recovered_failed'
      && state.activeStep === 'started') {
    recovery(stream, 'RECOVERED')
    recovery(stream, 'FAILED')
    terminal(stream, 'PROTOCOL_ERROR', '', false)
  } else {
    json(response, { code: 1, message: '场景阶段不允许推进' }, 409)
    return
  }
  json(response, { code: 0, data: state.activeStep })
}

async function body(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host}`)
  if (request.method === 'OPTIONS') {
    response.writeHead(204, cors()); response.end(); return
  }
  if (request.method === 'GET' && url.pathname === '/health') {
    json(response, { status: 'UP' }); return
  }
  if (request.method === 'GET' && url.pathname === '/__e2e/state') {
    json(response, { ...state, activeResponse: Boolean(state.activeResponse) }); return
  }
  if (request.method === 'POST' && url.pathname === '/__e2e/control') {
    const data = await body(request)
    if (![
      'normal', 'recovery_success', 'recovery_failure', 'compression_overlap',
      'trusted_checkpoint_recovered_failed',
    ]
      .includes(data.scenario)) {
      json(response, { code: 1, message: '未知场景' }, 400); return
    }
    state.scenario = data.scenario
    state.requestCount = 0
    state.terminalCount = 0
    state.previewRefreshEligible = false
    json(response, { code: 0, data: state.scenario }); return
  }
  if (request.method === 'POST' && url.pathname === '/__e2e/advance') {
    request.resume(); advance(response); return
  }
  if (request.method === 'GET' && url.pathname === '/api/user/get/login') {
    json(response, { code: 0, data: {
      id: USER_ID, userName: '工具协议恢复验收用户', userRole: 'admin',
    } }); return
  }
  if (request.method === 'GET' && url.pathname === '/api/app/get/vo') {
    json(response, { code: 0, data: {
      id: APP_ID, appName: '工具协议恢复 Chrome E2E',
      codeGenType: 'vue_project', userId: USER_ID, initPrompt: '',
    } }); return
  }
  if (request.method === 'GET'
      && url.pathname === `/api/chatHistory/app/${APP_ID}`) {
    json(response, { code: 0, data: { records: [], totalRow: 0 } }); return
  }
  if (request.method === 'POST' && url.pathname === '/api/app/chat/gen/code') {
    request.resume(); beginGeneration(response); return
  }
  if (request.method === 'GET' && url.pathname.startsWith('/api/static/')) {
    response.writeHead(200, cors('text/html; charset=UTF-8'))
    response.end('<!doctype html><html lang="zh-CN"><body><h1>受控可信预览</h1></body></html>')
    return
  }
  json(response, { code: 0, data: null })
})

server.listen(PORT, HOST, () => {
  process.stdout.write(`tool protocol recovery E2E server: http://${HOST}:${PORT}\n`)
})

function close() { server.close(() => process.exit(0)) }
process.on('SIGINT', close)
process.on('SIGTERM', close)
