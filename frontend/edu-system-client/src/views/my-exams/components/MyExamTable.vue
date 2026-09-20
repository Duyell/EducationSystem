<script setup lang="ts">
import { computed } from 'vue'
import { examPhaseOf, examTimeRangeText, examTypeLabel } from '@/utils/exam'
import type { ExamPhaseInfo } from '@/utils/exam'
import type { ExamSchedule } from '@/types/models'

/**
 * 学生视角的考试列表。
 *
 * 单一职责：渲染 + 顺带展示"待考 / 进行中 / 已考"。
 * 它不发请求，也没有行内动作（学生只能看，改不了考试安排）。
 *
 * 同一个组件被**待考**与**已考**两个区块复用：两处的列完全一致，
 * 只有数据与空态文案不同——为此复制一份表格没有意义。
 *
 * `now` 由父级以 prop 传入而不是组件内现读时钟：
 * 让"阶段"的判定基准只有一个来源，刷新数据时父级会一并更新它。
 */
const props = defineProps<{
  exams: ExamSchedule[]
  loading: boolean
  emptyText: string
  now: Date
}>()

interface ExamRow {
  exam: ExamSchedule
  typeText: string
  /** "2026-09-27 09:00 ~ 11:00"（结束时间由 examTime + durationMinutes 算出） */
  timeText: string
  phase: ExamPhaseInfo
}

/** 每行的展示文案只算一次，派生逻辑不留在模板里 */
const rows = computed<ExamRow[]>(() =>
  props.exams.map((exam) => ({
    exam,
    typeText: examTypeLabel(exam),
    timeText: examTimeRangeText(exam),
    phase: examPhaseOf(exam, props.now),
  })),
)
</script>

<template>
  <el-table
    v-loading="loading"
    :data="rows"
    stripe
    :empty-text="emptyText"
  >
    <el-table-column
      prop="exam.courseCode"
      label="课程代码"
      width="110"
    />
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

    <!--
      阶段标签是学生最关心的信息（"这场考完了没"），
      具体到几点的解释放在悬浮提示里，不占表格宽度。
    -->
    <el-table-column
      label="状态"
      width="100"
      align="center"
    >
      <template #default="{ row }">
        <el-tooltip
          :content="row.phase.hint"
          placement="top"
          :show-after="150"
        >
          <el-tag
            :type="row.phase.tagType"
            size="small"
            effect="plain"
          >
            {{ row.phase.label }}
          </el-tag>
        </el-tooltip>
      </template>
    </el-table-column>

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
      width="130"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ row.exam.seatRange || '—' }}
      </template>
    </el-table-column>
  </el-table>
</template>
