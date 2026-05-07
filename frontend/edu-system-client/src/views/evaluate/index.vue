<template>
  <div class="crud-container">
    <!-- ==================== 学生视图：教师评价 ==================== -->
    <template v-if="userRole === 'student'">
      <div class="header-bar">
        <h3 class="section-title">教学评价</h3>
        <span class="tip-text">请对您已选课程的授课教师进行客观评价</span>
      </div>

      <el-table :data="evaluableCourses" border class="crud-table" stripe>
        <el-table-column prop="courseName" label="课程名称" min-width="160" />
        <el-table-column prop="teacherName" label="授课教师" min-width="100" />
        <el-table-column label="评价状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.evaluated ? 'success' : 'warning'" size="small">
              {{ row.evaluated ? '已评价' : '未评价' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="评价分数" width="200">
          <template #default="{ row }">
            <el-rate v-if="!row.evaluated" v-model="row.evalScore" :max="5" show-score score-template="{value}分" />
            <span v-else>{{ row.evalScore || '-' }} 分</span>
          </template>
        </el-table-column>
        <el-table-column label="评价内容" min-width="250">
          <template #default="{ row }">
            <el-input v-if="!row.evaluated" v-model="row.evalContent" type="textarea" :rows="2" placeholder="请填写评价内容..." />
            <span v-else>{{ row.evalContent || '无' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="!row.evaluated" size="small" type="primary" @click="submitEvaluation(row)">提交评价</el-button>
            <el-button v-else size="small" disabled>已评价</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- ==================== 教师视图：查看评价 ==================== -->
    <template v-if="userRole === 'teacher'">
      <div class="header-bar">
        <h3 class="section-title">收到的评价</h3>
        <div class="summary-card">
          平均评分：<el-rate :model-value="avgScore" :max="5" disabled show-score score-template="{value}" class="inline-rate" />
        </div>
      </div>

      <el-table :data="evaluationList" border class="crud-table" stripe>
        <el-table-column prop="courseName" label="课程名称" min-width="160" />
        <el-table-column prop="studentName" label="评价学生" min-width="100" />
        <el-table-column label="评分" width="150">
          <template #default="{ row }">
            <el-rate :model-value="row.score" :max="5" disabled show-score score-template="{value}分" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="评价内容" min-width="250" />
        <el-table-column prop="createTime" label="评价时间" width="180" />
      </el-table>

      <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize"
        :total="total" layout="total,prev,pager,next"
        @update:current-page="loadEvaluations" class="page-box" />
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'

const userRole = ref(sessionStorage.getItem('user') ? JSON.parse(sessionStorage.getItem('user')).role : 'admin')

// ===================== Student =====================
const evaluableCourses = ref([])

const loadEvaluableCourses = async () => {
  try {
    // Get my selected courses
    const myCoursesRes = await axios.get('/api/course-selection/my')
    const myCourses = myCoursesRes.data || []

    // Check evaluation status for each course
    const enriched = await Promise.all(myCourses.map(async (course) => {
      try {
        const checkRes = await axios.get(`/api/evaluate/check/${course.id}`)
        const evaluated = checkRes.data != null
        return {
          ...course,
          evaluated,
          evalScore: evaluated ? checkRes.data.score : 5,
          evalContent: evaluated ? checkRes.data.content : ''
        }
      } catch {
        return { ...course, evaluated: false, evalScore: 5, evalContent: '' }
      }
    }))
    evaluableCourses.value = enriched
  } catch {}
}

const submitEvaluation = async (course) => {
  try {
    await axios.post('/api/evaluate', {
      courseId: course.id,
      teacherId: course.teacherId,
      score: course.evalScore,
      content: course.evalContent || ''
    })
    ElMessage.success('评价成功')
    course.evaluated = true
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '评价失败')
  }
}

// ===================== Teacher =====================
const pageNum = ref(1), pageSize = ref(10), total = ref(0)
const evaluationList = ref([])
const avgScore = ref(0)

const loadEvaluations = async () => {
  try {
    const res = await axios.get('/api/evaluate/teacher', {
      params: { pageNum: pageNum.value, pageSize: pageSize.value }
    })
    evaluationList.value = res.data.list
    total.value = res.data.total

    // Calculate average score
    if (evaluationList.value.length > 0) {
      const sum = evaluationList.value.reduce((s, e) => s + e.score, 0)
      avgScore.value = parseFloat((sum / evaluationList.value.length).toFixed(1))
    }
  } catch {}
}

// ===================== Init =====================
onMounted(async () => {
  if (userRole.value === 'student') loadEvaluableCourses()
  else if (userRole.value === 'teacher') loadEvaluations()
})
</script>

<style scoped>
.crud-container { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.crud-table { margin-bottom: 20px; border-radius: 8px; overflow: hidden; }
.page-box { text-align: right; }
.header-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 18px; color: #333; }
.tip-text { font-size: 14px; color: #999; }
.summary-card { font-size: 14px; color: #666; display: flex; align-items: center; gap: 8px; }
.inline-rate { display: inline-flex; }
</style>
