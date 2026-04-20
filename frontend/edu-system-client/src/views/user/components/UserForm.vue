<template>
  <el-form :model="form" :rules="rules" ref="formRef">
    <el-form-item label="用户名" prop="username">
      <el-input v-model="form.username" />
    </el-form-item>
    <el-form-item label="密码" prop="password">
      <el-input v-model="form.password" type="password" />
    </el-form-item>
    <el-form-item label="角色" prop="role">
      <el-select v-model="form.role">
        <el-option label="管理员" value="admin" />
        <el-option label="教师" value="teacher" />
        <el-option label="学生" value="student" />
      </el-select>
    </el-form-item>
    <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
    <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
  </el-form>
  <div align="right">
    <el-button @click="emit('update:modelValue', false)">取消</el-button>
    <el-button type="primary" @click="submit">确定</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import axios from '@/utils/request'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['success', 'update:modelValue'])
const formRef = ref()
const form = ref({ id: 0, username: '', password: '', role: 'student', email: '', phone: '' })

const rules = { username: [{ required: true }] }

const setData = (row: { id: number; username: string; password: string; role: string; email: string; phone: string } | { id: number; username: string; password: string; role: string; email: string; phone: string }) => (form.value = { ...row })
const reset = () => (form.value = { id: 0, username: '', password: '', role: 'student', email: '', phone: '' })

const submit = async () => {
  await formRef.value.validate()
  form.value.id ? await axios.put('/api/user', form.value) : await axios.post('/api/user', form.value)
  emit('success')
}
</script>