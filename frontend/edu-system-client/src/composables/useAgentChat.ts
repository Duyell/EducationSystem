import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import type { AgentSseEvent, ChatMsg } from '@/types/models'

/**
 * 与 Agent 的一次对话（SSE 流式 + 危险操作确认）。
 *
 * 为什么抽成 composable：这段是**有状态 + 副作用重**的逻辑（流式拼接、中止、确认卡片生命周期），
 * 和它相比视图只该负责"把消息画出来、把会话列表管起来"。
 * 抽出来还有个实际好处：协议解析（`handleSseEvent`）不依赖任何 DOM，可以单独测。
 *
 * 与视图的耦合点用两个回调表达，而不是让 composable 去 import 会话逻辑：
 * - `onConversation`：服务端确认/兜底新建了会话（流的第一个事件）→ 视图据此同步 URL 与侧栏；
 * - `onStreamEnd`：一轮结束（成功或失败）→ 视图据此刷新列表（标题由首条消息生成，会变）。
 */
export interface UseAgentChatOptions {
  /** 收到 `conversation` 事件（会话 id 可能是服务端兜底新建的） */
  onConversation?: (conversationId: string) => void
  /** 一轮对话结束（含异常收尾） */
  onStreamEnd?: () => void | Promise<void>
}

export function useAgentChat(options: UseAgentChatOptions = {}) {
  const messages = ref<ChatMsg[]>([])
  const loading = ref(false)
  const abortController = ref<AbortController | null>(null)
  const currentAssistantMsg = ref<ChatMsg | null>(null)
  /** 当前等待用户决定的确认卡片（同一时刻只允许一个） */
  const pendingConfirm = ref<ChatMsg | null>(null)

  /** 从 catch 到的 unknown 中安全提取消息（规范要求禁止在 catch 中标注 any） */
  const errorMessage = (err: unknown): string => (err instanceof Error ? err.message : String(err))

  /** 流结束/异常时收尾未决的确认卡片，避免留下点不动的可点按钮 */
  const expirePendingConfirm = () => {
    if (pendingConfirm.value && !pendingConfirm.value.decided) {
      pendingConfirm.value.decided = true
      pendingConfirm.value.expired = true
    }
    pendingConfirm.value = null
  }

  const handleSseEvent = (data: AgentSseEvent) => {
    switch (data.type) {
      case 'conversation':
        // M2：流的第一个事件，把（可能是服务端兜底新建的）会话 id 交给视图
        if (data.conversationId) {
          options.onConversation?.(data.conversationId)
        }
        break

      case 'token':
        if (!currentAssistantMsg.value) {
          currentAssistantMsg.value = { role: 'assistant', type: 'token', content: '' }
          messages.value.push(currentAssistantMsg.value)
        }
        currentAssistantMsg.value.content += data.content
        break

      case 'status':
        // 状态提示当前不展示（协议里仍在，便于排查）
        break

      case 'error':
        messages.value.push({ role: 'assistant', type: 'error', content: data.content })
        break

      case 'confirm':
        // 危险操作确认卡片：后端已挂起 Agent，等待用户在前端确认后才执行
        pendingConfirm.value = {
          role: 'assistant',
          type: 'confirm',
          content: '',
          confirmId: data.confirmId,
          confirmTitle: data.displayName || data.tool || '未知操作',
          confirmArgs: Object.entries(data.args ?? {}).map(([key, value]) => ({
            key,
            value: value === null || value === undefined ? '' : String(value),
          })),
          decided: false,
        }
        messages.value.push(pendingConfirm.value)
        break

      case 'confirm_result':
        if (pendingConfirm.value && pendingConfirm.value.confirmId === data.confirmId) {
          pendingConfirm.value.decided = true
          pendingConfirm.value.approved = data.approved === true
          pendingConfirm.value.expired = data.expired === true
        }
        break

      case 'done':
        // 流结束：把最后一条流式消息固化为 text（此后不再拼接）
        if (currentAssistantMsg.value) {
          currentAssistantMsg.value.type = 'text'
        }
        currentAssistantMsg.value = null
        expirePendingConfirm()
        break
    }
  }

  /** 处理一个 SSE 数据帧（抽出以便单测与复用） */
  const consumeFrame = (raw: string) => {
    try {
      handleSseEvent(JSON.parse(raw) as AgentSseEvent)
    } catch {
      // 不完整/非 JSON 帧：跳过（保持既有行为）
    }
  }

  /**
   * 发送一条消息。
   *
   * @param text           用户输入
   * @param conversationId 当前会话 id；空串表示新对话（服务端会兜底新建并通过 SSE 回传）
   */
  const send = async (text: string, conversationId: string) => {
    if (!text.trim()) return

    messages.value.push({ role: 'user', type: 'text', content: text })
    loading.value = true
    currentAssistantMsg.value = null
    abortController.value = new AbortController()
    const token = sessionStorage.getItem('token')

    try {
      const response = await fetch('/api/ai/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'token': token || '',
        },
        // 带上会话 id → 服务端据此取历史消息实现多轮；为空时服务端兜底新建
        body: JSON.stringify(conversationId ? { message: text, conversationId } : { message: text }),
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
          consumeFrame(trimmed.slice(5).trim())
        }
      }

      // 收尾：缓冲区里可能还剩最后一帧（没有以换行结束）
      if (buffer.trim().startsWith('data:')) {
        consumeFrame(buffer.trim().slice(5).trim())
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        messages.value.push({ role: 'assistant', type: 'status', content: '已取消' })
      } else {
        messages.value.push({
          role: 'assistant',
          type: 'error',
          content: '连接失败: ' + (errorMessage(err) || '未知错误'),
        })
      }
    } finally {
      loading.value = false
      abortController.value = null
      // 流意外中断时，未决的确认卡片不能继续可点（后端已放弃该操作）
      expirePendingConfirm()
      await options.onStreamEnd?.()
    }
  }

  /** 用户点击确认/取消，唤醒后端挂起的 Agent 线程 */
  const confirm = async (msg: ChatMsg, approved: boolean) => {
    if (!msg.confirmId || msg.decided) return
    msg.decided = true
    msg.approved = approved
    if (pendingConfirm.value === msg) {
      pendingConfirm.value = null
    }
    try {
      await request.post('/api/ai/confirm', { confirmId: msg.confirmId, approved })
      // 后端收到决定后会继续 SSE 流，后续回复仍由 consumeFrame 处理
    } catch (err: unknown) {
      msg.decided = true
      msg.approved = undefined
      messages.value.push({
        role: 'assistant',
        type: 'error',
        content: '确认失败：' + (errorMessage(err) || '该操作可能已失效，请重新发起'),
      })
    }
  }

  /** 清空聊天区（切会话 / 删除当前会话时用） */
  const resetMessages = () => {
    messages.value = []
    currentAssistantMsg.value = null
    expirePendingConfirm()
  }

  /** 用落库的历史消息覆盖聊天区（切到历史会话时用；历史里没有流式中间态） */
  const replaceMessages = (next: ChatMsg[]) => {
    messages.value = next
    currentAssistantMsg.value = null
    expirePendingConfirm()
  }

  return { messages, loading, send, confirm, resetMessages, replaceMessages }
}
