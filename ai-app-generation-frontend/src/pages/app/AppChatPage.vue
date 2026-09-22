<template>
  <div id="appChatPage">
    <!-- 顶部栏 -->
    <div class="header-bar">
      <div class="header-left">
        <h1 class="app-name">{{ appInfo?.appName || '网站生成器' }}</h1>
        <a-tag v-if="appInfo?.codeGenType" color="blue" class="code-gen-type-tag">
          {{ formatCodeGenType(appInfo.codeGenType) }}
        </a-tag>
      </div>
      <div class="header-right">
        <a-button type="default" @click="showAppDetail">
          <template #icon>
            <InfoCircleOutlined />
          </template>
          应用详情
        </a-button>
        <a-button
          type="primary"
          ghost
          @click="downloadCode"
          :loading="downloading"
          :disabled="!isOwner"
        >
          <template #icon>
            <DownloadOutlined />
          </template>
          下载代码
        </a-button>
        <a-button type="primary" @click="deployApp" :loading="deploying">
          <template #icon>
            <CloudUploadOutlined />
          </template>
          部署
        </a-button>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div
      ref="workspaceContainer"
      class="main-content"
      :class="{ 'generation-workspace': isVue && layout === 'workspace', 'resizable-workspace': resizeEnabled, 'is-resizing': isResizing }"
      :style="resizeStyle"
    >
      <!-- 左侧对话区域 -->
      <div id="workspace-left-panel" class="chat-section" :class="{ 'result-chat': showLeftTabs }">
        <div v-if="isVue && isOwner" class="workspace-toolbar">
          <div v-if="showLeftTabs" class="left-tabs" role="tablist" aria-label="对话与计划" @keydown="handleLeftTabKeydown">
            <button id="chat-tab" type="button" role="tab" :aria-selected="activeLeftTab === 'chat'" aria-controls="chat-panel" :tabindex="activeLeftTab === 'chat' ? 0 : -1" @click="activeLeftTab = 'chat'">对话与代码</button>
            <button id="plan-tab" type="button" role="tab" :aria-selected="activeLeftTab === 'plan'" aria-controls="plan-panel" :tabindex="activeLeftTab === 'plan' ? 0 : -1" @click="activeLeftTab = 'plan'">计划</button>
          </div>
          <span v-else>{{ isGenerating ? '正在生成' : '生成工作区' }}</span>
          <div v-if="!showLeftTabs">
            <template v-if="layout === 'workspace' && !isGenerating">
              <a-button v-if="previousAvailable" size="small" type="text" @click="layout = 'previous-preview'">查看上一版</a-button>
              <span v-else class="previous-unavailable">上一版预览暂不可用</span>
            </template>
          </div>
        </div>
        <div v-if="isVue && isOwner" v-show="showPlanTab" id="plan-panel" ref="planTabContainer" class="plan-tab-panel" role="tabpanel" aria-labelledby="plan-tab" tabindex="0" @scroll="handlePlanScroll">
          <GenerationPlanPanel :snapshot="planSnapshot" :status="planStatus" :error="planError" :tools="currentTools" :active="isGenerating" @retry="planQuery.retry" />
        </div>
        <!-- 消息区域 -->
        <div v-show="showConversation" id="chat-panel" class="messages-container" ref="messagesContainer" :role="showLeftTabs ? 'tabpanel' : undefined" :aria-labelledby="showLeftTabs ? 'chat-tab' : undefined">
          <!-- 加载更多按钮 -->
          <div v-if="hasMoreHistory" class="load-more-container">
            <a-button type="link" @click="loadMoreHistory" :loading="loadingHistory" size="small">
              加载更多历史消息
            </a-button>
          </div>
          <div v-for="message in messages" :key="message.id" class="message-item">
            <div v-if="message.type === 'user'" class="user-message">
              <div class="message-content">{{ message.content }}</div>
              <div class="message-avatar">
                <UserAvatar />
              </div>
            </div>
            <div v-else class="ai-message">
              <div class="message-avatar">
                <a-avatar :src="aiAvatar" />
              </div>
              <div
                class="message-content"
                :class="{ 'status-only': message.loading && !message.content && !message.toolCalls?.size && !message.displayBlocks?.length }"
              >
                <template v-if="message.displayBlocks">
                  <template v-for="block in message.displayBlocks" :key="block.key">
                    <MarkdownRenderer v-if="block.kind === 'markdown'" :content="block.text" />
                    <template v-else>
                      <ToolOperationCard
                        v-for="view in toolViewsForBlock(message, block)" :key="view.id"
                        :tool-key="block.key" :view="view"
                        :session-status="message.generationStatus ?? 'done'"
                        :state="message.toolCardStates?.[block.key] ?? DEFAULT_TOOL_CARD_STATE"
                        @update:state="updateToolCardState(message, block.key, $event)"
                      />
                    </template>
                  </template>
                </template>
                <MarkdownRenderer v-else-if="message.content" :content="message.content" />
                <!-- 工具调用实时视图:文件路径 + 流式内容预览 -->
                <div
                  v-if="legacyToolCalls(message).size > 0"
                  class="tool-calls-panel"
                >
                  <div
                    v-for="[id, view] in legacyToolCalls(message)"
                    :key="id"
                    class="tool-call-card"
                    :class="{
                      'is-done':
                        view.name === 'buildProject'
                          ? getBuildProjectVisualState(view) === 'success'
                          : view.status === 'done',
                      'is-build-failed':
                        view.name === 'buildProject' &&
                        getBuildProjectVisualState(view) === 'failed',
                      'is-build-cancelled':
                        view.name === 'buildProject' &&
                        getBuildProjectVisualState(view) === 'cancelled',
                    }"
                  >
                    <div class="tool-call-header">
                      <span class="tool-call-name">{{
                        view.name === 'readSkill' ? '读取 Skill' : view.name
                      }}</span>
                      <span v-if="view.args.relativeFilePath || view.args.skillName" class="tool-call-path">
                        {{ view.args.relativeFilePath || view.args.skillName }}
                      </span>
                      <span
                        v-if="view.name !== 'buildProject'"
                        class="tool-call-status"
                        :class="view.status"
                      >
                        <template v-if="view.status === 'done'">
                          <svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <polyline points="20 6 9 17 4 12" />
                          </svg>
                          已完成
                        </template>
                        <template v-else>
                          <svg class="status-icon spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
                            <circle cx="12" cy="12" r="9" stroke-dasharray="14 30" />
                          </svg>
                          执行中
                        </template>
                      </span>
                      <span
                        v-else-if="getBuildProjectDisplayState(view) === 'streaming'"
                        class="tool-call-status streaming"
                      >
                        <svg class="status-icon spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
                          <circle cx="12" cy="12" r="9" stroke-dasharray="14 30" />
                        </svg>
                        执行中
                      </span>
                      <span
                        v-else-if="getBuildProjectDisplayState(view) === 'unrecognized'"
                        class="tool-call-status error"
                      >
                        结果不可识别
                      </span>
                    </div>
                    <template v-if="view.name === 'buildProject' && view.build">
                      <div class="build-status-row">
                        <span
                          class="build-status-text"
                          :class="{
                            success: view.build.success === true,
                            failed:
                              view.build.invocationStatus === 'COMPLETED' &&
                              view.build.success === false,
                            cancelled: view.build.invocationStatus === 'CANCELLED',
                          }"
                        >
                          {{ view.build.statusText }}
                        </span>
                        <span v-if="view.build.stage" class="build-stage">
                          阶段：{{ view.build.stage }}
                        </span>
                      </div>
                      <details v-if="view.build.errorSummary" class="build-error-details">
                        <summary>查看错误摘要</summary>
                        <pre class="build-error-summary">{{ view.build.errorSummary }}</pre>
                      </details>
                    </template>
                    <!-- 修改文件:展示 old → new 两段 -->
                    <template v-if="view.name === 'modifyFile'">
                      <div v-if="view.args.oldContent" class="tool-call-label">旧内容</div>
                      <pre v-if="view.args.oldContent" v-auto-scroll class="tool-call-code old">{{ view.args.oldContent }}</pre>
                      <div v-if="view.args.newContent !== undefined" class="tool-call-label">新内容</div>
                      <pre v-if="view.args.newContent !== undefined" v-auto-scroll class="tool-call-code new">{{ view.args.newContent }}</pre>
                    </template>
                    <!-- 写入文件:实时文件内容 -->
                    <template v-else-if="view.name === 'writeFile' && view.args.content !== undefined">
                      <pre v-auto-scroll class="tool-call-code">{{ view.args.content }}</pre>
                    </template>
                  </div>
                </div>
                <div
                  v-if="message.loading"
                  class="loading-indicator"
                  role="status"
                  aria-live="polite"
                >
                  <a-spin size="small" />
                  <span>{{
                    getGenerationStatusText(
                      message.contextCompression || 'idle',
                      message.incompleteToolChainRecovery || 'idle',
                      message.toolProtocolRecovery || 'idle',
                      'AI 正在思考...',
                    )
                  }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 选中元素信息展示 -->
        <a-alert
          v-if="selectedElementInfo && showConversation"
          class="selected-element-alert"
          type="info"
          closable
          @close="clearSelectedElement"
        >
          <template #message>
            <div class="selected-element-info">
              <div class="element-header">
                <span class="element-tag">
                  选中元素：{{ selectedElementInfo.tagName.toLowerCase() }}
                </span>
                <span v-if="selectedElementInfo.id" class="element-id">
                  #{{ selectedElementInfo.id }}
                </span>
                <span v-if="selectedElementInfo.className" class="element-class">
                  .{{ selectedElementInfo.className.split(' ').join('.') }}
                </span>
              </div>
              <div class="element-details">
                <div v-if="selectedElementInfo.textContent" class="element-item">
                  内容: {{ selectedElementInfo.textContent.substring(0, 50) }}
                  {{ selectedElementInfo.textContent.length > 50 ? '...' : '' }}
                </div>
                <div v-if="selectedElementInfo.pagePath" class="element-item">
                  页面路径: {{ selectedElementInfo.pagePath }}
                </div>
                <div class="element-item">
                  选择器:
                  <code class="element-selector-code">{{ selectedElementInfo.selector }}</code>
                </div>
              </div>
            </div>
          </template>
        </a-alert>

        <!-- 用户消息输入框 -->
        <div v-show="showConversation" class="input-container">
          <div class="input-wrapper">
            <a-tooltip v-if="!isOwner" title="无法在别人的作品下对话哦~" placement="top">
              <a-textarea
                v-model:value="userInput"
                :placeholder="getInputPlaceholder()"
                :rows="4"
                :maxlength="1000"
                @keydown="handleInputKeydown"
                :disabled="isGenerating || !isOwner"
              />
            </a-tooltip>
            <a-textarea
              v-else
              v-model:value="userInput"
              :placeholder="getInputPlaceholder()"
              :rows="4"
              :maxlength="1000"
              @keydown="handleInputKeydown"
              :disabled="isGenerating"
            />
            <div class="input-shortcut-hint">Enter 发送，Shift + Enter 换行</div>
            <div class="input-actions">
              <a-button
                type="primary"
                @click="isGenerating ? stopGeneration() : sendMessage()"
                :loading="isStopping"
                :disabled="!isOwner || isStopping"
                :danger="isGenerating"
                :aria-label="isGenerating ? '停止本轮生成' : '发送消息'"
              >
                <template #icon>
                  <StopOutlined v-if="isGenerating" />
                  <SendOutlined v-else />
                </template>
              </a-button>
            </div>
          </div>
        </div>
      </div>
      <aside v-if="isVue && isOwner && layout === 'workspace'" class="generation-plan-sidebar">
        <details open class="workspace-plan-details"><summary>计划与执行进度</summary>
          <GenerationPlanPanel :snapshot="planSnapshot" :status="planStatus" :error="planError" :tools="currentTools" :active="isGenerating" @retry="planQuery.retry" />
          <GenerationExecutionTimeline :tools="currentTools" :status="lastSession?.status ?? 'done'" @locate="locateTool" />
        </details>
      </aside>
      <div
        v-if="resizeEnabled"
        ref="workspaceSeparator"
        class="workspace-separator"
        role="separator"
        aria-label="调整左侧面板宽度"
        aria-orientation="vertical"
        aria-controls="workspace-left-panel"
        :aria-valuenow="Math.round(leftPercent)"
        :aria-valuemin="30"
        :aria-valuemax="50"
        :aria-valuetext="`左侧 ${Math.round(leftPercent)}%，右侧 ${100 - Math.round(leftPercent)}%`"
        tabindex="0"
        title="拖动调整宽度，双击恢复默认比例"
        @pointerdown="startResize"
        @pointermove="moveResize"
        @pointerup="endResize"
        @pointercancel="endResize"
        @lostpointercapture="endResize"
        @keydown="handleResizeKeydown"
        @dblclick="resetWorkspaceResize"
      ></div>
      <!-- 右侧网页展示区域 -->
      <div v-show="!isVue || layout !== 'workspace'" class="preview-section">
        <div class="preview-header">
          <h3>{{ layout === 'previous-preview' ? '上一版预览' : '生成后的网页展示' }}</h3>
          <div class="preview-actions">
            <a-button v-if="isVue && layout === 'previous-preview'" type="link" @click="layout = 'workspace'">返回执行区</a-button>
            <a-button
              v-if="isOwner && previewUrl && layout === 'preview' && previewReady"
              type="link"
              :danger="isEditMode"
              @click="toggleEditMode"
              :class="{ 'edit-mode-active': isEditMode }"
              style="padding: 0; height: auto; margin-right: 12px"
            >
              <template #icon>
                <EditOutlined />
              </template>
              {{ isEditMode ? '退出编辑' : '编辑模式' }}
            </a-button>
            <a-button v-if="previewUrl && layout !== 'previous-preview'" type="link" @click="openInNewTab">
              <template #icon>
                <ExportOutlined />
              </template>
              新窗口打开
            </a-button>
          </div>
        </div>
        <div class="preview-content">
          <div v-if="isVue && layout === 'previous-preview' && !previousAvailable" class="preview-placeholder">上一版预览暂不可用</div>
          <div v-else-if="!previewUrl && !isGenerating" class="preview-placeholder">
            <div class="placeholder-icon" aria-hidden="true">
              <svg viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="32" cy="32" r="24" />
                <ellipse cx="32" cy="32" rx="24" ry="10" />
                <ellipse cx="32" cy="32" rx="10" ry="24" />
                <line x1="8" y1="32" x2="56" y2="32" />
              </svg>
            </div>
            <p>网站文件生成完成后将在这里展示</p>
          </div>
          <div v-else-if="isGenerating && !isVue" class="preview-loading" role="status" aria-live="polite">
            <a-spin size="large" />
            <p>{{
              getGenerationStatusText(
                contextCompression,
                incompleteToolChainRecovery,
                toolProtocolRecovery,
                '正在生成网站...',
              )
            }}</p>
          </div>
          <iframe
            v-if="previewUrl && (isVue || !isGenerating)"
            v-show="layout !== 'previous-preview' || previousAvailable"
            :key="previewLoadId"
            ref="previewFrame"
            :src="previewUrl"
            :data-load-id="previewLoadId"
            class="preview-iframe"
            frameborder="0"
            @load="onIframeLoad"
            @error="invalidatePreview"
          ></iframe>
          <p v-if="isVue && layout === 'previous-preview' && previousAvailable && crossOrigin" class="preview-note">已保留本页内容，部分资源状态无法确认</p>
        </div>
      </div>
    </div>

    <!-- 应用详情弹窗 -->
    <AppDetailModal
      v-model:open="appDetailVisible"
      :app="appInfo"
      :show-actions="isOwner || isAdmin"
      @edit="editApp"
      @delete="deleteApp"
    />

    <!-- 部署成功弹窗 -->
    <DeploySuccessModal
      v-model:open="deployModalVisible"
      :deploy-url="deployUrl"
      @open-site="openDeployedSite"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, watch, onMounted, nextTick, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { useWorkspaceResize } from '@/composables/useWorkspaceResize'
import UserAvatar from '@/components/UserAvatar.vue'
import {
  getAppVoById,
  deployApp as deployAppApi,
  deleteApp as deleteAppApi,
} from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { CodeGenTypeEnum, formatCodeGenType } from '@/utils/codeGenTypes'
import { createUuid } from '@/utils/uuid'
import request from '@/request'
import {
  type ToolCallView,
  type GenerationDisplayBlock,
  type GenerationStatus,
  type ToolCardState,
  type GenerationSessionSnapshot,
  type GenerationOutcome,
  type ContextCompressionState,
  type ToolProtocolRecoveryState,
  type IncompleteToolChainRecoveryState,
  startGenerationSession,
  subscribeGenerationSession,
  getGenerationSessionSnapshot,
  getActiveGenerationId,
  clearGenerationSession,
  getBuildProjectDisplayState,
  getBuildProjectVisualState,
  shouldRefreshGenerationPreview,
  shouldHideToolCall,
  isOperationTool,
  setGenerationToolCardState,
  getGenerationStatusText,
  shouldShowGenerationStatus,
} from '@/utils/generationSession'
import { cancelChatGeneration } from '@/api/appController'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import ToolOperationCard from '@/components/ToolOperationCard.vue'
import GenerationPlanPanel from '@/components/GenerationPlanPanel.vue'
import GenerationExecutionTimeline from '@/components/GenerationExecutionTimeline.vue'
import { useGenerationPlan } from '@/composables/useGenerationPlan'
import { useGenerationPreview } from '@/composables/useGenerationPreview'
import { vAutoScroll } from '@/directives/autoScroll'
import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'
import { API_BASE_URL, getStaticPreviewUrl } from '@/config/env'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'

import {
  CloudUploadOutlined,
  SendOutlined,
  StopOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  DownloadOutlined,
  EditOutlined,
} from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

// 应用信息
const appInfo = ref<API.AppVO>()
const appId = ref<string>()

// 对话相关
interface Message {
  id: string
  type: 'user' | 'ai'
  content: string
  loading?: boolean
  contextCompression?: ContextCompressionState
  toolProtocolRecovery?: ToolProtocolRecoveryState
  incompleteToolChainRecovery?: IncompleteToolChainRecoveryState
  createTime?: string
  /** tool call id → 当前调用参数视图;保持插入顺序用 Map */
  toolCalls?: Map<string, ToolCallView>
  displayBlocks?: GenerationDisplayBlock[]
  toolCardStates?: Record<string, ToolCardState>
  generationStatus?: GenerationStatus
}

const DEFAULT_TOOL_CARD_STATE: ToolCardState = { expanded: true, codeVersion: 'after' }

function toolViewsForBlock(item: Message, block: Extract<GenerationDisplayBlock, { kind: 'tool' }>): ToolCallView[] {
  const view = item.toolCalls?.get(block.toolRequestId)
  return view?.generation === block.generation ? [view] : []
}

function legacyToolCalls(item: Message): Map<string, ToolCallView> {
  return new Map([...item.toolCalls ?? []].filter(([, view]) =>
    !item.displayBlocks || !isOperationTool(view.name)))
}

function updateToolCardState(item: Message, key: string, state: ToolCardState) {
  item.toolCardStates ??= {}
  item.toolCardStates[key] = { ...state }
  if (appId.value) setGenerationToolCardState(appId.value, key, state)
}

const messages = ref<Message[]>([])
const userInput = ref('')
const isGenerating = ref(false)
const isStopping = ref(false)
const messagesContainer = ref<HTMLElement>()
const activeSessionAppId = ref<string | null>(null)
const sessionMessageKey = ref<string | null>(null)
const detachSession = ref<null | (() => void)>(null)
const contextCompression = ref<ContextCompressionState>('idle')
const toolProtocolRecovery = ref<ToolProtocolRecoveryState>('idle')
const incompleteToolChainRecovery =
  ref<IncompleteToolChainRecoveryState>('idle')

// 外层消息容器智能吸底状态:用户上滑取消吸底,滑回接近底部(距底 ≤ 32px)恢复吸底。
// 阈值 32 不是宽容,是流式场景下 scrollHeight 持续增长的兜底 —— 1px 在抖动中不可达。
const stickBottom = ref(true)
const handleMessagesScroll = () => {
  const el = messagesContainer.value
  if (!showConversation.value || !el || el.clientHeight === 0) return
  chatScrollTop = el.scrollTop
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  stickBottom.value = distance <= 32
}

// 对话历史相关
const loadingHistory = ref(false)
const hasMoreHistory = ref(false)
const lastCreateTime = ref<string>()
const historyLoaded = ref(false)

// 预览相关
const previewUrl = ref('')
const previewReady = ref(false)
const previewFrame = ref<HTMLIFrameElement>()
const previewLoadId = ref(0)
const previewState = useGenerationPreview()
const { layout, crossOrigin } = previewState
const previousAvailable = computed(() => previewState.retained.value && previewState.available.value)
let previewTimer: ReturnType<typeof setTimeout> | undefined
let releaseResourceObserver: (() => void) | undefined
let previewProbe: AbortController | undefined
let pageEpoch = 0
const lastSession = shallowRef<GenerationSessionSnapshot | null>(null)
const currentTools = computed(() => [...lastSession.value?.toolCalls.values() ?? []])
const activeLeftTab = ref<'chat' | 'plan'>('chat')
const planTabContainer = ref<HTMLElement>()
let chatScrollTop = 0
let planScrollTop = 0
const planQuery = useGenerationPlan()
const { snapshot: planSnapshot, status: planStatus, error: planError } = planQuery
const isVue = computed(() => appInfo.value?.codeGenType === CodeGenTypeEnum.VUE_PROJECT)
let initialPreviewPending = true
// 部署相关
const deploying = ref(false)
const deployModalVisible = ref(false)
const deployUrl = ref('')

// 下载相关
const downloading = ref(false)

// 可视化编辑相关
const isEditMode = ref(false)
const selectedElementInfo = ref<ElementInfo | null>(null)
const visualEditor = new VisualEditor({
  onElementSelected: (elementInfo: ElementInfo) => {
    selectedElementInfo.value = elementInfo
  },
})

// 权限相关
const isOwner = computed(() => {
  return appInfo.value?.userId === loginUserStore.loginUser.id
})

const isAdmin = computed(() => {
  return loginUserStore.loginUser.userRole === 'admin'
})

const showLeftTabs = computed(() => isVue.value && isOwner.value && layout.value !== 'workspace')
const {
  container: workspaceContainer, separator: workspaceSeparator, leftPercent,
  enabled: resizeEnabled, isResizing, style: resizeStyle,
  startResize, moveResize, endResize, handleKeydown: handleResizeKeydown, reset: resetWorkspaceResize,
} = useWorkspaceResize(showLeftTabs)
const showPlanTab = computed(() => showLeftTabs.value && activeLeftTab.value === 'plan')
const showConversation = computed(() => !showPlanTab.value)

function handlePlanScroll() {
  const el = planTabContainer.value
  if (showPlanTab.value && el && el.clientHeight > 0) planScrollTop = el.scrollTop
}

function handleLeftTabKeydown(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  activeLeftTab.value = event.key === 'Home' ? 'chat' : event.key === 'End' ? 'plan'
    : activeLeftTab.value === 'chat' ? 'plan' : 'chat'
  const tabList = event.currentTarget as HTMLElement
  void nextTick(() => tabList.querySelector<HTMLElement>('[aria-selected="true"]')?.focus({ preventScroll: true }))
}

watch(showPlanTab, (visible, wasVisible) => {
  const previous = wasVisible ? planTabContainer.value : messagesContainer.value
  if (isVue.value && isOwner.value && previous && previous.clientHeight > 0) {
    if (wasVisible) planScrollTop = previous.scrollTop
    else chatScrollTop = previous.scrollTop
  }
  const epoch = pageEpoch
  void nextTick(() => {
    if (epoch !== pageEpoch || visible !== showPlanTab.value || !showLeftTabs.value) return
    const target = visible ? planTabContainer.value : messagesContainer.value
    if (target) target.scrollTop = visible ? planScrollTop : chatScrollTop
  })
})

function syncPlanContext() {
  const userId = loginUserStore.loginUser.id
  planQuery.setContext(isVue.value && isOwner.value && userId && appId.value ? {
    userId: String(userId), appId: appId.value, turnId: lastSession.value?.localTurnId ?? '',
  } : null, lastSession.value?.planObservation ?? 0)
}

watch([isOwner, isVue], syncPlanContext)
watch([planStatus, planSnapshot, historyLoaded], () => {
  if (!historyLoaded.value || !initialPreviewPending || lastSession.value?.localTurnId || isGenerating.value) return
  if (planStatus.value !== 'ready' && planStatus.value !== 'empty' && planStatus.value !== 'error') return
  initialPreviewPending = false
  if (planSnapshot.value && planSnapshot.value.status !== 'BUILT') layout.value = 'workspace'
  else if (messages.value.length >= 2 && !previewUrl.value) updatePreview()
})

function locateTool(id: string) {
  const block = lastSession.value?.displayBlocks?.find((entry) => entry.kind === 'tool' && entry.toolRequestId === id)
  if (!block) return
  const card = [...document.querySelectorAll<HTMLElement>('[data-tool-key]')].find((element) => element.dataset.toolKey === block.key)
  card?.scrollIntoView({ block: 'center', behavior: 'auto' })
}

// 应用详情相关
const appDetailVisible = ref(false)

// 显示应用详情
const showAppDetail = () => {
  appDetailVisible.value = true
}

// 加载对话历史
const loadChatHistory = async (isLoadMore = false) => {
  if (!appId.value || loadingHistory.value) return
  loadingHistory.value = true
  const epoch = pageEpoch
  try {
    const params: API.listAppChatHistoryParams = {
      appId: appId.value as unknown as number,
      pageSize: 10,
    }
    // 如果是加载更多，传递最后一条消息的创建时间作为游标
    if (isLoadMore && lastCreateTime.value) {
      params.lastCreateTime = lastCreateTime.value
    }
    const res = await listAppChatHistory(params)
    if (epoch !== pageEpoch) return
    if (res.data.code === 0 && res.data.data) {
      const chatHistories = res.data.data.records || []
      if (chatHistories.length > 0) {
        // 将对话历史转换为消息格式，并按时间正序排列（老消息在前）
        const historyMessages: Message[] = chatHistories
          .map((chat) => ({
            id: chat.id ? `history-${chat.id}` : createUuid(),
            type: (chat.messageType === 'user' ? 'user' : 'ai') as 'user' | 'ai',
            content: chat.message || '',
            createTime: chat.createTime,
          }))
          .reverse() // 反转数组，让老消息在前
        if (isLoadMore) {
          // 加载更多时，将历史消息添加到开头
          messages.value.unshift(...historyMessages)
        } else {
          // 初始加载，直接设置消息列表
          messages.value = historyMessages
        }
        // 更新游标
        lastCreateTime.value = chatHistories[chatHistories.length - 1]?.createTime
        // 检查是否还有更多历史
        hasMoreHistory.value = chatHistories.length === 10
      } else {
        hasMoreHistory.value = false
      }
      historyLoaded.value = true
    }
  } catch (error) {
    console.error('加载对话历史失败：', error)
    message.error('加载对话历史失败')
  } finally {
    if (epoch === pageEpoch) loadingHistory.value = false
  }
}

// 加载更多历史消息
const loadMoreHistory = async () => {
  await loadChatHistory(true)
}

// 获取应用信息
const fetchAppInfo = async () => {
  const epoch = pageEpoch
  const id = route.params.id as string
  if (!id) {
    message.error('应用ID不存在')
    router.push('/')
    return
  }

  appId.value = id

  try {
    const res = await getAppVoById({ id: id as unknown as number })
    if (epoch !== pageEpoch) return
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data

      // 先加载对话历史
      await loadChatHistory()
      if (epoch !== pageEpoch) return
      const sessionSnapshot = getGenerationSessionSnapshot(id)
      if (isOwner.value && sessionSnapshot?.status === 'streaming') {
        if (!isVue.value && messages.value.length >= 2) {
          updatePreview()
        }
        restoreActiveSessionIfNeeded()
      } else if (isOwner.value && sessionSnapshot?.localTurnId) {
        createSessionMessage(sessionSnapshot)
        applySessionSnapshot(sessionSnapshot)
        activeSessionAppId.value = id
        finalizeGeneration(sessionSnapshot)
      } else if ((!isVue.value || !isOwner.value) && messages.value.length >= 2) {
        updatePreview()
      }
      syncPlanContext()
      // 只有自己的空应用才自动发送初始提示词；已有对话则在会话恢复完成后定位到最新消息。
      const initialPrompt = appInfo.value.initPrompt || ''
      const shouldSendInitialMessage =
        initialPrompt.length > 0 &&
        isOwner.value &&
        messages.value.length === 0 &&
        historyLoaded.value

      if (shouldSendInitialMessage) {
        await sendInitialMessage(initialPrompt)
      } else {
        scrollToLatestMessageOnEntry()
      }
    } else {
      message.error('获取应用信息失败')
      router.push('/')
    }
  } catch (error) {
    if (epoch !== pageEpoch) return
    console.error('获取应用信息失败：', error)
    message.error('获取应用信息失败')
    router.push('/')
  }
}

