import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { computed, ref, shallowRef } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { getReadonlyPreviewUrl, isAppOwner } from '../../utils/appViewAccess'

const script = parse(readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')).descriptor.scriptSetup!.content
const source = ts.createSourceFile('AppChatPage.ts', script, ts.ScriptTarget.ESNext, true, ts.ScriptKind.TS)

/** 执行真实页面函数，只替换 API、DOM 和外部生成入口。 */
function pageFunctions(names: string[], context: object): Record<string, (...args: unknown[]) => unknown> {
  const declarations = source.statements.filter((statement) =>
    (ts.isVariableStatement(statement) && statement.declarationList.declarations.some((declaration) =>
      names.includes(declaration.name.getText(source)))) ||
    (ts.isFunctionDeclaration(statement) && statement.name && names.includes(statement.name.getText(source))))
  expect(declarations).toHaveLength(names.length)
  const executable = ts.transpileModule(declarations.map((node) => node.getText(source)).join('\n'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText
  return runInNewContext(`${executable}\n({ ${names.join(', ')} })`, context)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function fixture(userId = '200') {
  const app = { id: 7, userId: 100, priority: 99, codeGenType: 'vue_project', deployKey: 'published',
    deployedTime: '2026-11-03T10:00:00', deployUrl: '/published/7/', initPrompt: '创建一个应用' }
  const appInfo = ref<API.AppVO>()
  const context = {
    pageEpoch: 0,
    route: { params: { id: '7' } },
    appInfo, appId: ref(''),
    loginUserStore: { loginUser: { id: userId } },
    isOwner: computed(() => isAppOwner(appInfo.value, userId)),
    isAdmin: ref(false),
    isVue: computed(() => appInfo.value?.codeGenType === 'vue_project'),
    getAppVoById: vi.fn().mockResolvedValue({ data: { code: 0, data: app } }),
    getReadonlyPreviewUrl,
    getStaticPreviewUrl: vi.fn().mockReturnValue('/draft/7/'),
    CodeGenTypeEnum: { HTML: 'html' },
    loadChatHistory: vi.fn().mockResolvedValue(true),
    updatePreview: vi.fn(),
    messages: ref<unknown[]>([]),
    historyLoaded: ref(true),
    getGenerationSessionSnapshot: vi.fn().mockReturnValue(null),
    restoreActiveSessionIfNeeded: vi.fn(),
    createSessionMessage: vi.fn(),
    applySessionSnapshot: vi.fn(),
    finalizeGeneration: vi.fn(),
    sendInitialMessage: vi.fn(),
    scrollToLatestMessageOnEntry: vi.fn(),
    initialPreviewPending: true,
    activeSessionAppId: ref<string>(),
    lastSession: ref(null),
    planQuery: { setContext: vi.fn() },
    message: { error: vi.fn() },
    router: { push: vi.fn() },
    console,
  }
  return { app, context, functions: pageFunctions(['fetchAppInfo', 'syncPlanContext'], context) }
}

describe('真实聊天页的只读初始化', () => {
  it('历史仍在等待时已开始发布预览，空历史不触发生成或计划查询', async () => {
    const { app, context, functions } = fixture()
    const history = deferred<boolean>()
    context.loadChatHistory.mockReturnValue(history.promise)
    const loading = functions.fetchAppInfo!()
    await vi.waitFor(() => expect(context.updatePreview).toHaveBeenCalledExactlyOnceWith(false, app.deployUrl))
    expect(context.getGenerationSessionSnapshot).not.toHaveBeenCalled()
    history.resolve(true)
    await loading
    expect(context.updatePreview).toHaveBeenCalledTimes(1)
    expect(context.sendInitialMessage).not.toHaveBeenCalled()
    expect(context.restoreActiveSessionIfNeeded).not.toHaveBeenCalled()
    expect(context.planQuery.setContext).toHaveBeenCalledWith(null, 0)
  })

  it.each([0, 1, 2])('历史加载失败或只有 %i 条时仍保留发布预览', async (count) => {
    const { app, context, functions } = fixture()
    context.messages.value = Array.from({ length: count }, () => ({ content: '历史' }))
    context.historyLoaded.value = false
    context.loadChatHistory.mockResolvedValue(false)
    await functions.fetchAppInfo!()
    expect(context.updatePreview).toHaveBeenCalledExactlyOnceWith(false, app.deployUrl)
    expect(context.sendInitialMessage).not.toHaveBeenCalled()
    expect(context.router.push).not.toHaveBeenCalled()
  })

  it.each(['not-deployed', 'not-featured'])('非作者的 %s 应用不会回退到草稿或恢复作者会话', async (missing) => {
    const { app, context, functions } = fixture()
    context.getAppVoById.mockResolvedValue({ data: { code: 0, data: missing === 'not-deployed'
      ? { ...app, deployedTime: undefined } : { ...app, priority: 0 } } })
    context.getGenerationSessionSnapshot.mockReturnValue({ status: 'streaming', localTurnId: 'owner-turn' })
    await functions.fetchAppInfo!()
    expect(context.updatePreview).not.toHaveBeenCalled()
    expect(context.getGenerationSessionSnapshot).not.toHaveBeenCalled()
    expect(context.createSessionMessage).not.toHaveBeenCalled()
    expect(context.planQuery.setContext).toHaveBeenCalledWith(null, 0)
  })

  it('作者自己的空应用仍能自动发送初始提示词', async () => {
    const { app, context, functions } = fixture('100')
    await functions.fetchAppInfo!()
    expect(context.sendInitialMessage).toHaveBeenCalledExactlyOnceWith(app.initPrompt)
    expect(context.updatePreview).not.toHaveBeenCalled()
    expect(context.planQuery.setContext).toHaveBeenCalledWith({ userId: '100', appId: '7', turnId: '' }, 0)
  })

  it('作者的流式会话继续恢复而不覆盖为发布预览', async () => {
    const { context, functions } = fixture('100')
    context.messages.value = [{ content: '用户需求' }, { content: '生成中' }]
    context.getGenerationSessionSnapshot.mockReturnValue({ status: 'streaming', localTurnId: 'owner-turn' })
    await functions.fetchAppInfo!()
    expect(context.restoreActiveSessionIfNeeded).toHaveBeenCalledOnce()
    expect(context.updatePreview).not.toHaveBeenCalled()
    expect(context.sendInitialMessage).not.toHaveBeenCalled()
  })

  it('切换应用或账号后的旧应用信息不进入新视图', async () => {
    const { app, context, functions } = fixture()
    const pending = deferred<unknown>()
    context.getAppVoById.mockReturnValue(pending.promise)
    const loading = functions.fetchAppInfo!()
    context.pageEpoch++
    pending.resolve({ data: { code: 0, data: app } })
    await loading
    expect(context.appInfo.value).toBeUndefined()
    expect(context.updatePreview).not.toHaveBeenCalled()
    expect(context.loadChatHistory).not.toHaveBeenCalled()
  })

  it('切换之后的旧历史完成不恢复会话、查询计划或移动新页面', async () => {
    const { context, functions } = fixture()
    const pending = deferred<boolean>()
    context.loadChatHistory.mockReturnValue(pending.promise)
    const loading = functions.fetchAppInfo!()
    await vi.waitFor(() => expect(context.loadChatHistory).toHaveBeenCalledOnce())
    context.pageEpoch++
    pending.resolve(true)
    await loading
    expect(context.planQuery.setContext).not.toHaveBeenCalled()
    expect(context.scrollToLatestMessageOnEntry).not.toHaveBeenCalled()
    expect(context.getGenerationSessionSnapshot).not.toHaveBeenCalled()
  })
})

describe('真实处理函数的非作者守卫', () => {
  it.each(['startGeneration', 'sendMessage', 'sendInitialMessage', 'stopGeneration',
    'restoreActiveSessionIfNeeded', 'deployApp', 'downloadCode', 'toggleEditMode',
    'handleIframeMessage', 'editApp', 'deleteApp'])(
    '%s 在身份不允许时不访问后续操作依赖', async (name) => {
      const functions = pageFunctions([name], { isOwner: ref(false), isAdmin: ref(false) })
      expect(await functions[name]!('ignored', 0)).toBeUndefined()
    },
  )

  it('旧 iframe 的错误事件不能把新预览标为失败', () => {
    const oldFrame = { dataset: { loadId: '1' } }
    const currentFrame = { dataset: { loadId: '2' } }
    const invalidatePreview = vi.fn()
    const functions = pageFunctions(['onIframeError'], {
      previewFrame: shallowRef(currentFrame), previewLoadId: ref(2), invalidatePreview,
    })
    functions.onIframeError!({ target: oldFrame })
    expect(invalidatePreview).not.toHaveBeenCalled()
    functions.onIframeError!({ target: currentFrame })
    expect(invalidatePreview).toHaveBeenCalledOnce()
  })
})
