<script setup lang="ts">
import { ref } from 'vue'
import CourseApplyForm from './components/CourseApplyForm.vue'
import CourseApplyTable from './components/CourseApplyTable.vue'
import MyScheduleApplyTable from './components/MyScheduleApplyTable.vue'
import MyTimetable from './components/MyTimetable.vue'
import ScheduleApplyDialog from './components/ScheduleApplyDialog.vue'
import { useMyCourseApply } from '@/composables/useMyCourseApply'
import type { CourseApply } from '@/types/models'

/**
 * 开课申请与排课（教师）。
 *
 * 本视图是**组合面**：只做取数编排与四张卡片的拼装。
 * 取数在 composables/useMyCourseApply.ts，渲染细节在 components/ 下。
 *
 * 流程：新建开课申请 → 管理员审批 → 审批通过后才出现「申请排课」→ 提交后等管理员审批生效。
 */
const {
  applies,
  appliesLoading,
  appliesError,
  scheduleApplies,
  scheduleLoading,
  scheduleError,
  timetable,
  timetableLoading,
  timetableError,
  term,
  loadApplies,
  loadScheduleApplies,
  loadTimetable,
} = useMyCourseApply()

const formVisible = ref(false)
const scheduleVisible = ref(false)
/** 正在排课的开课申请 */
const scheduleTarget = ref<CourseApply | null>(null)

/** 最近一次提交带回来的冲突详情：非空时在页面上持续提示，直到用户关闭 */
const lastConflict = ref('')

const openSchedule = (row: CourseApply) => {
  scheduleTarget.value = row
  scheduleVisible.value = true
}

/** 排课提交成功后：刷新申请单与课表；有冲突则留下一条可见提示 */
const handleSubmitted = (conflictInfo: string) => {
  lastConflict.value = conflictInfo
  loadScheduleApplies()
  loadTimetable()
}
</script>

<template>
  <div class="apply-page">
    <el-alert
      v-if="lastConflict"
      type="warning"
      :closable="true"
      show-icon
      title="最近提交的排课申请存在时间冲突"
      class="page-alert"
      @close="lastConflict = ''"
    >
      {{ lastConflict }} —— 该申请仍会进入待审批，由管理员决定是否调整时间或教室。
    </el-alert>

    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">我的开课申请</span>
          <el-button
            type="primary"
            @click="formVisible = true"
          >
            新建申请
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="appliesError"
        :title="'加载失败：' + appliesError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <CourseApplyTable
        :applies="applies"
        :loading="appliesLoading"
        @schedule="openSchedule"
      />

      <div class="flow-hint">
        流程：提交申请 → 管理员审批通过（生成课程）→ 申请排课 → 管理员审批通过后排入课表。
        课程只有在管理员开启的选课轮次内才能被学生选到。
      </div>
    </el-card>

    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">我的排课申请</span>
          <el-button
            :loading="scheduleLoading"
            @click="loadScheduleApplies"
          >
            刷新
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="scheduleError"
        :title="'加载失败：' + scheduleError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <MyScheduleApplyTable
        :applies="scheduleApplies"
        :loading="scheduleLoading"
      />
    </el-card>

    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">我的课表</span>
          <div class="header-actions">
            <el-input
              v-model="term"
              placeholder="学期（如 2025-2026-1，留空为全部）"
              clearable
              style="width: 240px"
              @keyup.enter="loadTimetable"
            />
            <el-button
              type="primary"
              :loading="timetableLoading"
              @click="loadTimetable"
            >
              查询
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="timetableError"
        :title="'加载失败：' + timetableError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <MyTimetable
        :times="timetable"
        :loading="timetableLoading"
      />
    </el-card>

    <CourseApplyForm
      v-model="formVisible"
      @success="loadApplies"
    />

    <ScheduleApplyDialog
      v-model="scheduleVisible"
      :apply="scheduleTarget"
      @submitted="handleSubmitted"
    />
  </div>
</template>

<style scoped>
.apply-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-card {
  border-radius: 8px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.inline-alert {
  margin-bottom: 12px;
}
.page-alert {
  border-radius: 8px;
}
.flow-hint {
  margin-top: 12px;
  font-size: 12px;
  color: #999;
  line-height: 1.7;
}
</style>
