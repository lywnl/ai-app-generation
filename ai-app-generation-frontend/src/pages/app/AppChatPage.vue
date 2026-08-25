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
    <div class="main-content">
      <!-- 左侧对话区域 -->
      <div class="chat-section">
        <!-- 消息区域 -->
        <div class="messages-container" ref="messagesContainer">
          <!-- 加载更多按钮 -->
          <div v-if="hasMoreHistory" class="load-more-container">
            <a-button type="link" @click="loadMoreHistory" :loading="loadingHistory" size="small">
              加载更多历史消息
            </a-button>
          </div>
          <div v-for="(message, index) in messages" :key="index" class="message-item">
            <div v-if="message.type === 'user'" class="user-message">
              <div class="message-content">{{ message.content }}</div>
              <div class="message-avatar">
                <a-avatar :src="loginUserStore.loginUser.userAvatar" />
              </div>
            </div>
            <div v-else class="ai-message">
              <div class="message-avatar">
                <a-avatar :src="aiAvatar" />
              </div>
              <div class="message-content">
                <MarkdownRenderer v-if="message.content" :content="message.content" />
                <!-- 工具调用实时视图:文件路径 + 流式内容预览 -->
                <div
                  v-if="message.toolCalls && message.toolCalls.size > 0"
                  class="tool-calls-panel"
                >
                  <div
                    v-for="[id, view] in message.toolCalls"
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
                      <span class="tool-call-name">{{ view.name }}</span>
                      <span v-if="view.args.relativeFilePath" class="tool-call-path">
                        {{ view.args.relativeFilePath }}
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
                      message.internalOutputRecovery || 'idle',
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
          v-if="selectedElementInfo"
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
        <div class="input-container">
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
                @click="sendMessage"
                :loading="isGenerating"
                :disabled="!isOwner"
              >
                <template #icon>
                  <SendOutlined />
                </template>
              </a-button>
            </div>
          </div>
        </div>
      </div>
      <!-- 右侧网页展示区域 -->
      <div class="preview-section">
        <div class="preview-header">
          <h3>生成后的网页展示</h3>
          <div class="preview-actions">
            <a-button
              v-if="isOwner && previewUrl"
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
            <a-button v-if="previewUrl" type="link" @click="openInNewTab">
              <template #icon>
                <ExportOutlined />
              </template>
              新窗口打开
            </a-button>
          </div>
        </div>
        <div class="preview-content">
          <div v-if="!previewUrl && !isGenerating" class="preview-placeholder">
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
          <div v-else-if="isGenerating" class="preview-loading" role="status" aria-live="polite">
            <a-spin size="large" />
            <p>{{
              getGenerationStatusText(
                contextCompression,
                internalOutputRecovery,
                incompleteToolChainRecovery,
                toolProtocolRecovery,
                '正在生成网站...',
              )
            }}</p>
          </div>
          <iframe
            v-else
            :src="previewUrl"
            class="preview-iframe"
            frameborder="0"
            @load="onIframeLoad"
          ></iframe>
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
import { ref, onMounted, nextTick, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  getAppVoById,
  deployApp as deployAppApi,
  deleteApp as deleteAppApi,
} from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { CodeGenTypeEnum, formatCodeGenType } from '@/utils/codeGenTypes'
import request from '@/request'
import {
  type ToolCallView,
  type GenerationSessionSnapshot,
  type GenerationOutcome,
  type ContextCompressionState,
  type InternalOutputRecoveryState,
  type ToolProtocolRecoveryState,
  type IncompleteToolChainRecoveryState,
  startGenerationSession,
  subscribeGenerationSession,
  getGenerationSessionSnapshot,
  clearGenerationSession,
  getBuildProjectDisplayState,
  getBuildProjectVisualState,
  shouldRefreshGenerationPreview,
  getGenerationStatusText,
  shouldShowGenerationStatus,
} from '@/utils/generationSession'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

