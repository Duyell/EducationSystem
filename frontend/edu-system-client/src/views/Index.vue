<template>
  <div class="layout-container">
    <!-- 顶部导航栏 -->
    <el-header class="header-bar">
      <div class="header-left">
        <div class="logo-box">
          <el-icon :size="32" color="#fff"><House /></el-icon>
          <span class="system-name">教务系统</span>
        </div>

        <!-- 动态导航栏：根据角色切换（关键修改点） -->
        <el-menu
          :default-active="activeMenu"
          mode="horizontal"
          class="nav-menu"
          :ellipsis="false"
          :router="true"
        >
          <!-- 只渲染一个菜单列表，内容由角色计算属性决定 -->
          <el-menu-item 
            v-for="item in menuList" 
            :key="item.path" 
            :index="item.path"
          >
            {{ item.name }}
          </el-menu-item>
        </el-menu>
      </div>

      <div class="header-right">
        <el-icon :size="24" color="#fff" class="search-icon"><Search /></el-icon>

        <el-dropdown class="user-dropdown" @command="handleUserCommand">
          <span class="user-info">
            <el-avatar :size="40" :src="userAvatar" />
            <span class="user-name">{{ userName }}</span>
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePwd">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <!-- 主体内容区 -->
    <el-main class="main-content">
      <div class="welcome-banner">
        <h1 class="welcome-title">{{ welcomeTitle }}</h1>
        <p class="security-tip">
          （本平台为互联网非涉密平台，严禁处理、传输国家秘密）
        </p>
        <div class="date-info">
          今天是 {{ currentDate }} 星期{{ weekDay }} &nbsp;&nbsp; 2025-2026-2 &nbsp;&nbsp; 第6周
        </div>
      </div>

      <!-- 数据面板 -->
      <div class="content-area">
        <el-row :gutter="20">
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-title">学生总数</div>
              <div class="stat-number">{{ studentTotal }}</div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-title">课程总数</div>
              <div class="stat-number">{{ courseTotal }}</div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-title">教师总数</div>
              <div class="stat-number">{{ teacherTotal }}</div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="stat-card" v-if="userRole === 'admin'">
              <div class="stat-title">班级总数</div>
              <div class="stat-number">{{ classTotal }}</div>
            </el-card>
            <el-card class="stat-card" v-else>
              <div class="stat-title">今日签到</div>
              <div class="stat-number">987</div>
            </el-card>
          </el-col>
        </el-row>
      </div>
      <router-view/>
    </el-main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, ArrowDown, House } from '@element-plus/icons-vue'
import axios from '@/utils/request'

const router = useRouter()

// ====================== 1. 用户角色与菜单定义 ======================
const userRole = ref('') // 从登录信息中获取：admin / teacher / student
const userName = ref('')
const userAvatar = ref('https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png')

// 管理员专用菜单
const adminMenuList = [
  { name: '首页', path: '/index' },
  { name: '用户管理', path: '/user' },
  { name: '教师管理', path: '/teacher' },
  { name: '学生管理', path: '/student' },
  { name: '学院管理', path: '/college' },
  { name: '专业管理', path: '/major' },
  { name: '班级管理', path: '/clazz' },
  { name: '课程管理', path: '/course' },
  { name: '成绩管理', path: '/score' }
]

// 教师/学生公共菜单（非管理员统一使用）
const commonMenuList = [
  { name: '首页', path: '/index' },
  { name: '课程', path: '/course' },
  { name: '成绩', path: '/score' },
  { name: '教评', path: '/evaluate' }
]

// 根据角色动态计算当前应显示的菜单
const menuList = computed(() => {
  if (userRole.value === 'admin') {
    return adminMenuList
  } else {
    return commonMenuList
  }
})

// 导航激活项
const activeMenu = ref('/index')

// 欢迎标题（按角色显示）
const welcomeTitle = computed(() => {
  switch (userRole.value) {
    case 'admin': return 'HI,欢迎进入管理后台'
    case 'teacher': return 'HI,欢迎进入您的教学空间'
    case 'student': return 'HI,欢迎进入学习空间'
    default: return 'HI,欢迎使用教务系统'
  }
})

