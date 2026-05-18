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
        component: () => import('../views/index.vue')
      },
      {
        path: 'ai',
        component: () => import('../views/ai/index.vue')
      },
      {
        path: 'user',
        component: () => import('../views/user/index.vue')
      },
      {
        path: 'teacher',
        component: () => import('../views/teacher/index.vue')
      },
      {
        path: 'student',
        component: () => import('../views/student/index.vue')
      },
      {
        path: 'course',
        component: () => import('../views/course/index.vue')
      },
      {
        path: 'score',
        component: () => import('../views/score/index.vue')
      },
      {
        path: 'clazz',
        component: () => import('../views/clazz/index.vue')
      },
      {
        path: 'college',
        component: () => import('../views/college/index.vue')
      },
      {
        path: 'major',
        component: () => import('../views/major/index.vue')
      },
      {
        path: 'evaluate',
        component: () => import('../views/evaluate/index.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

// 路由守卫：检查登录状态
router.beforeEach((to, _from, next) => {
  const token = sessionStorage.getItem('token')
  if (to.meta?.requireAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router