<template>
  <a-modal v-model:open="visible" title="部署成功" :footer="null" width="600px">
    <div class="deploy-success">
      <div class="success-badge">
        <CheckCircleOutlined class="success-icon" />
      </div>
      <h3>网站部署成功！</h3>
      <p>你的网站已经成功部署，可以通过以下链接访问：</p>
      <div class="deploy-url">
        <a-input :value="deployUrl" readonly>
          <template #suffix>
            <a-button type="text" @click="handleCopyUrl" aria-label="复制链接">
              <CopyOutlined />
            </a-button>
          </template>
        </a-input>
      </div>
      <div class="deploy-actions">
        <a-button type="primary" @click="handleOpenSite" class="primary-btn">访问网站</a-button>
        <a-button @click="handleClose">关闭</a-button>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { message } from 'ant-design-vue'
import { CheckCircleOutlined, CopyOutlined } from '@ant-design/icons-vue'

interface Props {
  open: boolean
  deployUrl: string
}

interface Emits {
  (e: 'update:open', value: boolean): void
  (e: 'open-site'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const visible = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const handleCopyUrl = async () => {
  try {
    await navigator.clipboard.writeText(props.deployUrl)
    message.success('链接已复制到剪贴板')
  } catch (error) {
    console.error('复制失败：', error)
    message.error('复制失败')
  }
}

const handleOpenSite = () => {
  emit('open-site')
}

const handleClose = () => {
  visible.value = false
}
</script>

<style scoped>
.deploy-success {
  text-align: center;
  padding: 24px;
}

.success-badge {
  width: 80px;
  height: 80px;
  margin: 0 auto 20px;
  border-radius: var(--radius-pill);
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(59, 130, 246, 0.12) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  box-shadow: 0 8px 24px rgba(16, 185, 129, 0.18);
}

.success-badge::before {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: var(--radius-pill);
  border: 2px solid rgba(16, 185, 129, 0.25);
  pointer-events: none;
}

.success-icon {
  color: var(--success);
  font-size: 44px;
}

.deploy-success h3 {
  margin: 0 0 16px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}

.deploy-success p {
  margin: 0 0 24px;
  color: var(--text-secondary);
}

.deploy-url {
  margin-bottom: 24px;
}

.deploy-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.primary-btn {
  border: none;
  background: var(--brand-gradient-pure);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
  transition: transform var(--transition-base), box-shadow var(--transition-base);
}

.primary-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(59, 130, 246, 0.42);
}
</style>
