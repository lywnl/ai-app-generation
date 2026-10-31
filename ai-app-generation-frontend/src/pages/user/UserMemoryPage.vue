<template>
  <div id="userMemoryPage" class="memory-page memory-theme">
    <main>
      <div class="page-heading">
        <div class="heading-copy">
          <div class="page-icon"><MemorySymbol name="memory" :size="26" /></div>
          <div>
            <h1>长期记忆</h1>
            <p class="subtitle">管理个人偏好，应用于你创建的所有应用。</p>
          </div>
        </div>
        <div class="heading-actions">
          <button class="button" @click="openHistory">
            <MemorySymbol name="history" />变更历史
          </button>
          <button class="button primary" :disabled="busy" @click="openEditor()">
            <MemorySymbol name="plus" />新增记忆
          </button>
        </div>
      </div>
      <section class="panel" aria-label="长期记忆管理">
        <div class="tab-bar" role="tablist" aria-label="记忆状态">
          <button
            id="memory-active-tab"
            class="tab"
            role="tab"
            :aria-selected="query.status === 'ACTIVE'"
            :tabindex="query.status === 'ACTIVE' ? 0 : -1"
            aria-controls="memory-panel"
            @click="switchTab('ACTIVE')"
            @keydown="tabKeydown"
          >
            已生效<span class="count">{{ activeCount }}</span>
          </button>
          <button
            id="memory-candidate-tab"
            class="tab pending"
            role="tab"
            :aria-selected="query.status === 'CANDIDATE'"
            :tabindex="query.status === 'CANDIDATE' ? 0 : -1"
            aria-controls="memory-panel"
            @click="switchTab('CANDIDATE')"
            @keydown="tabKeydown"
          >
            待确认<span class="count">{{ pendingCount }}</span>
          </button>
          <span class="scope-hint"><MemorySymbol name="globe" :size="15" />跨应用使用</span>
        </div>
        <div
          id="memory-panel"
          role="tabpanel"
          :aria-labelledby="
            query.status === 'ACTIVE' ? 'memory-active-tab' : 'memory-candidate-tab'
          "
          :aria-busy="loading"
        >
          <div class="toolbar">
            <div class="filters" aria-label="按类别筛选">
              <button
                v-for="item in ['', ...MEMORY_CATEGORIES]"
                :key="item"
                class="filter"
                :aria-pressed="query.category === item"
                @click="memories.setQuery({ category: item })"
              >
                {{ item || '全部' }}
              </button>
            </div>
            <label class="search"
              ><MemorySymbol name="search" :size="16" /><input
                v-model="searchText"
                type="search"
                maxlength="128"
                placeholder="搜索偏好名称或内容"
                aria-label="搜索偏好名称或内容"
                @input="searchChanged"
            /></label>
          </div>
          <div v-if="query.status === 'CANDIDATE'" class="notice">
            <MemorySymbol name="info" :size="16" /><span
              >这些偏好尚未用于生成。采用时，如果同一项已有偏好，会请你确认替换。</span
            >
          </div>
          <div class="list-head" aria-hidden="true">
            <span>记忆内容</span><span>类别</span><span class="date-heading">最近更新</span
            ><span>操作</span>
          </div>
          <div v-if="loading" class="loading-state" role="status">
            <a-spin /><span>正在加载记忆…</span>
          </div>
          <div v-else-if="error" class="empty error-state" role="alert">
            <MemorySymbol name="info" :size="28" />
            <h3>{{ error }}</h3>
            <button class="button" @click="memories.reload">重新加载</button>
          </div>
          <div
            v-else-if="records.length"
            class="memory-list"
            role="list"
            :aria-label="query.status === 'ACTIVE' ? '已生效记忆' : '待确认记忆'"
          >
            <article
              v-for="row in records"
              :key="row.id"
              class="memory-row"
              :class="{ 'candidate-row': row.status === 'CANDIDATE' }"
              role="listitem"
            >
              <div class="memory-description">
                <span class="memory-icon"
                  ><MemorySymbol :name="categoryStyles[row.category][1]" :size="18"
                /></span>
                <div class="memory-body">
                  <h3 class="memory-title">{{ row.attributeName }}</h3>
                  <div class="memory-content">{{ row.content }}</div>
                  <div v-if="row.status === 'CANDIDATE'" class="source-line">
                    {{
                      row.requiresConfirmation ? '手动保存，等待你确认采用' : '来自聊天中的偏好线索'
                    }}
                  </div>
                  <div
                    v-if="row.activeMemory && row.activeMemory.content !== row.content"
                    class="conflict"
                  >
                    <MemorySymbol name="swap" :size="13" /><span
                      >当前已生效：{{ row.activeMemory.content }}</span
                    >
                  </div>
                </div>
              </div>
              <span class="category" :class="categoryStyles[row.category][0]">{{
                row.category
              }}</span>
              <time class="date" :datetime="row.updateTime" :title="formatTime(row.updateTime)">{{
                relativeTime(row.updateTime)
              }}</time>
              <div class="row-actions">
                <template v-if="row.status === 'ACTIVE'">
                  <button
                    class="text-button"
                    :aria-label="`编辑${row.attributeName}`"
                    :disabled="busy"
                    @click="openEditor(row)"
                  >
                    编辑
                  </button>
                  <button
                    class="text-button muted"
                    :aria-label="`撤销${row.attributeName}`"
                    :disabled="busy"
                    @click="openConfirmation('REVOKE', row)"
                  >
                    撤销
                  </button>
                </template>
                <template v-else>
                  <button
                    class="button primary small"
                    :aria-label="`采用${row.attributeName}候选`"
                    :disabled="busy"
                    @click="openConfirmation('ADOPT', row)"
                  >
                    采用
                  </button>
                  <button
                    class="text-button muted"
                    :aria-label="`取消${row.attributeName}候选`"
                    :disabled="busy"
                    @click="openConfirmation('CANCEL', row)"
                  >
                    取消
                  </button>
                </template>
              </div>
            </article>
          </div>
          <div v-else class="empty">
            <div class="empty-icon"><MemorySymbol name="empty" :size="27" /></div>
            <h3>
              {{
                query.keyword || query.category
                  ? '没有找到匹配的记忆'
                  : query.status === 'CANDIDATE'
                    ? '没有待确认的记忆'
                    : '还没有已生效的记忆'
              }}
            </h3>
            <p>
              {{
                query.keyword || query.category
                  ? '试试其他关键词，或切换到全部类别。'
                  : query.status === 'CANDIDATE'
                    ? '聊天中发现的偏好线索，会在这里等待进一步确认。'
                    : '点击右上角“新增记忆”，记录你的第一个偏好。'
              }}
            </p>
          </div>
          <div class="panel-footer">
            <span>共 {{ total }} 条{{ query.status === 'ACTIVE' ? '已生效' : '待确认' }}记忆</span>
            <a-pagination
              v-if="total > query.pageSize"
              size="small"
              :current="query.pageNum"
              :page-size="query.pageSize"
              :total="total"
              :show-size-changer="false"
              @change="changePage"
            />
            <span v-else class="footer-principle"
              ><MemorySymbol name="shield" :size="15" />{{
                query.status === 'ACTIVE'
                  ? '生成时参考已生效记忆，当前对话的明确要求优先。'
                  : '新增或取消候选，都不会改变已生效的记忆。'
              }}</span
            >
          </div>
        </div>
      </section>
      <div class="below-panel">
        <div class="chat-tip">
          <MemorySymbol name="chat" :size="16" /><span
            >也可以在聊天中说：“记住，以后页面默认使用中文。”</span
          >
        </div>
      </div>
    </main>

    <a-modal
      :open="editorOpen"
      :title="editingRow ? '编辑记忆' : '新增记忆'"
      :width="510"
      :footer="null"
      :mask-closable="!busy"
      :keyboard="!busy"
      :closable="!busy"
      @cancel="closeEditor"
    >
      <form class="memory-theme memory-editor" @submit.prevent="saveMemory">
        <p class="dialog-description">
          {{ editingRow ? '修改这项偏好，应用于之后的生成。' : '每条记忆只表达一个明确的偏好。' }}
        </p>
        <div class="field-row">
          <div class="field">
            <label for="memory-category">类别</label
            ><select id="memory-category" v-model="form.category" :disabled="!!editingRow || busy">
              <option v-for="item in MEMORY_CATEGORIES" :key="item">{{ item }}</option>
            </select>
          </div>
          <div v-if="!editingRow" class="field">
            <label for="memory-save-mode">保存方式</label
            ><select id="memory-save-mode" v-model="form.saveMode" :disabled="busy">
              <option value="ACTIVE">立即生效</option>
              <option value="CANDIDATE">先存为待确认</option>
            </select>
          </div>
        </div>
        <div class="field">
          <label for="memory-attribute">偏好名称</label
          ><input
            id="memory-attribute"
            v-model="form.attributeName"
            :disabled="!!editingRow || busy"
            list="memory-attribute-hints"
            maxlength="128"
            placeholder="例如：页面默认语言、卡片圆角"
            required
            autocomplete="off"
          /><datalist id="memory-attribute-hints">
            <option v-for="item in attributeHints" :key="item" :value="item" />
          </datalist>
        </div>
        <div class="field">
          <label for="memory-content">偏好内容</label
          ><textarea
            id="memory-content"
            v-model="form.content"
            :disabled="busy"
            maxlength="1000"
            placeholder="例如：页面默认使用简体中文"
            required
          />
          <div class="form-help">
            {{
              editingRow
                ? '修改前的内容会保留在变更历史中。'
                : form.saveMode === 'CANDIDATE'
                  ? '暂不用于生成，需要你之后明确确认采用。'
                  : '保存后，会作为你跨应用的通用偏好。'
            }}
          </div>
        </div>
        <div v-if="previewLoading" class="form-help" role="status">正在检查同名偏好…</div>
        <div v-else-if="previewError" class="form-error" role="alert">
          {{ previewError }}
          <button class="text-button" type="button" @click="refreshPreview">重试检查</button>
        </div>
        <div
          v-else-if="!editingRow && form.saveMode === 'ACTIVE' && preview?.activeMemory"
          class="form-message"
        >
          已经有“{{ preview.attributeName }}”：{{
            preview.activeMemory.content
          }}。保存后会替换这一项，原内容保留在历史中。
        </div>
        <p v-if="editorError" class="form-error" role="alert">{{ editorError }}</p>
        <div class="dialog-footer">
          <button type="button" class="button" :disabled="busy" @click="closeEditor">取消</button
          ><button
            type="submit"
            class="button primary"
            :disabled="busy || previewLoading || !!previewError"
          >
            {{
              busy
                ? '正在保存…'
                : editingRow
                  ? '保存修改'
                  : form.saveMode === 'CANDIDATE'
                    ? '保存为待确认'
                    : preview?.activeMemory
                      ? '替换并保存'
                      : '保存记忆'
            }}
          </button>
        </div>
      </form>
    </a-modal>

    <a-modal
      :open="!!confirmation"
      :title="confirmationTitle"
      :width="510"
      :footer="null"
      :mask-closable="!busy"
      :keyboard="!busy"
      :closable="!busy"
      @cancel="closeConfirmation"
    >
      <div v-if="confirmation" class="memory-theme">
        <p class="dialog-description">
          {{
            confirmation.action === 'ADOPT'
              ? `确认将“${confirmation.row.attributeName}”设为跨应用的通用偏好。`
              : `${confirmation.row.attributeName}：${confirmation.row.content}`
          }}
        </p>
        <div v-if="confirmation.action === 'ADOPT'" class="comparison">
          <div class="comparison-line">
            <span class="comparison-label">当前偏好</span
            ><span class="comparison-value">{{
              confirmation.row.activeMemory?.content || '尚未设置'
            }}</span>
          </div>
          <div class="comparison-line">
            <span class="comparison-label">采用后</span
            ><span class="comparison-value">{{ confirmation.row.content }}</span>
          </div>
        </div>
        <p class="confirm-footnote">
          {{
            confirmation.action === 'CANCEL'
              ? '只取消这条待确认内容，不影响已生效的记忆。'
              : confirmation.action === 'REVOKE'
                ? '撤销后不再用于生成，历史记录仍会保留。'
                : '旧偏好会保留在变更历史中，其他偏好不受影响。'
          }}
        </p>
        <p v-if="confirmationError" class="form-error" role="alert">{{ confirmationError }}</p>
        <div class="dialog-footer">
          <button class="button" :disabled="busy" @click="closeConfirmation">再想想</button
          ><button
            class="button"
            :class="confirmation.action === 'REVOKE' ? 'danger' : 'primary'"
            :disabled="busy"
            @click="confirmChange"
          >
            {{
              busy
                ? '正在处理…'
                : confirmation.action === 'ADOPT'
                  ? '确认采用'
                  : confirmation.action === 'REVOKE'
                    ? '确认撤销'
                    : '确认取消'
            }}
          </button>
        </div>
      </div>
    </a-modal>

    <a-drawer
      title="变更历史"
      :open="historyOpen"
      width="min(470px, 100vw)"
      @close="historyOpen = false"
    >
      <div class="memory-theme">
        <p class="dialog-description">修改前的内容与候选处理记录都保留在这里。</p>
        <div v-if="historyLoading" class="loading-state" role="status">
          <a-spin /><span>正在加载历史记录…</span>
        </div>
        <div v-else-if="historyError" class="empty" role="alert">
          <p>{{ historyError }}</p>
          <button class="button" @click="memories.loadHistory(historyPage)">重新加载</button>
        </div>
        <div v-else-if="historyEntries.length" class="history-body">
          <template v-for="entry in historyEntries" :key="entry.id"
            ><article
              v-for="(change, index) in entry.changes"
              :key="`${entry.id}-${index}`"
              class="history-item"
              :class="{ 'pending-event': change.targetKind === 'CANDIDATE' }"
            >
              <span class="history-dot"
                ><MemorySymbol
                  :name="change.targetKind === 'CANDIDATE' ? 'info' : 'history'"
                  :size="14"
              /></span>
              <div class="history-meta">
                <span>{{
                  entry.sourceType === 'MANUAL'
                    ? '手动操作'
                    : entry.sourceType === 'CHAT'
                      ? '聊天中更新'
                      : '此前保存'
                }}</span
                ><time>{{ formatTime(entry.createTime) }}</time>
              </div>
              <h3 class="history-title">{{ historyTitle(change) }}</h3>
              <div class="history-values">
                <div v-if="change.beforeContent" class="old-value">{{ change.beforeContent }}</div>
                <div v-if="change.afterContent" class="new-value">{{ change.afterContent }}</div>
              </div>
              <div class="history-caption">{{ historyCaption(change) }}</div>
            </article></template
          >
        </div>
        <div v-else class="empty">
          <MemorySymbol name="history" :size="28" />
          <h3>还没有变更记录</h3>
          <p>保存或修改记忆后，会在这里留下记录。</p>
        </div>
        <a-pagination
          v-if="historyTotal > 10"
          size="small"
          :current="historyPage"
          :page-size="10"
          :total="historyTotal"
          :show-size-changer="false"
          @change="memories.loadHistory"
        />
      </div>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import MemorySymbol from '@/components/MemorySymbol.vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { useUserMemories } from '@/composables/useUserMemories'
