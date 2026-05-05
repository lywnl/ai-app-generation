<template>
  <a-layout-header class="header">
    <a-row :wrap="false" align="middle">
      <!-- 左侧：Logo和标题 -->
      <a-col flex="220px">
        <RouterLink to="/" aria-label="返回主页">
          <div class="header-left">
            <img class="logo" src="@/assets/logo.png" alt="AI 应用生成 Logo" />
            <h1 class="site-title">AI 应用生成</h1>
          </div>
        </RouterLink>
      </a-col>
      <!-- 中间：导航菜单 -->
      <a-col flex="auto">
        <a-menu
          v-model:selectedKeys="selectedKeys"
          mode="horizontal"
          :items="menuItems"
          @click="handleMenuClick"
          class="header-menu"
        />
      </a-col>
      <!-- 右侧：用户操作区域 -->
      <a-col>
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id">
            <a-dropdown>
              <a-space class="user-trigger">
                <a-avatar :src="loginUserStore.loginUser.userAvatar" />
                <span class="user-name">{{ loginUserStore.loginUser.userName ?? '无名' }}</span>
              </a-space>
              <template #overlay>
                <a-menu>
                  <a-menu-item @click="doLogout">
                    <LogoutOutlined />
                    退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div v-else>
            <a-button type="primary" href="/user/login" class="login-btn">登录</a-button>
          </div>
        </div>
      </a-col>
    </a-row>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRouter } from 'vue-router'
import { type MenuProps, message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import { LogoutOutlined, HomeOutlined } from '@ant-design/icons-vue'

const loginUserStore = useLoginUserStore()
const router = useRouter()
// 当前选中菜单
const selectedKeys = ref<string[]>(['/'])
// 监听路由变化，更新当前选中菜单
router.afterEach((to, from, next) => {
  selectedKeys.value = [to.path]
})

// 菜单配置项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/appManage',
    label: '应用管理',
    title: '应用管理',
  },
  {
    key: '/admin/chatManage',
    label: '对话管理',
    title: '对话管理',
  },

]


// 过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    const menuKey = menu?.key as string
    if (menuKey?.startsWith('/admin')) {
      const loginUser = loginUserStore.loginUser
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const menuItems = computed<MenuProps['items']>(() => filterMenus(originItems))

// 处理菜单点击
const handleMenuClick: MenuProps['onClick'] = (e) => {
  const key = e.key as string
  selectedKeys.value = [key]
  // 跳转到对应页面
  if (key.startsWith('/')) {
    router.push(key)
  }
}

// 退出登录
const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.header {
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  padding: 0 24px;
  border-bottom: 1px solid var(--border-light);
  box-shadow: var(--shadow-sm);
  position: sticky;
  top: 0;
  z-index: 50;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.logo {
  height: 40px;
  width: 40px;
  border-radius: var(--radius-sm);
  transition: transform var(--transition-base);
}

.header-left:hover .logo {
  transform: rotate(-6deg) scale(1.05);
}

.site-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
  background: var(--brand-gradient-pure);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}

.header-menu :deep(.ant-menu-horizontal) {
  border-bottom: none !important;
  background: transparent;
}

.header-menu :deep(.ant-menu-item-selected) {
  color: var(--brand-primary) !important;
}

.header-menu :deep(.ant-menu-item-selected::after) {
  border-bottom-color: var(--brand-primary) !important;
}

.user-trigger {
  cursor: pointer;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  transition: background var(--transition-base);
}

.user-trigger:hover {
  background: var(--bg-mute);
}

.user-name {
  color: var(--text-primary);
  font-size: 14px;
}

.login-btn {
  border: none;
  background: var(--brand-gradient-pure);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
  transition: transform var(--transition-base), box-shadow var(--transition-base);
}

.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(59, 130, 246, 0.4);
}

.user-login-status {
  display: flex;
  align-items: center;
}
</style>
