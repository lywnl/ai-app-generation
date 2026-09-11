import defaultUserAvatar from '@/assets/defaultUserAvatar.jpg'

export { defaultUserAvatar }

export function formatUserDisplayName(name?: string): string {
  return name && /^用户_[0-9]{8}$/.test(name) ? name : '用户'
}
