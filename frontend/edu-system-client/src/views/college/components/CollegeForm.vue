<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="学院名称" prop="collegeName">
      <el-input v-model="form.collegeName" placeholder="请输入学院名称" />
    </el-form-item>
  </el-form>

  <div class="dialog-footer">
    <el-button @click="close">取消</el-button>
    <el-button type="primary" @click="submit">确定</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import axios from '@/utils/request'
import { ElMessage } from 'element-plus'

interface College {
  id: number
  collegeName: string
}

const emit = defineEmits(['success', 'close'])
const formRef = ref()

const form = ref({
  id: 0,
  collegeName: ''
})

const rules = ref({
  collegeName: [{ required: true, message: '学院名称不能为空', trigger: 'blur' }]
})

const setData = (row: any) => {
  form.value = { ...row }
}

const reset = () => {
  form.value = { id: 0, collegeName: '' }
}

const submit = async () => {
  await formRef.value.validate()
  if (form.value.id) {
    await axios.put('/api/college', form.value)
  } else {
    await axios.post('/api/college', form.value)
  }
  ElMessage.success('保存成功')
  emit('success')
  emit('close')
  close()
}

const close = () => {
  formRef.value?.clearValidate()
  emit('close')
}

defineExpose({ setData, reset })
</script>

<style scoped>
.dialog-footer {
  text-align: right;
  margin-top: 10px;
}
</style>