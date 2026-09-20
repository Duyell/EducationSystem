<script setup lang="ts">
import type { Course } from '@/types/models'

/**
 * 我的已选课程。
 *
 * 单一职责：只展示（表格 + 学分合计）。学分是否超上限的判定在 composable 里算好传进来，
 * 展示组件不承担业务规则。
 *
 * 这里**不放退课按钮**：可退与否取决于该课程所属学期的轮次状态，
 * 而这张卡片可能跨学期列出课程，用当前学期的开关去判断会给出错误结论；
 * 退课统一在「可选课程」列表里做（那里每行都带着服务端算好的可否退课）。
 */
defineProps<{
  courses: Course[]
  loading: boolean
  /** 已选学分合计（1 位小数） */
  totalCredit: number
  /** 本轮学分上限，可空 = 不限 */
  maxCredits?: number | null
  /** 是否已超出本轮上限 */
  overCap: boolean
}>()
</script>

<template>
  <div class="my-courses">
    <div class="summary">
      <span class="summary-main">
        已选 {{ courses.length }} 门 · 合计 <strong>{{ totalCredit }}</strong> 学分
      </span>
      <span
        v-if="maxCredits !== null && maxCredits !== undefined"
        class="muted-text"
      >
        （本轮上限 {{ maxCredits }} 学分）
      </span>
      <span
        v-else
        class="muted-text"
      >
        （本轮未设学分上限）
      </span>
    </div>

    <el-alert
      v-if="overCap"
      title="已超出本轮学分上限，建议退掉部分课程"
      type="warning"
      :closable="false"
      show-icon
      class="cap-alert"
    />

    <el-table
      v-loading="loading"
      :data="courses"
      stripe
      empty-text="还没有选任何课程"
    >
      <el-table-column
        label="课程代码"
        width="110"
      >
        <template #default="{ row }">
          {{ row.courseCode || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        prop="courseName"
        label="课程名称"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column
        prop="teacherName"
        label="授课教师"
        width="110"
      />
      <el-table-column
        prop="term"
        label="学期"
        width="130"
      />
      <el-table-column
        prop="credit"
        label="学分"
        width="80"
        align="center"
      />
    </el-table>
  </div>
</template>

<style scoped>
.my-courses {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.summary {
  font-size: 13px;
  color: #666;
}
.summary-main strong {
  color: #165dff;
}
.muted-text {
  font-size: 12px;
  color: #999;
}
.cap-alert {
  border-radius: 8px;
}
</style>
