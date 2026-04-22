import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/components/Layout.vue'
import path from 'path'

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
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router