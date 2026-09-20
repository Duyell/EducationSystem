<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SelectionRoundFilterBar from './components/SelectionRoundFilterBar.vue'
import SelectionRoundForm from './components/SelectionRoundForm.vue'
import SelectionRoundTable from './components/SelectionRoundTable.vue'
import ScopeManager from './components/ScopeManager.vue'
import { useSelectionRoundManage } from '@/composables/useSelectionRoundManage'
import type { SelectionRound } from '@/types/models'

/**
 * 选课轮次管理（管理员）。
 *
 * 本视图是**组合面**：取数编排 + 一张表格 + 两个弹层（编辑对话框、范围抽屉）。
 * 取数在 composables/useSelectionRoundManage.ts，渲染与意图在 components/ 下。
 *
 * 三种状态（可选 / 只能退 / 只能看）是前端按「开关 + 两个时间窗」算出来的，
 * 页面上有说明——避免管理员误以为是后端给的状态。
 */
const {
  rounds,
  loading,
  loadError,
  query,
  loadRounds,
  resetQuery,
  setRoundStatus,
  removeRound,
} = useSelectionRoundManage()

const formVisible = ref(false)
/** 正在编辑的轮次；null = 新建 */
const editingRound = ref<SelectionRound | null>(null)

const scopeVisible = ref(false)
const scopeRound = ref<SelectionRound | null>(null)

const openCreate = () => {
  editingRound.value = null
  formVisible.value = true
}

const openEdit = (round: SelectionRound) => {
  editingRound.value = round
  formVisible.value = true
}

const openScopes = (round: SelectionRound) => {
  scopeRound.value = round
  scopeVisible.value = true
}

/** 开启 / 关闭选课：这是"学生能不能选"的总开关，故先确认再动 */
const handleToggleStatus = async (round: SelectionRound) => {
  if (!round.id) return
  const opening = round.status !== 1
  const action = opening ? '开启' : '关闭'
  try {
    await ElMessageBox.confirm(
      opening
        ? `确定开启「${round.roundName}」？开启后，落在选课时间窗内的学生就能选课了。`
        : `确定关闭「${round.roundName}」？关闭后学生只能查看课程，不能选也不能退。`,
      '提示',
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消：ElMessageBox 取消时会 reject，不接住就是未处理的 Promise 异常
  }
  const ok = await setRoundStatus(round.id, opening ? 1 : 0)
  if (ok) {
    ElMessage.success(opening ? '选课已开启' : '选课已关闭')
    await loadRounds()
  }
}

const handleRemove = async (round: SelectionRound) => {
  if (!round.id) return
  try {
    await ElMessageBox.confirm(
      `确定删除「${round.roundName}」？该轮次的适用范围也会一并删除。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return // 用户取消
  }
  const ok = await removeRound(round.id)
  if (ok) {
    ElMessage.success('已删除')
    await loadRounds()
  }
}

/** 范围改动后列表里的「适用范围」摘要要跟着变 */
const handleScopeChanged = () => {
  loadRounds()
}
</script>

<template>
  <div class="round-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">选课轮次管理</span>
          <el-button
            type="primary"
            @click="openCreate"
          >
            新建轮次
          </el-button>
        </div>
      </template>

      <div class="state-hint">
        状态由「开关」与「时间窗」共同决定，<strong>由前端按当前时间实时计算</strong>：
        <el-tag
          type="success"
          size="small"
          effect="plain"
        >
          开启中·可选
        </el-tag>
        = 开关开启且落在选课窗内；
        <el-tag
          type="warning"
          size="small"
          effect="plain"
        >
          补退选·只能退
        </el-tag>
        = 选课窗已过、补退选窗开放（学生只能退课）；
        <el-tag
          type="info"
          size="small"
          effect="plain"
        >
          已关闭·只能看
        </el-tag>
        = 开关未开启。鼠标悬浮状态标签可看到具体说明。
      </div>

      <SelectionRoundFilterBar
        v-model="query"
        @search="loadRounds"
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

      <SelectionRoundTable
        :rounds="rounds"
        :loading="loading"
        @edit="openEdit"
        @toggle-status="handleToggleStatus"
        @manage-scope="openScopes"
        @remove="handleRemove"
      />
    </el-card>

    <SelectionRoundForm
      v-model="formVisible"
      :round="editingRound"
      @success="loadRounds"
    />

    <ScopeManager
      v-model="scopeVisible"
      :round="scopeRound"
      @changed="handleScopeChanged"
    />
  </div>
</template>

<style scoped>
.round-page {
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
.state-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 14px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.8;
  color: #666;
  background: #f7f9fc;
  border-radius: 6px;
}
.inline-alert {
  margin-bottom: 12px;
}
</style>
