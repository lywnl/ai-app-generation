import type { ObjectDirective } from 'vue'

type ScrollElement = HTMLElement & { __autoScrollHandler__?: () => void }

/** 用户离开代码底部后暂停跟随，重新回到底部时恢复。 */
export const vAutoScroll: ObjectDirective<ScrollElement> = {
  mounted(el) {
    el.scrollTop = el.scrollHeight
    el.dataset.stickBottom = '1'
    const onScroll = () => {
      el.dataset.stickBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 16 ? '1' : '0'
    }
    el.__autoScrollHandler__ = onScroll
    el.addEventListener('scroll', onScroll)
  },
  updated(el) {
    if (el.dataset.stickBottom === '1') el.scrollTop = el.scrollHeight
  },
  beforeUnmount(el) {
    if (el.__autoScrollHandler__) el.removeEventListener('scroll', el.__autoScrollHandler__)
    delete el.__autoScrollHandler__
  },
}