const applySessionSnapshot = (snapshot: GenerationSessionSnapshot) => {
  if (snapshot.localTurnId) {
    const newTurn = lastSession.value?.localTurnId !== snapshot.localTurnId
    lastSession.value = snapshot
    if (isVue.value && newTurn) {
      previewState.begin(snapshot.localTurnId)
      visualEditor.disableEditMode(); isEditMode.value = false; clearSelectedElement()
      if (!previewState.retained.value) previewReady.value = false
    }
    syncPlanContext()
  }
  contextCompression.value = snapshot.contextCompression
  toolProtocolRecovery.value = snapshot.toolProtocolRecovery
  incompleteToolChainRecovery.value = snapshot.incompleteToolChainRecovery
  const aiMessage = messages.value.find((item) => item.id === sessionMessageKey.value)
  if (!aiMessage) return
  aiMessage.content = snapshot.content
  const visibleToolCalls = new Map(
    [...snapshot.toolCalls].filter(
      ([, view]) => !shouldHideToolCall(snapshot, view),
    ),
  )
  aiMessage.toolCalls = visibleToolCalls
  aiMessage.displayBlocks = snapshot.displayBlocks
  aiMessage.toolCardStates = snapshot.toolCardStates
  aiMessage.generationStatus = snapshot.status
  aiMessage.contextCompression = snapshot.contextCompression
  aiMessage.toolProtocolRecovery = snapshot.toolProtocolRecovery
  aiMessage.incompleteToolChainRecovery = snapshot.incompleteToolChainRecovery
  const hasVisibleOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0
  aiMessage.loading = shouldShowGenerationStatus(
    snapshot.loading,
    snapshot.contextCompression,
    snapshot.incompleteToolChainRecovery,
    snapshot.toolProtocolRecovery,
    hasVisibleOutput,
  )
  isGenerating.value = snapshot.status === 'streaming'
}

