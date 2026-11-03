import { describe, expect, it } from 'vitest'
import { getReadonlyPreviewUrl, isAppDeployed, isAppFeaturedAndDeployed, isAppOwner } from './appViewAccess'

const deployed = {
  userId: '475079696219832320',
  priority: 99,
  deployKey: 'published',
  deployedTime: '2026-11-03T10:00:00',
  deployUrl: 'https://example.test/b/published/?theme=light',
}
const draft = '/api/static/vue_project_7/dist/index.html'

describe('应用查看身份与发布预览', () => {
  it('身份未加载时不能把两个缺失 ID 当作同一作者', () => {
    expect(isAppOwner(undefined, undefined)).toBe(false)
    expect(isAppOwner({}, null)).toBe(false)
    expect(isAppOwner({ userId: undefined }, undefined)).toBe(false)
  })

  it('保留雪花 ID 字符串并兼容安全的数字 ID', () => {
    expect(isAppOwner(deployed, deployed.userId)).toBe(true)
    expect(isAppOwner(deployed, '475079696219832321')).toBe(false)
    expect(isAppOwner({ userId: 9 }, '9')).toBe(true)
    expect(isAppOwner({ userId: '9' }, 9)).toBe(true)
  })

  it.each([0, -1, '', '0', 'undefined', Number.NaN, Number.MAX_SAFE_INTEGER + 1])(
    '无效 ID %s 不会取得作者权限', (id) => {
      expect(isAppOwner({ userId: id }, id)).toBe(false)
    },
  )

  it('部署判断与精选独立，但共享资格要求二者同时成立', () => {
    expect(isAppDeployed(deployed)).toBe(true)
    expect(isAppDeployed({ ...deployed, priority: 0 })).toBe(true)
    expect(isAppFeaturedAndDeployed(deployed)).toBe(true)
    expect(isAppFeaturedAndDeployed({ ...deployed, priority: 0 })).toBe(false)
    expect(isAppFeaturedAndDeployed({ ...deployed, deployedTime: undefined })).toBe(false)
    expect(isAppDeployed({ deployKey: 'key' })).toBe(false)
    expect(isAppDeployed({ deployedTime: deployed.deployedTime })).toBe(false)
    expect(isAppDeployed({ deployKey: ' ', deployedTime: deployed.deployedTime })).toBe(false)
  })

  it.each([undefined, null, 0, 98, 100])('优先级 %s 不允许普通访客加载已部署页面', (priority) => {
    expect(getReadonlyPreviewUrl({ ...deployed, priority }, false, draft)).toBe('')
  })

  it('访客只打开完整发布地址，不能回退到作者草稿', () => {
    expect(getReadonlyPreviewUrl(deployed, false, draft)).toBe(deployed.deployUrl)
    expect(getReadonlyPreviewUrl({ ...deployed, deployUrl: undefined }, false, draft)).toBe('')
    expect(getReadonlyPreviewUrl({ ...deployed, deployedTime: undefined }, false, draft)).toBe('')
    expect(getReadonlyPreviewUrl(undefined, false, draft)).toBe('')
  })

  it('管理员未部署查看保留草稿预览，已部署时仍选择发布地址', () => {
    expect(getReadonlyPreviewUrl({ userId: 9 }, true, draft)).toBe(draft)
    expect(getReadonlyPreviewUrl(deployed, true, draft)).toBe(deployed.deployUrl)
    expect(getReadonlyPreviewUrl({ ...deployed, priority: 0 }, true, draft)).toBe(deployed.deployUrl)
    expect(getReadonlyPreviewUrl({ ...deployed, deployUrl: undefined }, true, draft)).toBe('')
  })
})
