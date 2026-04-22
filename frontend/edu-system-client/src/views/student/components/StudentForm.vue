<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="学生姓名" prop="studentName">
      <el-input v-model="form.studentName" placeholder="请输入学生姓名" />
    </el-form-item>

    <el-form-item label="学号" prop="studentId">
      <el-input v-model="form.studentId" placeholder="请输入学号" />
    </el-form-item>

    <el-form-item label="性别">
      <el-radio-group v-model="form.gender">
        <el-radio value="男">男</el-radio>
        <el-radio value="女">女</el-radio>
      </el-radio-group>
    </el-form-item>

    <el-form-item label="手机号">
      <el-input v-model="form.phone" placeholder="请输入手机号" />
    </el-form-item>

    <el-form-item label="邮箱">
      <el-input v-model="form.email" placeholder="请输入邮箱" />
    </el-form-item>

    <el-form-item label="出生日期">
      <el-date-picker v-model="form.birthday" type="date" placeholder="选择日期" />
    </el-form-item>

    <el-form-item label="所属班级" prop="clazzId">
      <el-select v-model="form.clazzId" placeholder="请选择班级">
        <el-option
          v-for="item in clazzList"
          :key="item.id"
          :label="item.clazzName"
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

const emit = defineEmits(['success'])
const formRef = ref()

interface Clazz {
  id: number
  clazzName: string
  majorId: number
  grade: number
}

interface Student {
  id: number
  studentName: string
  studentId: string
  gender: string
  phone: string
  email: string
  birthday: Date | null
  clazzId: string
}

// 表单数据
const form = ref<Student>({
  id: 0,
  studentName: '',
  studentId: '',
  gender: '男',
  phone: '',
  email: '',
  birthday: null,
  clazzId: ''
})

// 校验规则
const rules = ref({
  studentName: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
  studentId: [{ required: true, message: '学号不能为空', trigger: 'blur' }],
  clazzId: [{ required: true, message: '请选择班级', trigger: 'change' }]
})

// 班级列表
const clazzList = ref<Clazz[]>([])
const getClazzList = async () => {
  const res = await axios.get('/api/clazz')
  clazzList.value = res.data.list
}

// 赋值（编辑）
const setData = (row: { id: number; studentName: string; studentId: string; gender: string; phone: string; email: string; birthday: Date | null; clazzId: string } | { id: number; studentName: string; studentId: string; gender: string; phone: string; email: string; birthday: Date|null; clazzId: string }) => {
  form.value = { ...row }
}   

// 重置（新增）
const reset = () => {
  form.value = {
    id: 0,
    studentName: '',
    studentId: '',
    gender: '男',
    phone: '',
    email: '',
    birthday: null,
    clazzId: ''
  }
}

// 提交
const submit = async () => {
  await formRef.value.validate()
  if (form.value.id) {
    await axios.put('/api/student', form.value)
  } else {
    await axios.post('/api/student', form.value)
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

onMounted(() => {
  getClazzList()
})

defineExpose({ setData, reset })
</script>

<style scoped>
.dialog-footer {
  text-align: right;
  margin-top: 10px;
}
</style>