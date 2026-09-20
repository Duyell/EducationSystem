<script setup lang="ts">
import { computed } from 'vue'
import type { GpaRank, GpaResult } from '@/types/models'

/**
 * 绩点总览：平均学分绩点、计入学分、专业内排名。
 *
 * 单一职责：只渲染总览数字，不发请求、不管理业务状态（由页面注入数据）。
 */
const props = defineProps<{
  gpa: GpaResult
  rank: GpaRank
}>()

/** 绩点保留 4 位（与后端 GPA 精度一致），去掉无意义的尾随 0 */
const gpaText = computed(() => formatNumber(props.gpa.gpa, 4))

/** 百分制等效分：绩点 + 5 后乘 10，便于学生理解（60 分 => 1.0 绩点） */
const equivalentScore = computed(() => {
  const g = Number(props.gpa.gpa)
  if (!g) return '—'
  return formatNumber((g + 5) * 10, 2)
})

const rankText = computed(() => {
  if (!props.rank.ranked) return '暂无排名'
  return `${props.rank.rank} / ${props.rank.total}`
})

/** 排名百分位（越靠前越好），用于展示"超过 xx%" */
const beatPercent = computed(() => {
  if (!props.rank.ranked || props.rank.total <= 1) return ''
  const beat = Math.round(((props.rank.total - props.rank.rank) / (props.rank.total - 1)) * 100)
  return `超过同专业 ${beat}% 的同学`
})

function formatNumber(value: number | null | undefined, scale: number) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return Number(value).toFixed(scale).replace(/\.?0+$/, '') || '0'
}
</script>

<template>
  <div class="gpa-summary">
    <div class="summary-main">
      <div class="main-value">
        {{ gpaText }}
      </div>
      <div class="main-label">
        平均学分绩点
      </div>
      <div class="main-hint">
        百分制约 {{ equivalentScore }} 分
      </div>
    </div>

    <el-divider direction="vertical" class="summary-divider" />

    <div class="summary-stats">
      <div class="stat-item">
        <div class="stat-value">
          {{ formatNumber(gpa.totalCredit, 1) }}
        </div>
        <div class="stat-label">
          计入学分
        </div>
      </div>
      <div class="stat-item">
        <div class="stat-value">
          {{ gpa.passedCount }}
        </div>
        <div class="stat-label">
          已通过课程
        </div>
      </div>
      <div class="stat-item">
        <div class="stat-value">
          {{ rankText }}
        </div>
        <div class="stat-label">
          专业内排名
        </div>
        <div
          v-if="rank.majorName"
          class="stat-hint"
        >
          {{ rank.majorName }} {{ rank.grade }} 级
        </div>
        <div
          v-if="beatPercent"
          class="stat-hint"
        >
          {{ beatPercent }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gpa-summary {
  display: flex;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
}
.summary-main {
  min-width: 150px;
}
.main-value {
  font-size: 40px;
  font-weight: 600;
  line-height: 1.1;
  color: #165dff;
}
.main-label {
  margin-top: 4px;
  font-size: 14px;
  color: #333;
}
.main-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #999;
}
.summary-divider {
  height: 56px;
}
.summary-stats {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}
.stat-item {
  min-width: 96px;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: #333;
}
.stat-label {
  margin-top: 2px;
  font-size: 13px;
  color: #666;
}
.stat-hint {
  font-size: 12px;
  color: #999;
}
</style>
