<template>
  <a-avatar class="user-avatar" :src="defaultUserAvatar" :size="size" alt="用户头像" />
</template>

<script setup lang="ts">
import { defaultUserAvatar } from '@/utils/userDisplay'

withDefaults(defineProps<{ size?: number | 'small' | 'default' | 'large' }>(), {
  size: 'default',
})
</script>

<style scoped>
.user-avatar {
  flex-shrink: 0;
  border-radius: 50%;
  overflow: hidden;
}

.user-avatar :deep(img) {
  object-fit: cover;
  object-position: center;
}
</style>
