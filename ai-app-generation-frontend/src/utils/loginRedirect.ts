/** 登录后只接受站内路径，避免将重定向参数变成站外跳转。 */
export function loginRedirect(value: unknown): string {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.includes('\\') &&
    !/[\u0000-\u0020]/.test(value)
    ? value
    : '/'
}
