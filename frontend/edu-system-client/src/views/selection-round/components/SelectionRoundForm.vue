<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import type { SelectionRound, SelectionRoundForm } from '@/types/models'

/**
 * 轮次新建 / 编辑对话框。
 *
 * 数据流：`v-model` 控制显隐（真双向契约，用 defineModel）；
 * 待编辑轮次通过 props 进；保存成功用事件通知父级刷新列表。
 *
 * 写入由对话框自己发（同 P1/P2 既有表单的做法）：写入是它自己的职责，
 * 父级只关心"成功了，去刷新列表"。
 *
 * ⚠️ 编辑时**不提交 status**：后端只更新非空字段，带上 status 会让"改个名字"
 * 顺手把开关也改了。开关是独立动作（列表上的开启/关闭按钮）。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 传入表示编辑，null 表示新建 */
  round: SelectionRound | null
}>()

const emit = defineEmits<{
  (e: 'success'): void
}>()

const formRef = ref()
const saving = ref(false)

/** 空字符串转 undefined：清空 el-date-picker / el-input-number 得到的是 null 或 ''，
 *  直接发给后端会变成 `dropStart=` 这类空串，Spring 绑定 LocalDateTime 会失败。 */
const blankToUndefined = (v?: string | null): string | undefined =>
  v === null || v === undefined || v === '' ? undefined : v

const form = reactive({
  roundName: '',
  term: '',
  selectStart: '',
  selectEnd: '',
  dropStart: '',
  dropEnd: '',
  maxCredits: undefined as number | undefined,
})

const rules = {
  roundName: [{ required: true, message: '轮次名称不能为空', trigger: 'blur' }],
  term: [{ required: true, message: '学期不能为空', trigger: 'blur' }],
  selectStart: [{ required: true, message: '请选择选课开始时间', trigger: 'change' }],
  selectEnd: [{ required: true, message: '请选择选课结束时间', trigger: 'change' }],
}

const resetForm = () => {
  Object.assign(form, {
    roundName: '',
    term: '',
    selectStart: '',
    selectEnd: '',
    dropStart: '',
    dropEnd: '',
    maxCredits: undefined,
  })
  formRef.value?.clearValidate()
}

// 每次打开都从干净状态开始，编辑时再用待编辑行覆盖
watch(visible, (open) => {
  if (!open) return
  resetForm()
  if (props.round) {
    Object.assign(form, {
      roundName: props.round.roundName ?? '',
      term: props.round.term ?? '',
      selectStart: props.round.selectStart ?? '',
      selectEnd: props.round.selectEnd ?? '',
      dropStart: props.round.dropStart ?? '',
      dropEnd: props.round.dropEnd ?? '',
      maxCredits: props.round.maxCredits ?? undefined,
    })
  }
})

/** 时间窗顺序校验：el-date-picker 的 min/max 表达不了"结束不能早于开始"（两端都在变） */
const timeOrderError = () => {
  if (form.selectStart && form.selectEnd && form.selectEnd < form.selectStart) {
    return '选课结束时间不能早于开始时间'
  }
  if (form.dropStart && form.dropEnd && form.dropEnd < form.dropStart) {
    return '补退选结束时间不能早于开始时间'
  }
  return ''
}

const submit = async () => {
  // el-form 的 validate() 校验失败时是 **reject**（不是返回 false）。
  // 不接住就会变成未处理的 Promise 异常；错误提示由 el-form 自己渲染。
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  const orderError = timeOrderError()
  if (orderError) {
    ElMessage.warning(orderError)
    return
  }

  const payload: SelectionRoundForm = {
    roundName: form.roundName.trim(),
    term: form.term.trim(),
    selectStart: form.selectStart,
    selectEnd: form.selectEnd,
    dropStart: blankToUndefined(form.dropStart),
    dropEnd: blankToUndefined(form.dropEnd),
    maxCredits: form.maxCredits,
  }

  saving.value = true
  try {
    if (props.round?.id) {
      await axios.put('/api/selection-round', { ...payload, id: props.round.id })
      ElMessage.success('更新成功')
    } else {
      await axios.post('/api/selection-round', payload)
      ElMessage.success('新建成功，轮次默认处于「关闭」状态，需要手动开启')
    }
    visible.value = false
    emit('success')
  } catch {
    // 业务错误（HTTP 200 + code!=200）已由拦截器统一提示；
    // 不接住的话点击处理函数会抛出未处理的 Promise 异常
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
    :title="round?.id ? '编辑选课轮次' : '新建选课轮次'"
    width="640px"
    @closed="close"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
    >
      <el-form-item
        label="轮次名称"
        prop="roundName"
      >
        <el-input
          v-model="form.roundName"
          placeholder="如：2024-2025-1 第一轮选课"
        />
      </el-form-item>

      <el-form-item
        label="适用学期"
        prop="term"
      >
        <el-input
          v-model="form.term"
          placeholder="如 2024-2025-1"
        />
      </el-form-item>

      <el-form-item
        label="选课开始"
        prop="selectStart"
      >
        <el-date-picker
          v-model="form.selectStart"
          type="datetime"
          placeholder="选择开始时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item
        label="选课结束"
        prop="selectEnd"
      >
        <el-date-picker
          v-model="form.selectEnd"
          type="datetime"
          placeholder="选择结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="补退选开始">
        <el-date-picker
          v-model="form.dropStart"
          type="datetime"
          placeholder="可留空（本轮没有补退选）"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="补退选结束">
        <el-date-picker
          v-model="form.dropEnd"
          type="datetime"
          placeholder="可留空"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="学分上限">
        <el-input-number
          v-model="form.maxCredits"
          :min="0"
          :precision="1"
          :step="1"
          controls-position="right"
          placeholder="留空表示不限"
          style="width: 180px"
        />
        <span class="field-hint">留空 = 本轮不限制选课学分总量</span>
      </el-form-item>
    </el-form>

    <el-alert
      title="轮次新建后默认是「关闭」状态：学生只能浏览课程，需要点「开启选课」才会真正放开。"
      type="info"
      :closable="false"
      show-icon
      class="form-hint"
    />

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

<style scoped>
.form-hint {
  margin-top: 4px;
}
.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #999;
}
</style>