/**
 * 代码块自动贴底滚动指令:
 * - 只在"用户没有手动向上滚"时才贴底(阈值 16px);否则让用户保持阅读位置,避免骚扰。
 * - 绑定到 pre 元素,每次 updated(即 DELTA 追加后 DOM 重排)尝试滚到底。
 */
const vAutoScroll = {
  mounted(el: HTMLElement) {
    el.scrollTop = el.scrollHeight
    el.dataset.stickBottom = '1'
    const onScroll = () => {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 16
      el.dataset.stickBottom = atBottom ? '1' : '0'
    }
    ;(el as HTMLElement & { __autoScrollHandler__?: () => void }).__autoScrollHandler__ = onScroll
    el.addEventListener('scroll', onScroll)
  },
  updated(el: HTMLElement) {
    if (el.dataset.stickBottom === '1') {
      el.scrollTop = el.scrollHeight
    }
  },
  beforeUnmount(el: HTMLElement) {
    const handler = (el as HTMLElement & { __autoScrollHandler__?: () => void })
      .__autoScrollHandler__
    if (handler) {
      el.removeEventListener('scroll', handler)
      delete (el as HTMLElement & { __autoScrollHandler__?: () => void }).__autoScrollHandler__
    }
  },
}
import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'
import { API_BASE_URL, getStaticPreviewUrl } from '@/config/env'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'

import {
  CloudUploadOutlined,
  SendOutlined,
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
  type: 'user' | 'ai'
  content: string
  loading?: boolean
  contextCompression?: ContextCompressionState
  internalOutputRecovery?: InternalOutputRecoveryState
  toolProtocolRecovery?: ToolProtocolRecoveryState
  incompleteToolChainRecovery?: IncompleteToolChainRecoveryState
  createTime?: string
  /** tool call id → 当前调用参数视图;保持插入顺序用 Map */
  toolCalls?: Map<string, ToolCallView>
}

const messages = ref<Message[]>([])
const userInput = ref('')
const isGenerating = ref(false)
const messagesContainer = ref<HTMLElement>()
const activeSessionAppId = ref<string | null>(null)
const sessionMessageIndex = ref<number | null>(null)
const detachSession = ref<null | (() => void)>(null)
const contextCompression = ref<ContextCompressionState>('idle')
const internalOutputRecovery = ref<InternalOutputRecoveryState>('idle')
const toolProtocolRecovery = ref<ToolProtocolRecoveryState>('idle')
const incompleteToolChainRecovery =
  ref<IncompleteToolChainRecoveryState>('idle')

