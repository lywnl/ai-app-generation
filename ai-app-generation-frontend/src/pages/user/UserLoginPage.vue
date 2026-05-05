<template>
  <div id="userLoginPage" class="auth-page">
    <div class="auth-bg-layer" aria-hidden="true"></div>
    <div class="auth-card">
      <div class="auth-brand">
        <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
          <defs>
            <linearGradient id="loginBrandGrad" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#3b82f6" />
              <stop offset="100%" stop-color="#8b5cf6" />
            </linearGradient>
          </defs>
          <rect width="32" height="32" rx="8" fill="url(#loginBrandGrad)" />
          <path
            d="M10 22V10l6 12 6-12v12"
            stroke="white"
            stroke-width="2.5"
            fill="none"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <h2 class="title">AI 应用生成</h2>
      </div>
      <div class="desc">不写一行代码，生成完整应用</div>
      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="formState.userAccount" placeholder="请输入账号" size="large" />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password v-model:value="formState.userPassword" placeholder="请输入密码" size="large" />
        </a-form-item>
        <div class="tips">
          没有账号？
          <RouterLink to="/user/register">去注册</RouterLink>
        </div>
        <a-form-item>
          <a-button type="primary" html-type="submit" size="large" class="submit-btn" block>登录</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { reactive } from 'vue'
import { userLogin } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const router = useRouter()
const loginUserStore = useLoginUserStore()

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  const res = await userLogin(values)
  // 登录成功，把登录态保存到全局状态中
  if (res.data.code === 0 && res.data.data) {
    await loginUserStore.fetchLoginUser()
    message.success('登录成功')
    router.push({
      path: '/',
      replace: true,
    })
  } else {
    message.error('登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.auth-page {
  min-height: calc(100vh - 64px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  background: var(--bg-soft);
  position: relative;
  overflow: hidden;
}

.auth-bg-layer {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 20% 30%, rgba(59, 130, 246, 0.18) 0%, transparent 50%),
    radial-gradient(circle at 80% 70%, rgba(139, 92, 246, 0.15) 0%, transparent 50%),
    radial-gradient(circle at 50% 100%, rgba(16, 185, 129, 0.08) 0%, transparent 60%);
  pointer-events: none;
}

.auth-card {
  position: relative;
  z-index: 1;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  padding: 40px;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xl);
  border: 1px solid var(--border-light);
  max-width: 420px;
  width: 100%;
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: center;
  margin-bottom: 8px;
}

.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  filter: drop-shadow(0 6px 16px rgba(59, 130, 246, 0.3));
}

.title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.3px;
  background: var(--brand-gradient-pure);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}

.desc {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 14px;
  margin-bottom: 28px;
}

.tips {
  text-align: right;
  color: var(--text-tertiary);
  font-size: 13px;
  margin-bottom: 16px;
}

.tips a {
  color: var(--brand-primary);
  font-weight: 500;
  text-decoration: none;
}

.tips a:hover {
  color: var(--brand-primary-hover);
  text-decoration: underline;
}

.submit-btn {
  border: none;
  background: var(--brand-gradient-pure);
  height: 44px;
  font-weight: 600;
  letter-spacing: 1px;
  box-shadow: 0 6px 16px rgba(59, 130, 246, 0.3);
  transition: transform var(--transition-base), box-shadow var(--transition-base);
}

.submit-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(59, 130, 246, 0.42);
}

@media (max-width: 480px) {
  .auth-card {
    padding: 32px 24px;
  }
}
</style>
