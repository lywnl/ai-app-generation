import { ref } from 'vue'
import type { GenerationSessionSnapshot } from '@/utils/generationSession'

export function useGenerationPreview() {
  const layout = ref<'workspace' | 'preview' | 'previous-preview'>('preview')
  const available = ref(false)
  const retained = ref(false)
  const crossOrigin = ref(false)
  let activeTurn = '', finalizedTurn = '', previousLayout: typeof layout.value = 'preview'
  let loadIdentity = 0, loadTurn = ''
  let loadValid = false
  function begin(turn: string) {
    if (activeTurn === turn) return
    previousLayout = layout.value
    activeTurn = turn
    retained.value = available.value
    layout.value = 'workspace'
  }
  function finish(snapshot: Pick<GenerationSessionSnapshot, 'localTurnId' | 'status' | 'outcome'>) {
    if (snapshot.status === 'streaming' || finalizedTurn === snapshot.localTurnId) return false
    finalizedTurn = snapshot.localTurnId
    if (snapshot.status === 'done' && snapshot.outcome === 'succeeded') {
      layout.value = 'preview'; retained.value = false
      return true
    }
    layout.value = snapshot.status === 'done' && snapshot.outcome === 'answered' ? previousLayout : 'workspace'
    return false
  }
  function startLoad() {
    available.value = false; crossOrigin.value = false
    loadValid = true
    loadTurn = activeTurn
    return ++loadIdentity
  }
  function loaded(identity: number, cross = false) {
    if (!loadValid || identity !== loadIdentity || loadTurn !== activeTurn) return false
    available.value = true; crossOrigin.value = cross
    return true
  }
  function invalidate(identity = loadIdentity) {
    if (identity !== loadIdentity) return
    loadValid = false
    available.value = false; retained.value = false
  }
  function reset() {
    loadIdentity++; activeTurn = ''; finalizedTurn = ''; previousLayout = 'preview'
    loadValid = false
    layout.value = 'preview'; available.value = false; retained.value = false; crossOrigin.value = false
  }
  return { layout, available, retained, crossOrigin, begin, finish, startLoad, loaded, invalidate, reset }
}
