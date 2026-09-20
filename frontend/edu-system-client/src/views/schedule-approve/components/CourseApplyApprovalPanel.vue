<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCourseApplyAdmin } from '@/composables/useCourseApplyAdmin'
import {
  APPLY_STATUS_OPTIONS,
  applyStatusLabel,
  applyStatusTagType,
  slotWithWeekText,
} from '@/utils/schedule'
import type { CourseApply } from '@/types/models'

/**
 * 开课申请审批面板（管理员）。
 *
 * 单一职责：一个审批流的筛选 + 列表 + 通过/驳回。
 * 取数与审批副作用在 composables/useCourseApplyAdmin.ts，本组件只管交互与提示。
 *
 * 所有 `ElMessageBox.*` 与接口调用都显式接住异常：
 * 前者取消时会 reject，后者业务失败时会 reject（拦截器已弹提示）——
 * 不接住就会变成未处理的 Promise 异常。
 */
const { list, loading, loadError, status, term, load, approve, reject } = useCourseApplyAdmin()

/** 期望时间：申请里字段名带 expected* 前缀，需映射成通用的时间片段 */
function expectedText(row: CourseApply): string {
  if (!row.expectedWeekday) return '未填写'
  return slotWithWeekText({
    weekday: row.expectedWeekday,
    startPeriod: row.expectedStartPeriod,
    endPeriod: row.expectedEndPeriod,
    startWeek: row.expectedStartWeek,
    endWeek: row.expectedEndWeek,
  })
}

const handleApprove = async (row: CourseApply) => {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(
      `确定通过「${row.courseCode} ${row.courseName}」的开课申请？通过后将据此生成课程。`,
      '审批确认',
      { type: 'warning', confirmButtonText: '通过', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  try {
    const created = await approve(row.id)
    ElMessage.success(
      created?.createdCourseId
        ? `已通过，生成课程 ID：${created.createdCourseId}`
        : '已通过',
    )
  } catch {
    // 拦截器已提示失败原因，列表已刷新
  }
}

const handleReject = async (row: CourseApply) => {
  if (row.id === undefined) return
  let reason = ''
  try {
    const res = (await ElMessageBox.prompt('请填写驳回原因（必填，会展示给教师）', '驳回开课申请', {
      inputPlaceholder: '如：课程代码与培养计划不一致',
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
    await reject(row.id, reason)
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
        v-model="status"
        placeholder="状态"
        style="width: 140px"
        @change="load"
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
      <el-input
        v-model="term"
        placeholder="学期（如 2025-2026-1）"
        clearable
        style="width: 200px"
        @keyup.enter="load"
      />
      <el-button
        type="primary"
        :loading="loading"
        @click="load"
      >
        查询
      </el-button>
      <el-button @click="load">
        刷新
      </el-button>
      <span class="filter-hint">共 {{ list.length }} 条</span>
    </div>

    <el-alert
      v-if="loadError"
      :title="'加载失败：' + loadError"
      type="error"
      :closable="false"
      show-icon
      class="inline-alert"
    />

    <el-table
      v-loading="loading"
      :data="list"
      stripe
      empty-text="没有符合条件的开课申请"
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
        label="申请教师"
        width="100"
      />
      <el-table-column
        prop="term"
        label="学期"
        width="115"
      />
      <el-table-column
        label="学分/学时"
        width="100"
        align="center"
      >
        <template #default="{ row }">
          {{ row.credit }} / {{ row.classHour }}
        </template>
      </el-table-column>
      <el-table-column
        prop="maxStudent"
        label="容量"
        width="70"
        align="center"
      />
      <el-table-column
        label="期望时间"
        min-width="180"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ expectedText(row) }}
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
        label="审批结果"
        min-width="150"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span
            v-if="row.status === 'REJECTED'"
            class="reject-text"
          >{{ row.rejectReason || '—' }}</span>
          <span
            v-else-if="row.status === 'APPROVED'"
          >课程 #{{ row.createdCourseId ?? '—' }}（{{ row.createdCourseCode || '—' }}）</span>
          <span
            v-else
            class="muted-text"
          >—</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="140"
        align="center"
        fixed="right"
      >
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button
              type="success"
              link
              size="small"
              @click="handleApprove(row)"
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
.muted-text {
  color: #999;
}
</style>
