<script setup lang="ts">
import { computed } from 'vue'
import type { PlanCourse } from '@/types/models'

/**
 * 培养计划的课程清单，按必修/选修分组展示。
 *
 * 单一职责：只渲染课程清单；数据由页面注入。
 * 必修给出建议修读学期（回答"大一上该上什么"），选修只关心学分是否够。
 */
const props = defineProps<{
  courses: PlanCourse[]
  /** 已通过的课程代码，用于标记完成情况 */
  passedCodes?: string[]
}>()

const passedSet = computed(() => new Set(props.passedCodes ?? []))

const requiredCourses = computed(() =>
  props.courses.filter((c) => c.category === 'REQUIRED'),
)
const electiveCourses = computed(() =>
  props.courses.filter((c) => c.category === 'ELECTIVE'),
)

function isPassed(code: string) {
  return passedSet.value.has(code)
}

/** 建议学期序号 -> 可读文案（大一上 = 1） */
function semesterText(semester?: number) {
  if (!semester || semester < 1) return '—'
  const grade = Math.floor((semester - 1) / 2) + 1
  const half = (semester - 1) % 2 === 0 ? '上' : '下'
  return `大${['一', '二', '三', '四', '五'][grade - 1] ?? grade}${half}`
}
</script>

<template>
  <div class="plan-courses">
    <div class="section">
      <div class="section-title">
        必修课程（{{ requiredCourses.length }} 门）
        <span class="section-hint">必须全部通过</span>
      </div>
      <el-table
        :data="requiredCourses"
        size="small"
        stripe
        empty-text="暂无必修课程"
      >
        <el-table-column
          prop="courseCode"
          label="课程代码"
          width="120"
        />
        <el-table-column
          prop="courseName"
          label="课程名称"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="credit"
          label="学分"
          width="80"
          align="center"
        />
        <el-table-column
          label="建议修读"
          width="100"
          align="center"
        >
          <template #default="{ row }">
            {{ semesterText(row.suggestSemester) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="100"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              :type="isPassed(row.courseCode) ? 'success' : 'danger'"
              size="small"
              effect="plain"
            >
              {{ isPassed(row.courseCode) ? '已通过' : '未通过' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="section">
      <div class="section-title">
        选修课程（{{ electiveCourses.length }} 门）
        <span class="section-hint">只需学分总和达标，修哪些不限</span>
      </div>
      <el-table
        :data="electiveCourses"
        size="small"
        stripe
        empty-text="暂无选修课程"
      >
        <el-table-column
          prop="courseCode"
          label="课程代码"
          width="120"
        />
        <el-table-column
          prop="courseName"
          label="课程名称"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="credit"
          label="学分"
          width="80"
          align="center"
        />
        <el-table-column
          label="建议修读"
          width="100"
          align="center"
        >
          <template #default="{ row }">
            {{ semesterText(row.suggestSemester) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="100"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              :type="isPassed(row.courseCode) ? 'success' : 'info'"
              size="small"
              effect="plain"
            >
              {{ isPassed(row.courseCode) ? '已通过' : '未修读' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.plan-courses {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.section-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}
.section-hint {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #999;
}
</style>
