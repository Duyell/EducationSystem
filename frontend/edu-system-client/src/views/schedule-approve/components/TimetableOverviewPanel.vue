<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, watch } from 'vue'
import { useScheduleApprove } from '@/composables/useScheduleApprove'
import { WEEKDAY_OPTIONS, slotWithWeekText } from '@/utils/schedule'

/**
 * 课表总览面板（管理员）。
 *
 * 单一职责：筛选 + 全量课表 + 删除一条排课。
 * 排课申请审批是另一个面板（ScheduleApplyApprovalPanel），两者共用 useScheduleApprove
 * 这个 composable 的不同部分——所以这里只加载自己需要的课表数据。
 */
const props = defineProps<{
  /** 该标签页当前是否可见：切回来时重新拉取，避免审批/删除后看到旧数据 */
  active: boolean
}>()

const { times, timesLoading, timesError, term, weekday, loadTimes, removeTime } =
  useScheduleApprove()

onMounted(loadTimes)

// 标签页采用 lazy，首次切到本页时组件才挂载；之后的每次切回都刷新，
// 保证与排课审批的结果一致（审批通过会新增课表行）。
watch(
  () => props.active,
  (isActive) => {
    if (isActive) loadTimes()
  },
)

const handleRemove = async (id: number) => {
  try {
    await ElMessageBox.confirm(
      '确定删除这条排课？删除后该课程在此时间不再占用教师与教室。',
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  try {
    await removeTime(id)
    ElMessage.success('已删除')
  } catch {
    // 拦截器已提示
  }
}

const resetQuery = () => {
  term.value = ''
  weekday.value = undefined
  loadTimes()
}
</script>

<template>
  <div class="panel">
    <div class="filter-bar">
      <el-input
        v-model="term"
        placeholder="学期（如 2025-2026-1，留空为全部）"
        clearable
        style="width: 240px"
        @keyup.enter="loadTimes"
      />
      <el-select
        v-model="weekday"
        placeholder="星期"
        clearable
        style="width: 130px"
      >
        <el-option
          v-for="day in WEEKDAY_OPTIONS"
          :key="day.value"
          :label="day.label"
          :value="day.value"
        />
      </el-select>
      <el-button
        type="primary"
        :loading="timesLoading"
        @click="loadTimes"
      >
        查询
      </el-button>
      <el-button @click="resetQuery">
        重置
      </el-button>
      <span class="filter-hint">共 {{ times.length }} 条排课</span>
    </div>

    <el-alert
      v-if="timesError"
      :title="'加载失败：' + timesError"
      type="error"
      :closable="false"
      show-icon
      class="inline-alert"
    />

    <el-table
      v-loading="timesLoading"
      :data="times"
      stripe
      empty-text="没有符合条件的排课记录"
    >
      <el-table-column
        prop="courseCode"
        label="课程代码"
        width="110"
      />
      <el-table-column
        prop="courseName"
        label="课程名称"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="teacherName"
        label="教师"
        width="100"
      />
      <el-table-column
        label="上课时间"
        min-width="190"
      >
        <template #default="{ row }">
          {{ slotWithWeekText(row) }}
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
        label="容量/选课人数上限"
        width="150"
        align="center"
      >
        <template #default="{ row }">
          {{ row.roomCapacity ?? '—' }} / {{ row.maxStudent ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        prop="term"
        label="学期"
        width="115"
      />
      <el-table-column
        label="操作"
        width="90"
        align="center"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            type="danger"
            link
            size="small"
            @click="handleRemove(row.id)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.filter-hint {
  font-size: 12px;
  color: #999;
}
.inline-alert {
  margin-bottom: 12px;
}
</style>
