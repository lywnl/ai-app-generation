/** 把后端分页总数归一化为组件可接受的非负安全整数。 */
export function normalizePageTotal(value: unknown): number {
  if (typeof value !== 'number' && typeof value !== 'string') {
    return 0
  }
  if (typeof value === 'string' && !/^\d+$/.test(value.trim())) {
    return 0
  }
  const total = Number(value)
  return Number.isSafeInteger(total) && total >= 0 ? total : 0
}
