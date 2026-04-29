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
                    :class="{ 'is-done': view.status === 'done' }"
                  >
                    <div class="tool-call-header">
                      <span class="tool-call-name">{{ view.name }}</span>
                      <span v-if="view.args.relativeFilePath" class="tool-call-path">
                        {{ view.args.relativeFilePath }}
                      </span>
                      <span class="tool-call-status" :class="view.status">
                        <template v-if="view.status === 'done'">✓ 已完成</template>
                        <template v-else>⏳ 执行中</template>
                      </span>
                    </div>
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
                <div v-if="message.loading" class="loading-indicator">
                  <a-spin size="small" />
                  <span>AI 正在思考...</span>
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
          <div v-if="!previewUrl && !isGenerating && !isBuildingVue" class="preview-placeholder">
            <div class="placeholder-icon">🌐</div>
            <p>网站文件生成完成后将在这里展示</p>
          </div>
          <div v-else-if="isBuildingVue" class="preview-loading">
            <a-spin size="large" />
            <p>正在构建 Vue 项目，可能需要几秒钟...</p>
          </div>
          <div v-else-if="isGenerating" class="preview-loading">
            <a-spin size="large" />
            <p>{{ generatingStatusText }}</p>
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
  startGenerationSession,
  subscribeGenerationSession,
  getGenerationSessionSnapshot,
  clearGenerationSession,
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
import { API_BASE_URL, STATIC_BASE_URL, getStaticPreviewUrl } from '@/config/env'
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
// Vue 构建就绪探测:后端 npm install + build 是异步的,前端用 HEAD 轮询 dist/index.html 来判定完成
const isBuildingVue = ref(false)
let buildWaitTimer: ReturnType<typeof setTimeout> | null = null

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

const isCodeChecking = computed(() => {
  if (!isGenerating.value) {
    return false
  }
  if (appInfo.value?.codeGenType !== CodeGenTypeEnum.VUE_PROJECT) {
    return false
  }
  const idx = sessionMessageIndex.value
  if (idx === null) {
    return false
  }
  const currentAiMessage = messages.value[idx]
  const toolCalls = currentAiMessage?.toolCalls
  if (!toolCalls || toolCalls.size === 0) {
    return false
  }
  for (const [, view] of toolCalls) {
    if (view.name === 'exit' && view.status === 'done') {
      return true
    }
  }
  return false
})

const generatingStatusText = computed(() => {
  return isCodeChecking.value ? '正在检查代码，请耐心等待...' : '正在生成网站...'
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
      } else if (sessionSnapshot?.status === 'done') {
        if (appInfo.value?.codeGenType === CodeGenTypeEnum.VUE_PROJECT) {
          await waitForVueBuild()
        } else if (messages.value.length >= 2) {
          updatePreview()
        }
        clearGenerationSession(id)
      } else if (sessionSnapshot?.status === 'error') {
        if (messages.value.length >= 2) {
          updatePreview()
        }
        isGenerating.value = false
        clearGenerationSession(id)
      } else if (messages.value.length >= 2) {
        updatePreview()
      }
      // 检查是否需要自动发送初始提示词
      // 只有在是自己的应用且没有对话历史时才自动发送
      if (
        appInfo.value.initPrompt &&
        isOwner.value &&
        messages.value.length === 0 &&
        historyLoaded.value
      ) {
        await sendInitialMessage(appInfo.value.initPrompt)
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
  const idx = sessionMessageIndex.value
  if (idx === null || !messages.value[idx]) {
    return
  }
  const aiMessage = messages.value[idx]
  aiMessage.content = snapshot.content
  aiMessage.toolCalls = new Map(snapshot.toolCalls)
  const hasAnyOutput = snapshot.content.length > 0 || snapshot.toolCalls.size > 0
  aiMessage.loading = !hasAnyOutput && snapshot.loading
  isGenerating.value = snapshot.status === 'streaming'
}

const attachSessionListener = (targetAppId: string) => {
  detachSession.value?.()
  detachSession.value = subscribeGenerationSession(targetAppId, (snapshot, eventType) => {
    if (snapshot.appId !== activeSessionAppId.value) {
      return
    }
    applySessionSnapshot(snapshot)
    scrollToBottom()
    if (eventType === 'business-error') {
      const errorMsg = snapshot.errorMessage || '生成过程中出现错误'
      message.error(errorMsg)
      return
    }
    if (eventType === 'error') {
      message.error(snapshot.errorMessage || '生成失败，请重试')
      return
    }
    if (eventType === 'done') {
      finalizeGeneration()
    }
  })
}

const startGeneration = async (inputMessage: string, aiMessageIndex: number) => {
  const targetAppId = appId.value
  if (!targetAppId) {
    message.error('应用ID不存在')
    return
  }
  activeSessionAppId.value = targetAppId
  sessionMessageIndex.value = aiMessageIndex
  isGenerating.value = true

  startGenerationSession({
    appId: targetAppId,
    userMessage: inputMessage,
    baseURL: request.defaults.baseURL || API_BASE_URL,
    renderMode:
      appInfo.value?.codeGenType === CodeGenTypeEnum.VUE_PROJECT ? 'direct' : 'throttled',
    throttleMs: 100,
  })
  attachSessionListener(targetAppId)
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
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
    toolCalls: new Map(),
  })
  activeSessionAppId.value = targetAppId
  sessionMessageIndex.value = aiMessageIndex
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
const updatePreview = () => {
  if (appId.value) {
    const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
    const newPreviewUrl = getStaticPreviewUrl(codeGenType, appId.value)
    previewUrl.value = newPreviewUrl
    previewReady.value = true
  }
}

