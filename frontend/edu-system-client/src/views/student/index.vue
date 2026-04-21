<template>
    <div class="crud-container">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="姓名"><el-input v-model="query.studentName" placeholder="学生姓名" /></el-form-item>
          <el-form-item label="学号"><el-input v-model="query.studentId" placeholder="学号" /></el-form-item>
          <el-form-item>
            <el-button type="primary" @click="getList">搜索</el-button>
            <el-button @click="resetQuery">重置</el-button>
            <el-button type="success" @click="handleAdd">新增</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="list" border class="crud-table">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="studentName" label="姓名" />
        <el-table-column prop="studentId" label="学号" />
        <el-table-column prop="gender" label="性别" />
        <el-table-column prop="phone" label="电话" />
        <el-table-column label="操作" width="160">
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

      <el-dialog v-model="dialogVisible" title="学生信息">
        <student-form ref="formRef" @success="getList" />
      </el-dialog>
    </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import StudentForm from './components/StudentForm.vue'

const pageNum = ref(1), pageSize = ref(10), total = ref(0), list = ref([])
const dialogVisible = ref(false), formRef = ref()
const query = reactive({ studentName: '', studentId: '' })

const getList = async () => {
  const res = await axios.get('/api/student', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list 
  total.value = res.data.total 
}

const resetQuery = () => { query.studentName = ''; query.studentId = ''; getList() }
const handleAdd = () => { dialogVisible.value = true; formRef.value?.reset() }
const handleEdit = (row) => { dialogVisible.value = true; formRef.value?.setData(row) }

const handleDelete = async (id) => {
  await ElMessageBox.confirm('确定删除？')
  await axios.delete(`/api/student/${id}`)
  ElMessage.success('删除成功')
  getList()
}

getList()
</script>

<style scoped>
.crud-container { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.search-box { background: #f9fbff; padding: 16px; border-radius: 8px; margin-bottom: 20px; }
.crud-table { margin-bottom: 20px; border-radius: 8px; overflow: hidden; }
.page-box { text-align: right; }
</style>