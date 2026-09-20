<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import axios from '@/utils/request'
import PlanCourseManager from './components/PlanCourseManager.vue'
import TrainingPlanForm from './components/TrainingPlanForm.vue'
import type { TrainingPlan } from '@/types/models'

/**
 * 培养计划维护（管理员）。
 *
 * 组合面：列表 + 两个子组件（方案表单、课程明细抽屉）。
 * 列表的取数逻辑直接写在这里——只有一个消费者，抽 composable 反而增加跳转成本。
 */
const list = ref<TrainingPlan[]>([])
const loading = ref(false)
const majorList = ref<{ id: number; majorName: string }[]>([])

/** 筛选条件 */
const query = reactive({ majorId: undefined as number | undefined, grade: '' })

/** 方案表单对话框 */
const formVisible = ref(false)
const editingRow = ref<TrainingPlan | null>(null)

/** 课程明细抽屉 */
const drawerVisible = ref(false)
const activePlanId = ref<number | null>(null)
const activePlanName = ref('')

const getList = async () => {
  loading.value = true
  try {
    const res = await axios.get('/api/training-plan', {
      params: {
        majorId: query.majorId,
        grade: query.grade || undefined,
      },
    })
    list.value = res.data ?? []
  } catch (e) {
    console.error('加载培养计划失败:', e)
  } finally {
    loading.value = false
  }
}

const getMajorList = async () => {
  try {
    // /major 默认 pageSize=10，下拉框需要全量，显式放大
    const res = await axios.get('/api/major', { params: { pageNum: 1, pageSize: 200 } })
    majorList.value = res.data.list ?? []
  } catch (e) {
    console.error('加载专业列表失败:', e)
  }
}

const resetQuery = () => {
  query.majorId = undefined
  query.grade = ''
  getList()
}

const handleCreate = () => {
  editingRow.value = null
  formVisible.value = true
}

const handleEdit = (row: TrainingPlan) => {
  editingRow.value = row
  formVisible.value = true
}

const handleManageCourses = (row: TrainingPlan) => {
  activePlanId.value = row.id ?? null
  activePlanName.value = row.planName
  drawerVisible.value = true
}

onMounted(() => {
  getList()
  getMajorList()
})
</script>

<template>
  <div class="manage-page">
    <el-card
      shadow="never"
      class="page-card"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">培养计划维护</span>
          <el-button
            type="primary"
            @click="handleCreate"
          >
            新建培养计划
          </el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-select
          v-model="query.majorId"
          placeholder="专业"
          clearable
          filterable
          style="width: 200px"
        >
          <el-option
            v-for="m in majorList"
            :key="m.id"
            :label="m.majorName"
            :value="m.id"
          />
        </el-select>
        <el-input
          v-model="query.grade"
          placeholder="年级（如 2023）"
          clearable
          style="width: 160px"
          @keyup.enter="getList"
        />
        <el-button
          type="primary"
          :loading="loading"
          @click="getList"
        >
          查询
        </el-button>
        <el-button @click="resetQuery">
          重置
        </el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        stripe
        empty-text="暂无培养计划"
      >
        <el-table-column
          prop="planName"
          label="方案名称"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column
          prop="majorName"
          label="专业"
          min-width="150"
        />
        <el-table-column
          prop="grade"
          label="年级"
          width="90"
          align="center"
        />
        <el-table-column
          prop="totalCredits"
          label="总学分"
          width="90"
          align="center"
        />
        <el-table-column
          prop="requiredCredits"
          label="必修学分"
          width="100"
          align="center"
        />
        <el-table-column
          prop="electiveCredits"
          label="选修学分"
          width="100"
          align="center"
        />
        <el-table-column
          label="状态"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.status === 1 ? 'success' : 'info'"
              size="small"
              effect="plain"
            >
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="180"
          align="center"
        >
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              size="small"
              @click="handleEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="handleManageCourses(row)"
            >
              课程明细
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <TrainingPlanForm
      v-model="formVisible"
      :row="editingRow"
      @success="getList"
    />

    <PlanCourseManager
      v-model="drawerVisible"
      :plan-id="activePlanId"
      :plan-name="activePlanName"
      @changed="getList"
    />
  </div>
</template>

<style scoped>
.manage-page {
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
.filter-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
</style>
