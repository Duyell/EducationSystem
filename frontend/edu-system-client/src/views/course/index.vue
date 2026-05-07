<template>
  <div class="crud-container">
    <!-- ==================== 管理员视图：课程 CRUD ==================== -->
    <template v-if="userRole === 'admin'">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="课程名"><el-input v-model="query.courseName" placeholder="课程名称" /></el-form-item>
          <el-form-item label="授课教师"><el-input v-model="query.teacherName" placeholder="教师姓名" /></el-form-item>
          <el-form-item label="所属学院">
            <el-select v-model="query.collegeId" placeholder="请选择学院" clearable style="width:160px">
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
        <el-table-column prop="courseName" label="课程名" width="180" />
        <el-table-column prop="teacherName" label="授课教师" width="120" />
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

      <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize"
        :total="total" layout="total,prev,pager,next"
        @update:current-page="getList" class="page-box" />

      <el-dialog v-model="dialogVisible" title="课程信息" width="650px">
        <course-form ref="formRef" @success="getList" @close="dialogVisible = false" />
      </el-dialog>
    </template>

    <!-- ==================== 学生视图：选课退课 ==================== -->
    <template v-if="userRole === 'student'">
      <div class="section-tabs">
        <el-radio-group v-model="studentTab" class="tab-group">
          <el-radio-button value="available">可选课程</el-radio-button>
          <el-radio-button value="selected">已选课程</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 可选课程列表 -->
      <template v-if="studentTab === 'available'">
        <div class="search-box">
          <el-form inline :model="courseQuery">
            <el-form-item label="课程名"><el-input v-model="courseQuery.courseName" placeholder="课程名称" /></el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadAvailableCourses">搜索</el-button>
              <el-button @click="courseQuery.courseName=''; loadAvailableCourses()">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
        <el-table :data="availableCourses" border class="crud-table" stripe>
          <el-table-column prop="courseName" label="课程名" min-width="160" />
          <el-table-column prop="teacherName" label="授课教师" min-width="100" />
          <el-table-column prop="collegeName" label="开课学院" min-width="140" />
          <el-table-column prop="term" label="学期" width="110" />
          <el-table-column prop="credit" label="学分" width="70" />
          <el-table-column prop="classHour" label="课时" width="70" />
          <el-table-column prop="maxStudent" label="限选" width="70" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary"
                :disabled="selectedIds.includes(row.id)"
                @click="handleSelect(row.id)">
                {{ selectedIds.includes(row.id) ? '已选' : '选课' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="availPage" v-model:page-size="availSize"
          :total="availTotal" layout="total,prev,pager,next"
          @update:current-page="loadAvailableCourses" class="page-box" />
      </template>

      <!-- 已选课程列表 -->
      <template v-if="studentTab === 'selected'">
        <el-table :data="myCourses" border class="crud-table" stripe>
          <el-table-column prop="courseName" label="课程名" min-width="160" />
          <el-table-column prop="teacherName" label="授课教师" min-width="100" />
          <el-table-column prop="collegeName" label="开课学院" min-width="140" />
          <el-table-column prop="term" label="学期" width="110" />
          <el-table-column prop="credit" label="学分" width="70" />
          <el-table-column label="成绩" width="80">
            <template #default="{ row }">
              <el-tag v-if="scoredCourseIds.includes(row.id)" type="success" size="small">已出</el-tag>
              <el-tag v-else type="warning" size="small">未出</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="danger"
                :disabled="scoredCourseIds.includes(row.id)"
                @click="handleDrop(row.id)">
                {{ scoredCourseIds.includes(row.id) ? '有成绩不可退' : '退课' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </template>

    <!-- ==================== 教师视图：我的课程及学生列表 ==================== -->
    <template v-if="userRole === 'teacher'">
      <div class="section-tabs">
        <el-radio-group v-model="teacherTab" class="tab-group">
          <el-radio-button value="courses">我的课程</el-radio-button>
          <el-radio-button v-if="selectedCourse" value="students">课程学生</el-radio-button>
        </el-radio-group>
      </div>

      <template v-if="teacherTab === 'courses'">
        <el-table :data="teacherCourses" border class="crud-table" stripe @row-click="onCourseClick">
          <el-table-column prop="courseName" label="课程名" min-width="180" />
          <el-table-column prop="collegeName" label="开课学院" min-width="150" />
          <el-table-column prop="term" label="学期" width="120" />
          <el-table-column prop="credit" label="学分" width="80" />
          <el-table-column prop="classHour" label="课时" width="80" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click.stop="viewStudents(row)">查看学生</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="teacherTab === 'students' && selectedCourse">
        <div class="back-bar">
          <el-button text @click="teacherTab='courses'; selectedCourse=null">
            &lt; 返回课程列表
          </el-button>
          <span class="course-label">{{ selectedCourse.courseName }} — 学生列表</span>
        </div>
        <el-table :data="courseStudents" border class="crud-table" stripe>
          <el-table-column prop="studentName" label="姓名" min-width="100" />
          <el-table-column prop="studentId" label="学号" width="120" />
          <el-table-column prop="clazzName" label="班级" min-width="150" />
          <el-table-column prop="gender" label="性别" width="70" />
          <el-table-column prop="phone" label="电话" min-width="130" />
        </el-table>
      </template>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import CourseForm from './components/CourseForm.vue'

const userRole = ref(sessionStorage.getItem('user') ? JSON.parse(sessionStorage.getItem('user')).role : 'admin')

// ===================== Admin state =====================
const pageNum = ref(1), pageSize = ref(10), total = ref(0), list = ref([])
const dialogVisible = ref(false), formRef = ref()
const query = reactive({ courseName: '', teacherName: '', teacherId: '', collegeId: null, credit: null, classHour: null, maxStudent: null })
const collegeList = ref([])

// ===================== Student state =====================
const studentTab = ref('available')
const availableCourses = ref([])
const myCourses = ref([])
const selectedIds = ref([])
const scoredCourseIds = ref([])
const availPage = ref(1), availSize = ref(10), availTotal = ref(0)
const courseQuery = reactive({ courseName: '' })

// ===================== Teacher state =====================
const teacherTab = ref('courses')
const teacherCourses = ref([])
const selectedCourse = ref(null)
const courseStudents = ref([])

// ===================== Admin methods =====================
const getCollegeList = async () => {
  try { const res = await axios.get('/api/college'); collegeList.value = res.data.list } catch {}
}
const getList = async () => {
  const res = await axios.get('/api/course', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list; total.value = res.data.total
}
const resetQuery = () => { query.courseName = ''; query.teacherName = ''; query.teacherId = ''; query.collegeId = null; getList() }
const handleAdd = () => { dialogVisible.value = true; formRef.value?.reset() }
const handleEdit = (row) => { dialogVisible.value = true; formRef.value?.setData(row) }
const handleDelete = async (id) => { await ElMessageBox.confirm('确定删除该课程？'); await axios.delete(`/api/course/${id}`); ElMessage.success('删除成功'); getList() }

// ===================== Student methods =====================
const loadAvailableCourses = async () => {
  const res = await axios.get('/api/course', {
    params: { courseName: courseQuery.courseName || undefined, pageNum: availPage.value, pageSize: availSize.value }
  })
  availableCourses.value = res.data.list
  availTotal.value = res.data.total
}

const loadMyCourseIds = async () => {
  try {
    const res = await axios.get('/api/course-selection/my-ids')
    selectedIds.value = res.data || []
  } catch {}
}

const loadMyCourses = async () => {
  try {
    const res = await axios.get('/api/course-selection/my')
    myCourses.value = res.data || []
    // 同时获取已出成绩的课程 ID
    const scoreRes = await axios.get('/api/score/my', { params: { pageSize: 100 } })
    const scores = scoreRes.data.list || []
    scoredCourseIds.value = scores.map(s => s.courseId)
  } catch {}
}

const handleSelect = async (courseId) => {
  try {
    await axios.post(`/api/course-selection/select/${courseId}`)
    ElMessage.success('选课成功')
    loadMyCourseIds()
    loadAvailableCourses()
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '选课失败')
  }
}

const handleDrop = async (courseId) => {
  try {
    await ElMessageBox.confirm('确定退选该课程？')
    await axios.delete(`/api/course-selection/${courseId}`)
    ElMessage.success('退课成功')
    loadMyCourses()
    loadMyCourseIds()
    loadAvailableCourses()
  } catch {}
}

// ===================== Teacher methods =====================
const loadTeacherCourses = async () => {
  try {
    const res = await axios.get('/api/course/my')
    teacherCourses.value = res.data || []
  } catch {}
}

const viewStudents = async (course) => {
  selectedCourse.value = course
  teacherTab.value = 'students'
  try {
    const res = await axios.get(`/api/course/${course.id}/students`)
    courseStudents.value = res.data || []
  } catch {}
}

const onCourseClick = (row) => viewStudents(row)

// 切换"已选课程"标签时加载数据
watch(studentTab, (tab) => {
  if (tab === 'selected') loadMyCourses()
})

// ===================== Init =====================
onMounted(async () => {
  if (userRole.value === 'admin') { getCollegeList(); getList() }
  else if (userRole.value === 'student') { loadAvailableCourses(); loadMyCourseIds() }
  else if (userRole.value === 'teacher') { loadTeacherCourses() }
})
</script>

<style scoped>
.crud-container { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.search-box { background: #f9fbff; padding: 16px; border-radius: 8px; margin-bottom: 20px; }
.crud-table { margin-bottom: 20px; border-radius: 8px; overflow: hidden; cursor: pointer; }
.page-box { text-align: right; }
.section-tabs { margin-bottom: 20px; }
.tab-group { margin-bottom: 16px; }
.back-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; padding: 8px 12px; background: #f9fbff; border-radius: 8px; }
.course-label { font-size: 16px; font-weight: 600; color: #165DFF; }
</style>
