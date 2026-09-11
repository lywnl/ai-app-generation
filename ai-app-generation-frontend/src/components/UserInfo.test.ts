import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import Antd from 'ant-design-vue'
import { describe, expect, it } from 'vitest'
import UserInfo from './UserInfo.vue'
import { defaultUserAvatar } from '@/utils/userDisplay'

describe('统一用户头像和昵称组件', () => {
  it.each(['user', 'admin'])('覆盖 %s 的自定义头像并显示持久化昵称', async (userRole) => {
    const app = createSSRApp(UserInfo, {
      user: {
        id: '9007199254740993',
        userName: '用户_00123456',
        userAvatar: 'https://old.example/avatar.jpg',
        userRole,
      },
    }).use(Antd)
    const html = await renderToString(app)
    expect(html).toContain('用户_00123456')
    expect(html).toContain(defaultUserAvatar)
    expect(html).not.toContain('https://old.example/avatar.jpg')
  })

  it('缺少用户资料时不伪造随机昵称，仍使用统一头像', async () => {
    const html = await renderToString(createSSRApp(UserInfo).use(Antd))
    expect(html).toContain(defaultUserAvatar)
    expect(html).not.toContain('未知用户')
    expect(html).not.toMatch(/用户_[0-9]{8}/)
  })
})
