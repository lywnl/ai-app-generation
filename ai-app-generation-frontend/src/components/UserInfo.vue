<template>
  <div class="user-info">
    <UserAvatar :size="size" />
    <span v-if="showName" class="user-name">{{ formatUserDisplayName(user?.userName) }}</span>
  </div>
</template>

<script setup lang="ts">
import UserAvatar from '@/components/UserAvatar.vue'
import { formatUserDisplayName } from '@/utils/userDisplay'

interface Props {
  user?: API.UserVO
  size?: number | 'small' | 'default' | 'large'
  showName?: boolean
}

withDefaults(defineProps<Props>(), {
  size: 'default',
  showName: true,
})
</script>

<style scoped>
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.user-name {
  font-size: 14px;
  color: var(--text-primary);
  white-space: nowrap;
}
</style>
