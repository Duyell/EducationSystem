<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import { PERIOD_MAX, PERIOD_MIN, WEEKDAY_OPTIONS, WEEK_MAX, WEEK_MIN } from '@/utils/schedule'

/**
 * 新建开课申请对话框（教师）。
 *
 * 数据流：`v-model` 控制显隐（真正的双向契约，用 defineModel）；
 *        保存成功用事件通知父级刷新列表，自己不碰列表状态。
 *
 * 注意：`teacherId` 由后端从 token 强制填充，这里**不发送**（发了也会被覆盖）。
 * 期望时间整体可选：勾选后才提交，避免给后端塞一堆没意义的默认值。
 */
const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  (e: 'success'): void
}>()

const formRef = ref()
const saving = ref(false)

/** 是否填写期望时间 */
const withExpectedTime = ref(false)

/** 表单模型 */
const form = reactive({
  courseCode: '',
  courseName: '',
  term: '',
  credit: 3,
  classHour: 48,
  maxStudent: 60,
  expectedWeekday: 1,
  expectedStartPeriod: 1,
  expectedEndPeriod: 2,
  expectedStartWeek: 1,
  expectedEndWeek: 16,
})

const rules = {
  courseCode: [{ required: true, message: '课程代码不能为空', trigger: 'blur' }],
  courseName: [{ required: true, message: '课程名称不能为空', trigger: 'blur' }],
  term: [{ required: true, message: '学期不能为空', trigger: 'blur' }],
}

const resetForm = () => {
  Object.assign(form, {
    courseCode: '',
    courseName: '',
    term: '',
    credit: 3,
    classHour: 48,
    maxStudent: 60,
    expectedWeekday: 1,
    expectedStartPeriod: 1,
    expectedEndPeriod: 2,
    expectedStartWeek: 1,
    expectedEndWeek: 16,
  })
  withExpectedTime.value = false
  formRef.value?.clearValidate()
}

// 每次打开都从干净状态开始，避免上一次的输入残留
watch(visible, (open) => {
  if (open) resetForm()
})

const submit = async () => {
  // el-form 的 validate() 校验失败时是 **reject**（不是返回 false），
  // 不接住就会变成未处理的 Promise 异常；错误提示由 el-form 自己渲染。
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  if (withExpectedTime.value) {
    if (form.expectedEndPeriod < form.expectedStartPeriod) {
      ElMessage.warning('期望结束节次不能早于起始节次')
      return
    }
    if (form.expectedEndWeek < form.expectedStartWeek) {
      ElMessage.warning('期望结束周次不能早于起始周次')
      return
    }
  }

  saving.value = true
  try {
    await axios.post('/api/course-apply', {
      courseCode: form.courseCode.trim(),
      courseName: form.courseName.trim(),
      term: form.term.trim(),
      credit: form.credit,
      classHour: form.classHour,
      maxStudent: form.maxStudent,
      ...(withExpectedTime.value
        ? {
            expectedWeekday: form.expectedWeekday,
            expectedStartPeriod: form.expectedStartPeriod,
            expectedEndPeriod: form.expectedEndPeriod,
            expectedStartWeek: form.expectedStartWeek,
            expectedEndWeek: form.expectedEndWeek,
          }
        : {}),
    })
    ElMessage.success('开课申请已提交，等待管理员审批')
    visible.value = false
    emit('success')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="新建开课申请"
    width="640px"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="110px"
    >
      <el-form-item
        label="课程代码"
        prop="courseCode"
      >
        <el-input
          v-model="form.courseCode"
          placeholder="如 CS108（同一门课共用同一代码）"
        />
      </el-form-item>

      <el-form-item
        label="课程名称"
        prop="courseName"
      >
        <el-input
          v-model="form.courseName"
          placeholder="如 编译原理"
        />
      </el-form-item>

      <el-form-item
        label="开课学期"
        prop="term"
      >
        <el-input
          v-model="form.term"
          placeholder="如 2025-2026-1"
        />
      </el-form-item>

      <el-form-item label="学分">
        <el-input-number
          v-model="form.credit"
          :min="0"
          :max="20"
          :precision="1"
          :step="0.5"
          controls-position="right"
        />
      </el-form-item>

      <el-form-item label="学时">
        <el-input-number
          v-model="form.classHour"
          :min="0"
          :max="400"
          :step="8"
          controls-position="right"
        />
      </el-form-item>

      <el-form-item label="选课容量">
        <el-input-number
          v-model="form.maxStudent"
          :min="1"
          :max="500"
          :step="10"
          controls-position="right"
        />
        <span class="field-hint">同时作为教学班容量与排课时推荐教室的容量下限</span>
      </el-form-item>

      <el-divider content-position="left">
        期望上课时间（可选）
      </el-divider>

      <el-form-item label="填写期望">
        <el-switch
          v-model="withExpectedTime"
          active-text="填写"
          inactive-text="暂不填写"
        />
        <span class="field-hint">填写后审批通过时作为排课申请的默认值</span>
      </el-form-item>

      <template v-if="withExpectedTime">
        <el-form-item label="星期">
          <el-select
            v-model="form.expectedWeekday"
            style="width: 160px"
          >
            <el-option
              v-for="day in WEEKDAY_OPTIONS"
              :key="day.value"
              :label="day.label"
              :value="day.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="节次">
          <el-input-number
            v-model="form.expectedStartPeriod"
            :min="PERIOD_MIN"
            :max="PERIOD_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="range-sep">至</span>
          <el-input-number
            v-model="form.expectedEndPeriod"
            :min="PERIOD_MIN"
            :max="PERIOD_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="field-hint">一天 {{ PERIOD_MAX }} 节</span>
        </el-form-item>

        <el-form-item label="周次">
          <el-input-number
            v-model="form.expectedStartWeek"
            :min="WEEK_MIN"
            :max="WEEK_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="range-sep">至</span>
          <el-input-number
            v-model="form.expectedEndWeek"
            :min="WEEK_MIN"
            :max="WEEK_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="field-hint">第 {{ WEEK_MIN }}-{{ WEEK_MAX }} 周，课程可从中间周次开始</span>
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        提交申请
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #999;
  line-height: 1.5;
}
.range-sep {
  margin: 0 8px;
  color: #999;
}
</style>
