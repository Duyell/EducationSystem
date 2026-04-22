<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="学号">
      <el-input v-model="form.studentId" disabled />
    </el-form-item>

    <el-form-item label="学生姓名">
      <el-input v-model="form.studentName" disabled />
    </el-form-item>

    <el-form-item label="课程名称">
      <el-input v-model="form.courseName" disabled />
    </el-form-item>

    <el-form-item label="平时成绩">
      <el-input v-model="form.usualScore" type="number" placeholder="0~100" />
    </el-form-item>

    <el-form-item label="考试成绩">
      <el-input v-model="form.examScore" type="number" placeholder="0~100" />
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

interface Score {
  id: number
  studentId: string
  studentName: string
  courseId: number
  courseName: string
  usualScore: number
  examScore: number
  totalScore: number
}

const emit = defineEmits(['success', 'close'])
const formRef = ref()

const form = ref({
  id: 0,
  studentId: '',
  studentName: '',
  courseId: 0,
  courseName: '',
  usualScore: 0,
  examScore: 0,
  totalScore: 0
})

const rules = ref({})

const setData = (row: any) => {
  form.value = { ...row }
}

const reset = () => {
  form.value = {
    id: 0,
    studentId: '',
    studentName: '',
    courseId: 0,
    courseName: '',
    usualScore: 0,
    examScore: 0,
    totalScore: 0
  }
}

const submit = async () => {
  await formRef.value.validate()
  await axios.put('/api/score', form.value)
  ElMessage.success('成绩保存成功')
  emit('success')
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