import {
  MEMORY_CATEGORIES,
  MemoryApiError,
  userMemoryApi,
  type MemoryCategory,
  type MemoryHistoryChange,
  type MemoryMutation,
  type MemoryPreview,
  type MemoryStatus,
  type MemoryVersion,
  type UserMemory,
} from '@/api/userMemory'

const router = useRouter(),
  login = useLoginUserStore(),
  memories = useUserMemories()
const {
  query,
  records,
  total,
  activeCount,
  pendingCount,
  loading,
  error,
  busy,
  accessLost,
  history: historyEntries,
  historyTotal,
  historyPage,
  historyLoading,
  historyError,
} = memories
const searchText = ref(''),
  historyOpen = ref(false),
  editorOpen = ref(false),
  editingRow = ref<UserMemory | null>(null)
const editorError = ref(''),
  preview = ref<MemoryPreview | null>(null),
  previewLoading = ref(false),
  previewError = ref('')
const form = reactive({
  category: '语言偏好' as MemoryCategory,
  attributeName: '',
  content: '',
  saveMode: 'ACTIVE' as MemoryStatus,
})
const confirmation = ref<{ action: 'ADOPT' | 'REVOKE' | 'CANCEL'; row: UserMemory } | null>(null),
  confirmationError = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined,
  previewTimer: ReturnType<typeof setTimeout> | undefined
