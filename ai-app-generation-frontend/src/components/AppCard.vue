<template>
  <div class="app-card" :class="{ 'app-card--featured': featured }">
    <div class="app-preview">
      <img v-if="app.cover" :src="normalizeCoverUrl(app.cover)" :alt="app.appName" />
      <div v-else class="app-placeholder" aria-hidden="true">
        <svg viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <rect x="12" y="20" width="40" height="32" rx="4" />
          <circle cx="24" cy="34" r="2" fill="currentColor" />
          <circle cx="40" cy="34" r="2" fill="currentColor" />
          <line x1="32" y1="20" x2="32" y2="12" />
          <circle cx="32" cy="10" r="2" fill="currentColor" />
          <line x1="22" y1="44" x2="42" y2="44" />
        </svg>
      </div>
      <div class="app-overlay">
        <a-space>
          <a-button type="primary" @click="handleViewChat">查看对话</a-button>
          <a-button v-if="app.deployKey" type="default" @click="handleViewWork">查看作品</a-button>
        </a-space>
      </div>
    </div>
    <div class="app-info">
      <div class="app-info-left">
        <a-avatar :src="app.user?.userAvatar" :size="40">
          {{ app.user?.userName?.charAt(0) || 'U' }}
        </a-avatar>
      </div>
      <div class="app-info-right">
        <h3 class="app-title">{{ app.appName || '未命名应用' }}</h3>
        <p class="app-author">
          {{ app.user?.userName || (featured ? '官方' : '未知用户') }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface Props {
  app: API.AppVO
  featured?: boolean
}

interface Emits {
  (e: 'view-chat', appId: string | number | undefined): void
  (e: 'view-work', app: API.AppVO): void
}

const props = withDefaults(defineProps<Props>(), {
  featured: false,
})

const emit = defineEmits<Emits>()

const normalizeCoverUrl = (cover?: string) => {
  if (!cover) {
    return ''
  }
  const trimmed = cover.trim()
  if (!trimmed) {
    return ''
  }
  if (/^https?:\/\//i.test(trimmed) || trimmed.startsWith('data:') || trimmed.startsWith('blob:')) {
    return trimmed
  }
  return `https://${trimmed.replace(/^\/+/, '')}`
}

const handleViewChat = () => {
  emit('view-chat', props.app.id)
}

const handleViewWork = () => {
  emit('view-work', props.app)
}
</script>

<style scoped>
.app-card {
  background: #ffffff;
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-md);
  border: 1px solid var(--border-light);
  transition: transform var(--transition-slow), box-shadow var(--transition-slow), border-color var(--transition-slow);
  cursor: pointer;
  /* 滚动性能:把每张卡片提升为独立合成层并隔离 paint,
     避免滚动时与父级伪元素动画共享重绘区域 */
  contain: layout style paint;
  transform: translateZ(0);
  will-change: transform;
}

.app-card:hover {
  transform: translateY(-8px);
  box-shadow: var(--shadow-lg);
  border-color: rgba(59, 130, 246, 0.25);
}

.app-preview {
  height: 180px;
  background: linear-gradient(135deg, var(--bg-soft) 0%, var(--bg-mute) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  position: relative;
}

.app-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.app-placeholder {
  color: var(--text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
}

.app-placeholder svg {
  width: 64px;
  height: 64px;
}

.app-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(0deg, rgba(15, 23, 42, 0.85) 0%, rgba(59, 130, 246, 0.4) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity var(--transition-slow);
}

.app-card:hover .app-overlay {
  opacity: 1;
}

.app-info {
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  border-top: 1px solid var(--border-light);
}

.app-info-left {
  flex-shrink: 0;
}

.app-info-right {
  flex: 1;
  min-width: 0;
}

.app-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 4px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-author {
  font-size: 14px;
  color: var(--text-tertiary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
