/** 本机浏览器验收夹具：运行真实构建页面，API、身份、发布页面和 SSE 均为合成数据。 */
import { createServer } from 'node:http'
import { appendFileSync, createReadStream, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs'
import { resolve, extname, sep } from 'node:path'
import { parseArgs } from 'node:util'

const { values } = parseArgs({ options: { dist: { type: 'string' }, evidence: { type: 'string' } } })
if (!values.dist || !values.evidence) throw new Error('必须指定 --dist 和 --evidence，且输出位于项目 .codex 下')
const dist = resolve(values.dist)
const evidence = resolve(values.evidence)
if (!existsSync(resolve(dist, 'index.html'))) throw new Error('前端构建不存在')
mkdirSync(evidence, { recursive: true })

const definitions = {
  '7101': { name: '已部署的旅行应用', count: 14 },
  '7102': { name: '尚未部署的精选应用', deployed: false, priority: 99 },
  '7103': { name: '历史为空的已部署应用', count: 0 },
  '7104': { name: '历史失败后可重试的应用', failHistory: true },
  '7105': { name: '预览失败后可重试的应用', failPreview: true },
  '7106': { name: '作者工作区', owner: true },
  '7107': { name: 'HTML 已部署应用', type: 'html' },
  '7108': { name: '多文件已部署应用', type: 'multi_file' },
  '7111': { name: '迟到历史响应的应用', holdHistory: true },
  '7112': { name: '已部署但未精选的应用', priority: 0 },
  '7113': { name: '未精选且未部署的应用', priority: 0, deployed: false },
}
const historyRequests = new Map()
const previewRequests = new Map()
const heldHistory = []
const requests = []
let origin

const json = (res, data, code = 0, message = 'ok') => {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify({ code, data, message }))
}
const html = (res, text, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(text)
}
const identity = (req) => req.headers.cookie?.match(/(?:^|;\s*)fixtureUser=(100|200|300|none)(?:;|$)/)?.[1] ?? '200'
const loginUser = (id) => ({ id, userName: id === '100' ? '演示作者' : id === '300' ? '演示管理员' : '演示访客', userRole: id === '300' ? 'admin' : 'user' })
const appInfo = (id) => {
  const definition = definitions[id]
  if (!definition) return null
  return {
    id, userId: '100', appName: definition.name, codeGenType: definition.type ?? 'vue_project',
    initPrompt: '创建一个用于只读查看验收的合成应用', priority: definition.priority ?? 99,
    ...(definition.deployed !== false ? { deployKey: `fixture-${id}`, deployedTime: '2026-11-03T10:00:00', deployUrl: `${origin}/published/${id}/` } : {}),
    user: loginUser('100'), createTime: '2026-11-03T09:00:00',
  }
}
const history = (id, cursor) => {
  const count = definitions[id]?.count ?? 2
  return Array.from({ length: count }, (_, i) => ({
    id: `${id}${String(i + 1).padStart(2, '0')}`, appId: id, userId: '100',
    messageType: i % 2 ? 'ai' : 'user',
    message: i % 2 ? `历史消息 ${i + 1}：已完成页面调整，构建成功。` : `历史消息 ${i + 1}：请创建并完善这个应用。`,
    createTime: `2026-11-03T09:${String(i).padStart(2, '0')}:00`,
  })).reverse().filter((item) => !cursor || item.createTime < cursor).slice(0, 10)
}
const readBody = async (req) => {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
}
const previewHtml = (id, published) => `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${published ? '已部署页面' : '作者草稿'}</title><style>body{font-family:system-ui;padding:36px;background:#eef6f2;color:#183e31}button{padding:10px 20px}</style></head><body><h1>${published ? '已部署页面' : '作者草稿'} · ${id}</h1><p>这是隔离的合成页面，可正常浏览和交互。</p><button onclick="document.querySelector('#result').textContent='页面交互正常'">体验页面</button><p id="result"></p></body></html>`