// 外层消息容器智能吸底状态:用户上滑取消吸底,滑回接近底部(距底 ≤ 32px)恢复吸底。
// 阈值 32 不是宽容,是流式场景下 scrollHeight 持续增长的兜底 —— 1px 在抖动中不可达。
const stickBottom = ref(true)
const handleMessagesScroll = () => {
  const el = messagesContainer.value
  if (!el) return
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
    if (res.data.code === 0 && res.data.data) {
      const chatHistories = res.data.data.records || []
      if (chatHistories.length > 0) {
        // 将对话历史转换为消息格式，并按时间正序排列（老消息在前）
        const historyMessages: Message[] = chatHistories
          .map((chat) => ({
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
    loadingHistory.value = false
  }
}


// 加载更多历史消息
const loadMoreHistory = async () => {
  await loadChatHistory(true)
}

// 获取应用信息
const fetchAppInfo = async () => {
  const id = route.params.id as string
  if (!id) {
    message.error('应用ID不存在')
    router.push('/')
    return
  }

  appId.value = id

  try {
    const res = await getAppVoById({ id: id as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data

      // 先加载对话历史
      await loadChatHistory()
      const sessionSnapshot = getGenerationSessionSnapshot(id)
      if (sessionSnapshot?.status === 'streaming') {
        if (messages.value.length >= 2) {
          updatePreview()
        }
        restoreActiveSessionIfNeeded()
      } else if (sessionSnapshot) {
        createSessionMessage(sessionSnapshot)
        applySessionSnapshot(sessionSnapshot)
        if (shouldRefreshGenerationPreview(sessionSnapshot)) {
          updatePreview(true)
        } else if (messages.value.length >= 2 && !previewUrl.value) {
          updatePreview()
        }
        isGenerating.value = false
        clearGenerationSession(id)
      } else if (messages.value.length >= 2) {
        updatePreview()
      }
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
    console.error('获取应用信息失败：', error)
    message.error('获取应用信息失败')
    router.push('/')
  }
}

const applySessionSnapshot = (snapshot: GenerationSessionSnapshot) => {
  contextCompression.value = snapshot.contextCompression
  internalOutputRecovery.value = snapshot.internalOutputRecovery
  toolProtocolRecovery.value = snapshot.toolProtocolRecovery
  incompleteToolChainRecovery.value = snapshot.incompleteToolChainRecovery
  const idx = sessionMessageIndex.value
  if (idx === null || !messages.value[idx]) {
    return
  }
  const aiMessage = messages.value[idx]
  aiMessage.content = snapshot.content
  aiMessage.toolCalls = new Map(snapshot.toolCalls)
  aiMessage.contextCompression = snapshot.contextCompression
  aiMessage.internalOutputRecovery = snapshot.internalOutputRecovery
  aiMessage.toolProtocolRecovery = snapshot.toolProtocolRecovery
  aiMessage.incompleteToolChainRecovery = snapshot.incompleteToolChainRecovery
  const hasVisibleOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0
  aiMessage.loading = shouldShowGenerationStatus(
    snapshot.loading,
    snapshot.contextCompression,
    snapshot.internalOutputRecovery,
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
    sessionMessageIndex.value = lastMessageIndex
    return
  }
  sessionMessageIndex.value = messages.value.length
  messages.value.push({
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
      if (snapshot.outcome !== 'succeeded' && snapshot.outcome !== 'answered') {
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
  sessionMessageIndex.value = aiMessageIndex
  isGenerating.value = true

  attachSessionListener(targetAppId)
  startGenerationSession({
    appId: targetAppId,
    userMessage: inputMessage,
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

const restoreActiveSessionIfNeeded = () => {
  const targetAppId = appId.value
  if (!targetAppId || isGenerating.value || sessionMessageIndex.value !== null) {
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
    type: 'user',
    content: prompt,
  })

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
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
    previewUrl.value = newPreviewUrl
  }
}

// 后端回合终态是唯一刷新依据；失败时保留旧预览，便于用户继续对照修复。
const finalizeGeneration = (snapshot: GenerationSessionSnapshot) => {
  isGenerating.value = false
  if (shouldRefreshGenerationPreview(snapshot)) {
    updatePreview(true)
  }
  const currentAppId = activeSessionAppId.value
  if (currentAppId) {
    clearGenerationSession(currentAppId)
  }
  activeSessionAppId.value = null
  sessionMessageIndex.value = null
  detachSession.value?.()
  detachSession.value = null
}

// 滚动到底部
// 必须在 nextTick 里滚:流式 chunk 同步调用时 DOM 尚未更新,scrollHeight 还是旧值,
// 直接 scrollTop = scrollHeight 会把滚动条卡在"旧底部"即新视图的中间。
// nextTick 内二次检查 stickBottom:等待期间用户可能上滑,避免覆盖用户意图。
const scrollToBottom = () => {
  if (!stickBottom.value) return
  nextTick(() => {
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
const onIframeLoad = () => {
  previewReady.value = true
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (iframe) {
    visualEditor.init(iframe)
    visualEditor.onIframeLoad()
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
  visualEditor.handleIframeMessage(event)
}

// 页面加载时获取应用信息
onMounted(() => {
  fetchAppInfo()

  // 监听 iframe 消息
  window.addEventListener('message', handleIframeMessage)

  messagesContainer.value?.addEventListener('scroll', handleMessagesScroll, { passive: true })
})

// 清理资源
onUnmounted(() => {
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
  max-width: 70%;
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
  padding: 10px 0 0;
  gap: 14px;
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
</style>
