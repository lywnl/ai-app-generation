import { reactive, ref } from 'vue'
import {
  MemoryApiError,
  userMemoryApi,
  type MemoryMutation,
  type MemoryQuery,
  type UserMemory,
  type UserMemoryApi,
  type MemoryHistoryEntry,
} from '@/api/userMemory'
import { createUuid } from '@/utils/uuid'

/** 查询可以取消；写入只在成功后刷新，超时重试保留请求标识。 */
export function useUserMemories(api: UserMemoryApi = userMemoryApi) {
  const query = reactive<MemoryQuery>({
    status: 'ACTIVE',
    category: '',
    keyword: '',
    pageNum: 1,
    pageSize: 10,
  })
  const records = ref<UserMemory[]>([]),
    total = ref(0),
    activeCount = ref(0),
    pendingCount = ref(0)
  const loading = ref(false),
    error = ref(''),
    busy = ref(false),
    accessLost = ref(false)
  const history = ref<MemoryHistoryEntry[]>([]),
    historyTotal = ref(0),
    historyPage = ref(1)
  const historyLoading = ref(false),
    historyError = ref('')
  let disposed = false,
    userId = '',
    generation = 0,
    listVersion = 0,
    historyVersion = 0
  let listAbort: AbortController | null = null,
    historyAbort: AbortController | null = null
  let retry: { fingerprint: string; requestId: string } | null = null

  function rejectAccess() {
    accessLost.value = true
    records.value = []
    history.value = []
    total.value = activeCount.value = pendingCount.value = historyTotal.value = 0
  }
  async function reload() {
    if (disposed || !userId || accessLost.value) return
    listAbort?.abort()
    const controller = new AbortController()
    listAbort = controller
    const version = ++listVersion,
      context = generation
    loading.value = true
    error.value = ''
    records.value = []
    try {
      const page = await api.list({ ...query }, controller.signal)
      if (disposed || context !== generation || version !== listVersion) return
      const lastPage = Math.max(1, Math.ceil(page.totalRow / query.pageSize))
      if (query.pageNum > lastPage) {
        query.pageNum = lastPage
        await reload()
        return
      }
      records.value = page.records
      total.value = page.totalRow
      activeCount.value = page.activeCount
      pendingCount.value = page.pendingCount
    } catch (cause) {
      if (
        disposed ||
        context !== generation ||
        version !== listVersion ||
        controller.signal.aborted
      )
        return
      if (cause instanceof MemoryApiError && cause.accessLost) rejectAccess()
      error.value = cause instanceof MemoryApiError ? cause.message : '暂时无法加载记忆，请重试'
    } finally {
      if (context === generation && version === listVersion && !disposed) loading.value = false
    }
  }
  function setQuery(value: Partial<MemoryQuery>) {
    Object.assign(query, value)
    if (value.pageNum === undefined) query.pageNum = 1
    return reload()
  }
  function setUser(value: string) {
    if (disposed || value === userId) return
    generation++
    userId = value
    listAbort?.abort()
    historyAbort?.abort()
    retry = null
    records.value = []
    history.value = []
    error.value = ''
    historyError.value = ''
    accessLost.value = false
    total.value = activeCount.value = pendingCount.value = historyTotal.value = 0
    busy.value = loading.value = historyLoading.value = false
    query.pageNum = 1
    historyPage.value = 1
    void reload()
  }
  async function change(value: MemoryMutation) {
    if (disposed || !userId || accessLost.value || busy.value) return null
    const context = generation,
      fingerprint = JSON.stringify(value)
    if (!retry || retry.fingerprint !== fingerprint)
      retry = { fingerprint, requestId: createUuid() }
    const requestId = retry.requestId
    busy.value = true
    try {
      const result = await api.change({ ...value, requestId })
      if (disposed || context !== generation) return null
      retry = null
      if (value.action === 'CREATE')
        Object.assign(query, { status: value.saveMode, category: '', keyword: '', pageNum: 1 })
      await reload()
      return result
    } catch (cause) {
      if (disposed || context !== generation) return null
      if (cause instanceof MemoryApiError && cause.accessLost) rejectAccess()
      if (cause instanceof MemoryApiError && cause.conflict) {
        retry = null
        await reload()
      }
      throw cause
    } finally {
      if (context === generation && !disposed) busy.value = false
    }
  }
  async function loadHistory(page = 1) {
    if (disposed || !userId || accessLost.value) return
    historyAbort?.abort()
    const controller = new AbortController()
    historyAbort = controller
    const version = ++historyVersion,
      context = generation
    historyLoading.value = true
    historyError.value = ''
    historyPage.value = page
    history.value = []
    try {
      const result = await api.history(page, controller.signal)
      if (disposed || context !== generation || version !== historyVersion) return
      history.value = result.records
      historyTotal.value = result.totalRow
    } catch (cause) {
      if (
        disposed ||
        context !== generation ||
        version !== historyVersion ||
        controller.signal.aborted
      )
        return
      if (cause instanceof MemoryApiError && cause.accessLost) rejectAccess()
      historyError.value =
        cause instanceof MemoryApiError ? cause.message : '暂时无法加载历史记录，请重试'
    } finally {
      if (!disposed && context === generation && version === historyVersion)
        historyLoading.value = false
    }
  }
  function dispose() {
    disposed = true
    generation++
    listAbort?.abort()
    historyAbort?.abort()
    retry = null
  }
  return {
    query,
    records,
    total,
    activeCount,
    pendingCount,
    loading,
    error,
    busy,
    accessLost,
    history,
    historyTotal,
    historyPage,
    historyLoading,
    historyError,
    reload,
    setQuery,
    setUser,
    change,
    loadHistory,
    dispose,
  }
}
