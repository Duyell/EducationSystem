import { onMounted, ref } from 'vue'
import axios from '@/utils/request'
import type { MyTrainingPlan } from '@/types/models'

/**
 * 「我的培养方案」页面数据。
 *
 * 后端 `/training-plan/my` 一次返回方案 + 课程明细 + 毕业审核结果
 * （三者同源于同一份方案，分开请求会出现口径不一致的可能）。
 */
export function useMyTrainingPlan() {
  const data = ref<MyTrainingPlan | null>(null)
  const loading = ref(false)
  const loadError = ref('')

  const load = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const res = await axios.get('/api/training-plan/my')
      data.value = res.data as MyTrainingPlan
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      data.value = null
    } finally {
      loading.value = false
    }
  }

  onMounted(load)

  return { data, loading, loadError, load }
}
