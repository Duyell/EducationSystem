import { onMounted, ref } from 'vue'
import axios from '@/utils/request'
import type { PageResult, Room, RoomQuery } from '@/types/models'

/**
 * 教室维护（管理员）：分页列表 + 筛选 + 删除。
 *
 * ⚠️ 后端 `/room` 的分页参数是 **pageNum**（课程列表用的是 page，两者不同，别混用）。
 * 教室约 800 条，故默认每页 20 并展示总数分页器。
 */
export function useRoomManage() {
  const list = ref<Room[]>([])
  const total = ref(0)
  const loading = ref(false)
  const loadError = ref('')

  const pageNum = ref(1)
  const pageSize = ref(20)

  /** 筛选条件：与 RoomFilterBar 通过 v-model 双向绑定（对象整体是一个真双向契约） */
  const query = ref<RoomQuery>(createEmptyQuery())

  function createEmptyQuery(): RoomQuery {
    return { building: '', roomType: '', minCapacity: undefined, status: undefined }
  }

  /**
   * Element Plus 的 select / input-number 在"清空"时可能给出 `null` 或 `''`，
   * 而 axios 会把 `null` 序列化成 `minCapacity=`（空字符串），后端按 Integer 绑定会失败。
   * 故统一归一化成 `number | undefined`，非数字一律丢弃。
   */
  const toNumber = (value: unknown): number | undefined =>
    typeof value === 'number' && !Number.isNaN(value) ? value : undefined

  const load = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const res = await axios.get('/api/room', {
        params: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
          // 空值一律传 undefined，axios 会省略该参数，避免后端收到空字符串
          building: query.value.building || undefined,
          roomType: query.value.roomType || undefined,
          minCapacity: toNumber(query.value.minCapacity),
          status: toNumber(query.value.status),
        },
      })
      const page = res.data as PageResult<Room>
      list.value = page?.list ?? []
      total.value = page?.total ?? 0
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      list.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  /** 查询：条件变了必须回到第 1 页，否则会停在一个不存在的页码上看到空列表 */
  const search = async () => {
    pageNum.value = 1
    await load()
  }

  const resetQuery = async () => {
    query.value = createEmptyQuery()
    await search()
  }

  const changePage = async (page: number) => {
    pageNum.value = page
    await load()
  }

  const changePageSize = async (size: number) => {
    pageSize.value = size
    await search()
  }

  const remove = async (id: number): Promise<void> => {
    try {
      await axios.delete(`/api/room/${id}`)
    } finally {
      // 删掉当页最后一条时页码可能越界，回到第 1 页重新取更稳
      await load()
    }
  }

  onMounted(load)

  return {
    list,
    total,
    loading,
    loadError,
    pageNum,
    pageSize,
    query,
    load,
    search,
    resetQuery,
    changePage,
    changePageSize,
    remove,
  }
}
