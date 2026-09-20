<script setup lang="ts">
import { roomTypeLabel } from '@/utils/schedule'
import type { Room } from '@/types/models'

/**
 * 教室列表（管理员）。
 *
 * 单一职责：只渲染表格，把编辑/删除意图交给父级（props in / events out）。
 */
defineProps<{
  list: Room[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'edit', row: Room): void
  (e: 'remove', row: Room): void
}>()
</script>

<template>
  <el-table
    v-loading="loading"
    :data="list"
    stripe
    empty-text="没有符合条件的教室"
  >
    <el-table-column
      prop="roomName"
      label="教室"
      width="130"
    />
    <el-table-column
      prop="building"
      label="楼栋"
      width="100"
    />
    <el-table-column
      label="楼层"
      width="80"
      align="center"
    >
      <template #default="{ row }">
        {{ row.floorNo ?? '—' }}
      </template>
    </el-table-column>
    <el-table-column
      prop="roomNo"
      label="房间号"
      width="90"
    />
    <el-table-column
      prop="capacity"
      label="容量"
      width="80"
      align="center"
    />
    <el-table-column
      label="类型"
      width="120"
    >
      <template #default="{ row }">
        {{ roomTypeLabel(row.roomType) }}
      </template>
    </el-table-column>
    <el-table-column
      label="状态"
      width="90"
      align="center"
    >
      <template #default="{ row }">
        <el-tag
          :type="row.status === 1 ? 'success' : 'info'"
          size="small"
          effect="plain"
        >
          {{ row.status === 1 ? '可用' : '停用' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column
      label="操作"
      width="130"
      align="center"
      fixed="right"
    >
      <template #default="{ row }">
        <el-button
          type="primary"
          link
          size="small"
          @click="emit('edit', row)"
        >
          编辑
        </el-button>
        <el-button
          type="danger"
          link
          size="small"
          @click="emit('remove', row)"
        >
          删除
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
