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
