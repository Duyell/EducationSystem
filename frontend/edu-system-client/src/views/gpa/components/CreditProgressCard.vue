<script setup lang="ts">
import { computed } from 'vue'
import type { CreditAudit } from '@/types/models'

/**
 * 毕业学分进度：总学分/必修/选修三条进度 + 未通过必修清单。
 *
 * 单一职责：只渲染审核结果；数据由页面注入。
 * 区分「未修读」与「已修未通过」——前者要选课、后者要补考，动作不同。
 */
const props = defineProps<{
  audit: CreditAudit
}>()

/** 方案未建立时不做任何进度展示，只提示需联系管理员 */
const hasPlan = computed(() => props.audit.planFound)

/**
 * 是否全部达标。
 *
 * 这里**必须**由三个标志推导，不能读 `audit.satisfied`：
 * 后端 `AuditResult.satisfied()` 是 record 派生方法，Jackson 只序列化 record 组件，
 * 报文里根本没有该字段（若直接读会恒为 undefined，成功分支永远走不到）。
 */
const allSatisfied = computed(
  () =>
    props.audit.planFound &&
    props.audit.creditSatisfied &&
    props.audit.requiredSatisfied &&
    props.audit.electiveSatisfied,
)

interface ProgressRow {
  key: string
  label: string
  earned: number
  required: number
  satisfied: boolean
}

const rows = computed<ProgressRow[]>(() => [
  {
    key: 'total',
    label: '总学分',
    earned: Number(props.audit.earnedCredits),
    required: Number(props.audit.totalCredits),
    satisfied: props.audit.creditSatisfied,
  },
  {
    key: 'required',
    label: '必修学分',
    earned: Number(props.audit.earnedRequiredCredit),
    required: Number(props.audit.requiredCredits),
    satisfied: props.audit.requiredSatisfied,
  },
  {
    key: 'elective',
    label: '选修学分',
    earned: Number(props.audit.earnedElectiveCredit),
    required: Number(props.audit.electiveCredits),
    satisfied: props.audit.electiveSatisfied,
  },
])

/** 进度百分比（上限 100，避免超过要求时进度条溢出） */
function percent(earned: number, required: number) {
  if (!required || required <= 0) return 0
  return Math.min(100, Math.round((earned / required) * 100))
}

function statusText(satisfied: boolean) {
  return satisfied ? '已达标' : '未达标'
}
</script>

<template>
  <div class="credit-progress">
    <el-empty
      v-if="!hasPlan"
      description="暂无培养计划，无法核算毕业学分（请联系管理员录入本专业本年级的培养计划）"
      :image-size="72"
    />

    <template v-else>
      <el-alert
        :title="allSatisfied ? '毕业学分要求已全部达成' : '尚未满足毕业学分要求'"
        :type="allSatisfied ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="credit-alert"
      />

      <div
        v-for="row in rows"
        :key="row.key"
        class="progress-row"
      >
        <div class="progress-head">
          <span class="progress-label">{{ row.label }}</span>
          <span class="progress-value">
            {{ row.earned }} / {{ row.required }}
            <el-tag
              :type="row.satisfied ? 'success' : 'danger'"
              size="small"
              effect="plain"
              class="progress-tag"
            >
              {{ statusText(row.satisfied) }}
            </el-tag>
          </span>
        </div>
        <el-progress
          :percentage="percent(row.earned, row.required)"
          :status="row.satisfied ? 'success' : undefined"
          :stroke-width="10"
        />
      </div>

      <div
        v-if="audit.missingRequired.length"
        class="missing-block"
      >
        <div class="missing-title">
          未通过的必修课（{{ audit.missingRequired.length }} 门）
        </div>
        <el-table
          :data="audit.missingRequired"
          size="small"
          empty-text="无"
        >
          <el-table-column
            prop="courseCode"
            label="课程代码"
            width="120"
          />
          <el-table-column
            prop="courseName"
            label="课程名称"
            min-width="140"
            show-overflow-tooltip
          />
          <el-table-column
            prop="credit"
            label="学分"
            width="80"
            align="center"
          />
          <el-table-column
            prop="suggestSemester"
            label="建议学期"
            width="90"
            align="center"
          >
            <template #default="{ row }">
              {{ row.suggestSemester ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="处理方式"
            width="110"
            align="center"
          >
            <template #default="{ row }">
              <el-tag
                :type="row.state === 'FAILED' ? 'warning' : 'info'"
                size="small"
                effect="plain"
              >
                {{ row.state === 'FAILED' ? '需补考' : '待选课' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div
        v-else
        class="missing-none"
      >
        必修课已全部通过
      </div>
    </template>
  </div>
</template>

<style scoped>
.credit-alert {
  margin-bottom: 16px;
}
.progress-row {
  margin-bottom: 14px;
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 13px;
}
.progress-label {
  color: #333;
}
.progress-value {
  color: #666;
}
.progress-tag {
  margin-left: 6px;
}
.missing-block {
  margin-top: 16px;
}
.missing-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #333;
}
.missing-none {
  margin-top: 12px;
  font-size: 13px;
  color: #67c23a;
}
</style>
