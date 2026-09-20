<script setup lang="ts">
import { ref } from 'vue'
import CourseApplyApprovalPanel from './components/CourseApplyApprovalPanel.vue'
import ScheduleApplyApprovalPanel from './components/ScheduleApplyApprovalPanel.vue'
import TimetableOverviewPanel from './components/TimetableOverviewPanel.vue'

/**
 * 排课审批（管理员）。
 *
 * 本视图是**纯组合面**：只负责三个标签页的切换，不持有任何业务状态。
 * 每个标签页是一个自包含面板（各自的筛选、列表、审批动作与 composable），
 * 用 `lazy` 让面板首次切到该页时才挂载并发起请求——避免一进页面就打三个接口。
 *
 * 三个审批流的关系（用户确认的流程）：
 *   教师提交开课申请 → 管理员审批通过（生成课程）→ 教师申请排课 → 管理员审批通过（排入课表）
 */
const activeTab = ref('course')
</script>

<template>
  <div class="approve-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">排课审批</span>
          <span class="card-hint">
            开课申请通过后才会生成课程，课程排课通过后才进入课表
          </span>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <el-tab-pane
          label="开课申请审批"
          name="course"
          lazy
        >
          <CourseApplyApprovalPanel />
        </el-tab-pane>
        <el-tab-pane
          label="排课申请审批"
          name="schedule"
          lazy
        >
          <ScheduleApplyApprovalPanel />
        </el-tab-pane>
        <el-tab-pane
          label="课表总览"
          name="timetable"
          lazy
        >
          <TimetableOverviewPanel :active="activeTab === 'timetable'" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.approve-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-card {
  border-radius: 8px;
}
.card-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.card-hint {
  font-size: 12px;
  color: #999;
}
</style>
