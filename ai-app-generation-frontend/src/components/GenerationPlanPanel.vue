<template>
  <section class="plan-panel" aria-label="当前计划">
    <div class="plan-heading"><h3>当前计划</h3><span v-if="snapshot">第 {{ snapshot.version }} 版</span></div>
    <p v-if="status === 'loading'" role="status">正在读取计划…</p>
    <p v-if="status === 'empty'">尚无计划</p>
    <div v-if="error" class="plan-error" role="status">
      {{ error }}
      <a-button size="small" type="text" aria-label="重新读取计划" @click="$emit('retry')"><ReloadOutlined /></a-button>
    </div>
    <template v-if="snapshot && presentation">
      <p class="plan-summary">{{ snapshot.summary }}</p>
      <div class="plan-status"><span :class="{ warning: snapshot.status === 'REPLAN_PENDING' }">{{ statusLabel }}</span><span v-if="status === 'stale'">待同步</span></div>
      <div class="plan-progress">
        <span>{{ presentation.total ? `已变更 ${presentation.touched} / ${presentation.total}` : '无需文件变更' }}</span>
        <progress v-if="presentation.total" :value="presentation.touched" :max="presentation.total" aria-label="文件变更进度" />
      </div>
      <ul class="plan-files">
        <li v-for="file in presentation.planned" :key="file.path">
          <div class="file-top"><code>{{ file.path }}</code><span>{{ actions[file.action] }}</span></div>
          <p v-if="file.purpose">{{ file.purpose }}</p>
          <div class="file-state" :class="{ touched: file.state === 'TOUCHED' }">{{ file.action === 'KEEP' ? '无需变更' : file.state === 'TOUCHED' ? '已变更' : '待变更' }}<span v-if="file.executing"> · 正在执行</span></div>
          <p v-if="file.waitingFor.length" class="dependency">等待依赖：{{ file.waitingFor.join('、') }}</p>
          <details v-else-if="file.dependsOn.length"><summary>文件依赖</summary><p>{{ file.dependsOn.join('、') }}</p></details>
        </li>
      </ul>
      <template v-if="presentation.outside.length">
        <h4>计划外文件</h4>
        <ul class="plan-files outside"><li v-for="file in presentation.outside" :key="file.path"><code>{{ file.path }}</code><p>不在当前计划内</p></li></ul>
      </template>
      <details v-if="snapshot.history.length" class="plan-history"><summary>计划修订 · {{ snapshot.history.length }}</summary>
        <ol><li v-for="(change, index) in snapshot.history" :key="index"><strong>第 {{ change.version }} 版</strong><p>{{ change.reason || '未记录修订原因' }}</p></li></ol>
      </details>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import type { PlanSnapshot } from '@/utils/planSnapshot'
import type { PlanSyncStatus } from '@/composables/useGenerationPlan'
import type { ToolCallView } from '@/utils/generationSession'
import { presentPlan } from '@/utils/planPresentation'
const props = defineProps<{ snapshot: PlanSnapshot | null; status: PlanSyncStatus; error: string; tools: ToolCallView[]; active: boolean }>()
defineEmits<{ retry: [] }>()
const actions = { CREATE: '新建', MODIFY: '修改', DELETE: '删除', KEEP: '保留' }
const presentation = computed(() => props.snapshot ? presentPlan(props.snapshot, props.tools, props.active) : null)
const statusLabel = computed(() => props.snapshot ? {
  PLANNED: props.active ? '计划执行中' : '计划未完成', REPLAN_PENDING: '计划待调整', READY_TO_BUILD: '待构建', BUILT: '已构建',
}[props.snapshot.status] : '')
</script>

<style scoped>
.plan-panel { min-width: 0; font-size: 13px; color: var(--text-primary); }
.plan-heading, .plan-status, .file-top { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.plan-heading h3 { font-size: 15px; margin: 0; }
.plan-heading span, .plan-status, .file-top > span, .file-state, details { font-size: 12px; color: var(--text-secondary); }
.plan-summary { margin: 14px 0 10px; font-weight: 500; }
p, code, li { overflow-wrap: anywhere; }
.plan-progress { margin: 18px 0 8px; display: grid; gap: 8px; }
progress { width: 100%; height: 5px; accent-color: var(--brand-primary); }
.plan-files { list-style: none; margin: 0; padding: 0; }
.plan-files li { padding: 12px 0; border-bottom: 1px solid var(--border-light); }
.file-top code { min-width: 0; font-size: 12px; }
.file-top > span { flex-shrink: 0; }
.plan-files p { margin: 6px 0; color: var(--text-secondary); font-size: 12px; }
.file-state.touched { color: var(--success, #23804b); }
.warning, .dependency, .replan-hint, .plan-error { color: var(--warning, #9e6400); }
.replan-hint { padding-left: 10px; border-left: 2px solid currentColor; }
h4 { font-size: 13px; margin: 18px 0 0; }
summary { cursor: pointer; padding: 8px 0; }
.plan-history { margin-top: 16px; }
.plan-history ol { padding-left: 18px; }
</style>
