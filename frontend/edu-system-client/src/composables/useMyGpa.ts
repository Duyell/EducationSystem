import { onMounted, ref } from 'vue'
import axios from '@/utils/request'
import type { CreditAudit, GpaPayload } from '@/types/models'

/**
 * 「我的绩点」页面数据。
 *
 * 把请求与状态从视图里抽出来：视图只负责组合展示，
 * 便于将来被其它页面（首页卡片、Agent 结果面板）复用。
 *
 * 绩点与毕业审核是两个接口，用 Promise.all 并发取，避免串行等待。
 */
export function useMyGpa() {
  const data = ref<GpaPayload | null>(null)
  const audit = ref<CreditAudit | null>(null)
  const loading = ref(false)
  const loadError = ref('')

  /** 学期筛选：空字符串 = 全部学期（累计） */
  const term = ref('')

  const load = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const [gpaRes, auditRes] = await Promise.all([
        axios.get('/api/gpa/my', { params: term.value ? { term: term.value } : {} }),
        axios.get('/api/gpa/audit/my'),
      ])
      data.value = gpaRes.data as GpaPayload
      audit.value = auditRes.data as CreditAudit
    } catch (e) {
      // 拦截器已统一提示错误，这里只记录状态供页面展示空态
      loadError.value = e instanceof Error ? e.message : String(e)
      data.value = null
      audit.value = null
    } finally {
      loading.value = false
    }
  }

  onMounted(load)

  return { data, audit, loading, loadError, term, load }
}
