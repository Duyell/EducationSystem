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
    path: '/index',  
    name: 'Index',
    component: () => import('../views/Index.vue'),
    meta: { requireAuth: true } ,   
    
  },
  { path: '/user', component: () => import('../views/user/index.vue') },
  { path: '/teacher', component: () => import('../views/teacher/index.vue') },
  { path: '/student', component: () => import('../views/student/index.vue') },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router