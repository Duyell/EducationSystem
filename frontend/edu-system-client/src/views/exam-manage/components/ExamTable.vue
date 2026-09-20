<script setup lang="ts">
import { computed } from 'vue'
import {
  examStatusLabel,
  examStatusTagType,
  examTimeRangeText,
  examTypeLabel,
} from '@/utils/exam'
import type { ExamSchedule } from '@/types/models'
import type { TagType } from '@/utils/schedule'

/**
 * 考试列表。
 *
 * 单一职责：渲染 + 抛意图。编辑、删除交给父级
 * （那些动作要弹确认框、要刷新列表，属于编排，不属于表格）。
 *
 * 每行的展示文案（类型/起止时间/状态）只算一次：
 * 直接在模板里逐个调用工具函数会在每个单元格各算一遍，derived 逻辑留在模板里也不好读。
 */
const props = defineProps<{
  exams: ExamSchedule[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'edit', exam: ExamSchedule): void
  (e: 'remove', exam: ExamSchedule): void
}>()

interface ExamRow {
  exam: ExamSchedule
  /** 后端给的中文类型标签（缺失时本地兜底） */
  typeText: string
  /** "2026-09-27 09:00 ~ 11:00" —— 结束时间由 examTime + durationMinutes 算出来 */
  timeText: string
  statusText: string
  statusType: TagType
  /** 作废的考试整行弱化显示 */
  muted: boolean
}

const rows = computed<ExamRow[]>(() =>
  props.exams.map((exam) => ({
    exam,
    typeText: examTypeLabel(exam),
    timeText: examTimeRangeText(exam),
    statusText: examStatusLabel(exam.status),
    statusType: examStatusTagType(exam.status),
    muted: exam.status === 0,
  })),
)

/**
 * 行样式回调：作废的考试整行弱化。
 *
 * 写成脚本里的具名函数而不是模板内的内联箭头：内联箭头在模板里拿不到类型，
 * `({ row }) => ...` 会被推成隐式 any（TS7031），显式标注参数类型才过得了严格检查。
 */
function rowClassName({ row }: { row: ExamRow }): string {
  return row.muted ? 'muted-row' : ''
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="rows"
    stripe
    empty-text="暂无考试安排"
    :row-class-name="rowClassName"
  >
    <el-table-column
      label="课程代码"
      width="110"
    >
      <template #default="{ row }">
        {{ row.exam.courseCode ?? '—' }}
      </template>
    </el-table-column>
    <el-table-column
      label="课程名称"
      min-width="160"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ row.exam.courseName ?? '—' }}
      </template>
    </el-table-column>
    <el-table-column
      label="类型"
      width="90"
      align="center"
    >
      <template #default="{ row }">
        <el-tag
          size="small"
          effect="plain"
        >
          {{ row.typeText }}
        </el-tag>
      </template>
    </el-table-column>

    <!-- 起止时间合并成一列：读者真正想看的是"9 点考到 11 点"，而不是再加一列自己心算 -->
    <el-table-column
      label="考试时间"
      min-width="200"
    >
      <template #default="{ row }">
        {{ row.timeText }}
      </template>
    </el-table-column>

    <el-table-column
      label="时长"
      width="90"
      align="center"
    >
      <template #default="{ row }">
        {{ row.exam.durationMinutes }} 分钟
      </template>
    </el-table-column>

    <el-table-column
      label="考场"
      min-width="130"
    >
      <template #default="{ row }">
        {{ row.exam.roomName ?? '待定' }}
      </template>
    </el-table-column>

    <el-table-column
      label="座位号段"
      width="120"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ row.exam.seatRange || '—' }}
      </template>
    </el-table-column>

    <el-table-column
      label="监考教师"
      min-width="130"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ row.exam.invigilator || '—' }}
      </template>
    </el-table-column>

    <el-table-column
      label="状态"
      width="90"
      align="center"
    >
      <template #default="{ row }">
        <el-tag
          :type="row.statusType"
          size="small"
          effect="plain"
        >
          {{ row.statusText }}
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column
      label="操作"
      width="140"
      fixed="right"
    >
      <template #default="{ row }">
        <el-button
          type="primary"
          link
          size="small"
          @click="emit('edit', row.exam)"
        >
          编辑
        </el-button>
        <el-button
          type="danger"
          link
          size="small"
          @click="emit('remove', row.exam)"
        >
          删除
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
/* 作废的考试弱化，避免看串行；真正要看的还是有效那一批 */
:deep(.muted-row) {
  color: #a8abb2;
}
</style>
