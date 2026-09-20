<script setup lang="ts">
import { EXAM_TYPE_OPTIONS } from '@/utils/exam'
import type { Course, ExamQuery } from '@/types/models'

/**
 * 考试列表的筛选条。
 *
 * 单一职责：只渲染"学期 + 类型 + 课程"三个筛选项，并把查询/重置意图抛给父级。
 * 它不发请求、不持有列表状态。
 *
 * `v-model` 绑定筛选条件对象（真双向契约，用 defineModel）；
 * 对象内部字段就地修改，父级拿到的是同一个引用。
 */
const query = defineModel<ExamQuery>({ required: true })

defineProps<{
  /** 课程下拉数据由父级统一提供（页面级公共数据，筛选条自己去取会重复请求） */
  courseOptions: Course[]
}>()

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
}>()
</script>

<template>
  <div class="filter-bar">
    <el-input
      v-model="query.term"
      placeholder="学期（如 2024-2025-1）"
      clearable
      style="width: 200px"
      @keyup.enter="emit('search')"
    />
    <el-select
      v-model="query.examType"
      placeholder="考试类型"
      clearable
      style="width: 140px"
    >
      <el-option
        v-for="opt in EXAM_TYPE_OPTIONS"
        :key="opt.value"
        :label="opt.label"
        :value="opt.value"
      />
    </el-select>
    <el-select
      v-model="query.courseId"
      placeholder="课程（可留空）"
      clearable
      filterable
      style="width: 260px"
    >
      <el-option
        v-for="c in courseOptions"
        :key="c.id"
        :label="`${c.courseCode ?? ''} ${c.courseName}`.trim()"
        :value="c.id"
      />
    </el-select>
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
