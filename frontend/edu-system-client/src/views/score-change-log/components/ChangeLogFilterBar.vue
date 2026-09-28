<script setup lang="ts">
import type { ScoreChangeLogQuery } from '@/types/models'

/**
 * 成绩变更日志的筛选条。
 *
 * 单一职责：渲染"学号 / 操作人 / 课程ID"三个筛选项，并把查询、重置的**意图**抛给父级。
 * 它不发请求、不持有列表状态（与 `ExamFilterBar` / `RoomFilterBar` 同一分工）。
 *
 * `v-model` 绑定筛选条件对象：这是真双向契约（父级持有状态，子级就地改字段）。
 */
const query = defineModel<ScoreChangeLogQuery>({ required: true })

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
}>()
</script>

<template>
  <div class="filter-bar">
    <el-input
      v-model="query.studentId"
      placeholder="学号（如 2023001）"
      clearable
      style="width: 190px"
      @keyup.enter="emit('search')"
    />
    <el-input
      v-model="query.operatorId"
      placeholder="操作人工号/用户名"
      clearable
      style="width: 190px"
      @keyup.enter="emit('search')"
    />
    <el-input
      v-model="query.courseId"
      placeholder="课程ID（可选）"
      clearable
      style="width: 150px"
      @keyup.enter="emit('search')"
    />
    <el-button
      type="primary"
      @click="emit('search')"
    >
      查询
    </el-button>
    <el-button @click="emit('reset')">
      重置
    </el-button>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
</style>
