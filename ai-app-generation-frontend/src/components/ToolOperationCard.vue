<template>
  <div class="tool-operation-card" :data-tool-key="toolKey" :data-tool-status="result.status">
    <div class="operation-header">
      <a-tooltip :title="state.expanded ? '收起详情' : '展开详情'">
        <button
          class="operation-toggle" type="button"
          :aria-label="`${state.expanded ? '收起' : '展开'}${label}详情`"
          :aria-expanded="state.expanded" :aria-controls="detailsId"
          @click="updateState({ expanded: !state.expanded })"
        ><DownOutlined v-if="state.expanded" /><RightOutlined v-else /></button>
      </a-tooltip>
      <span class="operation-label">{{ label }}</span>
      <span class="operation-target" :title="target">{{ target }}</span>
      <span class="operation-status" :class="result.status">
        <LoadingOutlined v-if="result.status === 'streaming'" />{{ result.label }}
      </span>
    </div>
    <div v-if="state.expanded" :id="detailsId" class="operation-details">
      <div v-if="view.name !== 'buildProject'" class="operation-full-path">{{ target }}</div>
      <p class="operation-result">{{ result.message }}</p>
      <div v-if="result.plan" class="operation-plan-summary">
        <p>第 {{ result.plan.version }} 版：{{ result.plan.summary }}</p>
        <ul v-if="result.plan.files.length"><li v-for="file in result.plan.files" :key="file.path"><code>{{ file.path }}</code></li></ul>
      </div>
      <template v-if="view.name === 'buildProject' && view.build">
        <div v-if="view.build.stage" class="operation-build-stage" :title="view.build.stage">
          阶段：{{ buildStageLabel }}
        </div>
        <details v-if="view.build.errorSummary" class="operation-build-error">
          <summary>查看错误摘要</summary>
          <pre class="operation-error-summary">{{ view.build.errorSummary }}</pre>
        </details>
      </template>
      <template v-if="hasCode">
        <div class="operation-code-header">
          <a-radio-group
            v-if="view.name === 'modifyFile'" size="small" :value="state.codeVersion"
            @update:value="updateState({ codeVersion: $event })"
          >
            <a-radio-button value="before">修改前</a-radio-button>
            <a-radio-button value="after">修改后</a-radio-button>
          </a-radio-group>
          <span class="operation-code-label">{{ codeLabel }}</span>
        </div>
        <pre class="operation-code"><code><template v-if="renderedTokens"><span v-for="(token, index) in renderedTokens" :key="index" :class="[token.syntax ? `code-${token.syntax}` : undefined, token.change ? `code-${token.change}` : undefined]">{{ token.text }}</span></template><template v-else>{{ displayedCode }}</template></code></pre>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, useId } from 'vue'
