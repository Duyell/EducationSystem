<template>
  <div class="ai-chat-container">
    <!-- Header -->
    <div class="chat-header">
      <div class="header-left">
        <el-icon :size="22"><ChatDotSquare /></el-icon>
        <span>AI 助手</span>
      </div>
      <div class="header-right">
        <el-tag size="small" type="info" effect="plain" v-if="aiModel">
          {{ aiModel }}
        </el-tag>
        <el-tag size="small" type="danger" effect="plain" v-else>
          未配置
        </el-tag>
      </div>
    </div>

    <!-- Messages -->
    <div class="chat-messages" ref="messagesRef">
      <!-- Empty state -->
      <div v-if="messages.length === 0" class="empty-state">
        <el-icon :size="48" color="#999"><ChatLineSquare /></el-icon>
        <p class="empty-title">有什么可以帮你的？</p>
        <div class="example-prompts">
          <el-tag
            v-for="(prompt, i) in examplePrompts"
            :key="i"
            :class="{ 'is-admin': userRole === 'admin' }"
            class="prompt-tag"
            effect="plain"
            @click="sendMessage(prompt)"
          >
            {{ prompt }}
          </el-tag>
        </div>
      </div>

      <!-- Message list -->
      <div v-for="(msg, i) in messages" :key="i" class="message-row" :class="msg.role">
        <div class="avatar-col">
          <el-avatar v-if="msg.role === 'assistant'" :size="36" class="ai-avatar">
            <el-icon :size="20"><ChatDotRound /></el-icon>
          </el-avatar>
          <el-avatar v-else-if="msg.role === 'user'" :size="36" class="user-avatar">
            <el-icon :size="20"><User /></el-icon>
          </el-avatar>
        </div>
        <div class="message-bubble" :class="msg.role">
          <!-- Status messages -->
          <div v-if="msg.type === 'status'" class="status-msg">
            <el-icon class="status-icon"><Loading /></el-icon>
            {{ msg.content }}
          </div>
          <!-- Token messages (streaming) -->
          <div v-else-if="msg.type === 'token' || msg.type === 'text'" class="text-msg">
            <div v-html="renderMarkdown(msg.content)"></div>
          </div>
          <!-- Error messages -->
          <div v-else-if="msg.type === 'error'" class="error-msg">
            <el-icon :size="16"><WarningFilled /></el-icon>
            {{ msg.content }}
          </div>
        </div>
      </div>

      <!-- Loading indicator -->
      <div v-if="loading" class="message-row assistant">
        <div class="avatar-col">
          <el-avatar :size="36" class="ai-avatar">
            <el-icon :size="20"><ChatDotRound /></el-icon>
          </el-avatar>
        </div>
        <div class="message-bubble assistant">
          <div class="typing-indicator">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
          </div>
        </div>
      </div>
    </div>

    <!-- Input area -->
    <div class="chat-input-area">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        :disabled="loading"
        :autosize="{ minRows: 2, maxRows: 4 }"
        placeholder="输入你的问题，按 Enter 发送..."
        @keydown.enter.prevent="handleSend"
      />
      <div class="input-actions">
        <span class="hint">Enter 发送，Shift+Enter 换行</span>
        <el-button
          type="primary"
          :disabled="!inputText.trim() || loading"
          :loading="loading"
          @click="handleSend"
        >
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotSquare, ChatLineSquare, ChatDotRound, User, Loading, WarningFilled } from '@element-plus/icons-vue'
import { marked } from 'marked'
import request from '@/utils/request'

// Configure marked for safe rendering
const renderer = new marked.Renderer()
marked.setOptions({
  breaks: true,
  gfm: true,
})

interface ChatMsg {
  role: 'user' | 'assistant' | 'system'
  type: 'text' | 'token' | 'status' | 'error'
  content: string
}

const messages = ref<ChatMsg[]>([])
const inputText = ref('')
const loading = ref(false)
const messagesRef = ref<HTMLElement | null>(null)
const abortController = ref<AbortController | null>(null)
const aiModel = ref('')
const userRole = ref('')
let currentAssistantMsg = ref<ChatMsg | null>(null)

