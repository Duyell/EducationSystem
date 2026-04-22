<template>
    <div class="crud-container">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="课程名">
            <el-input v-model="query.courseName" placeholder="课程名称" />
          </el-form-item>
          <el-form-item label="授课教师">
            <el-input v-model="query.teacherName" placeholder="教师姓名" />
          </el-form-item>
          <el-form-item label="授课教师">
            <el-input v-model="query.teacherId" placeholder="教师工号" />
          </el-form-item>
          <el-form-item label="所属学院">
            <el-select v-model="query.collegeId" placeholder="请选择学院" clearable style="width: 160px" value-key="id">
              <el-option
                v-for="item in collegeList"
                :key="item.id"
                :label="item.collegeName"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="课程学分">
            <el-input v-model="query.credit" placeholder="学分" />
          </el-form-item>
          <el-form-item label="课程课时">
            <el-input v-model="query.classHour" placeholder="课时" />
          </el-form-item>
          <el-form-item label="授课人数">
            <el-input v-model="query.maxStudent" placeholder="最大人数" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="getList">搜索</el-button>
            <el-button @click="resetQuery">重置</el-button>
            <el-button type="success" @click="handleAdd">新增</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="list" border class="crud-table" stripe>
        <el-table-column prop="courseName" label="课程名" width="180" />
        <el-table-column prop="teacherName" label="授课教师姓名" width="120" />
        <el-table-column prop="teacherId" label="授课教师工号" width="120" />
        <el-table-column prop="collegeName" label="开课学院" min-width="150" />
        <el-table-column prop="term" label="学期" width="120" />
        <el-table-column prop="credit" label="学分" width="80" />
        <el-table-column prop="classHour" label="课时" width="80" />
        <el-table-column prop="maxStudent" label="最大人数" width="100" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
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

      <el-dialog v-model="dialogVisible" title="课程信息" width="650px">
        <course-form ref="formRef" @success="getList" @close="dialogVisible = false" />
      </el-dialog>
    </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, ElTableColumn } from 'element-plus'
import axios from '@/utils/request'
import CourseForm from './components/CourseForm.vue'

const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const list = ref([])

const dialogVisible = ref(false)
const formRef = ref()

const query = reactive({
  courseName: '',
  teacherId: '',
  collegeId: null,
  term: ''
})

const collegeList = ref([])

const getCollegeList = async () => {
  try {
    const res = await axios.get('/api/college')
    collegeList.value = res.data.list
  } catch (err) {
    console.error('加载学院失败')
  }
}

const getList = async () => {
  const res = await axios.get('/api/course', {
    params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value }
  })
  list.value = res.data.list
  total.value = res.data.total
}

const resetQuery = () => {
  query.courseName = ''
  query.teacherId = ''
  query.collegeId = null
  query.term = ''
  query.credit = null
  query.classHour = null
  query.maxStudent = null
  getList()
}

const handleAdd = () => {
  dialogVisible.value = true
  formRef.value?.reset()
}

const handleEdit = (row) => {
  dialogVisible.value = true
  formRef.value?.setData(row)
}

const handleDelete = async (id) => {
  await ElMessageBox.confirm('确定删除该课程？')
  await axios.delete(`/api/course/${id}`)
  ElMessage.success('删除成功')
  getList()
}

onMounted(() => {
  getCollegeList()
  getList()
})
</script>

<style scoped>
.crud-container {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}
.search-box {
  background: #f9fbff;
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 20px;
}
.crud-table {
  margin-bottom: 20px;
  border-radius: 8px;
  overflow: hidden;
}
.page-box {
  text-align: right;
}
</style>