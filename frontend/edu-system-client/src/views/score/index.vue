<template>
    <div class="crud-container">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="学号">
            <el-input v-model="query.studentId" placeholder="学生学号" />
          </el-form-item>
          <el-form-item label="课程">
            <el-select v-model="query.courseId" placeholder="课程" clearable style="width:180px">
              <el-option v-for="item in courseList" :key="item.id" :label="item.courseName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="学期">
            <el-input v-model="query.term" placeholder="2025-2026-1" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="getList">搜索</el-button>
            <el-button @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="list" border class="crud-table" stripe>
        <el-table-column prop="studentId" label="学号" width="120" />
        <el-table-column prop="studentName" label="姓名" width="100" />
        <el-table-column prop="courseName" label="课程名称" min-width="180" />
        <el-table-column prop="term" label="学期" width="120" />
        <el-table-column prop="usualScore" label="平时成绩" width="100" />
        <el-table-column prop="examScore" label="考试成绩" width="100" />
        <el-table-column prop="totalScore" label="总评成绩" width="100" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="handleEdit(row)">录入成绩</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        layout="total,prev,pager,next"
        @update:current-page="getList"
        class="page-box"
      />

      <el-dialog v-model="dialogVisible" title="成绩录入" width="550px">
        <score-form ref="formRef" @success="getList" @close="dialogVisible = false" />
      </el-dialog>
    </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import ScoreForm from './components/ScoreForm.vue'

const pageNum = ref(1), pageSize = ref(10), total = ref(0), list = ref([])
const dialogVisible = ref(false), formRef = ref()
const courseList = ref([])

const query = reactive({
  studentId: '',
  courseId: null,
  term: ''
})

const getCourseList = async () => {
  const res = await axios.get('/api/course')
  courseList.value = res.data.list
}

const getList = async () => {
  const res = await axios.get('/api/score', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list
  total.value = res.data.total
}

const resetQuery = () => {
  query.studentId = ''
  query.courseId = null
  query.term = ''
  getList()
}

const handleEdit = (row) => {
  dialogVisible.value = true
  formRef.value?.setData(row)
}

onMounted(() => { getCourseList(); getList() })
</script>

<style scoped>
.crud-container { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.search-box { background: #f9fbff; padding: 16px; border-radius: 8px; margin-bottom: 20px; }
.crud-table { margin-bottom: 20px; border-radius: 8px; overflow: hidden; }
.page-box { text-align: right; }
</style>