<template>
    <div class="crud-container">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="用户名">
            <el-input v-model="query.username" placeholder="用户名" />
          </el-form-item>
          <el-form-item label="角色">
            <!-- 加宽角色选择框 -->
            <el-select v-model="query.role" placeholder="角色" style="width: 160px;">
              <el-option label="管理员" value="admin" />
              <el-option label="教师" value="teacher" />
              <el-option label="学生" value="student" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="getList">搜索</el-button>
            <el-button @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="list" border class="crud-table">
        <!-- ID 列已删除 -->
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="role" label="角色" />
        <el-table-column prop="email" label="邮箱" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-switch v-model="row.status" :active-value="1" :inactive-value="0" @change="handleStatusChange(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        layout="total,prev,pager,next,jumper"
        @update:current-page="getList"
        @update:page-size="getList"
        class="page-box"
      />
    </div>
</template>

<script setup lang="ts">
console.log('User management component mounted')
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'

const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const list = ref([])
const query = reactive({ username: '', role: '' })

const getList = async () => {
  try {
    const res = await axios.get('/api/user', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
    list.value = res.data.list
    total.value = res.data.total
    console.log(res.data)
  } catch (error) {
    console.error('获取用户列表失败:', error)
    ElMessage.error('获取用户列表失败')
    list.value = []
    total.value = 0
  }
}

const resetQuery = () => {
  query.username = ''
  query.role = ''
  getList()
}

const handleDelete = async (id: any) => {
  try {
    await ElMessageBox.confirm('确定删除？')
    await axios.delete(`/api/user/${id}`)
    ElMessage.success('删除成功')
    getList()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
      ElMessage.error('删除失败')
    }
  }
}

const handleStatusChange = async (row: { id: any; status: any }) => {
  const oldStatus = row.status
  try {
    await axios.put(`/api/user/status/${row.id}`, { status: row.status })
    ElMessage.success('状态已更新')
  } catch (error) {
    console.error('更新状态失败:', error)
    ElMessage.error('更新状态失败')
    row.status = oldStatus
  }
}

getList()
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