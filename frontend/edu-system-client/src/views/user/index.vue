<template>
  <div class="crud-container">
    <div class="search-box">
      <el-form inline :model="query">
        <el-form-item label="用户名">
          <el-input v-model="query.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="query.role" placeholder="请选择角色">
            <el-option label="管理员" value="admin" />
            <el-option label="教师" value="teacher" />
            <el-option label="学生" value="student" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList">搜索</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button type="success" @click="handleAdd">新增</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-table :data="list" border>
      <el-table-column prop="id" label="ID" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="role" label="角色" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column prop="phone" label="手机号" />
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-switch
            v-model="row.status"
            @change="handleStatusChange(row)"
            active-text="启用"
            inactive-text="禁用"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
          <el-button type="danger" size="small" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="pageNum"
      v-model:page-size="pageSize"
      :total="total"
      layout="total, prev, pager, next, jumper"
      @update:page-size="getList"
      @update:current-page="getList"
    />

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" title="用户信息">
      <user-form ref="formRef" @success="getList" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import UserForm from './components/UserForm.vue'



const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const list = ref([])
const dialogVisible = ref(false)
const formRef = ref()

const query = reactive({
  username: '',
  role: ''
})

const getList = async () => {
  const res = await axios.get('/api/user', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list
  total.value = res.data.total
}

const resetQuery = () => {
  query.username = ''
  query.role = ''
  getList()
}

const handleAdd = () => {
  dialogVisible.value = true
  formRef.value?.reset()
}

const handleEdit = (row: any) => {
  dialogVisible.value = true
  formRef.value?.setData(row)
}

const handleDelete = async (id: any) => {
  await ElMessageBox.confirm('确定删除？')
  await axios.delete(`/api/user/${id}`)
  ElMessage.success('删除成功')
  getList()
}

const handleStatusChange = async (row: { id: any; status: any }) => {
  await axios.post(`/api/user/status/${row.id}`, { status: row.status })
  ElMessage.success('状态修改成功')
}

getList()
</script>