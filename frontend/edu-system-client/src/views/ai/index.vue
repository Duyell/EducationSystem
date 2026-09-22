<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotSquare } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useConversations } from '@/composables/useConversations'
import { useAgentChat } from '@/composables/useAgentChat'
import ConversationSidebar from './components/ConversationSidebar.vue'
import ChatMessageList from './components/ChatMessageList.vue'
import ChatInput from './components/ChatInput.vue'
import type { AiConversation, ChatMsg } from '@/types/models'

/**
 * AI 助手页（组合面）。
 *
 * 三层分工，刻意分开：
 * - **会话数据**（列表/新建/删除/历史）→ `useConversations`；
 * - **对话协议**（SSE 流式、确认卡片、消息状态）→ `useAgentChat`；
 * - **本组件**：只做编排——把两者接起来（会话 id 同步 URL、流结束刷列表）并组合三个渲染组件。
 *
 * 视图里不再有 DOM 操作、不再解析 SSE 帧：那些分别归消息组件与对话 composable。
 */

const route = useRoute()
const router = useRouter()

const {
  conversations,
  activeId,
  listLoading,
  historyLoading,
  loadList,
  createConversation,
  removeConversation,
  openConversation,
  startNewConversation,
} = useConversations()

const { messages, loading, send, confirm, resetMessages, replaceMessages } = useAgentChat({
  // 服务端兜底新建会话后，把 id 落到 URL 与侧栏（刷新/分享才能回到同一个会话）
  onConversation: (conversationId) => {
    if (conversationId === activeId.value) return
    activeId.value = conversationId
    syncUrl(conversationId)
  },
  // 一轮结束就刷一次列表：标题由首条用户消息生成，只在本地拼标题会漏掉这个变化
  onStreamEnd: () => loadList(),
})

const inputText = ref('')
const aiModel = ref('')
const userRole = ref('')

const userInfo = computed<{ role?: string }>(() => {
  try {
    return JSON.parse(sessionStorage.getItem('user') || '{}')
  } catch {
    return {}
  }
})

const examplePrompts = computed(() => {
  const role = userRole.value
  if (role === 'admin') {
    return ['查看系统统计数据', '列出所有学生', '查询教师列表', '查看课程列表']
  } else if (role === 'teacher') {
    return ['查看我的课程', '查看我的评价', '怎么录入成绩？']
  } else {
    return ['查看我的课程', '查看我的成绩', '有哪些课程可以选？']
  }
})

/** 把当前会话同步到 URL；空串表示"新对话"（不带参数） */
const syncUrl = (conversationId: string) => {
  const query = { ...route.query }
  if (conversationId) {
    query.conversationId = conversationId
  } else {
    delete query.conversationId
  }
  // replace 而非 push：切会话不该在浏览器历史里堆一大串
  void router.replace({ query })
}

/** 示例问题：直接当作用户提问发出（输入框不经过，避免"点了没反应"的错觉） */
const handlePickPrompt = (text: string) => {
  if (loading.value) return
  void send(text, activeId.value)
}

const handleSend = () => {
  if (!inputText.value.trim() || loading.value) return
  const text = inputText.value.trim()
  inputText.value = ''
  void send(text, activeId.value)
}

/** 正在回复时不允许切会话/新建：SSE 流与消息区是同一份状态，中途换会话会写错地方 */
const blockWhileReplying = (): boolean => {
  if (!loading.value) return false
  ElMessage.info('正在回复中，请稍后再操作')
  return true
}

/** 打开历史会话：把落库消息还原到聊天区 */
const loadConversation = async (id: string) => {
  const detail = await openConversation(id)
  if (!detail) {
    // 越权/不存在：服务端给的原因已由拦截器提示；这里补一句"接下来怎么办"并回落，别把用户卡在打不开的会话上
    ElMessage.warning('会话不存在或无权访问，已切换到新对话')
    startNewConversation()
    resetMessages()
    syncUrl('')
    return
  }
  replaceMessages(
    detail.messages
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .map<ChatMsg>((m) => ({ role: m.role, type: 'text', content: m.content })),
  )
}

const handleSelectConversation = async (id: string) => {
  if (id === activeId.value || blockWhileReplying()) return
  activeId.value = id
  syncUrl(id)
  await loadConversation(id)
}

