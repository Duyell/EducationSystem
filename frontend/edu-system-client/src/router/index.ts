import { createRouter, createWebHistory } from 'vue-router'

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
    path: '/index',  // 必须全小写！
    name: 'Index',
    component: () => import('../views/Index.vue'),
    meta: { requireAuth: true } // 需要登录才能访问
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router