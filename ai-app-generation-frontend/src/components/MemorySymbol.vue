<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.memory" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 17 })
const paths: Record<string, string> = {
  memory:
    'M9 4h7a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3Zm1 4h5m-5 4h5m-5 4h3M3 8h3m-3 4h3m-3 4h3',
  history: 'M3 11a9 9 0 1 1 2.5 7M3 4v7h7m2-4v5l3 2',
  plus: 'M12 5v14M5 12h14',
  search: 'M17 10.5a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0Zm-1 5.5 4.5 4.5',
  globe: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18',
  visual:
    'M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1.7-3.1 1.8 1.8 0 0 1 1.5-2.9H18a3 3 0 0 0 3-3 9 9 0 0 0-9-9ZM7 10h.1M11 7h.1M15.5 8h.1',
  tech: 'm8 7-5 5 5 5m8-10 5 5-5 5m-3-13-2 16',
  experience:
    'M5 4v7m0 4v5M12 4v2m0 4v10M19 4v10m0 4v2M7 13a2 2 0 1 1-4 0 2 2 0 0 1 4 0Zm7-5a2 2 0 1 1-4 0 2 2 0 0 1 4 0Zm7 8a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z',
  info: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-1v6m0-10v.5',
  shield: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Zm-4 9 3 3 5-6',
  chat: 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5h-1L5 22l1-5A8.5 8.5 0 1 1 21 11.5ZM8 10h8m-8 4h5',
  swap: 'M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4',
  empty:
    'M7 4h10a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm2 5h6m-6 4h6m-6 4h3',
}
</script>
