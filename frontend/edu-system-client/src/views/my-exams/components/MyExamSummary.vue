<script setup lang="ts">
import type { MyExamSummaryData } from '@/composables/useMyExams'

/**
 * "我的考试"页顶部的概览。
 *
 * 单一职责：只渲染 composable 算好的那句话 + 结束时间。
 * 它不判断"哪场最近"、也不算"还有几天"——那些派生都在 `useMyExams` 里，
 * 组件只决定用什么颜色呈现（临近考试用强调色）。
 */
defineProps<{
  summary: MyExamSummaryData
  /** 最近一场的结束时间文案，无待考时为空串 */
  endText: string
}>()
</script>

<template>
  <el-alert
    :type="summary.urgent ? 'warning' : 'info'"
    :closable="false"
    show-icon
    class="exam-summary"
  >
    <template #title>
      <span class="summary-title">{{ summary.text }}</span>
    </template>
    <div
      v-if="endText"
      class="summary-sub"
    >
      最近一场 {{ endText }}
    </div>
  </el-alert>
</template>

<style scoped>
.exam-summary {
  border-radius: 8px;
}
.summary-title {
  font-size: 14px;
  font-weight: 600;
}
.summary-sub {
  margin-top: 2px;
  font-size: 12px;
}
</style>