const userInfo = computed(() => {
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

const renderMarkdown = (content: string) => {
  try {
    return marked.parse(content, { async: false }) as string
  } catch {
    return content
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

const handleSend = () => {
  if (!inputText.value.trim() || loading.value) return
  sendMessage(inputText.value.trim())
  inputText.value = ''
}

const sendMessage = async (text: string) => {
  if (!text.trim()) return

  // Add user message
  messages.value.push({
    role: 'user',
    type: 'text',
    content: text,
  })
  scrollToBottom()

  loading.value = true
  currentAssistantMsg.value = null

  // Create abort controller
  abortController.value = new AbortController()
  const token = sessionStorage.getItem('token')

  try {
    const response = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'token': token || '',
      },
      body: JSON.stringify({ message: text }),
      signal: abortController.value.signal,
    })

    if (!response.ok) {
      if (response.status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        sessionStorage.removeItem('token')
        sessionStorage.removeItem('user')
        window.location.href = '/login'
        return
      }
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) throw new Error('No response body')

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed || !trimmed.startsWith('data:')) continue

        const dataStr = trimmed.slice(5).trim()
        try {
          const data = JSON.parse(dataStr)
          handleSseEvent(data)
        } catch {
          // Skip malformed JSON
        }
      }
    }

    // Process remaining buffer
    if (buffer.trim()) {
      const trimmed = buffer.trim()
      if (trimmed.startsWith('data:')) {
        try {
          const data = JSON.parse(trimmed.slice(5).trim())
          handleSseEvent(data)
        } catch {
          // Skip
        }
      }
    }

  } catch (err: any) {
    if (err.name === 'AbortError') {
      messages.value.push({
        role: 'assistant',
        type: 'status',
        content: '已取消',
      })
    } else {
      messages.value.push({
        role: 'assistant',
        type: 'error',
        content: '连接失败: ' + (err.message || '未知错误'),
      })
    }
  } finally {
    loading.value = false
    abortController.value = null
    scrollToBottom()
  }
}

const handleSseEvent = (data: any) => {
  const type = data.type

  switch (type) {
    case 'token':
      if (!currentAssistantMsg.value) {
        currentAssistantMsg.value = {
          role: 'assistant',
          type: 'token',
          content: '',
        }
        messages.value.push(currentAssistantMsg.value)
      }
      currentAssistantMsg.value.content += data.content
      scrollToBottom()
      break

    case 'status':
      // Status messages are not displayed
      break

    case 'error':
      messages.value.push({
        role: 'assistant',
        type: 'error',
        content: data.content,
      })
      scrollToBottom()
      break

    case 'done':
      // Convert the last token message to text type
      if (currentAssistantMsg.value) {
        currentAssistantMsg.value.type = 'text'
      }
      currentAssistantMsg.value = null
      scrollToBottom()
      break
  }
}

onMounted(async () => {
  userRole.value = userInfo.value.role || 'student'
  try {
    const res: any = await request.get('/api/ai/config')
    if (res.code === '200' && res.data) {
      if (res.data.configured) {
        aiModel.value = res.data.model || ''
      } else {
        aiModel.value = ''
      }
    }
  } catch {
    aiModel.value = ''
  }
})
</script>

<style scoped>
.ai-chat-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

/* Header */
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

/* Messages */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Empty state */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 16px;
  color: #999;
}
.empty-title {
  font-size: 16px;
  margin: 0;
}
.example-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 400px;
}
.prompt-tag {
  cursor: pointer;
  transition: all 0.2s;
}
.prompt-tag:hover {
  transform: translateY(-1px);
}
.prompt-tag.is-admin {
  /* admin tag style unchanged */
}

/* Message row */
.message-row {
  display: flex;
  gap: 10px;
  max-width: 85%;
}
.message-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.message-row.assistant {
  align-self: flex-start;
}

.avatar-col {
  flex-shrink: 0;
}
.ai-avatar {
  background: #165DFF;
}
.user-avatar {
  background: #52c41a;
}

.message-bubble {
  padding: 10px 14px;
  border-radius: 8px;
  line-height: 1.6;
  font-size: 14px;
  word-break: break-word;
}
.message-bubble.user {
  background: #165DFF;
  color: #fff;
  border-bottom-right-radius: 2px;
}
.message-bubble.assistant {
  background: #f5f7fa;
  color: #333;
  border-bottom-left-radius: 2px;
}

/* Status message */
.status-msg {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #666;
  font-size: 13px;
}
.status-icon {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Error message */
.error-msg {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #ff4d4f;
}

/* Text message */
.text-msg {
  white-space: pre-wrap;
}
.text-msg :deep(p) {
  margin: 0 0 8px;
}
.text-msg :deep(p:last-child) {
  margin-bottom: 0;
}
.text-msg :deep(ul),
.text-msg :deep(ol) {
  padding-left: 20px;
  margin: 4px 0;
}
.text-msg :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 13px;
}
.text-msg :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 8px 0;
}
.text-msg :deep(pre code) {
  background: none;
  color: inherit;
  padding: 0;
}
.text-msg :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 8px 0;
  font-size: 13px;
}
.text-msg :deep(th),
.text-msg :deep(td) {
  border: 1px solid #ddd;
  padding: 6px 10px;
  text-align: left;
}
.text-msg :deep(th) {
  background: #f0f0f0;
  font-weight: 600;
}

/* Typing indicator */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}
.dot {
  width: 8px;
  height: 8px;
  background: #999;
  border-radius: 50%;
  animation: bounce 1.4s ease-in-out infinite;
}
.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes bounce {
  0%, 80%, 100% { transform: translateY(0); }
  40% { transform: translateY(-8px); }
}

/* Input area */
.chat-input-area {
  padding: 12px 20px 16px;
  border-top: 1px solid #eee;
  background: #fafafa;
}
.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.hint {
  font-size: 12px;
  color: #999;
}
</style>
