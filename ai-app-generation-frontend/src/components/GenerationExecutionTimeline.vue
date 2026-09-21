<template>
  <section v-if="entries.length" class="execution-timeline" aria-label="执行记录">
    <details open><summary>执行记录 · {{ entries.length }}</summary>
      <ol><li v-for="entry in entries" :key="entry.id">
        <button type="button" @click="$emit('locate', entry.id)">
          <span class="entry-name">{{ entry.label }}</span><span class="entry-result" :class="entry.result.status">{{ entry.result.label }}</span>
          <code v-if="entry.path">{{ entry.path }}</code>
        </button>
      </li></ol>
    </details>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallView, GenerationStatus } from '@/utils/generationSession'
import { executionEntries } from '@/utils/planPresentation'
const props = defineProps<{ tools: ToolCallView[]; status: GenerationStatus }>()
defineEmits<{ locate: [id: string] }>()
const entries = computed(() => executionEntries(props.tools, props.status))
</script>
<style scoped>
.execution-timeline { border-top: 1px solid var(--border-light); margin-top: 20px; padding-top: 12px; font-size: 12px; }
summary { cursor: pointer; font-weight: 500; padding: 4px 0; }
ol { list-style: none; padding: 0; margin: 10px 0 0; }
li { border-left: 1px solid var(--border-light); padding-left: 8px; }
button { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 4px 12px; text-align: left; width: 100%; background: transparent; border: 0; padding: 8px 4px; cursor: pointer; color: var(--text-primary); font: inherit; }
button:hover { background: var(--bg-mute); }
button:focus-visible { outline: 2px solid var(--brand-primary); }
code { grid-column: 1 / -1; overflow-wrap: anywhere; font-size: 11px; color: var(--text-secondary); }
.entry-result { color: var(--text-secondary); }
.entry-result.APPLIED { color: var(--success, #23804b); }
.entry-result.REJECTED, .entry-result.FAILED, .entry-result.CONFLICT { color: var(--warning, #9e6400); }
</style>