// ====================== 2. 统计数据 ======================
const studentTotal = ref(0)
const teacherTotal = ref(0)
const courseTotal = ref(0)
const classTotal = ref(0)

// ====================== 3. 日期 ======================
const currentDate = computed(() => {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}年${month}月${day}日`
})

const weekDay = computed(() => {
  const weekMap = ['一', '二', '三', '四', '五', '六', '日']
  const now = new Date()
  return weekMap[now.getDay() === 0 ? 6 : now.getDay() - 1]
})

// ====================== 4. 生命周期：加载用户信息 + 统计数据 ======================
onMounted(() => {
  // 1. 从本地存储获取登录用户信息
  const loginUser = JSON.parse(sessionStorage.getItem('user') || '{}')
  userRole.value = loginUser.role || 'student'
  userName.value = loginUser.name || '用户'

  // 2. 获取首页统计数据
  axios.get('/api/home/statistics').then(res => {
    studentTotal.value = res.totalStudents
    teacherTotal.value = res.totalTeachers
    courseTotal.value = res.totalCourses
    classTotal.value = res.totalClasses
  })
})

// ====================== 5. 用户操作 ======================
const handleUserCommand = (command: string) => {
  switch (command) {
    case 'changePwd':
      ElMessage.info('跳转到修改密码页面')
      break
    case 'logout':
      ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        await axios.post('/api/logout')
        sessionStorage.removeItem('token')
        sessionStorage.removeItem('user')
        ElMessage.success('退出成功')
        router.push('/login')
      }).catch(() => {})
      break
  }
}
</script>

<style scoped>
/* 整体布局 */
.layout-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

/* 顶部导航栏 */
.header-bar {
  background: linear-gradient(90deg, #165DFF 0%, #4080FF 100%);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 64px !important;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.header-left {
  display: flex;
  align-items: center;
  height: 100%;
}

.logo-box {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-right: 30px;
}

.system-name {
  color: #fff;
  font-size: 20px;
  font-weight: bold;
}

.nav-menu {
  background: transparent;
  border: none;
  height: 100%;
  line-height: 64px;
}

.nav-menu .el-menu-item {
  color: #fff;
  font-size: 16px;
  height: 64px;
  line-height: 64px;
  border-bottom: 2px solid transparent;
}

.nav-menu .el-menu-item:hover,
.nav-menu .el-menu-item.is-active {
  background: rgba(255, 255, 255, 0.1);
  border-bottom-color: #fff;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.search-icon {
  cursor: pointer;
}

.user-dropdown {
  cursor: pointer;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-size: 16px;
}

.user-name {
  font-weight: 500;
}

/* 主体内容 */
.main-content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}

.welcome-banner {
  background: linear-gradient(135deg, #165DFF 0%, #4080FF 100%);
  border-radius: 12px;
  padding: 40px;
  margin-bottom: 20px;
  color: #fff;
  position: relative;
  overflow: hidden;
}

.welcome-banner::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image: radial-gradient(circle at 10% 20%, rgba(255,255,255,0.1) 0%, transparent 50%);
  pointer-events: none;
}

.welcome-title {
  font-size: 36px;
  margin: 0 0 10px 0;
  font-weight: bold;
}

.security-tip {
  font-size: 18px;
  color: #ff4d4f;
  margin: 0 0 30px 0;
  font-weight: bold;
}

.date-info {
  font-size: 16px;
  opacity: 0.9;
}

/* 统计卡片 */
.content-area {
  margin-top: 20px;
}

.stat-card {
  height: 120px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  transition: all 0.3s;
}

.stat-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 8px 16px rgba(0, 0, 0, 0.1);
}

.stat-title {
  font-size: 16px;
  color: #666;
  margin-bottom: 10px;
}

.stat-number {
  font-size: 32px;
  font-weight: bold;
  color: #165DFF;
}
</style>