import { DownOutlined, RightOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import type { GenerationStatus, ToolCallView, ToolCardState } from '@/utils/generationSession'
import { getToolOperationCode, getToolOperationResult, getToolOperationTarget, TOOL_OPERATION_LABELS } from '@/utils/toolOperationDisplay'
import { createModifyCodePresenter } from '@/utils/modifyCodePresentation'

const props = defineProps<{
  toolKey: string
  view: ToolCallView
  sessionStatus: GenerationStatus
  state: ToolCardState
}>()
const emit = defineEmits<{ 'update:state': [state: ToolCardState] }>()
const detailsId = useId()
const label = computed(() => TOOL_OPERATION_LABELS[props.view.name] ?? props.view.name)
const target = computed(() => getToolOperationTarget(props.view))
const result = computed(() => getToolOperationResult(props.view, props.sessionStatus))
const hasCode = computed(() => props.view.name === 'writeFile' || props.view.name === 'modifyFile')
// 仅在展开的详情读取此计算值，不为收起的文件解析代码。
const code = computed(() => getToolOperationCode(props.view))
const presentModifyCode = createModifyCodePresenter()
const modification = computed(() => props.state.expanded && props.view.name === 'modifyFile' && props.view.status === 'done'
  ? presentModifyCode(code.value.before, code.value.after, target.value) : undefined)
const currentPresentation = computed(() => modification.value?.[props.state.codeVersion])
const renderedTokens = computed(() => {
  const presentation = currentPresentation.value
  if (!presentation) return undefined
  const tokens = presentation.preview.available ? presentation.previewTokens : presentation.source
  return tokens?.length ? tokens : undefined
})
const displayedCode = computed(() => {
  if (currentPresentation.value?.preview.available) {
    return currentPresentation.value.preview.code || '暂无代码内容'
  }
  const value = props.view.name === 'modifyFile' && props.state.codeVersion === 'before'
    ? code.value.before : code.value.after
  if (value !== undefined && value.length > 0) return value
  return props.sessionStatus === 'streaming' && props.view.status === 'streaming'
    ? '正在生成代码…' : '暂无代码内容'
})
const codeLabel = computed(() => props.sessionStatus === 'streaming' && props.view.status === 'streaming'
  ? '正在生成代码' : '代码内容')
const BUILD_STAGE_LABELS: Record<string, string> = {
  VALIDATION: '配置检查', NPM_INSTALL: '安装依赖', NPM_BUILD: '编译打包',
  DIST_CHECK: '产物检查', SUCCESS: '完成',
}
const buildStageLabel = computed(() => {
  const stage = props.view.build?.stage
  return stage ? BUILD_STAGE_LABELS[stage] ?? stage : ''
})
function updateState(patch: Partial<ToolCardState>) {
  emit('update:state', { ...props.state, ...patch })
}
</script>

<style scoped>
.tool-operation-card {
  margin: 8px 0;
  border: 1px solid var(--border-light);
  border-radius: 6px;
  background: var(--bg-soft);
  min-width: 0;
}
.operation-header {
  display: grid;
  grid-template-columns: 28px auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  height: 42px;
  padding: 0 8px;
  font-size: 12px;
}
.operation-toggle {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.operation-toggle:hover { background: var(--bg-mute); }
.operation-toggle:focus-visible { outline: 2px solid var(--brand-primary); }
.operation-label { white-space: nowrap; font-weight: 500; }
.operation-target {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}
.operation-status {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  width: 80px;
  white-space: nowrap;
  color: var(--text-secondary);
}
.operation-status.APPLIED { color: var(--success); }
.operation-status.FAILED, .operation-status.REJECTED { color: var(--warning); }
.operation-details { padding: 4px 12px 12px; border-top: 1px solid var(--border-light); }
.operation-full-path, .operation-result { overflow-wrap: anywhere; font-size: 12px; }
.operation-plan-summary { font-size: 12px; overflow-wrap: anywhere; }
.operation-plan-summary ul { padding-left: 16px; }
.operation-full-path { color: var(--text-secondary); margin-top: 8px; }
.operation-result { margin: 8px 0; }
.operation-build-stage { font-size: 12px; color: var(--text-secondary); }
.operation-build-error { margin-top: 8px; font-size: 12px; }
.operation-build-error summary { cursor: pointer; }
.operation-error-summary {
  max-height: 180px;
  overflow: auto;
  margin: 8px 0 0;
  padding: 10px;
  border: 1px solid var(--border-light);
  border-radius: 4px;
  background: var(--bg-mute);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.operation-code-header { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.operation-code-label { font-size: 12px; color: var(--text-secondary); }
.code-keyword, .code-literal { color: #a62636; }
.code-string, .code-attribute { color: #246b40; }
.code-number, .code-title { color: #215ca8; }
.code-comment { color: #66736c; }
.code-tag { color: #7a3f83; }
.code-added { background: #dcfce7; box-shadow: inset 0 -1px #38975e; }
.code-removed { background: #fee2e2; box-shadow: inset 0 -1px #c35a5a; }
.operation-code {
  box-sizing: border-box;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: visible;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border-light);
  border-radius: 4px;
  background: var(--bg-mute);
  color: var(--text-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  overflow-wrap: normal;
  word-break: normal;
  overscroll-behavior-x: contain;
}
@media (max-width: 768px) {
  .operation-header { padding: 0 4px; gap: 4px; }
  .operation-details { padding: 4px 8px 8px; }
}
@media (prefers-reduced-motion: reduce) {
  .operation-status :deep(.anticon-spin) { animation: none; }
}
</style>
