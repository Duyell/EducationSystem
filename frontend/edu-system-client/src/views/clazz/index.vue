<template>
    <div class="crud-container">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="班级名称">
            <el-input v-model="query.clazzName" placeholder="班级" />
          </el-form-item>
          <el-form-item label="年级">
            <el-input v-model="query.grade" placeholder="2023级" />
          </el-form-item>
          <el-form-item label="专业">
            <el-select v-model="query.majorId" placeholder="专业" clearable style="width:160px">
              <el-option v-for="item in majorList" :key="item.id" :label="item.majorName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="学院">
            <el-select v-model="query.collegeId" placeholder="学院" clearable style="width:160px">
              <el-option v-for="item in collegeList" :key="item.id" :label="item.collegeName" :value="item.id" />
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
        <el-table-column prop="clazzName" label="班级名称" width="140" />
        <el-table-column prop="grade" label="年级" width="100" />
        <el-table-column prop="majorName" label="所属专业" min-width="150" />
        <el-table-column prop="collegeName" label="所属学院" min-width="150" />
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

      <el-dialog v-model="dialogVisible" title="班级信息" width="600px">
        <clazz-form ref="formRef" @success="getList" @close="dialogVisible = false" />
      </el-dialog>
    </div>
</template>

<script setup>
import { ref, reactive, onMounted,nextTick } from 'vue'
import { ElFormItem, ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import ClazzForm from './components/ClazzForm.vue'

const pageNum = ref(1), pageSize = ref(10), total = ref(0), list = ref([])
const dialogVisible = ref(false), formRef = ref()
const majorList = ref([])
const collegeList = ref([])


const query = reactive({
  clazzName: '',
  grade: '',
  majorId: null,
  collegeId: null
})

const getMajorList = async () => {
  const res = await axios.get('/api/major')
  majorList.value = res.data.list
}

const getCollegeList = async () => {
  const res = await axios.get('/api/college')
  collegeList.value = res.data.list
}

const getList = async () => {
  const res = await axios.get('/api/clazz', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list
  total.value = res.data.total
}

const resetQuery = () => {
  query.clazzName = ''
  query.grade = ''
  query.majorId = null
  getList()
}

const handleAdd = () => { dialogVisible.value = true; formRef.value?.reset() }
const handleEdit = (row) => { dialogVisible.value = true; 
  nextTick(() => { formRef.value?.setData(row) }) }

const handleDelete = async (id) => {
  await ElMessageBox.confirm('确定删除？')
  await axios.delete(`/api/clazz/${id}`)
  ElMessage.success('删除成功')
  getList()
}

onMounted(() => { getMajorList(); getList(); getCollegeList() })
</script>

<style scoped>
.crud-container { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.search-box { background: #f9fbff; padding: 16px; border-radius: 8px; margin-bottom: 20px; }
.crud-table { margin-bottom: 20px; border-radius: 8px; overflow: hidden; }
.page-box { text-align: right; }
</style>