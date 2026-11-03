type UserId = string | number | null | undefined

interface ViewableApp {
  userId?: UserId
  priority?: number | null
  deployKey?: string | null
  deployedTime?: string | null
  deployUrl?: string | null
}

function normalizeUserId(id: UserId): string | undefined {
  if (id == null || (typeof id === 'number' && !Number.isSafeInteger(id))) return
  const value = String(id)
  return /^[1-9]\d*$/.test(value) ? value : undefined
}

export function isAppOwner(app: ViewableApp | undefined, userId: UserId): boolean {
  const owner = normalizeUserId(app?.userId)
  return owner !== undefined && owner === normalizeUserId(userId)
}

export function isAppDeployed(app: ViewableApp | undefined): boolean {
  return !!app?.deployKey?.trim() && !!app.deployedTime?.trim()
}

export function isAppFeaturedAndDeployed(app: ViewableApp | undefined): boolean {
  return app?.priority === 99 && isAppDeployed(app)
}

/** 访客需要精选且已部署，发布地址缺失不回退草稿；管理员保留原有权限。 */
export function getReadonlyPreviewUrl(
  app: ViewableApp | undefined,
  isAdmin: boolean,
  draftUrl: string,
): string {
  if (!app) return ''
  if (!isAdmin && !isAppFeaturedAndDeployed(app)) return ''
  if (isAppDeployed(app)) return app.deployUrl?.trim() ?? ''
  return isAdmin ? draftUrl : ''
}
