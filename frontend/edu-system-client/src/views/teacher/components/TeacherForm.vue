<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="教师姓名" prop="teacherName">
      <el-input v-model="form.teacherName" placeholder="请输入教师姓名" />
    </el-form-item>

    <el-form-item label="工号" prop="teacherId">
      <el-input v-model="form.teacherId" placeholder="请输入工号" />
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

    <el-form-item label="职称">
      <el-input v-model="form.title" placeholder="讲师/副教授/教授" />
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

interface Teacher {
  id: number
  teacherName: string
  teacherId: string
  gender: string
  phone: string
  email: string
  birthday: Date | null
  collegeId: number | string
  title: string
}

const emit = defineEmits(['success'])
const formRef = ref()

// 表单数据
const form = ref<Teacher>({
  id: 0,
  teacherName: '',
  teacherId: '',
  gender: '男',
  phone: '',
  email: '',
  birthday: null,
  collegeId: '',
  title: ''
})

// 校验规则
const rules = ref({
  teacherName: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
  teacherId: [{ required: true, message: '工号不能为空', trigger: 'blur' }],
  collegeId: [{ required: true, message: '请选择学院', trigger: 'change' }]
})

// 学院列表
const collegeList = ref([] as College[])
const getCollegeList = async () => {
  const res = await axios.get('/api/college')
  collegeList.value = res.data.list
}

// 赋值（编辑时用）
const setData = (row: { id: number; teacherName: string; teacherId: string; gender: string; phone: string; email: string; birthday: Date | null; collegeId: string; title: string } | { id: number; teacherName: string; teacherId: string; gender: string; phone: string; email: string; birthday: Date|null; collegeId: string; title: string }) => {
  form.value = { ...row }
}

// 重置（新增时用）
const reset = () => {
  form.value = {
    id: 0,
    teacherName: '',
    teacherId: '',
    gender: '男',
    phone: '',
    email: '',
    birthday: null,
    collegeId: '',
    title: ''
  }
}

// 提交
const submit = async () => {
  await formRef.value.validate()
  if (form.value.id) {
    await axios.put('/api/teacher', form.value)
  } else {
    await axios.post('/api/teacher', form.value)
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