<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
    <el-form-item label="课程名称" prop="courseName">
      <el-input v-model="form.courseName" placeholder="请输入课程名称" />
    </el-form-item>

    <el-form-item label="授课教师" prop="teacherId">
      <el-select v-model="form.teacherId" placeholder="请选择授课教师" filterable clearable style="width:100%">
        <el-option
          v-for="item in teacherList"
          :key="item.teacherId"
          :label="`${item.teacherName}（${item.teacherId}）${item.collegeName ? ' - '+item.collegeName : ''}`"
          :value="item.teacherId"
        />
      </el-select>
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

    <el-form-item label="学期" prop="term">
      <el-input v-model="form.term" placeholder="例如：2025-2026-1" />
    </el-form-item>

    <el-form-item label="学分">
      <el-input v-model="form.credit" placeholder="请输入学分" type="number" />
    </el-form-item>

    <el-form-item label="课时">
      <el-input v-model="form.classHour" placeholder="请输入课时" type="number" />
    </el-form-item>

    <el-form-item label="最大人数">
      <el-input v-model="form.maxStudent" placeholder="最大选课人数" type="number" />
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

interface Teacher {
  id: number
  teacherName: string
  teacherId: string
  collegeName: string
}

interface College {
  id: number
  collegeName: string
}

const emit = defineEmits(['success', 'close'])
const formRef = ref()

const form = ref({
  id: 0,
  courseName: '',
  teacherId: '',
  collegeId: '',
  term: '',
  credit: 0,
  classHour: 0,
  maxStudent: 0
})

const rules = ref({
  courseName: [{ required: true, message: '课程名称不能为空', trigger: 'blur' }],
  teacherId: [{ required: true, message: '请选择授课教师', trigger: 'change' }],
  collegeId: [{ required: true, message: '请选择学院', trigger: 'change' }],
  term: [{ required: true, message: '学期不能为空', trigger: 'blur' }]
})

const teacherList = ref([] as Teacher[])
const collegeList = ref([] as College[])

const getTeacherList = async () => {
  try {
    const res = await axios.get('/api/teacher/all')
    teacherList.value = res.data || []
  } catch {}
}

const getCollegeList = async () => {
  const res = await axios.get('/api/college')
  collegeList.value = res.data.list
}

const setData = (row: any) => {
  form.value = { ...row }
}

const reset = () => {
  form.value = {
    id: 0,
    courseName: '',
    teacherId: '',
    collegeId: '',
    term: '',
    credit: 0,
    classHour: 0,
    maxStudent: 0
  }
}

const submit = async () => {
  await formRef.value.validate()
  if (form.value.id) {
    await axios.put('/api/course', form.value)
  } else {
    await axios.post('/api/course', form.value)
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
  getTeacherList()
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
