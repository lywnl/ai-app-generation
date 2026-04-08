<template>
  <div class="global-header">
    <a-row :wrap="false" align="middle">
      <a-col flex="200px">
        <div class="logo-area">
          <img src="@/assets/logo.png" class="logo" />
          <span class="title">AI代码生成平台</span>
        </div>
      </a-col>
      <a-col flex="auto">
        <a-menu
          v-model:selectedKeys="current"
          mode="horizontal"
          :items="menuItems"
          @click="doMenuClick"
        />
      </a-col>
      <a-col flex="80px">
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id">
            <a-space>
              <a-avatar :src="loginUserStore.loginUser.userAvatar" />
              {{ loginUserStore.loginUser.userName ?? '无名' }}
            </a-space>
          </div>
          <div v-else>
            <a-button type="primary" href="/user/login">登录</a-button>
          </div>
        </div>

      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/loginUser.ts'

const loginUserStore = useLoginUserStore()

const router = useRouter()
const current = ref<string[]>(['/'])

// 支持通过配置设置菜单项
const menuItems = ref([
  {
    key: '/',
    label: '主页',
    title: '主页',
  },
  {
    key: '/about',
    label: '关于我们',
    title: '关于我们',
  },
])

const doMenuClick = ({ key }: { key: string }) => {
  router.push(key)
}
</script>

<style scoped>
.global-header {
  box-sizing: border-box;
  width: 100%;
}

.logo-area {
  display: flex;
  align-items: center;
  padding-left: 20px;
}

.logo {
  height: 32px;
  margin-right: 12px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.user-info {
  display: flex;
  justify-content: flex-end;
  padding-right: 20px;
}
</style>
