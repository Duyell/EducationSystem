<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ChatDotRound, ChatLineSquare, Loading, User, WarningFilled } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { ChatMsg } from '@/types/models'

/**
 * 消息区。
 *
 * 单一职责：把 `messages` 画出来（空态示例、气泡、确认卡片、打字指示），
 * 并把"用户在确认卡片上做了决定""点了示例问题"抛给父组件。
 *
 * 它**不碰 SSE、不发请求、不改消息内容**——流式拼接与取消逻辑在 `composables/useAgentChat.ts`，
 * 因为那属于对话协议（"当前是否在流式输出"只有它知道）。
 *
 * 自动滚动也放在这里：滚动容器是本组件自己的根元素，DOM 归它管；
 * 让父组件去 `document.querySelector` 找别人的 DOM 既脆弱又不好测。
 */
const props = defineProps<{
  messages: ChatMsg[]
  loading: boolean
  examplePrompts: string[]
  /** 管理员示例问题用不同样式弱化"这是学生视角"的错觉 */
  isAdmin: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', msg: ChatMsg, approved: boolean): void
  (e: 'pick-prompt', text: string): void
}>()

const listRef = ref<HTMLElement | null>(null)

const scrollToBottom = async () => {
  await nextTick()
  const el = listRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

// 新消息、流式增量（内容变长）、打字指示出现/消失都要跟随
watch(() => props.messages, () => void scrollToBottom(), { deep: true })
watch(() => props.loading, () => void scrollToBottom())
onMounted(() => void scrollToBottom())

const renderMarkdown = (content: string) => {
  try {
    const html = marked.parse(content, { async: false }) as string
    // XSS 防护：AI 输出（可能含工具返回的学生名/课程名等）渲染前先消毒
    return DOMPurify.sanitize(html)
  } catch {
    return content
  }
}
</script>

<template>
  <div
    ref="listRef"
    class="chat-messages"
  >
    <!-- 空态：直接给可点的示例问题，而不是让用户对着空框发愣 -->
    <div
      v-if="props.messages.length === 0"
      class="empty-state"
    >
      <el-icon
        :size="48"
        color="#999"
      >
        <ChatLineSquare />
      </el-icon>
      <p class="empty-title">
        有什么可以帮你的？
      </p>
      <div class="example-prompts">
        <el-tag
          v-for="(prompt, i) in props.examplePrompts"
          :key="i"
          :class="{ 'is-admin': props.isAdmin }"
          class="prompt-tag"
          effect="plain"
          @click="emit('pick-prompt', prompt)"
        >
          {{ prompt }}
        </el-tag>
      </div>
    </div>

    <div
      v-for="(msg, i) in props.messages"
      :key="i"
      class="message-row"
      :class="msg.role"
    >
      <div class="avatar-col">
        <el-avatar
          v-if="msg.role === 'assistant'"
          :size="36"
          class="ai-avatar"
        >
          <el-icon :size="20">
            <ChatDotRound />
          </el-icon>
        </el-avatar>
        <el-avatar
          v-else-if="msg.role === 'user'"
          :size="36"
          class="user-avatar"
        >
          <el-icon :size="20">
            <User />
          </el-icon>
        </el-avatar>
      </div>
      <div
        class="message-bubble"
        :class="msg.role"
      >
        <div
          v-if="msg.type === 'status'"
          class="status-msg"
        >
          <el-icon class="status-icon">
            <Loading />
          </el-icon>
          {{ msg.content }}
        </div>
        <div
          v-else-if="msg.type === 'token' || msg.type === 'text'"
          class="text-msg"
          v-html="renderMarkdown(msg.content)"
        ></div>
        <div
          v-else-if="msg.type === 'error'"
          class="error-msg"
        >
          <el-icon :size="16">
            <WarningFilled />
          </el-icon>
          {{ msg.content }}
        </div>
        <!-- 危险操作确认卡片（HITL）：确认前工具不会执行 -->
        <div
          v-else-if="msg.type === 'confirm'"
          class="confirm-card"
        >
          <div class="confirm-head">
            <el-icon
              :size="16"
              color="#FA8C16"
            >
              <WarningFilled />
            </el-icon>
            <span class="confirm-title">需要你确认：{{ msg.confirmTitle }}</span>
          </div>
          <div
            v-if="msg.confirmArgs && msg.confirmArgs.length"
            class="confirm-args"
          >
            <div
              v-for="item in msg.confirmArgs"
              :key="item.key"
              class="confirm-arg"
            >
              <span class="arg-key">{{ item.key }}</span>
              <span class="arg-value">{{ item.value }}</span>
            </div>
          </div>
          <div class="confirm-foot">
            <template v-if="!msg.decided">
              <el-button
                size="small"
                type="primary"
                @click="emit('confirm', msg, true)"
              >
                确认执行
              </el-button>
              <el-button
                size="small"
                @click="emit('confirm', msg, false)"
              >
                取消
              </el-button>
            </template>
            <el-tag
              v-else-if="msg.approved === true"
              size="small"
              type="success"
              effect="plain"
            >
              已确认执行
            </el-tag>
            <el-tag
              v-else-if="msg.expired"
              size="small"
              type="warning"
              effect="plain"
            >
              已超时，操作未执行
            </el-tag>
            <el-tag
              v-else
              size="small"
              type="info"
              effect="plain"
            >
              已取消
            </el-tag>
          </div>
        </div>
      </div>
    </div>

    <div
      v-if="props.loading"
      class="message-row assistant"
    >
      <div class="avatar-col">
        <el-avatar
          :size="36"
          class="ai-avatar"
        >
          <el-icon :size="20">
            <ChatDotRound />
          </el-icon>
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
</template>

<style scoped>
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 空态 */
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

/* 消息行 */
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
  background: #165dff;
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
  background: #165dff;
  color: #fff;
  border-bottom-right-radius: 2px;
}
.message-bubble.assistant {
  background: #f5f7fa;
  color: #333;
  border-bottom-left-radius: 2px;
}

/* 状态消息（后端会推送，但当前设计不展示；保留渲染分支以免协议变化时丢信息） */
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
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

/* 错误消息 */
.error-msg {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #ff4d4f;
}

/* 危险操作确认卡片 */
.confirm-card {
  min-width: 260px;
  padding: 12px 14px;
  border: 1px solid #ffd591;
  border-left: 3px solid #fa8c16;
  border-radius: 6px;
  background: #fffbf5;
}
.confirm-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #ad4e00;
}
.confirm-title {
  line-height: 1.4;
}
.confirm-args {
  margin: 10px 0 4px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #fff;
  border: 1px solid #ffe7ba;
  font-size: 13px;
}
.confirm-arg {
  display: flex;
  gap: 8px;
  line-height: 1.8;
}
.arg-key {
  flex-shrink: 0;
  min-width: 76px;
  color: #8c8c8c;
}
.arg-value {
  color: #333;
  word-break: break-all;
}
.confirm-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

/* 正文（Markdown 渲染结果） */
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

/* 打字指示 */
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
.dot:nth-child(2) {
  animation-delay: 0.2s;
}
.dot:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
  }
  40% {
    transform: translateY(-8px);
  }
}
</style>