const createSessionMessage = (snapshot: GenerationSessionSnapshot) => {
  const lastMessageIndex = messages.value.length - 1
  const canReuseLastAiMessage =
    snapshot.status !== 'streaming' && messages.value[lastMessageIndex]?.type === 'ai'
  if (canReuseLastAiMessage) {
    sessionMessageKey.value = messages.value[lastMessageIndex]?.id ?? null
    return
  }
  const id = createUuid()
  sessionMessageKey.value = id
  messages.value.push({
    id,
    type: 'ai',
    content: '',
    loading: true,
    toolCalls: new Map(),
  })
}

const outcomeMessage = (outcome: GenerationOutcome, fallback?: string) => {
  if (fallback) {
    return fallback
  }
  const messages: Partial<Record<GenerationOutcome, string>> = {
    answered: '',
    failed: '生成失败，请根据构建信息重试',
    cancelled: '生成已取消',
    timed_out: '生成超时，请重试',
    system_error: '系统异常，请稍后重试',
    protocol_error: '生成协议异常，请重试',
    incomplete_tool_chain: '真实工具执行和构建未完成，请继续重试',
  }
  return messages[outcome] || '生成失败，请重试'
}

const attachSessionListener = (targetAppId: string) => {
  detachSession.value?.()
  detachSession.value = subscribeGenerationSession(targetAppId, (snapshot, eventType) => {
    if (snapshot.appId !== activeSessionAppId.value) {
      return
    }
    applySessionSnapshot(snapshot)
    scrollToBottom()
    if (eventType === 'error') {
      message.error(outcomeMessage(snapshot.outcome, snapshot.errorMessage))
      finalizeGeneration(snapshot)
      return
    }
    if (eventType === 'done') {
      if (
        snapshot.outcome !== 'cancelled' &&
        snapshot.outcome !== 'succeeded' &&
        snapshot.outcome !== 'answered'
      ) {
        message.error(outcomeMessage(snapshot.outcome, snapshot.errorMessage))
      }
      finalizeGeneration(snapshot)
    }
  })
}