// 等 Vue 项目构建就绪
// 原子性依据:Vite 在所有 bundle 就绪后才写 dist/index.html,所以该文件存在 = 构建完成
// 破缓存:StaticResourceController 未设 Cache-Control,一次 404 会被浏览器按启发式规则缓存
const waitForVueBuild = async () => {
  if (!appId.value) return
  if (buildWaitTimer) {
    clearTimeout(buildWaitTimer)
    buildWaitTimer = null
  }
  isBuildingVue.value = true
  const url = `${STATIC_BASE_URL}/vue_project_${appId.value}/dist/index.html`
  const startAt = Date.now()
  const TIMEOUT_MS = 8 * 60 * 1000
  const INTERVAL_MS = 1500
  try {
    while (Date.now() - startAt < TIMEOUT_MS) {
      try {
        const resp = await fetch(`${url}?_t=${Date.now()}`, {
          method: 'HEAD',
          credentials: 'include',
          cache: 'no-store',
        })
        if (resp.ok) {
          updatePreview()
          return
        }
      } catch {
        // 网络抖动/连接被中断,静默重试
      }
      await new Promise<void>((resolve) => {
        buildWaitTimer = setTimeout(() => resolve(), INTERVAL_MS)
      })
    }
    message.error('构建超时，请重试')
  } finally {
    isBuildingVue.value = false
    buildWaitTimer = null
  }
}

// 流式生成收尾:Vue 走轮询探测,其他类型直接刷预览
const finalizeGeneration = async () => {
  isGenerating.value = false
  await fetchAppInfo()
  const codeGenType = appInfo.value?.codeGenType
  if (codeGenType === CodeGenTypeEnum.VUE_PROJECT) {
    await waitForVueBuild()
  } else {
    updatePreview()
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
  if (buildWaitTimer) {
    clearTimeout(buildWaitTimer)
    buildWaitTimer = null
  }
})
</script>

<style scoped>
#appChatPage {
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 16px;
  background: #fdfdfd;
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
  color: #1a1a1a;
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
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.messages-container {
  flex: 0.9;
  padding: 16px;
  overflow-y: auto;
  scroll-behavior: smooth;
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
  background: #1890ff;
  color: white;
}

.ai-message .message-content {
  background: #f5f5f5;
  color: #1a1a1a;
  padding: 8px 12px;
}

.message-avatar {
  flex-shrink: 0;
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #666;
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
  background: white;
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
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #e8e8e8;
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
  color: #666;
}