const server = createServer(async (req, res) => {
  const url = new URL(req.url, origin ?? 'http://127.0.0.1')
  const path = url.pathname
  const user = identity(req)
  const entry = { at: new Date().toISOString(), method: req.method, path, query: url.search, user }
  requests.push(entry)
  res.once('finish', () => appendFileSync(resolve(evidence, 'requests.jsonl'), JSON.stringify({ ...entry, status: res.statusCode }) + '\n'))
  try {
    if (path === '/__fixture/start') {
      const requestedUser = url.searchParams.get('user') ?? '200'
      const id = url.searchParams.get('app') ?? '7101'
      if (!['100', '200', '300', 'none'].includes(requestedUser) || !definitions[id]) return json(res, null, 40000, '合成身份或应用不存在')
      res.writeHead(302, { 'Set-Cookie': `fixtureUser=${requestedUser}; Path=/; SameSite=Lax`, Location: `/app/chat/${id}?view=1` })
      return res.end()
    }
    if (path === '/__fixture/report') return json(res, requests)
    if (path === '/api/user/get/login') return user === 'none' ? json(res, null, 40100, '未登录') : json(res, loginUser(user))
    if (path === '/api/user/logout') {
      res.setHeader('Set-Cookie', 'fixtureUser=none; Path=/; SameSite=Lax')
      return json(res, true)
    }
    if (path === '/api/user/login') {
      const body = await readBody(req)
      const next = body.userAccount === 'owner' ? '100' : '200'
      res.setHeader('Set-Cookie', `fixtureUser=${next}; Path=/; SameSite=Lax`)
      return json(res, loginUser(next))
    }
    if (path === '/api/app/get/vo') {
      const id = url.searchParams.get('id')
      if (id !== '7111') heldHistory.splice(0).forEach((release) => setTimeout(release, 50))
      return appInfo(id) ? json(res, appInfo(id)) : json(res, null, 40400, '应用不存在')
    }
    if (['/api/app/my/list/page/vo', '/api/app/good/list/page/vo'].includes(path)) {
      const records = Object.keys(definitions).map(appInfo)
      return json(res, { records, totalRow: records.length, pageNumber: 1, pageSize: 20 })
    }
    const historyMatch = path.match(/^\/api\/chatHistory\/app\/(\d+)$/)
    if (historyMatch) {
      const id = historyMatch[1]
      const definition = definitions[id]
      if (user === 'none') return json(res, null, 40100, '未登录')
      if (!definition) return json(res, null, 40400, '应用不存在')
      if ((definition.deployed === false || (definition.priority ?? 99) !== 99) && !['100', '300'].includes(user)) return json(res, null, 40101, '仅精选且已部署的应用对其他用户开放对话历史')
      historyRequests.set(id, (historyRequests.get(id) ?? 0) + 1)
      if (definition.failHistory && historyRequests.get(id) === 1) return json(res, null, 50000, '合成历史服务暂不可用')
      const respond = () => json(res, { records: history(id, url.searchParams.get('lastCreateTime')), pageNumber: 1, pageSize: 10 })
      if (definition.holdHistory) { heldHistory.push(respond); return }
      return respond()
    }
    const planMatch = path.match(/^\/api\/app\/(\d+)\/plan$/)
    if (planMatch) {
      if (user !== '100') return json(res, null, 40101, '无权读取计划')
      return json(res, { planId: `fixture-plan-${planMatch[1]}`, version: 1, status: 'BUILT', summary: '合成展示计划', files: [{ path: 'src/App.vue', purpose: '展示页面', action: 'KEEP', state: 'TOUCHED', dependsOn: [] }], history: [] })
    }
    if (path === '/api/app/chat/gen/code') {
      await readBody(req)
      res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store' })
      const frame = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
      if (user !== '100') return res.end(frame('business-error', { protocol: 'generation-error/v1', kind: 'BUSINESS', code: 40101, message: '无权限访问该应用' }) + frame('done', { protocol: 'generation-stream/v1', sequence: 1 }))
      return res.end(frame('message', { protocol: 'generation-stream/v1', sequence: 1, kind: 'ai_text', data: '合成修改已完成。', generation: '1' }) + frame('turn-outcome', { protocol: 'vue-turn/v1', sequence: 2, outcome: 'SUCCEEDED', message: '合成构建成功', refreshPreview: true }) + frame('done', { protocol: 'generation-stream/v1', sequence: 3 }))
    }
    if (path.startsWith('/api/') && req.method !== 'GET' && req.method !== 'HEAD') return json(res, null, 40101, '只读验收不允许此操作')
    const published = path.match(/^\/published\/(\d+)\/$/)
    if (published) {
      const id = published[1]
      if (req.method === 'GET') previewRequests.set(id, (previewRequests.get(id) ?? 0) + 1)
      if (definitions[id]?.failPreview && previewRequests.get(id) === 1) return html(res, '<html><head><title>503 暂不可用</title></head><body>合成预览暂不可用</body></html>', 503)
      return html(res, req.method === 'HEAD' ? '' : previewHtml(id, true))
    }
    const draft = path.match(/^\/api\/static\/[^/]+_(\d+)\//)
    if (draft) return html(res, req.method === 'HEAD' ? '' : previewHtml(draft[1], false))
    if (path.startsWith('/api/')) return json(res, null, 40400, '合成接口不存在')
    const candidate = resolve(dist, `.${decodeURIComponent(path)}`)
    if (candidate !== dist && !candidate.startsWith(dist + sep)) return html(res, 'Not found', 404)
    const file = existsSync(candidate) && statSync(candidate).isFile() ? candidate : resolve(dist, 'index.html')
    const mime = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.ico': 'image/x-icon' }[extname(file)] ?? 'application/octet-stream'
    res.writeHead(200, { 'Content-Type': `${mime}${mime.startsWith('text/') ? '; charset=utf-8' : ''}`, 'Cache-Control': 'no-store' })
    if (req.method === 'HEAD') return res.end()
    createReadStream(file).pipe(res)
  } catch (error) {
    if (!res.headersSent) json(res, null, 50000, '合成夹具异常')
    else res.end()
    process.stderr.write(String(error) + '\n')
  }
})
server.listen(0, '127.0.0.1', () => {
  origin = `http://127.0.0.1:${server.address().port}`
  const state = { origin, pid: process.pid, dist, fixture: '全部身份及数据为合成内容', startedAt: new Date().toISOString() }
  writeFileSync(resolve(evidence, 'server.json'), JSON.stringify(state, null, 2) + '\n')
  process.stdout.write(JSON.stringify(state) + '\n')
})
process.once('SIGTERM', () => { server.close(); server.closeAllConnections() })
process.once('SIGINT', () => { server.close(); server.closeAllConnections() })
