import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'

const DEFAULT_PERCENT = 40
const MIN_PERCENT = 30
const MAX_PERCENT = 50

export function useWorkspaceResize(available: Ref<boolean>) {
  const container = ref<HTMLElement>()
  const separator = ref<HTMLElement>()
  const leftPercent = ref(DEFAULT_PERCENT)
  const isDesktop = ref(false)
  const isResizing = ref(false)
  const enabled = computed(() => available.value && isDesktop.value)
  const style = computed(() => ({
    '--left-share': `${leftPercent.value}fr`,
    '--right-share': `${100 - leftPercent.value}fr`,
  }))
  let media: MediaQueryList | undefined
  let drag: { pointerId: number; startX: number; startPercent: number; width: number } | undefined

  function setPercent(value: number) {
    leftPercent.value = Math.min(MAX_PERCENT, Math.max(MIN_PERCENT, value))
  }

  function stopResize() {
    const previous = drag
    drag = undefined
    isResizing.value = false
    if (previous && separator.value?.hasPointerCapture(previous.pointerId)) {
      separator.value.releasePointerCapture(previous.pointerId)
    }
  }

  function startResize(event: PointerEvent) {
    if (!enabled.value || event.button !== 0 || drag || !container.value || !separator.value) return
    const width = container.value.clientWidth - separator.value.getBoundingClientRect().width
    if (width <= 0) return
    event.preventDefault()
    separator.value.focus({ preventScroll: true })
    separator.value.setPointerCapture(event.pointerId)
    drag = { pointerId: event.pointerId, startX: event.clientX, startPercent: leftPercent.value, width }
    isResizing.value = true
  }

  function moveResize(event: PointerEvent) {
    if (!drag || drag.pointerId !== event.pointerId) return
    setPercent(drag.startPercent + (event.clientX - drag.startX) / drag.width * 100)
  }

  function endResize(event: PointerEvent) {
    if (drag?.pointerId === event.pointerId) stopResize()
  }

  function reset() {
    stopResize()
    leftPercent.value = DEFAULT_PERCENT
  }

  function handleKeydown(event: KeyboardEvent) {
    if (!enabled.value) return
    const values: Record<string, number> = {
      ArrowLeft: leftPercent.value - 1,
      ArrowRight: leftPercent.value + 1,
      Home: MIN_PERCENT,
      End: MAX_PERCENT,
      Enter: DEFAULT_PERCENT,
    }
    const value = values[event.key]
    if (value === undefined) return
    event.preventDefault()
    stopResize()
    setPercent(value)
  }

  function syncDesktop() {
    stopResize()
    isDesktop.value = media?.matches ?? false
  }

  watch(enabled, (value) => { if (!value) stopResize() }, { flush: 'sync' })
  onMounted(() => {
    media = window.matchMedia('(min-width: 1025px)')
    syncDesktop()
    media.addEventListener('change', syncDesktop)
    window.addEventListener('blur', stopResize)
    window.addEventListener('resize', stopResize)
  })
  onUnmounted(() => {
    stopResize()
    media?.removeEventListener('change', syncDesktop)
    window.removeEventListener('blur', stopResize)
    window.removeEventListener('resize', stopResize)
  })

  return {
    container, separator, leftPercent, enabled, isResizing, style,
    startResize, moveResize, endResize, handleKeydown, reset,
  }
}
