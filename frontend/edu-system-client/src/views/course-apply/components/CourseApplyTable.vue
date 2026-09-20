<script setup lang="ts">
import { applyStatusLabel, applyStatusTagType } from '@/utils/schedule'
import type { CourseApply } from '@/types/models'

/**
 * 我的开课申请列表（教师）。
 *
 * 单一职责：只渲染表格 + 把「申请排课」意图交给父级（props in / events out）。
 * 不具备任何取数能力，也不需要知道排课弹窗长什么样。
 */
defineProps<{
  applies: CourseApply[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'schedule', row: CourseApply): void
}>()

/** 只有审批通过且已生成课程的行才能继续排课 */
function canSchedule(row: CourseApply): boolean {
  return row.status === 'APPROVED' && !!row.createdCourseId
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="applies"
    stripe
    empty-text="还没有开课申请，点击右上角「新建申请」开始"
  >
    <el-table-column
      prop="courseCode"
      label="课程代码"
      width="110"
    />
    <el-table-column
      prop="courseName"
      label="课程名称"
      min-width="150"
      show-overflow-tooltip
    />
    <el-table-column
      prop="term"
      label="学期"
      width="120"
    />
    <el-table-column
      prop="credit"
      label="学分"
      width="70"
      align="center"
    />
    <el-table-column
      prop="classHour"
      label="学时"
      width="70"
      align="center"
    />
    <el-table-column
      prop="maxStudent"
      label="容量"
      width="70"
      align="center"
    />
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
      label="审批信息"
      min-width="180"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        <span
          v-if="row.status === 'REJECTED'"
          class="reject-text"
        >驳回原因：{{ row.rejectReason || '—' }}</span>
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
    <el-table-column
      label="生成课程"
      width="110"
      align="center"
    >
      <template #default="{ row }">
        <span v-if="row.createdCourseId">#{{ row.createdCourseId }}</span>
        <span
          v-else
          class="muted-text"
        >—</span>
      </template>
    </el-table-column>
    <el-table-column
      label="操作"
      width="110"
      align="center"
      fixed="right"
    >
      <template #default="{ row }">
        <el-button
          v-if="canSchedule(row)"
          type="primary"
          link
          size="small"
          @click="emit('schedule', row)"
        >
          申请排课
        </el-button>
        <span
          v-else
          class="muted-text"
        >—</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.reject-text {
  color: #f56c6c;
}
.muted-text {
  color: #999;
}
</style>