let previewAbort: AbortController | null = null,
  previewVersion = 0,
  previewSignature = ''
const categoryStyles: Record<MemoryCategory, [string, string]> = {
  语言偏好: ['language', 'globe'],
  视觉风格: ['visual', 'visual'],
  技术栈倾向: ['tech', 'tech'],
  体验偏好: ['experience', 'experience'],
}
const attributeHints = computed(() => [
  ...new Set(
    records.value.filter((row) => row.category === form.category).map((row) => row.attributeName),
  ),
])
const confirmationTitle = computed(() =>
  !confirmation.value
    ? ''
    : confirmation.value.action === 'REVOKE'
      ? '撤销这条记忆？'
      : confirmation.value.action === 'CANCEL'
        ? '取消这条候选？'
        : confirmation.value.row.activeMemory &&
            confirmation.value.row.activeMemory.content !== confirmation.value.row.content
          ? '采用并替换这项偏好？'
          : '采用这条候选？',
)
const version = (row: MemoryVersion): MemoryVersion => ({ id: row.id, revision: row.revision })
const snapshot = (row: UserMemory): UserMemory => ({
  ...row,
  activeMemory: row.activeMemory ? { ...row.activeMemory } : null,
})

watch(
  () => login.loginUser.id,
  (value) => {
    editorOpen.value = false
    confirmation.value = null
    historyOpen.value = false
    preview.value = null
    form.content = ''
    form.attributeName = ''
    searchText.value = ''
    query.keyword = ''
    query.category = ''
    memories.setUser(value ? String(value) : '')
  },
  { immediate: true },
)
watch(accessLost, (value) => {
  if (value) goToLogin()
})
function goToLogin() {
  login.setLoginUser({ userName: '未登录' })
  void router.replace({ path: '/user/login', query: { redirect: '/user/memory' } })
}
function errorText(cause: unknown) {
  if (cause instanceof MemoryApiError && cause.accessLost) goToLogin()
  return cause instanceof MemoryApiError ? cause.message : '操作暂未完成，请重试'
}
function searchChanged() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    void memories.setQuery({ keyword: searchText.value.trim() })
  }, 250)
}
function changePage(page: number) {
  void memories.setQuery({ pageNum: page })
}
function switchTab(status: MemoryStatus) {
  clearTimeout(searchTimer)
  searchText.value = ''
  void memories.setQuery({ status, category: '', keyword: '' })
}
async function tabKeydown(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const status =
    event.key === 'Home'
      ? 'ACTIVE'
      : event.key === 'End'
        ? 'CANDIDATE'
        : query.status === 'ACTIVE'
          ? 'CANDIDATE'
          : 'ACTIVE'
  switchTab(status)
  await nextTick()
  document
    .getElementById(status === 'ACTIVE' ? 'memory-active-tab' : 'memory-candidate-tab')
    ?.focus()
}
function openHistory() {
  historyOpen.value = true
  void memories.loadHistory()
}
async function openEditor(row: UserMemory | null = null) {
  editingRow.value = row ? snapshot(row) : null
  editorError.value = ''
  preview.value = null
  previewError.value = ''
  previewSignature = ''
  form.category = row?.category || (query.category as MemoryCategory) || '语言偏好'
  form.attributeName = row?.attributeName || ''
  form.content = row?.content || ''
  form.saveMode = query.status
  editorOpen.value = true
  await nextTick()
  document.getElementById(row ? 'memory-content' : 'memory-attribute')?.focus()
}
function closeEditor() {
  if (!busy.value) editorOpen.value = false
}
watch(
  () => [editorOpen.value, editingRow.value?.id, form.category, form.attributeName, form.saveMode],
  () => {
    clearTimeout(previewTimer)
    previewAbort?.abort()
    previewVersion++
    previewSignature = ''
    preview.value = null
    previewError.value = ''
    previewLoading.value = false
    if (
      editorOpen.value &&
      !editingRow.value &&
      form.saveMode === 'ACTIVE' &&
      form.attributeName.trim()
    ) {
      previewLoading.value = true
      previewTimer = setTimeout(() => {
        void refreshPreview()
      }, 250)
    }
  },
)
async function refreshPreview() {
  clearTimeout(previewTimer)
  previewAbort?.abort()
  if (
    !editorOpen.value ||
    editingRow.value ||
    form.saveMode !== 'ACTIVE' ||
    !form.attributeName.trim()
  )
    return
  const controller = new AbortController()
  previewAbort = controller
  const current = ++previewVersion
  const input = { category: form.category, attributeName: form.attributeName.trim() }
  previewLoading.value = true
  previewError.value = ''
  try {
    const result = await userMemoryApi.preview(input, controller.signal)
    if (current !== previewVersion || controller.signal.aborted) return
    preview.value = result
    previewSignature = JSON.stringify(input)
  } catch (cause) {
    if (current !== previewVersion || controller.signal.aborted) return
    previewError.value = errorText(cause)
  } finally {
    if (current === previewVersion) previewLoading.value = false
  }
}
async function saveMemory() {
  if (busy.value) return
  editorError.value = ''
  const content = form.content.trim(),
    attributeName = form.attributeName.trim()
  if (!content || !attributeName) {
    editorError.value = '请填写偏好名称和具体内容'
    return
  }
  let operation: MemoryMutation
  if (editingRow.value) operation = { action: 'EDIT', target: version(editingRow.value), content }
  else {
    if (
      form.saveMode === 'ACTIVE' &&
      previewSignature !== JSON.stringify({ category: form.category, attributeName })
    ) {
      await refreshPreview()
      editorError.value = '请确认同名偏好检查结果后，再保存'
      return
    }
    operation = {
      action: 'CREATE',
      category: form.category,
      attributeName,
      content,
      saveMode: form.saveMode,
      expectedActive:
        form.saveMode === 'ACTIVE' && preview.value?.activeMemory
          ? version(preview.value.activeMemory)
          : null,
    }
  }
  try {
    const result = await memories.change(operation)
    if (!result) return
    editorOpen.value = false
    message.success(
      result.changed
        ? operation.action === 'CREATE' && operation.saveMode === 'CANDIDATE'
          ? '已保存为待确认，正式记忆保持不变'
          : '记忆已保存，历史记录已保留'
        : '内容没有变化，已保留现有记忆',
    )
  } catch (cause) {
    editorError.value = errorText(cause)
    if (cause instanceof MemoryApiError && cause.conflict) {
      editorOpen.value = false
      message.warning('记忆已发生变化，列表已刷新，请重新确认')
    }
  }
}
function openConfirmation(action: 'ADOPT' | 'REVOKE' | 'CANCEL', row: UserMemory) {
  confirmationError.value = ''
  confirmation.value = { action, row: snapshot(row) }
}
function closeConfirmation() {
  if (!busy.value) confirmation.value = null
}
async function confirmChange() {
  if (!confirmation.value || busy.value) return
  const { action, row } = confirmation.value
  const operation: MemoryMutation =
    action === 'ADOPT'
      ? {
          action,
          target: version(row),
          expectedActive: row.activeMemory ? version(row.activeMemory) : null,
        }
      : { action, target: version(row) }
  confirmationError.value = ''
  try {
    const result = await memories.change(operation)
    if (!result) return
    confirmation.value = null
    message.success(
      action === 'ADOPT'
        ? '候选已采用，新偏好已生效'
        : action === 'REVOKE'
          ? '已撤销记忆，历史记录已保留'
          : '已取消候选，正式记忆保持不变',
    )
  } catch (cause) {
    confirmationError.value = errorText(cause)
    if (cause instanceof MemoryApiError && cause.conflict) {
      confirmation.value = null
      message.warning('记忆已发生变化，列表已刷新，请重新确认')
    }
  }
}
function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      })
}
function relativeTime(value: string) {
  const time = new Date(value).getTime()
  if (!Number.isFinite(time)) return '—'
  const minutes = Math.max(0, Math.floor((Date.now() - time) / 60000))
  return minutes < 1
    ? '刚刚'
    : minutes < 60
      ? `${minutes} 分钟前`
      : minutes < 1440
        ? `${Math.floor(minutes / 60)} 小时前`
        : formatTime(value)
}
function historyTitle(change: MemoryHistoryChange) {
  const titles = {
    CREATE: '新增了',
    EDIT: '修改了',
    ADOPT: '采用了',
    REVOKE: '撤销了',
    CANDIDATE_CREATE: '新增了',
    CANCEL: '取消了',
    SAVED: '此前保存的',
  }
  return (
    titles[change.action] + change.attributeName + (change.targetKind === 'CANDIDATE' ? '候选' : '')
  )
}
function historyCaption(change: MemoryHistoryChange) {
  if (change.action === 'SAVED') return '这是此前保存的记录，内容已保留。'
  if (change.action === 'CANCEL') return '只取消这条候选，已生效的记忆不受影响。'
  if (change.action === 'REVOKE') return '已撤销，不再用于生成。'
  if (change.action === 'CANDIDATE_CREATE') return '记录为候选，尚未生效。'
  return change.beforeContent ? '已保存新偏好，旧内容保留在历史中。' : '偏好已保存。'
}
onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  clearTimeout(previewTimer)
  previewVersion++
  previewAbort?.abort()
  memories.dispose()
})
</script>

