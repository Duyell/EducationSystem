<script setup lang="ts">
import { computed } from 'vue'
import { formatDateTime } from '@/utils/exam'
import {
  changeSummaryText,
  operationLabel,
  operationTagType,
  roleLabel,
  scoreDiffParts,
  sourceLabel,
  sourceTagType,
} from '@/utils/scoreChangeLog'
import type { ScoreChangeLog } from '@/types/models'
import type { TagType } from '@/utils/schedule'

/**
 * 成绩变更日志表格（只读）。
 *
 * 单一职责：把一行日志渲染清楚——**谁、什么时候、对哪条成绩、改前是什么、改后是什么**。
 * 它不发请求、不做筛选（那些是父级的事），也不提供任何写操作（这张表是审计视图）。
 *
 * 派生文案统一在 `computed` 里预先算好：直接在模板里逐单元格调用工具函数会在每次重渲染时重算，
 * 而且"改前→改后"的拼接逻辑写在模板里既不好读也不好测（见 `utils/scoreChangeLog.ts` 的单测）。
 */
const props = defineProps<{
  list: ScoreChangeLog[]
  loading: boolean
}>()

interface ChangeRow {
  row: ScoreChangeLog
  timeText: string
  operationText: string
  operationType: TagType
  sourceText: string
  sourceType: TagType
  operatorText: string
  /** "平时 80 → 70；总评 86 → 50"（新增/删除则是快照） */
  summary: string
  /** 逐字段的差异（改动过的字段会被强调） */
  parts: ReturnType<typeof scoreDiffParts>
  /** 是否值得强调（UPDATE 且确实有字段变化） */
  highlight: boolean
}

const rows = computed<ChangeRow[]>(() =>
  props.list.map((row) => {
    const parts = scoreDiffParts(row, row.operation === 'UPDATE')
    return {
      row,
      timeText: formatDateTime(row.createTime),
      operationText: operationLabel(row.operation),
      operationType: operationTagType(row.operation),
      sourceText: sourceLabel(row.source),
      sourceType: sourceTagType(row.source),
      operatorText: `${row.operatorId}（${roleLabel(row.operatorRole)}）`,
      summary: changeSummaryText(row),
      parts,
      highlight: row.operation === 'UPDATE' && parts.some((p) => p.changed),
    }
  }),
)

/** 课程列：`CS101 Java程序设计`；课程被删掉时仍要能看出是哪门课（显示 id 兜底） */
function courseText(row: ScoreChangeLog): string {
  const code = row.courseCode ?? ''
  const name = row.courseName ?? ''
  const text = `${code} ${name}`.trim()
  return text || (row.courseId !== null && row.courseId !== undefined ? `课程 #${row.courseId}` : '—')
}

/** 学生列：`2023001 张三`；扩展字段缺失时只显示学号 */
function studentText(row: ScoreChangeLog): string {
  return `${row.studentId} ${row.studentName ?? ''}`.trim() || '—'
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="rows"
    class="change-log-table"
    stripe
    row-key="row.id"
  >
    <el-table-column
      label="时间"
      width="150"
    >
      <template #default="{ row }">
        <span class="time-text">{{ row.timeText }}</span>
      </template>
    </el-table-column>

    <el-table-column
      label="操作"
      width="86"
    >
      <template #default="{ row }">
        <el-tag
          :type="row.operationType"
          size="small"
          effect="plain"
        >
          {{ row.operationText }}
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column
      label="来源"
      width="104"
    >
      <template #default="{ row }">
        <el-tag
          :type="row.sourceType"
          size="small"
          effect="plain"
        >
          {{ row.sourceText }}
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column
      label="操作人"
      width="170"
    >
      <template #default="{ row }">
        <span class="operator-text">{{ row.operatorText }}</span>
      </template>
    </el-table-column>

    <el-table-column
      label="学生"
      width="150"
    >
      <template #default="{ row }">
        {{ studentText(row.row) }}
      </template>
    </el-table-column>

    <el-table-column
      label="课程"
      min-width="180"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ courseText(row.row) }}
      </template>
    </el-table-column>

    <el-table-column
      label="改前 → 改后"
      min-width="300"
    >
      <template #default="{ row }">
        <!--
          逐字段展示差异：改动过的字段用主色强调。
          这里刻意**不给单一字符串**——申诉时要一眼看出"哪一项被动了"，
          把五个字段拼成一句话反而看不出来。
        -->
        <div
          v-if="row.parts.length > 0"
          class="diff-parts"
        >
          <span
            v-for="part in row.parts"
            :key="part.key"
            class="diff-part"
            :class="{ 'diff-part-changed': part.changed }"
          >
            <span class="diff-label">{{ part.label }}</span>
            <span
              v-if="row.row.operation === 'UPDATE'"
              class="diff-value"
            >
              {{ part.before }} → {{ part.after }}
            </span>
            <span
              v-else
              class="diff-value"
            >
              {{ row.row.operation === 'DELETE' ? part.before : part.after }}
            </span>
          </span>
        </div>
        <span
          v-else
          class="diff-empty"
        >
          {{ row.summary }}
        </span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.change-log-table {
  width: 100%;
}
.time-text {
  color: #4e5969;
  font-variant-numeric: tabular-nums;
}
.operator-text {
  color: #4e5969;
}
.diff-parts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
}
.diff-part {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f7f8fa;
  font-size: 13px;
  color: #4e5969;
  /* 只动 transform/opacity 之外的静态属性没关系：这里没有动画，不涉及合成层 */
}
.diff-part-changed {
  background: rgba(22, 93, 255, 0.08);
  color: #165dff;
  font-weight: 600;
}
.diff-label {
  color: inherit;
  opacity: 0.75;
}
.diff-value {
  font-variant-numeric: tabular-nums;
}
.diff-empty {
  color: #86909c;
}
</style>
