<script setup lang="ts">
import ChangeLogFilterBar from './components/ChangeLogFilterBar.vue'
import ChangeLogTable from './components/ChangeLogTable.vue'
import { useScoreChangeLog } from '@/composables/useScoreChangeLog'

/**
 * 成绩变更日志（管理员）。
 *
 * 制度依据 JW-09 §4.5：成绩是**可申诉数据**，"谁在什么时候把谁的成绩从多少改成多少"
 * 必须可查——界面直接改的记录此前是缺失的一环（只有经 AI 助手的变更会写审计表）。
 *
 * 本视图是**组合面**：取数在 `composables/useScoreChangeLog.ts`，
 * 筛选条与表格各是一个单一职责子组件，这里只做拼装与分页编排。
 *
 * 刻意**只读**：这是一张审计视图，不提供任何修改入口（要改成绩请去「成绩管理」，
 * 那里会留下新的变更记录）。
 */
const {
  list,
  total,
  loading,
  loadError,
  pageNum,
  pageSize,
  query,
  search,
  submitSearch,
  resetQuery,
  changePage,
  changePageSize,
} = useScoreChangeLog()
</script>

<template>
  <div class="change-log-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">成绩变更日志</span>
          <el-button
            size="small"
            :loading="loading"
            @click="search"
          >
            刷新
          </el-button>
        </div>
      </template>

      <div class="rule-hint">
        谁、什么时候、把谁的哪门课成绩从多少改成了多少，都在这里。
        <strong>来源</strong>一列区分「界面/接口」与「AI 助手」：成绩申诉时先看它，
        再按操作人核实。本页<strong>只读</strong>；要改成绩请到「成绩管理」，改动会自动留下新记录。
      </div>

      <ChangeLogFilterBar
        v-model="query"
        @search="submitSearch"
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

      <ChangeLogTable
        :list="list"
        :loading="loading"
      />

      <el-pagination
        :current-page="pageNum"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        class="page-box"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </el-card>
  </div>
</template>

<style scoped>
.change-log-page {
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
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
}
.rule-hint {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f7f8fa;
  color: #4e5969;
  font-size: 13px;
  line-height: 1.7;
}
.inline-alert {
  margin-bottom: 12px;
}
.page-box {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
