<template>
  <div class="crud-container">
    <!-- ==================== 管理员视图：成绩管理 ==================== -->
    <template v-if="userRole === 'admin'">
      <div class="search-box">
        <el-form inline :model="query">
          <el-form-item label="学号"><el-input v-model="query.studentId" placeholder="学生学号" /></el-form-item>
          <el-form-item label="课程">
            <el-select v-model="query.courseId" placeholder="课程" clearable style="width:180px">
              <el-option v-for="item in courseList" :key="item.id" :label="item.courseName" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="学期"><el-input v-model="query.term" placeholder="2024-2025-1" /></el-form-item>
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

      <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize"
        :total="total" layout="total,prev,pager,next"
        @update:current-page="getList" class="page-box" />

      <el-dialog v-model="dialogVisible" title="成绩录入" width="550px">
        <score-form ref="formRef" @success="getList" @close="dialogVisible = false" />
      </el-dialog>
    </template>

    <!-- ==================== 学生视图：成绩查询 ==================== -->
    <template v-if="userRole === 'student'">
      <div class="header-bar">
        <h3 class="section-title">我的成绩</h3>
        <div class="summary-card">
          <span>已修课程：<strong>{{ list.length }}</strong> 门</span>
        </div>
      </div>

      <el-table :data="list" border class="crud-table" stripe>
        <el-table-column prop="courseName" label="课程名称" min-width="200" />
        <el-table-column prop="term" label="学期" width="130" />
        <el-table-column prop="usualScore" label="平时成绩" width="110" />
        <el-table-column prop="examScore" label="考试成绩" width="110" />
        <el-table-column prop="totalScore" label="总评成绩" width="110">
          <template #default="{ row }">
            <el-tag :type="getGradeType(row.totalScore)" size="large">{{ row.totalScore }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize"
        :total="total" layout="total,prev,pager,next"
        @update:current-page="getStudentScores" class="page-box" />
    </template>

    <!-- ==================== 教师视图：成绩录入 ==================== -->
    <template v-if="userRole === 'teacher'">
      <div class="section-tabs">
        <el-radio-group v-model="teacherTab" class="tab-group">
          <el-radio-button value="selectCourse">选择课程</el-radio-button>
          <el-radio-button value="enterScore" :disabled="!selectedCourseForScore">录入成绩</el-radio-button>
        </el-radio-group>
      </div>

      <template v-if="teacherTab === 'selectCourse'">
        <el-table :data="teacherCourses" border class="crud-table" stripe @row-click="onScoreCourseClick">
          <el-table-column prop="courseName" label="课程名" min-width="180" />
          <el-table-column prop="collegeName" label="开课学院" min-width="150" />
          <el-table-column prop="term" label="学期" width="120" />
          <el-table-column prop="credit" label="学分" width="80" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click.stop="selectCourseForScore(row)">录入成绩</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="teacherTab === 'enterScore' && selectedCourseForScore">
        <div class="back-bar">
          <el-button text @click="teacherTab='selectCourse'; selectedCourseForScore=null">
            &lt; 返回课程列表
          </el-button>
          <span class="course-label">{{ selectedCourseForScore.courseName }} — 成绩录入</span>
        </div>

        <el-table :data="scoreStudents" border class="crud-table" stripe>
          <el-table-column prop="studentName" label="姓名" min-width="100" />
          <el-table-column prop="studentId" label="学号" width="120" />
          <el-table-column label="平时成绩" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.usualScore" :min="0" :max="100" :precision="1" size="small" controls-position="right" />
            </template>
          </el-table-column>
          <el-table-column label="考试成绩" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.examScore" :min="0" :max="100" :precision="1" size="small" controls-position="right" />
            </template>
          </el-table-column>
          <el-table-column label="总评成绩" width="110">
            <template #default="{ row }">
              <span>{{ computeTotal(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="saveScore(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-button type="success" class="batch-save-btn" @click="saveAllScores">批量保存</el-button>
      </template>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import ScoreForm from './components/ScoreForm.vue'

const userRole = ref(sessionStorage.getItem('user') ? JSON.parse(sessionStorage.getItem('user')).role : 'admin')

// ===================== Common =====================
const pageNum = ref(1), pageSize = ref(10), total = ref(0), list = ref([])

const getGradeType = (score) => {
  if (!score && score !== 0) return 'info'
  const s = parseFloat(score)
  if (s >= 90) return 'success'
  if (s >= 80) return 'primary'
  if (s >= 70) return 'warning'
  if (s >= 60) return 'info'
  return 'danger'
}

// ===================== Admin =====================
const dialogVisible = ref(false), formRef = ref(), courseList = ref([])
const query = reactive({ studentId: '', courseId: null, term: '' })

const getCourseList = async () => {
  const res = await axios.get('/api/course'); courseList.value = res.data.list
}
const getList = async () => {
  const res = await axios.get('/api/score', { params: { ...query, pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list; total.value = res.data.total
}
const resetQuery = () => { query.studentId = ''; query.courseId = null; query.term = ''; getList() }
const handleEdit = (row) => { dialogVisible.value = true; formRef.value?.setData(row) }

// ===================== Student =====================
const getStudentScores = async () => {
  const res = await axios.get('/api/score/my', { params: { pageNum: pageNum.value, pageSize: pageSize.value } })
  list.value = res.data.list; total.value = res.data.total
}

// ===================== Teacher =====================
const teacherTab = ref('selectCourse')
const teacherCourses = ref([])
const selectedCourseForScore = ref(null)
const scoreStudents = ref([])

const loadTeacherCourses = async () => {
  try {
    const res = await axios.get('/api/course/my')
    teacherCourses.value = res.data || []
  } catch {}
}

const selectCourseForScore = async (course) => {
  selectedCourseForScore.value = course
  teacherTab.value = 'enterScore'
  // Load students enrolled in this course
  try {
    const studentsRes = await axios.get(`/api/course/${course.id}/students`)
    // Also load existing scores if any
    const scoresRes = await axios.get('/api/score', { params: { courseId: course.id, pageSize: 100 } })
    const existingScores = scoresRes.data.list || []

    scoreStudents.value = (studentsRes.data || []).map(s => {
      const existing = existingScores.find(e => e.studentId === s.studentId)
      return {
        studentId: s.studentId,
        studentName: s.studentName,
        courseId: course.id,
        id: existing?.id,
        usualScore: existing?.usualScore ?? 0,
        examScore: existing?.examScore ?? 0
      }
    })
  } catch {}
}

const onScoreCourseClick = (row) => selectCourseForScore(row)

const computeTotal = (row) => {
  const usual = parseFloat(row.usualScore) || 0
  const exam = parseFloat(row.examScore) || 0
  return (usual * 0.4 + exam * 0.6).toFixed(1)
}

const saveScore = async (row) => {
  const total = computeTotal(row)
  const payload = {
    courseId: row.courseId,
    studentId: row.studentId,
    usualScore: row.usualScore,
    examScore: row.examScore,
    totalScore: total
  }
  if (row.id) payload.id = row.id

  try {
    if (row.id) {
      await axios.put('/api/score', payload)
    } else {
      await axios.post('/api/score', payload)
    }
    ElMessage.success('保存成功')
    // Refresh to get the id
    selectCourseForScore(selectedCourseForScore.value)
  } catch {
    ElMessage.error('保存失败')
  }
}

const saveAllScores = async () => {
  for (const row of scoreStudents.value) {
    const total = computeTotal(row)
    const payload = {
      courseId: row.courseId,
      studentId: row.studentId,
      usualScore: row.usualScore,
      examScore: row.examScore,
      totalScore: total
    }
    if (row.id) payload.id = row.id
    try {
      if (row.id) await axios.put('/api/score', payload)
      else await axios.post('/api/score', payload)
    } catch {}
  }
  ElMessage.success('全部保存成功')
}

// ===================== Init =====================
onMounted(async () => {
  if (userRole.value === 'admin') { getCourseList(); getList() }
  else if (userRole.value === 'student') { getStudentScores() }
  else if (userRole.value === 'teacher') { loadTeacherCourses() }
})
</script>

<style scoped>
.crud-container { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.search-box { background: #f9fbff; padding: 16px; border-radius: 8px; margin-bottom: 20px; }
.crud-table { margin-bottom: 20px; border-radius: 8px; overflow: hidden; }
.crud-table.el-table--striped { cursor: pointer; }
.page-box { text-align: right; }
.section-tabs { margin-bottom: 20px; }
.tab-group { margin-bottom: 16px; }
.back-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; padding: 8px 12px; background: #f9fbff; border-radius: 8px; }
.course-label { font-size: 16px; font-weight: 600; color: #165DFF; }
.header-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 18px; color: #333; }
.summary-card { font-size: 14px; color: #666; }
.batch-save-btn { margin-top: 16px; }
</style>
