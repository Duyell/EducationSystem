<script setup lang="ts">
import { computed } from 'vue'
import CourseGpaTable from './components/CourseGpaTable.vue'
import CreditProgressCard from './components/CreditProgressCard.vue'
import GpaSummaryCard from './components/GpaSummaryCard.vue'
import { useMyGpa } from '@/composables/useMyGpa'

/**
 * 我的绩点（学生）。
 *
 * 本视图是**组合面**（composition surface）：只负责取数与组合三个展示组件，
 * 具体渲染逻辑分别落在 components/ 下的单一职责组件里。
 * 数据获取与状态在 composables/useMyGpa.ts。
 */
const { data, audit, loading, loadError, term, load } = useMyGpa()

/** 有数据且确实完成了排名，才展示排名相关区块 */
const hasData = computed(() => !!data.value)
</script>

<template>
  <div
    v-loading="loading"
    class="gpa-page"
  >
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">我的绩点</span>
          <div class="header-actions">
            <el-input
              v-model="term"
              placeholder="学期（如 2024-2025-1，留空为累计）"
              clearable
              style="width: 240px"
              @keyup.enter="load"
            />
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

      <el-empty
        v-if="loadError"
        :description="'加载失败：' + loadError"
      />
      <el-empty
        v-else-if="!hasData"
        description="暂无绩点数据（需要先有已通过的成绩）"
      />
      <GpaSummaryCard
        v-else
        :gpa="data!.gpa"
        :rank="data!.rank"
      />
    </el-card>

    <el-card
      v-if="hasData"
      shadow="never"
      class="page-card"
    >
      <template #header>
        <span class="card-title">课程绩点明细</span>
      </template>
      <CourseGpaTable :details="data!.gpa.details" />
    </el-card>

    <el-card
      v-if="audit"
      shadow="never"
      class="page-card"
    >
      <template #header>
        <span class="card-title">毕业学分进度</span>
      </template>
      <CreditProgressCard :audit="audit" />
    </el-card>
  </div>
</template>

<style scoped>
.gpa-page {
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
</style>
