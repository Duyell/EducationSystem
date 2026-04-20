<template>
  <div class="crud-container">
    <div class="search-box">
      <el-form inline :model="query">
        <el-form-item label="教师姓名"><el-input v-model="query.teacherName" /></el-form-item>
        <el-form-item label="工号"><el-input v-model="query.teacherId" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList">搜索</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button type="success" @click="handleAdd">新增</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-table :data="list" border>
      <el-table-column prop="id" label="ID" />
      <el-table-column prop="teacherName" label="姓名" />
      <el-table-column prop="teacherId" label="工号" />
      <el-table-column prop="gender" label="性别" />
      <el-table-column prop="phone" label="电话" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="handleEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination 
      v-model:current-page="pageNum" 
      v-model:page-size="pageSize" 
      :total="total" layout="total, prev, pager, next" 
      @update:current-pagee="getList"
      @update:page-size="getList" 
    />

    <el-dialog v-model="dialogVisible" title="教师信息">
      <teacher-form ref="formRef" @success="getList" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import TeacherForm from './components/TeacherForm.vue'

const pageNum = ref(1), pageSize = ref(10), total = ref(0), list = ref([])
const dialogVisible = ref(false), formRef = ref()
const query = reactive({ teacherName: '', teacherId: '' })

const getList = async () => {
  const res = await axios.get('/api/teacher', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list
  total.value = res.data.total
}
const resetQuery = () => { query.teacherName = ''; query.teacherId = ''; getList() }
const handleAdd = () => { dialogVisible.value = true; formRef.value?.reset() }
const handleEdit = (row: any) => { dialogVisible.value = true; formRef.value?.setData(row) }
const handleDelete = async (id: any) => {
  await ElMessageBox.confirm('确定删除？')
  await axios.delete(`/api/teacher/${id}`)
  ElMessage.success('删除成功')
  getList()
}
getList()
</script>