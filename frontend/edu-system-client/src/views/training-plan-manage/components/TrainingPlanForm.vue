<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import type { Major, TrainingPlan } from '@/types/models'

/**
 * 培养计划新建/编辑对话框。
 *
 * 单一职责：只处理方案本身（不含课程明细，明细见 PlanCourseManager）。
 * 数据流：`v-model` 控制显隐（真正的双向契约，用 defineModel）；
 *        待编辑行通过 props 进；保存成功用事件通知父级刷新列表。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 传入表示编辑，null 表示新建 */
  row: TrainingPlan | null
}>()

const emit = defineEmits<{
  (e: 'success'): void
}>()

const formRef = ref()
const majorList = ref<Major[]>([])
const saving = ref(false)

/** 表单模型。majorId 用联合类型：el-select 未选择时为 '' */
const form = reactive({
  id: undefined as number | undefined,
  planName: '',
  majorId: '' as number | string,
  grade: '',
  totalCredits: 0,
  requiredCredits: 0,
  electiveCredits: 0,
  status: 1,
  remark: '',
})

const rules = {
  planName: [{ required: true, message: '方案名称不能为空', trigger: 'blur' }],
  majorId: [{ required: true, message: '请选择专业', trigger: 'change' }],
  grade: [{ required: true, message: '适用年级不能为空', trigger: 'blur' }],
}

/** 打开时重置并用待编辑行填充（编辑时覆盖新建默认值） */
watch(visible, (open) => {
  if (!open) return
  resetForm()
  if (props.row) {
    Object.assign(form, {
      id: props.row.id,
      planName: props.row.planName,
      majorId: props.row.majorId,
      grade: props.row.grade,
      totalCredits: Number(props.row.totalCredits),
      requiredCredits: Number(props.row.requiredCredits),
      electiveCredits: Number(props.row.electiveCredits),
      status: props.row.status,
      remark: props.row.remark ?? '',
    })
  }
  loadMajors()
})

const loadMajors = async () => {
  try {
    const res = await axios.get('/api/major', { params: { pageNum: 1, pageSize: 200 } })
    majorList.value = res.data.list ?? []
  } catch (e) {
    console.error('加载专业列表失败:', e)
  }
}

const resetForm = () => {
  Object.assign(form, {
    id: undefined,
    planName: '',
    majorId: '',
    grade: '',
    totalCredits: 0,
    requiredCredits: 0,
    electiveCredits: 0,
    status: 1,
    remark: '',
  })
  formRef.value?.clearValidate()
}

const submit = async () => {
  // el-form 的 validate() 校验失败时会 **reject**（不是返回 false）。
  // 不接住就会变成未处理的 Promise 异常（浏览器报 unhandled rejection）。
  // 错误提示由 el-form 自行渲染，这里只需中止提交。
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await axios.put('/api/training-plan', form)
      ElMessage.success('更新成功')
    } else {
      await axios.post('/api/training-plan', form)
      ElMessage.success('创建成功')
    }
    visible.value = false
    emit('success')
  } finally {
    saving.value = false
  }
}

const close = () => {
  formRef.value?.clearValidate()
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="form.id ? '编辑培养计划' : '新建培养计划'"
    width="620px"
    @closed="close"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="110px"
    >
      <el-form-item
        label="方案名称"
        prop="planName"
      >
        <el-input
          v-model="form.planName"
          placeholder="如：计算机科学与技术 2023 级培养计划"
        />
      </el-form-item>

      <el-form-item
        label="适用专业"
        prop="majorId"
      >
        <el-select
          v-model="form.majorId"
          placeholder="请选择专业"
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="m in majorList"
            :key="m.id"
            :label="m.majorName"
            :value="m.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item
        label="适用年级"
        prop="grade"
      >
        <el-input
          v-model="form.grade"
          placeholder="如 2023（决定版本，老生沿用入学年级方案）"
        />
      </el-form-item>

      <el-form-item label="总学分要求">
        <el-input-number
          v-model="form.totalCredits"
          :min="0"
          :precision="1"
          :step="0.5"
        />
      </el-form-item>

      <el-form-item label="必修学分要求">
        <el-input-number
          v-model="form.requiredCredits"
          :min="0"
          :precision="1"
          :step="0.5"
        />
      </el-form-item>

      <el-form-item label="选修学分要求">
        <el-input-number
          v-model="form.electiveCredits"
          :min="0"
          :precision="1"
          :step="0.5"
        />
      </el-form-item>

      <el-form-item label="状态">
        <el-switch
          v-model="form.status"
          :active-value="1"
          :inactive-value="0"
          active-text="启用"
          inactive-text="停用"
        />
      </el-form-item>

      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          placeholder="可选"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>
