import { ref, shallowRef } from 'vue'
import { getAppPlan, PlanQueryError } from '@/api/appPlan'
import type { PlanSnapshot } from '@/utils/planSnapshot'

export type PlanSyncStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'stale' | 'error'
export interface PlanContext { userId: string; appId: string; turnId: string }
type Query = typeof getAppPlan

/** SSE 只推进观察序号；在最后一次观察之后确认一份完整服务端快照。 */
export function useGenerationPlan(query: Query = getAppPlan) {
  const snapshot = shallowRef<PlanSnapshot | null>(null)
  const status = ref<PlanSyncStatus>('idle')
  const error = ref('')
  let context: PlanContext | null = null
  let epoch = 0, observation = -1, requestId = 0
  let running = false, dirty = false, blocked = false, scheduled = false
  let controller: AbortController | undefined

  function setContext(next: PlanContext | null, revision = 0) {
    const same = next && context && next.userId === context.userId && next.appId === context.appId && next.turnId === context.turnId
    if (same) { observe(revision); return }
    const sameOwner = next && context && next.userId === context.userId && next.appId === context.appId
    epoch++; controller?.abort(); controller = undefined
    context = next ? { ...next } : null
    observation = revision; running = false; dirty = false; blocked = false; scheduled = false
    error.value = ''
    if (!sameOwner) snapshot.value = null
    status.value = next ? (snapshot.value ? 'stale' : 'loading') : 'idle'
    if (next) { dirty = true; schedule() }
  }
  function observe(revision: number) {
    if (!context || blocked || revision === observation) return
    observation = revision; dirty = true
    status.value = snapshot.value ? 'stale' : 'loading'
    schedule()
  }
  function schedule() {
    if (scheduled || running || blocked || !context) return
    scheduled = true
    const currentEpoch = epoch
    queueMicrotask(() => {
      if (currentEpoch !== epoch) return
      scheduled = false
      void run()
    })
  }
  async function run() {
    if (!context || blocked || running || !dirty) return
    running = true; dirty = false
    const currentEpoch = epoch, observed = observation, id = ++requestId
    const abort = new AbortController(); controller = abort
    const appId = context.appId
    try {
      const result = await query(appId, abort.signal)
      if (currentEpoch !== epoch || id !== requestId || abort.signal.aborted || observed !== observation) return
      snapshot.value = result; status.value = result ? 'ready' : 'empty'; error.value = ''
    } catch (cause) {
      if (currentEpoch !== epoch || id !== requestId || abort.signal.aborted) return
      if (cause instanceof PlanQueryError && cause.accessLost) {
        snapshot.value = null; blocked = true; dirty = false
        status.value = 'error'; error.value = '当前计划不可访问'
      } else if (observed === observation) {
        status.value = snapshot.value ? 'stale' : 'error'
        error.value = '计划暂时无法读取，请重试'
      }
    } finally {
      if (currentEpoch === epoch && id === requestId) {
        running = false; controller = undefined
        if (dirty) schedule()
      }
    }
  }
  function retry() {
    if (!context || blocked) return
    error.value = ''; dirty = true
    status.value = snapshot.value ? 'stale' : 'loading'
    schedule()
  }
  return { snapshot, status, error, setContext, observe, retry, dispose: () => setContext(null) }
}