.placeholder-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.preview-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #666;
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
    color: #007bff;
  }

  .element-id {
    color: #28a745;
    margin-left: 4px;
  }

  .element-class {
    color: #ffc107;
    margin-left: 4px;
  }

  .element-selector-code {
    font-family: 'Monaco', 'Menlo', monospace;
    background: #f6f8fa;
    padding: 2px 4px;
    border-radius: 3px;
    font-size: 12px;
    color: #d73a49;
    border: 1px solid #e1e4e8;
  }

  /* 编辑模式按钮样式 */
  .edit-mode-active {
    background-color: #52c41a !important;
    border-color: #52c41a !important;
    color: white !important;
  }

  .edit-mode-active:hover {
    background-color: #73d13d !important;
    border-color: #73d13d !important;
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
  border: 1px solid #e4e7eb;
  border-radius: 8px;
  background: #fafbfc;
  padding: 8px 10px;
  font-size: 12px;
  transition: border-color 0.2s, background 0.2s;
}
.tool-call-card.is-done {
  border-color: #b7eb8f;
  background: #f6ffed;
}
.tool-call-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.tool-call-name {
  font-weight: 600;
  color: #1677ff;
}
.tool-call-path {
  color: #595959;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  word-break: break-all;
  flex: 1;
}
.tool-call-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
  white-space: nowrap;
}
.tool-call-status.streaming {
  color: #d46b08;
  background: #fff7e6;
  border: 1px solid #ffd591;
}
.tool-call-status.done {
  color: #389e0d;
  background: #f6ffed;
  border: 1px solid #b7eb8f;
}
.tool-call-label {
  margin: 4px 0 2px;
  color: #8c8c8c;
}
.tool-call-code {
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 8px;
  background: #0b1021;
  color: #e6edf3;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.tool-call-code.old {
  background: #3a1f1f;
  color: #fecaca;
}
.tool-call-code.new {
  background: #0f2a1a;
  color: #bbf7d0;
}

/* Meituan-like visual overrides */
#appChatPage {
  background:
    radial-gradient(circle at 100% 0%, rgba(255, 152, 63, 0.16) 0%, transparent 30%),
    #f8f6f2;
}

.header-bar {
  background: linear-gradient(135deg, #fffaf2 0%, #ffffff 100%);
  border: 1px solid #f4e4cd;
  border-radius: 16px;
  box-shadow: 0 8px 18px rgba(52, 39, 21, 0.06);
}

.app-name {
  color: #2f2a20;
}

.main-content {
  padding: 10px 0 0;
  gap: 14px;
}

.chat-section,
.preview-section {
  border: 1px solid #f2e8da;
  border-radius: 16px;
  box-shadow: 0 10px 20px rgba(47, 35, 18, 0.07);
}

.messages-container {
  background: linear-gradient(180deg, #fffefc 0%, #fff8f0 100%);
}

.message-content {
  border-radius: 14px;
}

.user-message .message-content {
  background: linear-gradient(135deg, #ff8f1f 0%, #ff6a00 100%);
}

.ai-message .message-content {
  background: #fff;
  border: 1px solid #f2e6d5;
  color: #3a352d;
}

.input-container {
  background: #fff;
  border-top: 1px solid #f2e8da;
}

.input-wrapper .ant-input {
  border-radius: 12px;
  border-color: #f2dfc5;
  background: #fffdf9;
}

.input-wrapper .ant-input:focus {
  border-color: #ffb36e;
  box-shadow: 0 0 0 3px rgba(255, 126, 22, 0.14);
}

.input-shortcut-hint {
  position: absolute;
  left: 10px;
  bottom: 12px;
  font-size: 12px;
  color: #8a816f;
}

.input-actions {
  right: 10px;
  bottom: 10px;
}

.input-actions :deep(.ant-btn-primary) {
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #ff8f1f 0%, #ff6a00 100%);
  box-shadow: 0 8px 16px rgba(255, 106, 0, 0.24);
}

.preview-header {
  border-bottom: 1px solid #f2e8da;
  background: #fffaf3;
}

.preview-header h3 {
  color: #3b3428;
}

.preview-placeholder,
.preview-loading {
  color: #7e7566;
}

.placeholder-icon {
  width: 68px;
  height: 68px;
  border-radius: 999px;
  background: #fff0dd;
  color: #de6500;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
}

.tool-call-card {
  border-radius: 10px;
  border-color: #f0dfc9;
  background: #fff;
}

.tool-call-name {
  color: #dc6200;
}

@media (max-width: 768px) {
  .header-bar {
    border-radius: 12px;
    padding: 10px 12px;
  }

  .chat-section,
  .preview-section {
    border-radius: 12px;
  }
}
</style>