const resolveVueSessionType = (): boolean | undefined => {
  const codeGenType = appInfo.value?.codeGenType
  if (codeGenType === CodeGenTypeEnum.VUE_PROJECT) {
    return true
  }
  if (codeGenType === CodeGenTypeEnum.HTML || codeGenType === CodeGenTypeEnum.MULTI_FILE) {
    return false
  }
  return undefined
}

const startGeneration = async (inputMessage: string, aiMessageIndex: number) => {
  const targetAppId = appId.value
  if (!targetAppId) {
    message.error('应用ID不存在')
    messages.value.splice(aiMessageIndex, 1)
    return
  }
  const expectVueTurnOutcome = resolveVueSessionType()
  if (expectVueTurnOutcome === undefined) {
    message.error('应用类型尚未加载或不受支持')
    messages.value.splice(aiMessageIndex, 1)
    return
  }
  activeSessionAppId.value = targetAppId
  sessionMessageKey.value = messages.value[aiMessageIndex]?.id ?? null
  isGenerating.value = true

  attachSessionListener(targetAppId)
  startGenerationSession({
    appId: targetAppId,
    userMessage: inputMessage,
    generationId: createUuid(),
    baseURL: request.defaults.baseURL || API_BASE_URL,
    renderMode:
      expectVueTurnOutcome ? 'direct' : 'throttled',
    throttleMs: 100,
    expectVueTurnOutcome,
  })
  const snapshot = getGenerationSessionSnapshot(targetAppId)
  if (snapshot) {
    applySessionSnapshot(snapshot)
  }
}

