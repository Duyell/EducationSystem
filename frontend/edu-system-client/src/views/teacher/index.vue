<template>
    <div class="crud-container">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="姓名">
            <el-input v-model="query.teacherName" placeholder="教师姓名" />
          </el-form-item>
          <el-form-item label="工号">
            <el-input v-model="query.teacherId" placeholder="工号" />
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
          <el-form-item label="职称">
            <el-select v-model="query.title" placeholder="请选择职称" clearable style="width: 140px">
              <el-option label="讲师" value="讲师" />
              <el-option label="副教授" value="副教授" />
              <el-option label="教授" value="教授" />
              <el-option label="助教" value="助教" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="getList">搜索</el-button>
            <el-button @click="resetQuery">重置</el-button>
            <el-button type="success" @click="handleAdd">新增</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="list" border class="crud-table" stripe>
        <el-table-column prop="teacherName" label="姓名" width="90" />
        <el-table-column prop="teacherId" label="工号" width="110" />
        <el-table-column prop="gender" label="性别" width="70" />
        <el-table-column prop="birthday" label="生日" width="120" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column prop="collegeName" label="所属学院" min-width="150" />
        <el-table-column prop="title" label="职称" width="100" />
        <el-table-column prop="createTime" label="创建时间" min-width="170" />
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

      <el-dialog v-model="dialogVisible" title="教师信息" width="650px">
        <teacher-form ref="formRef" @success="getList" @close="dialogVisible = false" />
      </el-dialog>
    </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import TeacherForm from './components/TeacherForm.vue'

const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const list = ref([])

const dialogVisible = ref(false)
const formRef = ref()

// 搜索条件
const query = reactive({
  teacherName: '',
  teacherId: '',
  collegeId: null,
  title: ''
})

// 学院下拉数据
const collegeList = ref([])

// 获取学院列表
const getCollegeList = async () => {
  try {
    const res = await axios.get('/api/college')
    collegeList.value = res.data.list
  } catch (err) {
    console.error('加载学院下拉失败：', err)
  }
}

// 教师列表
const getList = async () => {
  const res = await axios.get('/api/teacher', {
    params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value }
  })
  list.value = res.data.list
  total.value = res.data.total
}

// 重置搜索
const resetQuery = () => {
  query.teacherName = ''
  query.teacherId = ''
  query.collegeId = null
  query.title = ''
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
  await ElMessageBox.confirm('确定删除该教师信息？')
  await axios.delete(`/api/teacher/${id}`)
  ElMessage.success('删除成功')
  getList()
}

// 页面初始化
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