/** 新对话：真的建一个会话（POST /ai/conversations），这样刷新/URL 立刻可用 */
const handleCreateConversation = async () => {
  if (blockWhileReplying()) return
  const created = await createConversation()
  if (!created) {
    ElMessage.error('新建会话失败，请稍后再试')
    return
  }
  resetMessages()
  syncUrl(created.id)
}

const handleRemoveConversation = async (conversation: AiConversation) => {
  try {
    await ElMessageBox.confirm(
      `确定删除会话「${conversation.title || '新对话'}」吗？该会话的消息会一并删除。`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 用户取消：ElMessageBox 以 reject 结束，必须接住（本仓库踩过的坑）
    return
  }
  // 先记住"删的是不是当前会话"：删除后 activeId 会被清空，事后再判断就晚了
  const wasActive = conversation.id === activeId.value
  const ok = await removeConversation(conversation.id)
  if (!ok) return
  if (wasActive) {
    resetMessages()
    syncUrl('')
  }
}

/** 带 ?conversationId= 打开/刷新时恢复该会话 */
const restoreFromUrl = async () => {
  const queryId = typeof route.query.conversationId === 'string' ? route.query.conversationId : ''
  if (!queryId) {
    startNewConversation()
    return
  }
  activeId.value = queryId
  await loadConversation(queryId)
}

/** GET /ai/config 的返回体（request.ts 拦截器已解包为 { code, msg, data }） */
interface AiConfigResp {
  code?: string
  data?: { configured?: boolean; model?: string }
}

onMounted(async () => {
  userRole.value = userInfo.value.role || 'student'
  try {
    const res = (await request.get('/api/ai/config')) as unknown as AiConfigResp
    aiModel.value = res.code === '200' && res.data?.configured ? res.data.model || '' : ''
  } catch {
    aiModel.value = ''
  }

  await loadList()
  await restoreFromUrl()
})

/**
 * 浏览器前进/后退改变 URL 时同步会话。
 * 自身 syncUrl 造成的变更会被 `id === activeId` 挡住，因此不会来回触发。
 */
watch(
  () => route.query.conversationId,
  async (value) => {
    const id = typeof value === 'string' ? value : ''
    if (!id) {
      if (activeId.value) {
        startNewConversation()
        resetMessages()
      }
      return
    }
    if (id === activeId.value || loading.value) return
    activeId.value = id
    await loadConversation(id)
  },
)
</script>

<template>
  <div class="ai-chat-container">
    <ConversationSidebar
      :conversations="conversations"
      :active-id="activeId"
      :loading="listLoading || historyLoading"
      @select="handleSelectConversation"
      @create="handleCreateConversation"
      @remove="handleRemoveConversation"
    />

    <section class="chat-main">
      <div class="chat-header">
        <div class="header-left">
          <el-icon :size="22">
            <ChatDotSquare />
          </el-icon>
          <span>AI 助手</span>
        </div>
        <div class="header-right">
          <el-tag
            v-if="aiModel"
            size="small"
            type="info"
            effect="plain"
          >
            {{ aiModel }}
          </el-tag>
          <el-tag
            v-else
            size="small"
            type="danger"
            effect="plain"
          >
            未配置
          </el-tag>
        </div>
      </div>

      <ChatMessageList
        :messages="messages"
        :loading="loading"
        :example-prompts="examplePrompts"
        :is-admin="userRole === 'admin'"
        @confirm="confirm"
        @pick-prompt="handlePickPrompt"
      />

      <ChatInput
        v-model="inputText"
        :loading="loading"
        @send="handleSend"
      />
    </section>
  </div>
</template>

<style scoped>
.ai-chat-container {
  display: flex;
  height: calc(100vh - 120px);
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

/* 右侧主区：头部 + 消息 + 输入，纵向铺满 */
.chat-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #eee;
  background: #fafafa;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 窄屏：改为上下堆叠，避免侧栏把消息区挤没（技能里移动端塌陷的裁剪版） */
@media (max-width: 900px) {
  .ai-chat-container {
    flex-direction: column;
    height: auto;
  }
  .chat-main {
    min-height: 60vh;
  }
}
</style>