const stopGeneration = async () => {
  const targetAppId = activeSessionAppId.value
  const generationId = targetAppId ? getActiveGenerationId(targetAppId) : undefined
  if (!targetAppId || !generationId || isStopping.value) return
  isStopping.value = true
  try {
    const res = await cancelChatGeneration({ appId: targetAppId, generationId })
    if (res.data.code !== 0 || res.data.data !== true) {
      throw new Error(res.data.message || '停止请求未生效')
    }
    message.info('生成已被暂停')
  } catch (error) {
    console.error('停止生成失败：', error)
    message.error('停止生成失败，请重试')
  } finally {
    isStopping.value = false
  }
}

const restoreActiveSessionIfNeeded = () => {
  const targetAppId = appId.value
  if (!targetAppId || isGenerating.value || sessionMessageKey.value !== null) {
    return
  }
  const snapshot = getGenerationSessionSnapshot(targetAppId)
  if (!snapshot || snapshot.status !== 'streaming') {
    return
  }
  createSessionMessage(snapshot)
  activeSessionAppId.value = targetAppId
  attachSessionListener(targetAppId)
  applySessionSnapshot(snapshot)
  scrollToBottom()
}

// 发送初始消息
const sendInitialMessage = async (prompt: string) => {
  // 添加用户消息
  messages.value.push({
    id: createUuid(),
    type: 'user',
    content: prompt,
  })

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
    id: createUuid(),
    type: 'ai',
    content: '',
    loading: true,
    toolCalls: new Map(),
  })

  await nextTick()
  stickBottom.value = true
  scrollToBottom()

  await startGeneration(prompt, aiMessageIndex)
}

// 发送消息
const sendMessage = async () => {
  if (!userInput.value.trim() || isGenerating.value) {
    return
  }

  let message = userInput.value.trim()
  // 如果有选中的元素，将元素信息添加到提示词中
  if (selectedElementInfo.value) {
    let elementContext = `\n\n选中元素信息：`
    if (selectedElementInfo.value.pagePath) {
      elementContext += `\n- 页面路径: ${selectedElementInfo.value.pagePath}`
    }
    elementContext += `\n- 标签: ${selectedElementInfo.value.tagName.toLowerCase()}\n- 选择器: ${selectedElementInfo.value.selector}`
    if (selectedElementInfo.value.textContent) {
      elementContext += `\n- 当前内容: ${selectedElementInfo.value.textContent.substring(0, 100)}`
    }
    message += elementContext
  }
  userInput.value = ''
  // 添加用户消息（包含元素信息）
  messages.value.push({
    id: createUuid(),
    type: 'user',
    content: message,
  })

  // 发送消息后，清除选中元素并退出编辑模式
  if (selectedElementInfo.value) {
    clearSelectedElement()
    if (isEditMode.value) {
      toggleEditMode()
    }
  }

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
    id: createUuid(),
    type: 'ai',
    content: '',
    loading: true,
  })

  await nextTick()
  stickBottom.value = true
  scrollToBottom()

  await startGeneration(message, aiMessageIndex)
}

// 更新预览
const updatePreview = (forceReload = false) => {
  if (appId.value) {
    const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
    const basePreviewUrl = getStaticPreviewUrl(codeGenType, appId.value)
    const newPreviewUrl = forceReload
      ? `${basePreviewUrl}${basePreviewUrl.includes('?') ? '&' : '?'}_t=${Date.now()}`
      : basePreviewUrl
    previewReady.value = false
    releaseResourceObserver?.()
    previewProbe?.abort()
    clearTimeout(previewTimer)
    previewLoadId.value = previewState.startLoad()
    const identity = previewLoadId.value
    if (isVue.value) previewTimer = setTimeout(() => { if (identity === previewLoadId.value) invalidatePreview() }, 15000)
    previewUrl.value = newPreviewUrl
  }
}

// 后端回合终态是唯一刷新依据；失败时保留旧预览，便于用户继续对照修复。
const finalizeGeneration = (snapshot: GenerationSessionSnapshot) => {
  isGenerating.value = false
  isStopping.value = false
  const refresh = isVue.value ? previewState.finish(snapshot) : shouldRefreshGenerationPreview(snapshot)
  if (refresh) {
    updatePreview(true)
  }
  const currentAppId = activeSessionAppId.value
  if (currentAppId) {
    clearGenerationSession(currentAppId)
  }
  activeSessionAppId.value = null
  sessionMessageKey.value = null
  detachSession.value?.()
  detachSession.value = null
}

