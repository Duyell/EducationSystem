<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import type { PlanCourse } from '@/types/models'

/**
 * 培养计划课程明细管理（抽屉）。
 *
 * 单一职责：维护「某个方案包含哪些课程」；方案本身的字段编辑见 TrainingPlanForm。
 * 数据流：props 进（方案 id 与名称）、事件出（明细变更后通知父级刷新课程数）。
 */
const props = defineProps<{
  modelValue: boolean
  planId: number | null
  planName: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const courses = ref<PlanCourse[]>([])
const loading = ref(false)
const adding = ref(false)

/** 新增明细表单 */
const draft = reactive({
  courseCode: '',
  courseName: '',
  category: 'REQUIRED' as PlanCourse['category'],
  suggestSemester: 1 as number | undefined,
  credit: 0,
})

const requiredCount = computed(() => courses.value.filter((c) => c.category === 'REQUIRED').length)
const electiveCount = computed(() => courses.value.filter((c) => c.category === 'ELECTIVE').length)

const totalCredit = computed(() =>
  courses.value.reduce((sum, c) => sum + Number(c.credit || 0), 0),
)

const creditByCategory = computed(() => ({
  required: courses.value
    .filter((c) => c.category === 'REQUIRED')
    .reduce((s, c) => s + Number(c.credit || 0), 0),
  elective: courses.value
    .filter((c) => c.category === 'ELECTIVE')
    .reduce((s, c) => s + Number(c.credit || 0), 0),
}))

watch(visible, (open) => {
  if (open) {
    loadCourses()
    resetDraft()
  }
})

const loadCourses = async () => {
  if (!props.planId) return
  loading.value = true
  try {
    const res = await axios.get(`/api/training-plan/${props.planId}`)
    courses.value = res.data?.courses ?? []
  } catch (e) {
    console.error('加载课程明细失败:', e)
  } finally {
    loading.value = false
  }
}

const resetDraft = () => {
  Object.assign(draft, {
    courseCode: '',
    courseName: '',
    category: 'REQUIRED',
    suggestSemester: 1,
    credit: 0,
  })
}

const addCourse = async () => {
  if (!props.planId) return
  if (!draft.courseCode.trim()) {
    ElMessage.warning('请填写课程代码')
    return
  }
  adding.value = true
  try {
    await axios.post('/api/training-plan/course', {
      planId: props.planId,
      courseCode: draft.courseCode.trim(),
      courseName: draft.courseName.trim() || draft.courseCode.trim(),
      category: draft.category,
      suggestSemester: draft.suggestSemester,
      credit: draft.credit,
    })
    ElMessage.success('已添加')
    resetDraft()
    await loadCourses()
    emit('changed')
  } finally {
    adding.value = false
  }
}

const removeCourse = async (row: PlanCourse) => {
  await ElMessageBox.confirm(`确定从方案中移除「${row.courseName}」？`, '提示', { type: 'warning' })
  await axios.delete(`/api/training-plan/course/${row.id}`)
  ElMessage.success('已移除')
  await loadCourses()
  emit('changed')
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="'课程明细 — ' + planName"
    size="880px"
  >
    <div class="course-manager">
      <el-card
        shadow="never"
        class="add-card"
      >
        <template #header>
          <span class="card-title">添加课程</span>
        </template>
        <div class="add-form">
          <el-input
            v-model="draft.courseCode"
            placeholder="课程代码（如 CS101）"
            style="width: 180px"
          />
          <el-input
            v-model="draft.courseName"
            placeholder="课程名称"
            style="width: 200px"
          />
          <el-select
            v-model="draft.category"
            style="width: 110px"
          >
            <el-option
              label="必修"
              value="REQUIRED"
            />
            <el-option
              label="选修"
              value="ELECTIVE"
            />
          </el-select>
          <el-input-number
            v-model="draft.credit"
            :min="0"
            :precision="1"
            :step="0.5"
            controls-position="right"
            style="width: 120px"
          />
          <el-input-number
            v-model="draft.suggestSemester"
            :min="1"
            :max="8"
            controls-position="right"
            style="width: 120px"
          />
          <el-button
            type="primary"
            :loading="adding"
            @click="addCourse"
          >
            添加
          </el-button>
        </div>
        <div class="add-hint">
          课程代码是培养计划与课程的唯一关联依据（同一门课多学期开课共用同一代码）；
          学分是快照，保存后不随课程学分变动。
        </div>
      </el-card>

      <div class="summary">
        共 {{ courses.length }} 门（必修 {{ requiredCount }} / 选修 {{ electiveCount }}）；
        学分合计 {{ totalCredit.toFixed(1) }}
        （必修 {{ creditByCategory.required.toFixed(1) }} / 选修 {{ creditByCategory.elective.toFixed(1) }}）
      </div>

      <el-table
        v-loading="loading"
        :data="courses"
        stripe
        empty-text="该方案还没有课程"
      >
        <el-table-column
          prop="courseCode"
          label="课程代码"
          width="120"
        />
        <el-table-column
          prop="courseName"
          label="课程名称"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column
          label="类别"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.category === 'REQUIRED' ? 'danger' : 'info'"
              size="small"
              effect="plain"
            >
              {{ row.category === 'REQUIRED' ? '必修' : '选修' }}
            </el-tag>
          </template>
        </el-table-column>
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
        />
        <el-table-column
          label="操作"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-button
              type="danger"
              link
              size="small"
              @click="removeCourse(row)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-drawer>
</template>

<style scoped>
.course-manager {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.add-card {
  border-radius: 8px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}
.add-form {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.add-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}
.summary {
  font-size: 13px;
  color: #666;
}
</style>
