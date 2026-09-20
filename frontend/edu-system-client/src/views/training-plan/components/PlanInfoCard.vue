<script setup lang="ts">
import type { TrainingPlan } from '@/types/models'

/**
 * 培养计划基本信息（专业/年级/学分要求/备注）。
 *
 * 单一职责：只渲染方案头部信息；数据由页面注入。
 */
defineProps<{
  plan: TrainingPlan
}>()
</script>

<template>
  <el-descriptions
    :column="4"
    border
    class="plan-info"
  >
    <el-descriptions-item label="方案名称">
      {{ plan.planName }}
    </el-descriptions-item>
    <el-descriptions-item label="适用专业">
      {{ plan.majorName || '—' }}
    </el-descriptions-item>
    <el-descriptions-item label="适用年级">
      {{ plan.grade }} 级
    </el-descriptions-item>
    <el-descriptions-item label="所属学院">
      {{ plan.collegeName || '—' }}
    </el-descriptions-item>
    <el-descriptions-item label="总学分要求">
      {{ plan.totalCredits }}
    </el-descriptions-item>
    <el-descriptions-item label="必修学分要求">
      {{ plan.requiredCredits }}
    </el-descriptions-item>
    <el-descriptions-item label="选修学分要求">
      {{ plan.electiveCredits }}
    </el-descriptions-item>
    <el-descriptions-item label="状态">
      <el-tag
        :type="plan.status === 1 ? 'success' : 'info'"
        size="small"
        effect="plain"
      >
        {{ plan.status === 1 ? '启用' : '停用' }}
      </el-tag>
    </el-descriptions-item>
    <el-descriptions-item
      v-if="plan.remark"
      label="备注"
      :span="4"
    >
      {{ plan.remark }}
    </el-descriptions-item>
  </el-descriptions>
</template>

<style scoped>
.plan-info {
  width: 100%;
}
</style>