// 滚动到底部
// 必须在 nextTick 里滚:流式 chunk 同步调用时 DOM 尚未更新,scrollHeight 还是旧值,
// 直接 scrollTop = scrollHeight 会把滚动条卡在"旧底部"即新视图的中间。
// nextTick 内二次检查 stickBottom:等待期间用户可能上滑,避免覆盖用户意图。
const scrollToBottom = () => {
  if (!showConversation.value) return
  if (!stickBottom.value) return
  nextTick(() => {
    if (!showConversation.value) return
    if (!stickBottom.value) return
    const el = messagesContainer.value
    if (!el) return
    el.scrollTop = el.scrollHeight
  })
}

// 首次进入已有对话时忽略旧的滚动状态，在历史与本地生成会话恢复后定位到最新消息。
const scrollToLatestMessageOnEntry = () => {
  if (messages.value.length === 0) return
  stickBottom.value = true
  scrollToBottom()
}

// 下载代码
const downloadCode = async () => {
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }
  downloading.value = true
  try {
    const API_BASE_URL = request.defaults.baseURL || ''
    const url = `${API_BASE_URL}/app/download/${appId.value}`
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error(`下载失败: ${response.status}`)
    }
    // 获取文件名
    const contentDisposition = response.headers.get('Content-Disposition')
    const fileName = contentDisposition?.match(/filename="(.+)"/)?.[1] || `app-${appId.value}.zip`
    // 下载文件
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    link.click()
    // 清理
    URL.revokeObjectURL(downloadUrl)
    message.success('代码下载成功')
  } catch (error) {
    console.error('下载失败：', error)
    message.error('下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

// 部署应用
const deployApp = async () => {
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }

  deploying.value = true
  try {
    const res = await deployAppApi({
      appId: appId.value as unknown as number,
    })

    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      deployModalVisible.value = true
      message.success('部署成功')
    } else {
      message.error('部署失败：' + res.data.message)
    }
  } catch (error) {
    console.error('部署失败：', error)
    message.error('部署失败，请重试')
  } finally {
    deploying.value = false
  }
}

// 在新窗口打开预览
const openInNewTab = () => {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank')
  }
}

// 打开部署的网站
const openDeployedSite = () => {
  if (deployUrl.value) {
    window.open(deployUrl.value, '_blank')
  }
}

// iframe加载完成
const invalidatePreview = () => {
  previewState.invalidate(); previewReady.value = false
  previewProbe?.abort()
  clearTimeout(previewTimer)
}
const onIframeLoad = async (event: Event) => {
  const iframe = event.target as HTMLIFrameElement
  const identity = Number(iframe.dataset.loadId)
  if (iframe !== previewFrame.value || identity !== previewLoadId.value) return
  if (!isVue.value) {
    previewReady.value = true
    visualEditor.init(iframe); visualEditor.onIframeLoad()
    return
  }
  let cross = false
  try {
    const doc = iframe.contentWindow?.document
    if (doc && (/^(404|500|502|503)\b/.test(doc.title) || doc.querySelector('h1')?.textContent === 'Whitelabel Error Page'
      || (doc.title === 'Error' && /^Cannot GET\b/.test(doc.body?.textContent?.trim() ?? '')))) {
      invalidatePreview(); return
    }
    if (doc) {
      const failed = () => { if (identity === previewLoadId.value) invalidatePreview() }
      doc.addEventListener('error', failed, true)
      releaseResourceObserver?.()
      releaseResourceObserver = () => doc.removeEventListener('error', failed, true)
      const failedImage = [...doc.images].some(image => image.complete && !!image.currentSrc && image.naturalWidth === 0)
      const failedResource = iframe.contentWindow?.performance.getEntriesByType('resource')
        .some(entry => 'responseStatus' in entry && Number(entry.responseStatus) >= 400)
      if (failedImage || failedResource) { invalidatePreview(); return }
    }
  } catch { cross = true }
  // load 也会在空体 404 时触发；同源下以 HEAD 核实主文档，跨源保持有限可观测提示。
  if (!cross) {
    previewProbe?.abort()
    const probe = new AbortController(); previewProbe = probe
    try {
      const response = await fetch(iframe.contentWindow?.location.href || iframe.src, {
        method: 'HEAD', credentials: 'same-origin', cache: 'no-store', signal: probe.signal,
      })
      if (probe.signal.aborted || iframe !== previewFrame.value || identity !== previewLoadId.value) return
      if (!response.ok) { invalidatePreview(); return }
    } catch {
      if (!probe.signal.aborted && iframe === previewFrame.value && identity === previewLoadId.value) invalidatePreview()
      return
    }
  }
  if (previewState.loaded(identity, cross)) {
    clearTimeout(previewTimer)
    previewReady.value = true
    visualEditor.init(iframe)
    visualEditor.onIframeLoad()
  } else if (isVue.value) {
    invalidatePreview()
  }
}

// 编辑应用
const editApp = () => {
  if (appInfo.value?.id) {
    router.push(`/app/edit/${appInfo.value.id}`)
  }
}

// 删除应用
const deleteApp = async () => {
  if (!appInfo.value?.id) return

  try {
    const res = await deleteAppApi({ id: appInfo.value.id })
    if (res.data.code === 0) {
      message.success('删除成功')
      appDetailVisible.value = false
      router.push('/')
    } else {
      message.error('删除失败：' + res.data.message)
    }
  } catch (error) {
    console.error('删除失败：', error)
    message.error('删除失败')
  }
}

// 可视化编辑相关函数
const toggleEditMode = () => {
  // 检查 iframe 是否已经加载
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (!iframe) {
    message.warning('请等待页面加载完成')
    return
  }
  // 确保 visualEditor 已初始化
  if (!previewReady.value) {
    message.warning('请等待页面加载完成')
    return
  }
  const newEditMode = visualEditor.toggleEditMode()
  isEditMode.value = newEditMode
}

const clearSelectedElement = () => {
  selectedElementInfo.value = null
  visualEditor.clearSelection()
}

const getInputPlaceholder = () => {
  if (selectedElementInfo.value) {
    return `正在编辑 ${selectedElementInfo.value.tagName.toLowerCase()} 元素，描述您想要的修改...`
  }
  return '请描述你想生成的网站，越详细效果越好哦'
}

const handleInputKeydown = (event: KeyboardEvent) => {
  if (event.key !== 'Enter') {
    return
  }
  if (event.shiftKey) {
    return
  }
  event.preventDefault()
  sendMessage()
}

const handleIframeMessage = (event: MessageEvent) => {
  if (isVue.value && (layout.value !== 'preview' || isGenerating.value)) return
  if (event.source !== previewFrame.value?.contentWindow || !event.data || typeof event.data !== 'object') return
  visualEditor.handleIframeMessage(event)
}

watch(() => [route.params.id, loginUserStore.loginUser.id], () => {
  pageEpoch++; detachSession.value?.(); detachSession.value = null
  resetWorkspaceResize()
  activeLeftTab.value = 'chat'; chatScrollTop = 0; planScrollTop = 0
  planQuery.dispose(); lastSession.value = null; activeSessionAppId.value = null; sessionMessageKey.value = null
  messages.value = []; appInfo.value = undefined; historyLoaded.value = false; loadingHistory.value = false
  hasMoreHistory.value = false; lastCreateTime.value = undefined; isGenerating.value = false
  visualEditor.disableEditMode(); isEditMode.value = false; selectedElementInfo.value = null
  releaseResourceObserver?.(); previewProbe?.abort(); clearTimeout(previewTimer); previewState.reset()
  previewUrl.value = ''; previewReady.value = false; initialPreviewPending = true
  void fetchAppInfo()
})

