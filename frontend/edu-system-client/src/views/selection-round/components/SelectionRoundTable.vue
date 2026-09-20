<script setup lang="ts">
import { computed } from 'vue'
import type { SelectionRound } from '@/types/models'
import { dateTimeRangeText, roundPhaseOf, scopesSummary } from '@/utils/selection'
import type { RoundPhaseInfo } from '@/utils/selection'

/**
 * 轮次列表。
 *
 * 单一职责：渲染 + 抛意图。开关、删除、打开适用范围抽屉都交给父级
 * （那些动作要弹确认框、要刷新列表，属于编排，不属于表格）。
 */
const props = defineProps<{
  rounds: SelectionRound[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'edit', round: SelectionRound): void
  (e: 'toggle-status', round: SelectionRound): void
  (e: 'manage-scope', round: SelectionRound): void
  (e: 'remove', round: SelectionRound): void
}>()

interface RoundRow {
  round: SelectionRound
  phase: RoundPhaseInfo
  openable: boolean
}

/**
 * 状态每行只算一次。
 * 直接在模板里调 `roundPhaseOf(...)` 会在每个单元格各算一次（同一行算 3 遍），
 * 而且 derived 逻辑留在模板里也不好读。
 */
const rows = computed<RoundRow[]>(() =>
  props.rounds.map((round) => ({
    round,
    phase: roundPhaseOf(round),
    openable: round.status !== 1,
  })),
)
</script>

<template>
  <el-table
    v-loading="loading"
    :data="rows"
    stripe
    empty-text="暂无选课轮次"
  >
    <el-table-column
      label="轮次名称"
      min-width="200"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ row.round.roundName }}
      </template>
    </el-table-column>

    <el-table-column
      label="学期"
      width="130"
    >
      <template #default="{ row }">
        {{ row.round.term }}
      </template>
    </el-table-column>

    <el-table-column
      label="选课时间窗"
      min-width="230"
    >
      <template #default="{ row }">
        {{ dateTimeRangeText(row.round.selectStart, row.round.selectEnd) }}
      </template>
    </el-table-column>

    <el-table-column
      label="补退选时间窗"
      min-width="230"
    >
      <template #default="{ row }">
        {{ dateTimeRangeText(row.round.dropStart, row.round.dropEnd) }}
      </template>
    </el-table-column>

    <el-table-column
      label="学分上限"
      width="100"
      align="center"
    >
      <template #default="{ row }">
        {{ row.round.maxCredits ?? '不限' }}
      </template>
    </el-table-column>

    <!--
      三种状态是这个功能的核心，故用颜色区分 + 悬浮解释"为什么是这个状态、学生此刻能做什么"。
      状态是前端按「开关 + 两个时间窗」算出来的（后端没这个字段），提示里已说明。
    -->
    <el-table-column
      label="状态"
      width="150"
      align="center"
    >
      <template #default="{ row }">
        <el-tooltip
          :content="row.phase.hint"
          placement="top"
          :show-after="150"
        >
          <el-tag
            :type="row.phase.tagType"
            size="small"
            effect="plain"
          >
            {{ row.phase.label }}
          </el-tag>
        </el-tooltip>
      </template>
    </el-table-column>

    <el-table-column
      label="适用范围"
      min-width="170"
      show-overflow-tooltip
    >
      <template #default="{ row }">
        {{ scopesSummary(row.round.scopes) }}
      </template>
    </el-table-column>

    <el-table-column
      label="操作"
      width="240"
      fixed="right"
    >
      <template #default="{ row }">
        <el-button
          type="primary"
          link
          size="small"
          @click="emit('edit', row.round)"
        >
          编辑
        </el-button>
        <el-button
          :type="row.openable ? 'success' : 'warning'"
          link
          size="small"
          @click="emit('toggle-status', row.round)"
        >
          {{ row.openable ? '开启选课' : '关闭选课' }}
        </el-button>
        <el-button
          type="primary"
          link
          size="small"
          @click="emit('manage-scope', row.round)"
        >
          适用范围
        </el-button>
        <el-button
          type="danger"
          link
          size="small"
          @click="emit('remove', row.round)"
        >
          删除
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
