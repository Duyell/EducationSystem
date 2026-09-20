<script setup lang="ts">
import { computed } from 'vue'
import { groupByWeekday, weekText } from '@/utils/schedule'
import type { ClassTime } from '@/types/models'

/**
 * 我的课表（教师）。
 *
 * 单一职责：把已生效的排课记录按星期几分组展示。
 * 分组本身是纯派生，放 computed（不写进模板里做筛选/排序）。
 */
const props = defineProps<{
  times: ClassTime[]
  loading: boolean
}>()

const groups = computed(() => groupByWeekday(props.times))

/** 课表条数 = 一周要上几次课，作为一句话概览 */
const summary = computed(() => {
  if (!props.times.length) return ''
  const courses = new Set(props.times.map((item) => item.courseId))
  return `共 ${courses.size} 门课、${props.times.length} 个课次`
})
</script>

<template>
  <div
    v-loading="loading"
    class="timetable"
  >
    <el-empty
      v-if="!loading && !times.length"
      description="暂无已生效的排课（需管理员审批通过后才进入课表）"
      :image-size="72"
    />

    <template v-else>
      <div
        v-if="summary"
        class="timetable-summary"
      >
        {{ summary }}
      </div>

      <div
        v-for="group in groups"
        :key="group.weekday"
        class="day-block"
      >
        <div class="day-title">
          {{ group.label }}
          <span class="day-count">{{ group.rows.length }} 次</span>
        </div>
        <el-table
          :data="group.rows"
          size="small"
          stripe
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
            label="节次"
            width="110"
            align="center"
          >
            <template #default="{ row }">
              {{ row.startPeriod }}-{{ row.endPeriod }} 节
            </template>
          </el-table-column>
          <el-table-column
            label="周次"
            width="120"
            align="center"
          >
            <template #default="{ row }">
              {{ weekText(row.startWeek, row.endWeek) }}
            </template>
          </el-table-column>
          <el-table-column
            label="教室"
            width="130"
          >
            <template #default="{ row }">
              {{ row.roomName || '待分配' }}
            </template>
          </el-table-column>
          <el-table-column
            prop="term"
            label="学期"
            width="120"
          />
        </el-table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.timetable {
  min-height: 80px;
}
.timetable-summary {
  margin-bottom: 12px;
  font-size: 13px;
  color: #666;
}
.day-block {
  margin-bottom: 18px;
}
.day-title {
  margin-bottom: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}
.day-count {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #999;
}
</style>