// 页面加载时获取应用信息
onMounted(() => {
  fetchAppInfo()

  // 监听 iframe 消息
  window.addEventListener('message', handleIframeMessage)

  messagesContainer.value?.addEventListener('scroll', handleMessagesScroll, { passive: true })
})

// 清理资源
onUnmounted(() => {
  pageEpoch++; planQuery.dispose(); clearTimeout(previewTimer); releaseResourceObserver?.(); previewProbe?.abort()
  window.removeEventListener('message', handleIframeMessage)
  detachSession.value?.()
  detachSession.value = null
  messagesContainer.value?.removeEventListener('scroll', handleMessagesScroll)
})
</script>

<style scoped>
#appChatPage {
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 16px;
  padding-top: 6px;
  background: var(--bg-soft);
}

/* 顶部栏 */
.header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.code-gen-type-tag {
  font-size: 12px;
}

.app-name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.header-right {
  display: flex;
  gap: 12px;
}

/* 主要内容区域 */
.main-content {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 8px;
  overflow: hidden;
}

/* 左侧对话区域 */
.chat-section {
  flex: 2;
  display: flex;
  flex-direction: column;
  background: var(--bg-base);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.messages-container {
  flex: 0.9;
  padding: 16px;
  overflow-y: auto;
  scroll-behavior: auto;
}

.message-item {
  margin-bottom: 12px;
}

.user-message {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 8px;
}

.ai-message {
  display: flex;
  justify-content: flex-start;
  align-items: flex-start;
  gap: 8px;
}

.message-content {
  max-width: 80%;
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.5;
  word-wrap: break-word;
}

.user-message .message-content {
  background: var(--brand-primary);
  color: var(--text-inverse);
}

.ai-message .message-content {
  background: var(--bg-mute);
  color: var(--text-primary);
  padding: 8px 12px;
}

.message-avatar {
  flex-shrink: 0;
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-tertiary);
}

/* 加载更多按钮 */
.load-more-container {
  text-align: center;
  padding: 8px 0;
  margin-bottom: 16px;
}

/* 输入区域 */
.input-container {
  padding: 16px;
  background: var(--bg-base);
}

.input-wrapper {
  position: relative;
}

.input-wrapper .ant-input {
  padding-right: 50px;
}

.input-actions {
  position: absolute;
  bottom: 8px;
  right: 8px;
}

/* 右侧预览区域 */
.preview-section {
  flex: 3;
  display: flex;
  flex-direction: column;
  background: var(--bg-base);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid var(--border-light);
}

.preview-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.preview-actions {
  display: flex;
  gap: 8px;
}

.preview-content {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.preview-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-tertiary);
}

.preview-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-tertiary);
}

