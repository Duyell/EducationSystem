<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import MySelectedCourses from './components/MySelectedCourses.vue'
import SelectableCourseTable from './components/SelectableCourseTable.vue'
import SelectionStatusBanner from './components/SelectionStatusBanner.vue'
import { useMyCourseSelection } from '@/composables/useMyCourseSelection'

/**
 * 选课（学生）。
 *
 * 本视图是**组合面**：学期输入 + 状态横幅 + 两张卡片（可选课程、我的已选）。
 * 取数在 composables/useMyCourseSelection.ts，渲染在 components/ 下。
 *
 * 三条用户明确要求的规则都体现在这里：
 * 1. 只有管理员**开启选课**后才选得了，否则只能看 → 横幅 + 按钮禁用；
 * 2. 选课期间可以退，否则要等**补退选**期间 → 退课按钮由服务端的 `canDrop` 控制；
 * 3. 能不能选某门课要过六道校验（轮次/范围/学分上限/已修过/时间冲突/容量）
 *    → 服务端把**第一条**拦住的理由放在 `reason` 里，页面直接显示。
 */
const {
  term,
  status,
  statusLoading,
  statusError,
  selectable,
  listLoading,
  listError,
  myCourses,
  myLoading,
  myError,
  myTotalCredits,
  maxCredits,
  overCreditCap,
  loadAll,
  selectCourse,
  dropCourse,
} = useMyCourseSelection()

/** 选课：成功后 composable 会同时刷新可选列表与已选列表 */
const handleSelect = async (courseId: number) => {
  const ok = await selectCourse(courseId)
  if (ok) ElMessage.success('选课成功')
}

/** 退课的破坏性更强（尤其在补退选期间是唯一可做的操作），故先确认 */
const handleDrop = async (courseId: number) => {
  try {
    await ElMessageBox.confirm(
      '确定退掉这门课？退课后名额会释放给其他同学。',
      '提示',
      { type: 'warning', confirmButtonText: '退课', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消：ElMessageBox 取消时会 reject，不接住就是未处理的 Promise 异常
  }
  const ok = await dropCourse(courseId)
  if (ok) ElMessage.success('退课成功')
}
</script>

<template>
  <div class="selection-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">选课</span>
          <div class="header-actions">
            <el-input
              v-model="term"
              placeholder="学期（如 2024-2025-1）"
              clearable
              style="width: 220px"
              @keyup.enter="loadAll"
            />
            <el-button
              type="primary"
              :loading="statusLoading || listLoading || myLoading"
              @click="loadAll"
            >
              查询
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="statusError"
        :title="'选课状态加载失败：' + statusError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <SelectionStatusBanner
        :status="status"
        :loading="statusLoading"
      />

      <el-alert
        v-if="listError"
        :title="'加载失败：' + listError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <SelectableCourseTable
        :rows="selectable"
        :status="status"
        :loading="listLoading"
        @select="handleSelect"
        @drop="handleDrop"
      />
    </el-card>

    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <span class="card-title">我的已选课程</span>
      </template>

      <el-alert
        v-if="myError"
        :title="'加载失败：' + myError"
        type="error"
        :closable="false"
        show-icon
        class="inline-alert"
      />

      <MySelectedCourses
        :courses="myCourses"
        :loading="myLoading"
        :total-credit="myTotalCredits"
        :max-credits="maxCredits"
        :over-cap="overCreditCap"
      />
    </el-card>
  </div>
</template>

<style scoped>
.selection-page {
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
.inline-alert {
  margin-bottom: 12px;
}
</style>
