<template>
  <div class="crud-container">
    <!-- ==================== 学生视图：教师评价 ==================== -->
    <template v-if="userRole === 'student'">
      <div class="header-bar">
        <h3 class="section-title">教学评价</h3>
        <span class="tip-text">请对您已选课程的授课教师进行客观评价</span>
      </div>

      <el-table :data="evaluableCourses" border class="crud-table" stripe v-loading="loading" empty-text="暂无数据">
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

      <el-table :data="evaluationList" border class="crud-table" stripe v-loading="loading" empty-text="暂无数据">
        <el-table-column prop="courseName" label="课程名称" min-width="160" />
        <!-- 匿名评教：教师能看到评价内容，但看不到提交人（2026-09-22 确认） -->
        <el-table-column label="提交人" width="100">
          <template #default>
            <el-tag type="info" size="small">匿名</el-tag>
          </template>
        </el-table-column>
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

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import type { Course, EvaluableCourse, TeacherEvaluation } from '@/types/models'

const userRole = ref(sessionStorage.getItem('user') ? JSON.parse(sessionStorage.getItem('user') || '{}').role : 'admin')

// ===================== Student =====================
const evaluableCourses = ref<EvaluableCourse[]>([])

const loadEvaluableCourses = async () => {
  loading.value = true
  try {
    // Get my selected courses
    const myCoursesRes = await axios.get('/api/course-selection/my')
    const myCourses = myCoursesRes.data || []

    // Check evaluation status for each course
    const enriched = await Promise.all(myCourses.map(async (course: Course) => {
      try {
        const checkRes = await axios.get(`/api/evaluate/check/${course.id}`)
        const evaluated = checkRes.data != null
        return {
          ...course,
          evaluated,
          evalScore: evaluated ? checkRes.data.score : 5,
          evalContent: evaluated ? checkRes.data.content : ''
        }
      } catch (e) {
        console.error('查询评价状态失败:', e)
        return { ...course, evaluated: false, evalScore: 5, evalContent: '' }
      }
    }))
    evaluableCourses.value = enriched
  } catch (e) { console.error('加载可评课程失败:', e) } finally {
    loading.value = false
  }
}

const submitEvaluation = async (course: EvaluableCourse) => {
  try {
    await axios.post('/api/evaluate', {
      courseId: course.id,
      teacherId: course.teacherId,
      score: course.evalScore,
      content: course.evalContent || ''
    })
    ElMessage.success('评价成功')
    course.evaluated = true
  } catch {
    // request.ts 拦截器已统一弹出错误提示，此处不重复提示
  }
}

// ===================== Teacher =====================
const pageNum = ref(1), pageSize = ref(10), total = ref(0)
const loading = ref(false)
const evaluationList = ref<TeacherEvaluation[]>([])
const avgScore = ref(0)

const loadEvaluations = async () => {
  loading.value = true
  try {
    const res = await axios.get('/api/evaluate/teacher', {
      params: { pageNum: pageNum.value, pageSize: pageSize.value }
    })
    evaluationList.value = res.data.list
    total.value = res.data.total

    // 平均分取自后端聚合接口（全部评价），不受当前页影响
    const avgRes = await axios.get('/api/evaluate/teacher/avg')
    avgScore.value = parseFloat(avgRes.data ?? 0)
  } catch (e) { console.error('加载评价列表失败:', e) } finally {
    loading.value = false
  }
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
