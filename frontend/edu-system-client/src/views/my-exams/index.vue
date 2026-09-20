<script setup lang="ts">
import MyExamSummary from './components/MyExamSummary.vue'
import MyExamTable from './components/MyExamTable.vue'
import { useMyExams } from '@/composables/useMyExams'

/**
 * 我的考试（学生）。
 *
 * 本视图是**组合面**：取数在 composables/useMyExams.ts，
 * 概览与表格在 components/ 下（同一个表格组件复用于"待考"与"已考"两段）。
 *
 * 分两段的理由：学生打开这一页，90% 是想知道"接下来考什么、在哪考"，
 * 已经考完的只是备查——所以待考按时间**升序**排在最上面，已考降序落在下面。
 */
const {
  loading,
  loadError,
  term,
  upcomingOnly,
  now,
  pendingExams,
  pastExams,
  summary,
  nextEndText,
  load,
  resetFilters,
} = useMyExams()
</script>

<template>
  <div class="my-exam-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">我的考试</span>
          <div class="header-actions">
            <el-input
              v-model="term"
              placeholder="学期（如 2024-2025-1，留空为全部）"
              clearable
              style="width: 240px"
              @keyup.enter="load"
            />
            <el-checkbox
              v-model="upcomingOnly"
              @change="load"
            >
              只看未开考
            </el-checkbox>
            <el-button
              :loading="loading"
              @click="resetFilters"
            >
              重置
            </el-button>
            <el-button
              type="primary"
              :loading="loading"
              @click="load"
            >
              查询
            </el-button>
          </div>
        </div>
      </template>

      <div class="hint-line">
        只列出<strong>你已选课程</strong>的考试安排。勾选「只看未开考」后，
        正在进行中的考试也会被服务端过滤掉——想看全部就取消勾选。
      </div>

      <el-alert
        v-if="loadError"
        :title="'加载失败：' + loadError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <MyExamSummary
        :summary="summary"
        :end-text="nextEndText"
      />
    </el-card>

    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">待考（{{ pendingExams.length }}）</span>
          <span class="card-sub">按开考时间升序，最近的在最上面</span>
        </div>
      </template>
      <MyExamTable
        :exams="pendingExams"
        :loading="loading"
        :now="now"
        empty-text="暂无待考考试"
      />
    </el-card>

    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">已考（{{ pastExams.length }}）</span>
          <span class="card-sub">按开考时间降序，刚考完的在最上面</span>
        </div>
      </template>
      <MyExamTable
        :exams="pastExams"
        :loading="loading"
        :now="now"
        empty-text="暂无已结束的考试"
      />
    </el-card>
  </div>
</template>

<style scoped>
.my-exam-page {
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
.card-sub {
  font-size: 12px;
  color: #999;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.hint-line {
  margin-bottom: 12px;
  font-size: 12px;
  line-height: 1.8;
  color: #999;
}
.inline-alert {
  margin-bottom: 12px;
}
</style>
