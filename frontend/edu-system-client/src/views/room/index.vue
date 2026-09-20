<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import RoomFilterBar from './components/RoomFilterBar.vue'
import RoomForm from './components/RoomForm.vue'
import RoomTable from './components/RoomTable.vue'
import { useRoomManage } from '@/composables/useRoomManage'
import type { Room } from '@/types/models'

/**
 * 教室维护（管理员）。
 *
 * 本视图是**组合面**：取数在 composables/useRoomManage.ts，
 * 筛选栏 / 表格 / 表单各是一个单一职责子组件，这里只做拼装与交互编排。
 *
 * 约 800 间教室，故走服务端分页（默认每页 20），筛选栏与分页都由后端出结果。
 */
const {
  list,
  total,
  loading,
  loadError,
  pageNum,
  pageSize,
  query,
  search,
  resetQuery,
  changePage,
  changePageSize,
  remove,
} = useRoomManage()

const formVisible = ref(false)
const editingRow = ref<Room | null>(null)

const openCreate = () => {
  editingRow.value = null
  formVisible.value = true
}

const openEdit = (row: Room) => {
  editingRow.value = row
  formVisible.value = true
}

const handleRemove = async (row: Room) => {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(
      `确定删除教室「${row.roomName}」？已排课的教室请先调整课表。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  try {
    await remove(row.id)
    ElMessage.success('删除成功')
  } catch {
    // 拦截器已提示失败原因
  }
}
</script>

<template>
  <div class="room-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">教室维护</span>
          <el-button
            type="primary"
            @click="openCreate"
          >
            新增教室
          </el-button>
        </div>
      </template>

      <RoomFilterBar
        v-model="query"
        @search="search"
        @reset="resetQuery"
      />

      <el-alert
        v-if="loadError"
        :title="'加载失败：' + loadError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <RoomTable
        :list="list"
        :loading="loading"
        @edit="openEdit"
        @remove="handleRemove"
      />

      <el-pagination
        :current-page="pageNum"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        class="page-box"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </el-card>

    <RoomForm
      v-model="formVisible"
      :row="editingRow"
      @success="search"
    />
  </div>
</template>

<style scoped>
.room-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-card {
  border-radius: 8px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.inline-alert {
  margin-bottom: 12px;
}
.page-box {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