.preview-loading p {
  margin-top: 16px;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

.selected-element-alert {
  margin: 0 16px;
}

/* 响应式设计 */
@media (max-width: 1024px) {
  .main-content {
    flex-direction: column;
  }

  .chat-section,
  .preview-section {
    flex: none;
    height: 50vh;
  }
}

@media (max-width: 768px) {
  .header-bar {
    padding: 12px 16px;
  }

  .app-name {
    font-size: 16px;
  }

  .main-content {
    padding: 8px;
    gap: 8px;
  }

  .message-content {
    max-width: 85%;
  }

  /* 选中元素信息样式 */
  .selected-element-alert {
    margin: 0 16px;
  }

  .selected-element-info {
    line-height: 1.4;
  }

  .element-header {
    margin-bottom: 8px;
  }

  .element-details {
    margin-top: 8px;
  }

  .element-item {
    margin-bottom: 4px;
    font-size: 13px;
  }

  .element-item:last-child {
    margin-bottom: 0;
  }

  .element-tag {
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 14px;
    font-weight: 600;
    color: var(--brand-primary);
  }

  .element-id {
    color: var(--success);
    margin-left: 4px;
  }

  .element-class {
    color: var(--warning);
    margin-left: 4px;
  }

  .element-selector-code {
    font-family: 'Monaco', 'Menlo', monospace;
    background: var(--bg-mute);
    padding: 2px 4px;
    border-radius: 3px;
    font-size: 12px;
    color: var(--brand-secondary);
    border: 1px solid var(--border-light);
  }

  /* 编辑模式按钮样式 */
  .edit-mode-active {
    background-color: var(--success) !important;
    border-color: var(--success) !important;
    color: white !important;
  }

  .edit-mode-active:hover {
    background-color: #34d399 !important;
    border-color: #34d399 !important;
  }
}

/* 工具调用实时视图 */
.tool-calls-panel {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool-call-card {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-soft);
  padding: 10px 12px;
  font-size: 12px;
  transition: border-color var(--transition-base), background var(--transition-base), box-shadow var(--transition-base);
}
.tool-call-card:hover {
  border-color: var(--border-default);
  box-shadow: var(--shadow-sm);
}
.tool-call-card.is-done {
  border-color: var(--success-border);
  background: var(--success-bg);
}
.tool-call-card.is-build-failed {
  border-color: var(--warning-border);
  background: var(--warning-bg);
}
.tool-call-card.is-build-cancelled {
  border-color: var(--border-default);
  background: var(--bg-soft);
}
.tool-call-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.tool-call-name {
  font-weight: 600;
  color: var(--brand-primary);
}
.tool-call-path {
  color: var(--text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  word-break: break-all;
  flex: 1;
}
.tool-call-status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.status-icon {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
}
.status-icon.spin {
  animation: status-spin 1.2s linear infinite;
}
@keyframes status-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .status-icon.spin {
    animation: none;
  }
}
.tool-call-status.streaming {
  color: var(--warning);
  background: var(--warning-bg);
  border: 1px solid var(--warning-border);
}
.tool-call-status.done {
  color: var(--success);
  background: var(--success-bg);
  border: 1px solid var(--success-border);
}
.tool-call-status.error {
  color: var(--warning);
  background: var(--warning-bg);
  border: 1px solid var(--warning-border);
}
.build-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--text-secondary);
}
.build-status-text {
  font-weight: 600;
}
.build-status-text.success {
  color: var(--success);
}
.build-status-text.failed {
  color: var(--warning);
}
.build-status-text.cancelled {
  color: var(--text-secondary);
}
.build-stage {
  flex-shrink: 0;
  color: var(--text-tertiary);
}
.build-error-details {
  margin-top: 8px;
  color: var(--text-secondary);
}
.build-error-details summary {
  cursor: pointer;
  user-select: none;
}
.build-error-summary {
  max-height: 180px;
  overflow: auto;
  margin: 8px 0 0;
  padding: 10px;
  border: 1px solid var(--warning-border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  color: var(--text-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.tool-call-label {
  margin: 6px 0 2px;
  color: var(--text-tertiary);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
}
.tool-call-code {
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: var(--radius-sm);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
  border: 1px solid rgba(148, 163, 184, 0.15);
}
.tool-call-code.old {
  background: #1e1b1b;
  color: #fca5a5;
  border-color: rgba(239, 68, 68, 0.25);
}
.tool-call-code.new {
  background: #0c1f1a;
  color: #86efac;
  border-color: rgba(16, 185, 129, 0.25);
}

/* ========== 科技蓝紫主题覆盖 ========== */
#appChatPage {
  background:
    radial-gradient(circle at 0% 0%, rgba(59, 130, 246, 0.12) 0%, transparent 35%),
    radial-gradient(circle at 100% 100%, rgba(139, 92, 246, 0.10) 0%, transparent 35%),
    var(--bg-soft);
}

.header-bar {
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: saturate(180%) blur(12px);
  -webkit-backdrop-filter: saturate(180%) blur(12px);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.app-name {
  color: var(--text-primary);
  letter-spacing: -0.3px;
}

.main-content {
  padding: 6px 0 0;
  gap: 10px;
}

.chat-section,
.preview-section {
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  background: var(--bg-base);
}

.messages-container {
  background: linear-gradient(180deg, #ffffff 0%, var(--bg-soft) 100%);
}

.message-content {
  border-radius: 14px;
}

.user-message .message-content {
  background: var(--brand-gradient-pure);
  color: var(--text-inverse);
  box-shadow: 0 6px 16px rgba(59, 130, 246, 0.22);
}

.ai-message .message-content {
  background: var(--bg-base);
  border: 1px solid var(--border-light);
  color: var(--text-primary);
  box-shadow: var(--shadow-sm);
}

.input-container {
  background: var(--bg-base);
  border-top: 1px solid var(--border-light);
}

.input-wrapper .ant-input {
  border-radius: var(--radius-md);
  border-color: var(--border-light);
  background: var(--bg-soft);
  transition: border-color var(--transition-base), box-shadow var(--transition-base), background var(--transition-base);
}

.input-wrapper .ant-input:hover {
  border-color: var(--border-default);
  background: var(--bg-base);
}

.input-wrapper .ant-input:focus {
  border-color: var(--brand-primary);
  background: var(--bg-base);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.14);
}

.input-shortcut-hint {
  position: absolute;
  left: 12px;
  bottom: 14px;
  font-size: 12px;
  color: var(--text-muted);
  pointer-events: none;
}

.input-actions {
  right: 10px;
  bottom: 10px;
}

.input-actions :deep(.ant-btn-primary) {
  border: none;
  border-radius: var(--radius-sm);
  background: var(--brand-gradient-pure);
  box-shadow: 0 6px 14px rgba(59, 130, 246, 0.28);
  transition: transform var(--transition-base), box-shadow var(--transition-base);
}

.input-actions :deep(.ant-btn-primary:not(:disabled):hover) {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(59, 130, 246, 0.4);
}

.preview-header {
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-soft);
}

.preview-header h3 {
  color: var(--text-primary);
}

.preview-placeholder,
.preview-loading {
  color: var(--text-tertiary);
}

.placeholder-icon {
  width: 88px;
  height: 88px;
  border-radius: var(--radius-pill);
  background: var(--brand-gradient-soft);
  color: var(--brand-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
  box-shadow: var(--shadow-glow);
}

.placeholder-icon svg {
  width: 44px;
  height: 44px;
}

.header-right :deep(.ant-btn-primary:not(.ant-btn-background-ghost)) {
  border: none;
  background: var(--brand-gradient-pure);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.25);
}

.header-right :deep(.ant-btn-primary:not(.ant-btn-background-ghost):hover) {
  box-shadow: 0 6px 16px rgba(59, 130, 246, 0.35);
  transform: translateY(-1px);
}

.header-bar {
  padding: 7px 12px;
}

.header-right {
  gap: 8px;
}

.header-right :deep(.ant-btn) {
  height: 28px;
  padding: 0 12px;
}

@media (min-width: 769px) {
  .result-chat .workspace-toolbar,
  .preview-header {
    height: 32px;
    min-height: 32px;
    box-sizing: border-box;
    padding: 0 16px;
    flex-shrink: 0;
  }

  .preview-actions :deep(.ant-btn) {
    height: 28px;
    padding-top: 0;
    padding-bottom: 0;
  }
}

@media (max-width: 768px) {
  .header-bar {
    border-radius: var(--radius-md);
    padding: 10px 12px;
  }

  .chat-section,
  .preview-section {
    border-radius: var(--radius-md);
  }
}
.workspace-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 10px 16px; border-bottom: 1px solid var(--border-light); font-size: 13px; flex-shrink: 0; }
.previous-unavailable { color: var(--text-secondary); font-size: 12px; }
#appChatPage { height: calc(100dvh - 64px); box-sizing: border-box; }
.generation-plan-sidebar { width: 320px; max-width: 34%; flex-shrink: 0; overflow-y: auto; padding: 16px; background: var(--bg-base); border-left: 1px solid var(--border-light); }
.workspace-plan-details > summary { display: none; }
.left-tabs { display: flex; gap: 16px; }
.left-tabs button { padding: 0; border: 0; background: transparent; color: var(--text-secondary); font: inherit; cursor: pointer; }
.left-tabs button[aria-selected='true'] { color: var(--text-primary); font-weight: 600; box-shadow: 0 2px 0 var(--text-primary); }
.plan-tab-panel { flex: 1; min-height: 0; overflow-y: auto; padding: 16px; }
.result-chat .messages-container { flex: 1; min-height: 0; }
.result-chat .input-container { padding: 8px 16px; flex-shrink: 0; }
.preview-note { position: absolute; bottom: 0; left: 0; right: 0; background: var(--bg-base); padding: 8px; margin: 0; font-size: 12px; }
.chat-section, .preview-section { min-width: 0; min-height: 0; }
.main-content { min-height: 0; }
.main-content.resizable-workspace {
  display: grid;
  grid-template-columns: minmax(0, var(--left-share)) 1px minmax(0, var(--right-share));
  grid-template-rows: minmax(0, 1fr);
  gap: 0;
}
/* 页签已有滚动位置恢复逻辑，避免浏览器在网格重排后再次调整计划位置。 */
.resizable-workspace .plan-tab-panel { overflow-anchor: none; }
.resizable-workspace > .chat-section {
  border-right: 0;
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  box-shadow: none;
}
.resizable-workspace > .preview-section {
  border-left: 0;
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
  box-shadow: none;
}
.resizable-workspace .preview-header { flex-wrap: nowrap; }
.resizable-workspace .preview-header h3 { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resizable-workspace .preview-actions { flex-shrink: 0; }
.workspace-separator { position: relative; z-index: 1; background: var(--border-light); cursor: col-resize; touch-action: none; }
.workspace-separator::after { content: ''; position: absolute; top: 0; bottom: 0; left: -4px; width: 9px; }
.workspace-separator:hover,
.workspace-separator:focus-visible,
.is-resizing .workspace-separator { background: var(--brand-primary); }
.workspace-separator:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: 1px; }
.resizable-workspace.is-resizing { cursor: col-resize; user-select: none; }
/* 拖动跨越 iframe 时，保持指针事件由分隔线捕获。 */
.is-resizing .preview-iframe { pointer-events: none; }
.generation-workspace .chat-section { flex: 1; box-shadow: none; border-radius: 0; }
.generation-workspace .messages-container { flex: 1; min-height: 0; }
.generation-workspace .ai-message .message-content { width: 100%; max-width: calc(100% - 40px); }
.generation-workspace .ai-message .message-content.status-only { width: fit-content; }
.header-left { min-width: 0; }
.app-name { overflow-wrap: anywhere; }
.preview-header { flex-wrap: wrap; gap: 8px; }
@media (min-width: 769px) and (max-width: 1024px) {
  .generation-workspace { flex-direction: row; }
  .generation-workspace .chat-section { height: auto; }
}
@media (max-width: 768px) {
  #appChatPage { padding: 4px; height: calc(100dvh - 64px); min-height: 500px; }
  .header-bar { flex-wrap: wrap; gap: 8px; padding: 8px; }
  .header-right { gap: 6px; flex-wrap: wrap; }
  .main-content { padding: 4px; gap: 8px; }
  .chat-section, .preview-section { flex: 1; height: auto; }
  .generation-workspace { flex-direction: column; }
  .generation-plan-sidebar { order: -1; width: 100%; max-width: 100%; max-height: 30%; padding: 8px 12px; border-left: 0; border-bottom: 1px solid var(--border-light); box-sizing: border-box; }
  .workspace-plan-details > summary { display: list-item; cursor: pointer; font-size: 13px; }
  .workspace-plan-details .plan-panel { padding-top: 10px; }
  .generation-workspace .chat-section { min-height: 260px; }
  .generation-workspace .messages-container { padding: 8px; }
  .message-content { min-width: 0; }
  .workspace-toolbar { padding: 6px 10px; }
  .preview-header {
    height: auto;
    min-height: 0;
    box-sizing: border-box;
    padding: 6px 10px;
  }
  .preview-section { min-height: 240px; }
}
</style>
