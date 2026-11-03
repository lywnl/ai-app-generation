import { ref } from 'vue'

type LoadPage = (params: API.listAppChatHistoryParams) => Promise<{
  data: API.BaseResponsePageChatHistory
}>

/** 只负责展示历史的分页与请求生命周期，不恢复生成会话或模型记忆。 */
export function useAppChatHistory(
  loadPage: LoadPage,
  onPage: (records: API.ChatHistory[], isLoadMore: boolean) => void,
) {
  const loading = ref(false)
  const loaded = ref(false)
  const hasMore = ref(false)
  const error = ref('')
  let cursor: string | undefined
  let epoch = 0
  let lastRequest: { appId: string; isLoadMore: boolean } | undefined

  async function load(appId: string, isLoadMore = false): Promise<boolean> {
    if (!appId || loading.value) return false
    const requestEpoch = epoch
    loading.value = true
    error.value = ''
    lastRequest = { appId, isLoadMore }
    try {
      const response = await loadPage({
        appId: appId as unknown as number,
        pageSize: 10,
        ...(isLoadMore && cursor ? { lastCreateTime: cursor } : {}),
      })
      if (requestEpoch !== epoch) return false
      if (response.data.code !== 0 || !response.data.data) {
        error.value = response.data.message || '加载对话历史失败，请重试'
        return false
      }
      const records = response.data.data.records ?? []
      onPage(records, isLoadMore)
      if (records.length > 0) cursor = records[records.length - 1]?.createTime
      hasMore.value = records.length === 10
      loaded.value = true
      return true
    } catch {
      if (requestEpoch === epoch) error.value = '加载对话历史失败，请重试'
      return false
    } finally {
      if (requestEpoch === epoch) loading.value = false
    }
  }

  function retry(): Promise<boolean> {
    return lastRequest
      ? load(lastRequest.appId, lastRequest.isLoadMore)
      : Promise.resolve(false)
  }

  function reset() {
    epoch++
    loading.value = false
    loaded.value = false
    hasMore.value = false
    error.value = ''
    cursor = undefined
    lastRequest = undefined
  }

  return { loading, loaded, hasMore, error, load, retry, reset }
}
