<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="班级名称" prop="clazzName">
      <el-input v-model="form.clazzName" placeholder="请输入班级名称" />
    </el-form-item>

    <el-form-item label="年级" prop="grade">
      <el-input v-model="form.grade" placeholder="例如：2023级" />
    </el-form-item>

    <el-form-item label="所属专业" prop="majorId">
      <el-select v-model="form.majorId" placeholder="请选择专业">
        <el-option
          v-for="item in majorList"
          :key="item.id"
          :label="item.majorName"
          :value="item.id"
        />
      </el-select>
    </el-form-item>
  </el-form>

  <div class="dialog-footer">
    <el-button @click="close">取消</el-button>
    <el-button type="primary" @click="submit">确定</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from '@/utils/request'
import { ElMessage } from 'element-plus'

interface Major {
  id: number
  majorName: string
}

interface Clazz {
  id: number
  clazzName: string
  grade: string
  majorId: number | string
}

const emit = defineEmits(['success', 'close'])
const formRef = ref()

const form = ref({
  id: 0,
  clazzName: '',
  grade: '',
  majorId: ''
})

const rules = ref({
  clazzName: [{ required: true, message: '班级名称不能为空', trigger: 'blur' }],
  grade: [{ required: true, message: '年级不能为空', trigger: 'blur' }],
  majorId: [{ required: true, message: '请选择专业', trigger: 'change' }]
})

const majorList = ref([] as Major[])
const getMajorList = async () => {
  const res = await axios.get('/api/major')
  majorList.value = res.data.list
}

const setData = (row: any) => {
  form.value = { ...row }
}

const reset = () => {
  form.value = { id: 0, clazzName: '', grade: '', majorId: '' }
}

const submit = async () => {
  await formRef.value.validate()
  if (form.value.id) {
    await axios.put('/api/clazz', form.value)
  } else {
    await axios.post('/api/clazz', form.value)
  }
  ElMessage.success('保存成功')
  emit('success')
  close()
}

const close = () => {
  formRef.value?.clearValidate()
  emit('close')
}

onMounted(() => {
  getMajorList()
})

defineExpose({ setData, reset })
</script>

<style scoped>
.dialog-footer {
  text-align: right;
  margin-top: 10px;
}
</style>