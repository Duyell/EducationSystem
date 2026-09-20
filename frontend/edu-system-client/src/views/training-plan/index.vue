<script setup lang="ts">
import { computed } from 'vue'
import CreditProgressCard from '@/views/gpa/components/CreditProgressCard.vue'
import PlanCourseList from './components/PlanCourseList.vue'
import PlanInfoCard from './components/PlanInfoCard.vue'
import { useMyTrainingPlan } from '@/composables/useMyTrainingPlan'

/**
 * 我的培养方案（学生）。
 *
 * 组合面：取数 + 组合三个展示组件。
 * 数据获取在 composables/useMyTrainingPlan.ts，渲染细节在 components/ 下。
 */
const { data, loading, loadError, load } = useMyTrainingPlan()

/** 已通过的课程代码（从审核结果的缺失清单反推，用于给清单打「已通过」标记） */
const passedCodes = computed(() => {
  const payload = data.value
  if (!payload?.plan || !payload.courses.length) return []
  const missing = new Set(payload.audit.missingRequired.map((m) => m.courseCode))
  const unmetElective = new Set(payload.audit.unmetElectiveCodes)
  // 方案内既不在缺失必修、也不在未修选修里的课程 = 已通过
  return payload.courses
    .map((c) => c.courseCode)
    .filter((code) => !missing.has(code) && !unmetElective.has(code))
})

/** 学生定位信息未齐时，提示为什么可能没有方案 */
const placementMissing = computed(() => {
  const p = data.value?.placement
  return !!p && (!p.majorId || !p.grade)
})
</script>

<template>
  <div
    v-loading="loading"
    class="plan-page"
  >
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">我的培养方案</span>
          <el-button
            :loading="loading"
            @click="load"
          >
            刷新
          </el-button>
        </div>
      </template>

      <el-empty
        v-if="loadError"
        :description="'加载失败：' + loadError"
      />

      <template v-else-if="data">
        <el-alert
          v-if="placementMissing"
          title="缺少专业或年级信息，无法定位培养方案（请联系管理员补全学籍信息）"
          type="warning"
          :closable="false"
          show-icon
          class="plan-alert"
        />
        <el-alert
          v-else-if="!data.plan"
          :title="`暂无适用于「${data.placement?.majorName || '本专业'} ${data.placement?.grade || ''} 级」的培养方案，请联系管理员录入`"
          type="warning"
          :closable="false"
          show-icon
          class="plan-alert"
        />

        <PlanInfoCard
          v-if="data.plan"
          :plan="data.plan"
        />
      </template>

      <el-empty
        v-else
        description="暂无数据"
      />
    </el-card>

    <el-card
      v-if="data?.plan && data.courses.length"
      shadow="never"
      class="page-card"
    >
      <template #header>
        <span class="card-title">课程清单</span>
      </template>
      <PlanCourseList
        :courses="data.courses"
        :passed-codes="passedCodes"
      />
    </el-card>

    <el-card
      v-if="data"
      shadow="never"
      class="page-card"
    >
      <template #header>
        <span class="card-title">毕业学分进度</span>
      </template>
      <CreditProgressCard :audit="data.audit" />
    </el-card>
  </div>
</template>

<style scoped>
.plan-page {
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
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
.plan-alert {
  margin-bottom: 12px;
}
</style>
