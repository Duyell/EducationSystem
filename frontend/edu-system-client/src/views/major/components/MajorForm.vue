<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="专业名称" prop="majorName">
      <el-input v-model="form.majorName" placeholder="请输入专业名称" />
    </el-form-item>

    <el-form-item label="所属学院" prop="collegeId">
      <el-select v-model="form.collegeId" placeholder="请选择学院">
        <el-option
          v-for="item in collegeList"
          :key="item.id"
          :label="item.collegeName"
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

interface College {
  id: number
  collegeName: string
}

interface Major {
  id: number
  majorName: string
  collegeId: number | string
}

const emit = defineEmits(['success', 'close'])
const formRef = ref()

const form = ref({
  id: 0,
  majorName: '',
  collegeId: ''
})

const rules = ref({
  majorName: [{ required: true, message: '专业名称不能为空', trigger: 'blur' }],
  collegeId: [{ required: true, message: '请选择学院', trigger: 'change' }]
})

const collegeList = ref([] as College[])
const getCollegeList = async () => {
  const res = await axios.get('/api/college')
  collegeList.value = res.data.list
}

const setData = (row: any) => {
  form.value = { ...row }
}

const reset = () => {
  form.value = { id: 0, majorName: '', collegeId: '' }
}

const submit = async () => {
  await formRef.value.validate()
  if (form.value.id) {
    await axios.put('/api/major', form.value)
  } else {
    await axios.post('/api/major', form.value)
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
  getCollegeList()
})

defineExpose({ setData, reset })
</script>

<style scoped>
.dialog-footer {
  text-align: right;
  margin-top: 10px;
}
</style>