<script setup lang="ts">
import { computed } from 'vue'
import type { CourseGpa } from '@/types/models'

/**
 * 逐门课程的绩点明细表。
 *
 * 单一职责：渲染明细 + 高亮补考记录；数据由页面注入。
 */
const props = defineProps<{
  details: CourseGpa[]
}>()

const emit = defineEmits<{
  /** 点击课程名时把课程代码抛给父级（例如将来跳转课程详情） */
  (e: 'select', courseCode: string): void
}>()

/** 按绩点降序展示，便于学生一眼看到拉低/拉高绩点的课 */
const sorted = computed(() => [...props.details].sort((a, b) => b.gradePoint - a.gradePoint))

/** 绩点对应的 Element Plus 标签色，沿用成绩页的分档口径 */
function tagType(gradePoint: number) {
  if (gradePoint >= 4) return 'success'
  if (gradePoint >= 3) return 'primary'
  if (gradePoint >= 2) return 'warning'
  return 'danger'
}

function formatScore(value: number | null | undefined) {
  if (value === null || value === undefined) return '—'
  // 成绩可能是 90.4 这种带小数的，去掉尾随 0
  return Number(value).toFixed(3).replace(/\.?0+$/, '')
}

function formatGradePoint(value: number) {
  return Number(value).toFixed(4).replace(/\.?0+$/, '')
}
</script>

<template>
  <el-table
    :data="sorted"
    stripe
    empty-text="暂无已通过课程"
    class="course-table"
  >
    <el-table-column
      prop="courseCode"
      label="课程代码"
      width="120"
    >
      <template #default="{ row }">
        <el-link
          type="primary"
          :underline="false"
          @click="emit('select', row.courseCode)"
        >
          {{ row.courseCode }}
        </el-link>
      </template>
    </el-table-column>

    <el-table-column
      prop="courseName"
      label="课程名称"
      min-width="160"
      show-overflow-tooltip
    />

    <el-table-column
      prop="credit"
      label="学分"
      width="80"
      align="center"
    />

    <el-table-column
      prop="rawScore"
      label="原始成绩"
      width="100"
      align="center"
    >
      <template #default="{ row }">
        {{ formatScore(row.rawScore) }}
      </template>
    </el-table-column>

    <el-table-column
      label="计入绩点分数"
      width="140"
      align="center"
    >
      <template #default="{ row }">
        <span>{{ formatScore(row.usedScore) }}</span>
        <el-tag
          v-if="row.fromMakeup"
          size="small"
          type="warning"
          effect="plain"
          class="makeup-tag"
        >
          补考记 60
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column
      label="绩点"
      width="110"
      align="center"
    >
      <template #default="{ row }">
        <el-tag
          :type="tagType(row.gradePoint)"
          effect="light"
        >
          {{ formatGradePoint(row.gradePoint) }}
        </el-tag>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.course-table {
  width: 100%;
}
.makeup-tag {
  margin-left: 6px;
}
</style>
