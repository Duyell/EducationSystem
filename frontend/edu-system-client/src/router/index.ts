import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/components/Layout.vue'
const routes = [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/',
    component: Layout,
    meta: { requireAuth: true },
    children: [
      {
        path: 'index',
        component: () => import('../views/index.vue'),
        meta: { roles: ['admin', 'teacher', 'student'] }
      },
      {
        path: 'ai',
        component: () => import('../views/ai/index.vue'),
        meta: { roles: ['admin', 'teacher', 'student'] }
      },
      {
        path: 'user',
        component: () => import('../views/user/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        path: 'teacher',
        component: () => import('../views/teacher/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        path: 'student',
        component: () => import('../views/student/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        path: 'course',
        component: () => import('../views/course/index.vue'),
        meta: { roles: ['admin', 'teacher', 'student'] }
      },
      {
        path: 'score',
        component: () => import('../views/score/index.vue'),
        meta: { roles: ['admin', 'teacher', 'student'] }
      },
      {
        path: 'clazz',
        component: () => import('../views/clazz/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        path: 'college',
        component: () => import('../views/college/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        path: 'major',
        component: () => import('../views/major/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        path: 'evaluate',
        component: () => import('../views/evaluate/index.vue'),
        meta: { roles: ['teacher', 'student'] }
      },
      {
        // 我的绩点：后端 /gpa/my 对学生开放；admin 访问会得到空数据（无本人成绩）
        path: 'gpa',
        component: () => import('../views/gpa/index.vue'),
        meta: { roles: ['admin', 'student'] }
      },
      {
        // 我的培养方案（学生）：后端 /training-plan/my
        path: 'training-plan',
        component: () => import('../views/training-plan/index.vue'),
        meta: { roles: ['admin', 'student'] }
      },
      {
        // 培养计划维护（管理员）：列表 + 方案 CRUD + 课程明细
        path: 'training-plan-manage',
        component: () => import('../views/training-plan-manage/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        // 开课申请与排课（教师）：开课申请 → 申请排课 → 我的课表
        path: 'course-apply',
        component: () => import('../views/course-apply/index.vue'),
        meta: { roles: ['teacher'] }
      },
      {
        // 排课审批（管理员）：开课申请审批 / 排课申请审批 / 课表总览
        path: 'schedule-approve',
        component: () => import('../views/schedule-approve/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        // 教室维护（管理员）：800 间教室的分页 CRUD
        path: 'room',
        component: () => import('../views/room/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        // 选课轮次管理（管理员）：开关 + 时间窗 + 适用范围
        path: 'selection-round',
        component: () => import('../views/selection-round/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        // 选课（学生）：可选课程 + 我的已选；补退选期间只能退课
        path: 'course-selection',
        component: () => import('../views/course-selection/index.vue'),
        meta: { roles: ['student'] }
      },
      {
        // 考试安排（管理员）：列表 + CRUD，新建/编辑时实时预检冲突
        path: 'exam-manage',
        component: () => import('../views/exam-manage/index.vue'),
        meta: { roles: ['admin'] }
      },
      {
        // 我的考试（学生）：待考与已考分段，只含本人已选课程的考试
        path: 'my-exams',
        component: () => import('../views/my-exams/index.vue'),
        meta: { roles: ['student'] }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

// 路由守卫：检查登录状态 + 角色权限
router.beforeEach((to, _from, next) => {
  const token = sessionStorage.getItem('token')
  if (to.meta?.requireAuth && !token) {
    next('/login')
    return
  }

  // 角色权限校验：未命中 meta.roles 的路由放行
  const roles = to.meta?.roles as string[] | undefined
  if (roles && token) {
    let userRole = ''
    try {
      userRole = JSON.parse(sessionStorage.getItem('user') || '{}').role || ''
    } catch {
      userRole = ''
    }
    if (!roles.includes(userRole)) {
      next('/index') // 无权限访问，回首页
      return
    }
  }
  next()
})

export default router