<style scoped>
.memory-theme {
  --blue: #3b82f6;
  --blue-dark: #2563eb;
  --ink: #172338;
  --muted: #6b778c;
  --line: #e9edf3;
  --surface: #fff;
  --bg: #f7f9fc;
  --green: #159877;
  --orange: #b76a16;
  --red: #d95454;
  --radius: 12px;
}
* {
  box-sizing: border-box;
}
.memory-page {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font:
    14px/1.6 -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  -webkit-font-smoothing: antialiased;
}
button,
input,
select,
textarea {
  font: inherit;
}
button,
a,
input,
select,
textarea {
  -webkit-tap-highlight-color: transparent;
}
button {
  cursor: pointer;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
button:focus-visible,
a:focus-visible {
  outline: 3px solid #b5d2ff;
  outline-offset: 3px;
}
a {
  color: inherit;
  text-decoration: none;
}
button {
  color: inherit;
}
svg {
  flex-shrink: 0;
  vertical-align: middle;
}
[hidden] {
  display: none !important;
}
main {
  max-width: 1152px;
  margin: 0 auto;
  padding: 33px 28px 48px;
}
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 27px;
}
.heading-copy {
  display: flex;
  align-items: center;
  gap: 16px;
}
.page-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border: 1px solid #dfeaff;
  border-radius: 13px;
  color: var(--blue);
  background: #edf4ff;
}
h1 {
  font-size: 25px;
  line-height: 1.35;
  letter-spacing: 0.5px;
  margin: 0 0 6px;
  font-weight: 650;
}
.subtitle {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}
.heading-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.button {
  min-height: 38px;
  border-radius: 7px;
  border: 1px solid #dfe5ee;
  background: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 8px 14px;
  font-size: 13px;
  line-height: 1.4;
  transition:
    background 0.15s,
    border-color 0.15s;
}
.button:hover {
  background: #f8faff;
  border-color: #bbcff0;
}
.button.primary {
  background: var(--blue);
  color: #fff;
  border-color: var(--blue);
  box-shadow: 0 2px 5px #3b82f61a;
}
.button.primary:hover {
  background: var(--blue-dark);
  border-color: var(--blue-dark);
}
.button.danger {
  color: #fff;
  background: var(--red);
  border-color: var(--red);
}
.button.small {
  min-height: 31px;
  padding: 6px 12px;
  font-size: 12px;
}
.text-button {
  border: 0;
  background: transparent;
  padding: 5px 7px;
  color: var(--blue-dark);
  font-size: 12px;
  border-radius: 5px;
}
.text-button:hover {
  background: #eef4ff;
}
.text-button.muted {
  color: #8490a3;
}
.text-button.muted:hover {
  color: var(--red);
  background: #fff2f2;
}
.panel {
  background: #fff;
  border: 1px solid #e2e8f1;
  border-radius: var(--radius);
  box-shadow: 0 3px 12px #1d365404;
  overflow: hidden;
}
.tab-bar {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--line);
  padding: 0 24px;
  gap: 28px;
}
.tab {
  height: 57px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  padding: 0 2px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #778498;
}
.tab[aria-selected='true'] {
  color: var(--blue-dark);
  border-bottom-color: var(--blue);
  font-weight: 600;
}
.count {
  min-width: 20px;
  padding: 1px 6px;
  text-align: center;
  border-radius: 5px;
  background: #f0f3f7;
  color: #8893a4;
  font-size: 11px;
  line-height: 18px;
  font-weight: 500;
}
.tab[aria-selected='true'] .count {
  background: #eaf2ff;
  color: var(--blue-dark);
}
.tab.pending .count {
  background: #fff5e8;
  color: #b17b35;
}
.scope-hint {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: #929cac;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px;
}
.filters {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.filter {
  border: 1px solid transparent;
  border-radius: 6px;
  background: none;
  color: #78859a;
  padding: 5px 10px;
  font-size: 12px;
  white-space: nowrap;
}
.filter:hover {
  background: #f5f7fa;
}
.filter[aria-pressed='true'] {
  color: #3374d4;
  background: #edf4ff;
  font-weight: 500;
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #e4e9f1;
  border-radius: 7px;
  padding: 7px 10px;
  color: #a1acbc;
  width: 223px;
  flex-shrink: 0;
}
.search:focus-within {
  border-color: #85b4fc;
  box-shadow: 0 0 0 2px #3b82f610;
}
.search input {
  width: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 12px;
  min-width: 0;
}
.search input::placeholder {
  color: #a1acbc;
}
.notice {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  margin: 0 24px 17px;
  border: 1px solid #f6e8c9;
  background: #fffbf2;
  color: #94702e;
  border-radius: 7px;
  padding: 10px 12px;
  font-size: 12px;
}
.notice svg {
  margin-top: 2px;
}
.list-head,
.memory-row {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 116px 108px 105px;
  align-items: center;
  gap: 18px;
}
.list-head {
  padding: 10px 26px;
  background: #fafbfd;
  border-top: 1px solid #f1f4f8;
  border-bottom: 1px solid var(--line);
  color: #929cab;
  font-size: 11px;
}
.list-head > span:last-child {
  text-align: right;
}
.memory-row {
  padding: 17px 26px;
  border-bottom: 1px solid #edf0f5;
  min-height: 85px;
  transition: background 0.15s;
}
.memory-row:last-child {
  border-bottom: 0;
}
.memory-row:hover {
  background: #fcfdff;
}
.memory-description {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.memory-icon {
  width: 33px;
  height: 33px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: #f1f5fa;
  color: #7d8ca1;
  margin-top: 3px;
  flex-shrink: 0;
}
.memory-.memory-page {
  min-width: 0;
}
.memory-title {
  margin: 0 0 3px;
  font-size: 13px;
  font-weight: 570;
  line-height: 1.5;
}
.memory-content {
  color: #748196;
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.category {
  font-size: 11px;
  border-radius: 5px;
  padding: 3px 8px;
  width: max-content;
  white-space: nowrap;
}
.language {
  background: #eef3ff;
  color: #6282bb;
}
.visual {
  background: #f2effb;
  color: #9a7cbc;
}
.tech {
  background: #eaf5fc;
  color: #548aa5;
}
.experience {
  background: #eaf6f0;
  color: #699a84;
}
.date {
  font-size: 11px;
  color: #96a0af;
}
.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  white-space: nowrap;
}
.source-line {
  font-size: 11px;
  color: #a0a9b6;
  margin-top: 5px;
}
.conflict {
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 5px;
  color: #b37c38;
  margin-top: 7px;
}
.conflict svg {
  color: #c89450;
}
.candidate-row {
  min-height: 111px;
}
.empty {
  text-align: center;
  padding: 58px 20px 62px;
  color: #8d99aa;
}
.empty-icon {
  width: 54px;
  height: 54px;
  margin: 0 auto 15px;
  border-radius: 15px;
  background: #f3f6fa;
  display: grid;
  place-items: center;
  color: #a2b0c2;
}
.empty h3 {
  font-size: 14px;
  color: #67788d;
  font-weight: 500;
  margin: 0 0 5px;
}
.empty p {
  font-size: 12px;
  margin: 0;
}
.panel-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid var(--line);
  padding: 14px 25px;
  color: #9aa4b2;
  font-size: 11px;
  gap: 15px;
}
.footer-principle {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #8c9aae;
}
.below-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: 17px;
  color: #98a4b5;
  font-size: 11px;
}
.chat-tip {
  display: flex;
  align-items: flex-start;
  gap: 7px;
}
.chat-tip svg {
  margin-top: 2px;
  color: #98aecb;
}
.dialog-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 23px 24px 18px;
  border-bottom: 1px solid var(--line);
}
.dialog-header h2 {
  font-size: 18px;
  margin: 0 0 4px;
  font-weight: 600;
}
.dialog-header p {
  font-size: 12px;
  color: #8b98aa;
  margin: 0;
}
.icon-button {
  width: 29px;
  height: 29px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  border-radius: 6px;
  color: #8c99aa;
  padding: 0;
}
.icon-button:hover {
  background: #f1f4f9;
}
.dialog-.memory-page {
  padding: 20px 24px;
}
.field {
  margin-bottom: 17px;
}
.field:last-child {
  margin-bottom: 0;
}
.field label {
  display: block;
  font-size: 12px;
  color: #57667b;
  margin-bottom: 6px;
}
.field input,
.field select,
.field textarea {
  width: 100%;
  border: 1px solid #dfe6f0;
  border-radius: 7px;
  padding: 9px 11px;
  color: var(--ink);
  background: #fff;
  outline: 0;
  font-size: 13px;
}
.field input:focus,
.field select:focus,
.field textarea:focus {
  border-color: #7bacf5;
  box-shadow: 0 0 0 3px #3b82f610;
}
.field input:disabled,
.field select:disabled {
  background: #f7f9fc;
  color: #8a98ab;
}
.field textarea {
  resize: vertical;
  min-height: 91px;
  line-height: 1.7;
}
.field textarea::placeholder,
.field input::placeholder {
  color: #b1bac7;
}
.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 15px;
}
.form-help {
  font-size: 11px;
  color: #98a5b6;
  margin-top: 7px;
}
.form-message {
  font-size: 12px;
  color: #977231;
  background: #fffbf2;
  padding: 10px 12px;
  border-radius: 7px;
  border: 1px solid #f5e8c9;
  margin: 13px 0 0;
}
.form-error {
  font-size: 12px;
  color: var(--red);
  margin: 10px 0 0;
}
.dialog-footer {
  padding: 15px 24px;
  border-top: 1px solid var(--line);
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  background: #fcfdff;
}
.dialog-description {
  margin: 0 0 17px;
  font-size: 13px;
  color: #77869b;
  line-height: 1.8;
}
.comparison {
  display: grid;
  gap: 10px;
  border: 1px solid var(--line);
  padding: 14px;
  border-radius: 9px;
  font-size: 12px;
}
.comparison-line {
  display: flex;
  align-items: flex-start;
  gap: 15px;
}
.comparison-label {
  color: #9aa5b4;
  min-width: 48px;
}
.comparison-value {
  overflow-wrap: anywhere;
}
.comparison-line:first-child .comparison-value {
  color: #96a1b0;
}
.comparison-line:last-child .comparison-value {
  color: #3880cf;
}
.confirm-footnote {
  color: #9aa5b4;
  font-size: 11px;
  margin: 14px 0 0;
}
.history-item {
  position: relative;
  border-left: 1px solid #e9eef5;
  margin-left: 12px;
  padding: 0 0 27px 25px;
}
.history-item:last-child {
  border-color: transparent;
}
.history-dot {
  position: absolute;
  left: -13px;
  top: -1px;
  display: grid;
  place-items: center;
  width: 25px;
  height: 25px;
  border-radius: 50%;
  background: #eff5ff;
  color: #79a4df;
  border: 4px solid #fff;
}
.history-item.pending-event .history-dot {
  background: #fff5e7;
  color: #c59d66;
}
.history-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 10px;
  color: #a4adba;
}
.history-title {
  font-size: 13px;
  font-weight: 550;
  margin: 7px 0 8px;
}
.history-values {
  padding: 10px 12px;
  border: 1px solid #ecf0f5;
  border-radius: 8px;
  background: #fafbfd;
  font-size: 12px;
  color: #74869c;
  line-height: 1.8;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.old-value {
  color: #9ba7b8;
  text-decoration: line-through;
}
.new-value {
  color: #4a86bb;
}
.history-caption {
  font-size: 11px;
  color: #a4adba;
  margin-top: 8px;
}
.history-empty {
  text-align: center;
  color: #9aa5b5;
  font-size: 12px;
  padding: 60px 0;
}
@media (min-width: 1550px) {
  main {
    padding-top: 43px;
  }
  .memory-row {
    padding-top: 19px;
    padding-bottom: 19px;
  }
}
@media (max-width: 950px) {
  .scope-hint {
    display: none;
  }
  .toolbar {
    align-items: flex-start;
    gap: 12px;
  }
  .search {
    width: 185px;
  }
  .filters {
    gap: 1px;
  }
  .filter {
    padding: 5px 7px;
  }
  .list-head,
  .memory-row {
    grid-template-columns: minmax(190px, 1fr) 96px 105px;
    gap: 12px;
  }
  .date,
  .date-heading {
    display: none;
  }
  .heading-actions .button {
    padding: 8px 11px;
  }
}
@media (max-width: 640px) {
  main {
    padding: 23px 16px 32px;
  }
  .page-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 20px;
    margin-bottom: 20px;
  }
  .heading-copy {
    gap: 12px;
  }
  .page-icon {
    width: 43px;
    height: 43px;
    border-radius: 11px;
  }
  h1 {
    font-size: 23px;
  }
  .subtitle {
    font-size: 12px;
  }
  .heading-actions {
    width: 100%;
    gap: 9px;
  }
  .heading-actions .button {
    flex: 1;
  }
  .tab-bar {
    padding: 0 17px;
    gap: 26px;
  }
  .tab {
    height: 53px;
  }
  .toolbar {
    padding: 15px;
    flex-direction: column;
    gap: 13px;
  }
  .filters {
    gap: 2px;
    width: 100%;
    justify-content: space-between;
  }
  .filter {
    padding: 5px 7px;
    font-size: 11px;
  }
  .search {
    width: 100%;
    padding: 8px 10px;
  }
  .notice {
    margin: 0 15px 15px;
    font-size: 11px;
  }
  .list-head {
    display: none;
  }
  .memory-row {
    padding: 17px 16px;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 11px 8px;
    min-height: 104px;
  }
  .memory-description {
    grid-column: 1 / -1;
    gap: 10px;
  }
  .memory-row > .category {
    margin-left: 43px;
    font-size: 10px;
  }
  .row-actions {
    grid-column: 2;
    grid-row: 2;
  }
  .memory-content {
    font-size: 12px;
  }
  .source-line {
    font-size: 10px;
  }
  .conflict {
    line-height: 1.8;
  }
  .panel-footer {
    padding: 13px 16px;
    flex-direction: column;
    align-items: flex-start;
    gap: 5px;
  }
  .below-panel {
    align-items: flex-start;
    font-size: 10px;
    gap: 14px;
  }
  .chat-tip {
    line-height: 1.9;
  }
  .dialog-header {
    padding: 20px;
  }
  .dialog-.memory-page {
    padding: 18px 20px;
  }
  .dialog-footer {
    padding: 14px 20px;
  }
  .history-dialog .dialog-header {
    padding: 22px 20px;
  }
  .history-.memory-page {
    padding: 24px 20px;
  }
  .field-row {
    gap: 10px;
  }
}
.button.primary {
  background: #2d71df;
  border-color: #2d71df;
}
.memory-content {
  color: #64748b;
}
.language {
  color: #526f9f;
}
.visual {
  color: #82639f;
}
.tech {
  color: #47768c;
}
.experience {
  color: #527d67;
}
.dialog-description {
  color: #68788d;
}
.dialog-header p,
.form-help,
.confirm-footnote {
  color: #7e8c9f;
}
.history-caption {
  color: #8290a3;
}
.history-meta {
  color: #8995a6;
}
@media (prefers-reduced-motion: reduce) {
  * {
    transition: none !important;
    scroll-behavior: auto !important;
  }
}
.loading-state {
  min-height: 260px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: var(--muted);
  font-size: 12px;
}
.error-state .button {
  margin-top: 16px;
}
.memory-editor {
  padding-top: 5px;
}
.memory-editor .dialog-footer {
  padding: 16px 0 0;
  margin-top: 20px;
  background: transparent;
}
.history-body {
  padding-top: 15px;
}
.memory-theme > .dialog-footer {
  padding: 16px 0 0;
  margin-top: 18px;
  background: transparent;
}
.memory-theme .dialog-description {
  font-size: 13px;
}
.below-panel {
  padding-bottom: 8px;
}
.memory-theme .button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
</style>
