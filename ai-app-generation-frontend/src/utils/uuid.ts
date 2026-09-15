export function createUuid(): string {
  const crypto = globalThis.crypto
  if (typeof crypto?.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  if (typeof crypto?.getRandomValues !== 'function') {
    throw new Error('当前浏览器不支持安全随机数生成')
  }

  // 普通 HTTP 不提供 randomUUID，但允许 getRandomValues；设置 UUID v4 的版本和变体位。
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  const view = new DataView(bytes.buffer)
  view.setUint8(6, (view.getUint8(6) & 0x0f) | 0x40)
  view.setUint8(8, (view.getUint8(8) & 0x3f) | 0x80)
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
