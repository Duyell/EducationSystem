<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ExamFilterBar from './components/ExamFilterBar.vue'
import ExamForm from './components/ExamForm.vue'
import ExamTable from './components/ExamTable.vue'
import { useExamManage } from '@/composables/useExamManage'
import type { ExamSchedule } from '@/types/models'

/**
 * 考试安排（管理员）。
 *
 * 本视图是**组合面**：取数编排 + 筛选条 + 一张表格 + 一个编辑对话框。
 * 取数在 composables/useExamManage.ts，渲染与意图在 components/ 下。
 *
 * 冲突检测全部在 `ExamForm` 里（那是唯一会发生冲突的地方），
 * 列表页只负责展示与增删改的编排。
 */
const {
  exams,
  loading,
  loadError,
  query,
  courseOptions,
  roomOptions,
  loadExams,
  resetQuery,
  removeExam,
} = useExamManage()

const formVisible = ref(false)
/** 正在编辑的考试；null = 新建 */
const editingExam = ref<ExamSchedule | null>(null)

const openCreate = () => {
  editingExam.value = null
  formVisible.value = true
}

const openEdit = (exam: ExamSchedule) => {
  editingExam.value = exam
  formVisible.value = true
}

const handleRemove = async (exam: ExamSchedule) => {
  if (!exam.id) return
  const label = `${exam.courseCode ?? ''} ${exam.courseName ?? ''}`.trim()
  try {
    await ElMessageBox.confirm(
      `确定删除「${label}」的这场考试安排？删除后学生就看不到它了。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return // 用户取消：ElMessageBox 取消时会 reject，不接住就是未处理的 Promise 异常
  }
  const ok = await removeExam(exam.id)
  if (ok) {
    ElMessage.success('已删除')
    await loadExams()
  }
}
</script>

<template>
  <div class="exam-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">考试安排</span>
          <el-button
            type="primary"
            @click="openCreate"
          >
            新建考试
          </el-button>
        </div>
      </template>

      <div class="rule-hint">
        冲突有两类：<strong>考场占用</strong>（同一考场同一时段）与
        <strong>学生撞考</strong>（同时选了这两门课的学生会被排在同一个时段）。
        新建/编辑时会实时预检并在服务端硬校验——<strong>有冲突就保存不了</strong>，
        请改时间或考场。
      </div>

      <ExamFilterBar
        v-model="query"
        :course-options="courseOptions"
        @search="loadExams"
        @reset="resetQuery"
      />

      <el-alert
        v-if="loadError"
        :title="'加载失败：' + loadError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <ExamTable
        :exams="exams"
        :loading="loading"
        @edit="openEdit"
        @remove="handleRemove"
      />
    </el-card>

    <ExamForm
      v-model="formVisible"
      :exam="editingExam"
      :course-options="courseOptions"
      :room-options="roomOptions"
      @success="loadExams"
    />
  </div>
</template>

<style scoped>
.exam-page {
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
.rule-hint {
  margin-bottom: 14px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.8;
  color: #666;
  background: #f7f9fc;
  border-radius: 6px;
}
.rule-hint strong {
  color: #333;
}
.inline-alert {
  margin-bottom: 12px;
}
</style>
