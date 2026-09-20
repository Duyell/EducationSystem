<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ScheduleApproveDialog from './ScheduleApproveDialog.vue'
import { useScheduleApprove } from '@/composables/useScheduleApprove'
import {
  APPLY_STATUS_OPTIONS,
  applyStatusLabel,
  applyStatusTagType,
  hasConflictInfo,
  slotWithWeekText,
} from '@/utils/schedule'
import type { ClassTimeApply, Room } from '@/types/models'

/**
 * 排课申请审批面板（管理员）。
 *
 * 单一职责：筛选 + 列表 + 通过（可改派教室）/ 驳回。
 * 取数、审批、空闲教室查询都在 composables/useScheduleApprove.ts；
 * 本组件负责确认交互、把空闲教室作为 props 传给对话框，并处理失败时的停留逻辑。
 */
const {
  applies,
  appliesLoading,
  appliesError,
  applyStatus,
  loadApplies,
  approveApply,
  rejectApply,
  loadFreeRooms,
} = useScheduleApprove()

const dialogVisible = ref(false)
const approveTarget = ref<ClassTimeApply | null>(null)
const roomOptions = ref<Room[]>([])
const freeLoading = ref(false)
const freeMessage = ref('')
const submitting = ref(false)

// 只加载本面板需要的排课申请（课表总览由另一个面板自己拉）
onMounted(loadApplies)

/** 打开审批弹窗并顺带查一次该时段的空闲教室，便于管理员改派 */
const openApprove = async (row: ClassTimeApply) => {
  approveTarget.value = row
  dialogVisible.value = true
  roomOptions.value = []
  freeMessage.value = ''

  if (!row.term) {
    // /room/free 的 term 是必填参数，缺学期就没法查
    freeMessage.value = '该申请缺少学期信息，无法查询空闲教室'
    return
  }
  freeLoading.value = true
  try {
    const result = await loadFreeRooms({
      term: row.term,
      weekday: row.weekday,
      startPeriod: row.startPeriod,
      endPeriod: row.endPeriod,
      startWeek: row.startWeek,
      endWeek: row.endWeek,
      // 排课申请里没有选课容量字段，故不传 minCapacity（后端按可选处理）
    })
    roomOptions.value = result?.candidates ?? []
    freeMessage.value = result?.message ?? ''
  } catch {
    freeMessage.value = '空闲教室查询失败，可直接通过或先调整时间后重新申请'
  } finally {
    freeLoading.value = false
  }
}

/**
 * 确认通过。
 *
 * 后端在审批环节**硬阻断冲突**：失败时拦截器已弹出冲突详情，
 * 这里保持弹窗打开（管理员可改教室后重试），列表也已由 composable 刷新。
 */
const handleConfirm = async (roomId: number | undefined) => {
  const row = approveTarget.value
  if (row?.id === undefined) return
  submitting.value = true
  try {
    const updated = await approveApply(row.id, roomId)
    ElMessage.success(
      updated?.roomName ? `已通过并排入课表，教室：${updated.roomName}` : '已通过并排入课表',
    )
    dialogVisible.value = false
  } catch {
    // 冲突被阻断：保持弹窗，等管理员改教室或改时间
  } finally {
    submitting.value = false
  }
}

const handleReject = async (row: ClassTimeApply) => {
  if (row.id === undefined) return
  let reason = ''
  try {
    const res = (await ElMessageBox.prompt('请填写驳回原因（必填，会展示给教师）', '驳回排课申请', {
      inputPlaceholder: '如：该时段教师已有课，请调整时间',
      inputValidator: (value: string) => (value && value.trim() ? true : '驳回原因不能为空'),
      confirmButtonText: '驳回',
      cancelButtonText: '取消',
    })) as { value?: string }
    reason = String(res.value ?? '').trim()
  } catch {
    return // 用户取消
  }
  if (!reason) return
  try {
    await rejectApply(row.id, reason)
    ElMessage.success('已驳回')
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="panel">
    <div class="filter-bar">
      <el-select
        v-model="applyStatus"
        placeholder="状态"
        style="width: 140px"
        @change="loadApplies"
      >
        <el-option
          label="全部状态"
          value=""
        />
        <el-option
          v-for="item in APPLY_STATUS_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-button
        type="primary"
        :loading="appliesLoading"
        @click="loadApplies"
      >
        查询
      </el-button>
      <el-button @click="loadApplies">
        刷新
      </el-button>
      <span class="filter-hint">共 {{ applies.length }} 条</span>
    </div>

    <el-alert
      v-if="appliesError"
      :title="'加载失败：' + appliesError"
      type="error"
      :closable="false"
      show-icon
      class="inline-alert"
    />

    <el-alert
      title="审批会先做冲突检测：教师时间冲突或教室占用冲突都会被阻断，课表不会生成。可改派教室后重试。"
      type="info"
      :closable="false"
      show-icon
      class="inline-alert"
    />

    <el-table
      v-loading="appliesLoading"
      :data="applies"
      stripe
      empty-text="没有符合条件的排课申请"
    >
      <el-table-column
        prop="courseCode"
        label="课程代码"
        width="110"
      />
      <el-table-column
        prop="courseName"
        label="课程名称"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="teacherName"
        label="教师"
        width="100"
      />
      <el-table-column
        label="上课时间"
        min-width="185"
      >
        <template #default="{ row }">
          {{ slotWithWeekText(row) }}
        </template>
      </el-table-column>
      <el-table-column
        label="申请教室"
        width="120"
      >
        <template #default="{ row }">
          {{ row.roomName || '未指定' }}
        </template>
      </el-table-column>
      <el-table-column
        label="冲突情况"
        width="100"
        align="center"
      >
        <template #default="{ row }">
          <el-tag
            v-if="hasConflictInfo(row.conflictInfo)"
            type="danger"
            size="small"
            effect="dark"
          >
            有冲突
          </el-tag>
          <span
            v-else
            class="ok-text"
          >无冲突</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
        align="center"
      >
        <template #default="{ row }">
          <el-tag
            :type="applyStatusTagType(row.status)"
            size="small"
            effect="plain"
          >
            {{ applyStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="审批信息"
        min-width="150"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span
            v-if="row.status === 'REJECTED'"
            class="reject-text"
          >{{ row.rejectReason || '—' }}</span>
          <span
            v-else-if="hasConflictInfo(row.conflictInfo)"
            class="reject-text"
          >{{ row.conflictInfo }}</span>
          <span
            v-else
            class="muted-text"
          >{{ row.status === 'APPROVED' ? `审批人：${row.reviewer || '—'}` : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="130"
        align="center"
        fixed="right"
      >
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button
              type="success"
              link
              size="small"
              @click="openApprove(row)"
            >
              通过
            </el-button>
            <el-button
              type="danger"
              link
              size="small"
              @click="handleReject(row)"
            >
              驳回
            </el-button>
          </template>
          <span
            v-else
            class="muted-text"
          >已处理</span>
        </template>
      </el-table-column>
    </el-table>

    <ScheduleApproveDialog
      v-model="dialogVisible"
      :apply="approveTarget"
      :room-options="roomOptions"
      :free-loading="freeLoading"
      :free-message="freeMessage"
      :submitting="submitting"
      @confirm="handleConfirm"
    />
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.filter-hint {
  font-size: 12px;
  color: #999;
}
.inline-alert {
  margin-bottom: 12px;
}
.reject-text {
  color: #f56c6c;
}
.ok-text {
  color: #67c23a;
}
.muted-text {
  color: #999;
}
</style>
