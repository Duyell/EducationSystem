<script setup lang="ts">
import { applyStatusLabel, applyStatusTagType, hasConflictInfo, slotWithWeekText } from '@/utils/schedule'
import type { ClassTimeApply } from '@/types/models'

/**
 * 我的排课申请列表（教师）。
 *
 * 单一职责：只渲染申请单。刷新按钮由父级放在卡片头部——
 * 取数与 loading 都归父级管，这里不该持有它。
 */
defineProps<{
  applies: ClassTimeApply[]
  loading: boolean
}>()
</script>

<template>
  <el-table
    v-loading="loading"
    :data="applies"
    stripe
    empty-text="还没有排课申请，在开课申请通过后点「申请排课」"
  >
    <el-table-column
      prop="courseCode"
      label="课程代码"
      width="110"
    />
    <el-table-column
      prop="courseName"
      label="课程名称"
      min-width="140"
      show-overflow-tooltip
    />
    <el-table-column
      label="上课时间"
      min-width="190"
    >
      <template #default="{ row }">
        {{ slotWithWeekText(row) }}
      </template>
    </el-table-column>
    <el-table-column
      label="教室"
      width="120"
    >
      <template #default="{ row }">
        {{ row.roomName || '待分配' }}
      </template>
    </el-table-column>
    <el-table-column
      label="状态"
      width="90"
      align="center"
    >
      <template #default="{ row }">
        <el-tag
          :type="applyStatusTagType(row.status)"
          size="small"
          effect="plain"
        >
          {{ applyStatusLabel(row.status) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column
      label="冲突情况"
      min-width="160"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        <el-tag
          v-if="hasConflictInfo(row.conflictInfo)"
          type="danger"
          size="small"
          effect="plain"
        >
          有冲突
        </el-tag>
        <span
          v-else
          class="ok-text"
        >无冲突</span>
      </template>
    </el-table-column>
    <el-table-column
      label="审批信息"
      min-width="170"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        <span
          v-if="row.status === 'REJECTED'"
          class="reject-text"
        >驳回原因：{{ row.rejectReason || '—' }}</span>
        <span
          v-else-if="hasConflictInfo(row.conflictInfo)"
          class="reject-text"
        >{{ row.conflictInfo }}</span>
        <span
          v-else-if="row.status === 'APPROVED'"
          class="muted-text"
        >审批人：{{ row.reviewer || '—' }}</span>
        <span
          v-else
          class="muted-text"
        >等待管理员审批</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.reject-text {
  color: #f56c6c;
}
.ok-text {
  color: #67c23a;
}
.muted-text {
  color: #999;
}
</style>
