import { ref } from 'vue'
import request from '@/utils/request'
import type { AiConversation, AiConversationDetail } from '@/types/models'

/**
 * AI 会话列表与历史消息。
 *
 * 为什么单独抽出来：会话的增删查是**有状态 + 带副作用**的逻辑（列表、当前选中、加载态），
 * 而 `views/ai/index.vue` 还要同时负责 SSE 对话流。把前者移到这里，
 * 视图只做组合（`vue-best-practices`：状态与副作用进 composable，组件只渲染）。
 *
 * `request` 的响应拦截器已经把 `{code,msg,data}` 解包并统一提示错误，
 * 因此这里只在**需要改变界面行为**时处理失败（返回 null/false 交给调用方决定回落逻辑）。
 */

/** 后端统一返回体（拦截器解包后仍是这个形状） */
interface ApiResult<T> {
  code?: string
  msg?: string
  data?: T
}

export function useConversations() {
  const conversations = ref<AiConversation[]>([])
  /** 当前会话 id；空串表示"新对话"（尚未在服务端建会话） */
  const activeId = ref('')
  const listLoading = ref(false)
  const historyLoading = ref(false)

  /** 拉取我的会话列表（按最近更新倒序，服务端已排序） */
  const loadList = async (): Promise<void> => {
    listLoading.value = true
    try {
      const res = (await request.get('/api/ai/conversations')) as unknown as ApiResult<AiConversation[]>
      conversations.value = res.data ?? []
    } catch {
      // 拦截器已提示；这里保持原列表，避免把界面清空
    } finally {
      listLoading.value = false
    }
  }

  /** 新建空会话并选中 */
  const createConversation = async (): Promise<AiConversation | null> => {
    try {
      // 刻意不传 role：服务端只认 token（前端传 role 等于允许学生拿教师权限）
      const res = (await request.post('/api/ai/conversations', {})) as unknown as ApiResult<AiConversation>
      const created = res.data ?? null
      if (created) {
        activeId.value = created.id
        await loadList()
      }
      return created
    } catch {
      return null
    }
  }

  /** 删除会话（返回是否真的删掉了） */
  const removeConversation = async (id: string): Promise<boolean> => {
    try {
      await request.delete(`/api/ai/conversations/${id}`)
      if (activeId.value === id) {
        activeId.value = ''
      }
      await loadList()
      return true
    } catch {
      return false
    }
  }

  /**
   * 打开一个会话：取回标题与历史消息。
   *
   * @returns 会话详情；越权或不存在时返回 null（拦截器已提示服务端给的原因）
   */
  const openConversation = async (id: string): Promise<AiConversationDetail | null> => {
    historyLoading.value = true
    try {
      const res = (await request.get(
        `/api/ai/conversations/${encodeURIComponent(id)}/messages`,
      )) as unknown as ApiResult<AiConversationDetail>
      const detail = res.data ?? null
      if (detail) {
        activeId.value = detail.conversation.id
      }
      return detail
    } catch {
      return null
    } finally {
      historyLoading.value = false
    }
  }

  /** 回到"新对话"（不建会话，等首条消息由服务端兜底新建） */
  const startNewConversation = (): void => {
    activeId.value = ''
  }

  return {
    conversations,
    activeId,
    listLoading,
    historyLoading,
    loadList,
    createConversation,
    removeConversation,
    openConversation,
    startNewConversation,
  }
}
