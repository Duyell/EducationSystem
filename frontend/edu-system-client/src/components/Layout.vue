<template>
  <div class="layout-container">
    <!-- 顶部导航栏 -->
    <el-header class="header-bar">
      <div class="header-left">
        <div class="logo-box">
          <el-icon :size="32" color="#fff"><House /></el-icon>
          <span class="system-name">教务系统</span>
        </div>

        <el-menu
          :default-active="activeMenu"
          mode="horizontal"
          class="nav-menu"
          :ellipsis="false"
          :router="false"
        >
          <el-menu-item 
            v-for="item in menuList" 
            :key="item.path" 
            :index="item.path"
            @click="onMenuClick(item.path)"
          >
            {{ item.name }}
          </el-menu-item>
        </el-menu>
      </div>

      <div class="header-right">
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
      <!-- 欢迎横幅（仅首页显示，其他页面隐藏） -->
      <div class="welcome-banner" v-if="isHomePage">
        <h1 class="welcome-title">{{ welcomeTitle }}</h1>
        <p class="security-tip">
          （本平台为互联网非涉密平台，严禁处理、传输国家秘密）
        </p>
        <div class="date-info">
          今天是 {{ currentDate }} 星期{{ weekDay }} &nbsp;&nbsp; 2025-2026-2 &nbsp;&nbsp; 第{{ currentWeek }}周
        </div>
      </div>

      <!-- 首页数据面板 -->
      <div class="content-area" v-if="isHomePage">
        <el-row :gutter="20" v-loading="loading">
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
            <el-card class="stat-card">
              <div class="stat-title">班级总数</div>
              <div class="stat-number">{{ classTotal }}</div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 业务页面插槽（核心：其他页面内容嵌入这里） -->
      <router-view />
    </el-main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, House } from '@element-plus/icons-vue'
import axios from '@/utils/request'

const router = useRouter()
const route = useRoute()

// ====================== 1. 用户角色与菜单定义 ======================
const userRole = ref('') 
const userName = ref('')
const userAvatar = ref('https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png')

// 管理员专用菜单
const adminMenuList = [
  { name: '首页', path: '/index' },
  { name: 'AI 助手', path: '/ai' },
  { name: '用户管理', path: '/user' },
  { name: '教师管理', path: '/teacher' },
  { name: '学生管理', path: '/student' },
  { name: '学院管理', path: '/college' },
  { name: '专业管理', path: '/major' },
  { name: '班级管理', path: '/clazz' },
  { name: '课程管理', path: '/course' },
  { name: '成绩管理', path: '/score' },
  { name: '培养计划', path: '/training-plan-manage' },
  { name: '教室管理', path: '/room' },
  { name: '排课审批', path: '/schedule-approve' },
  { name: '选课轮次', path: '/selection-round' }
]

// 教师与学生共用的基础菜单。
// ⚠️ 刻意抽出这一层：学生菜单曾经是 [...teacherMenuList]，结果给教师加菜单项时
// 会**顺带漏给学生**一个他们无权访问、点了会被守卫弹回首页的入口。
const commonMenuList = [
  { name: '首页', path: '/index' },
  { name: 'AI 助手', path: '/ai' },
  { name: '课程', path: '/course' },
  { name: '成绩', path: '/score' },
  { name: '教评', path: '/evaluate' }
]

// 教师菜单：基础菜单 + 开课申请/排课；不含培养方案与绩点，也不含选课
// （用户明确要求教师没有绩点与选课相关功能，后端对教师返回 403）
const teacherMenuList = [...commonMenuList, { name: '开课申请', path: '/course-apply' }]

// 学生菜单：基础菜单 + 本人专属的方案/绩点/选课（不含教师的开课申请，也不含管理员的选课轮次）
const studentMenuList = [
  ...commonMenuList,
  { name: '我的方案', path: '/training-plan' },
  { name: '我的绩点', path: '/gpa' },
  { name: '选课', path: '/course-selection' }
]

// 动态菜单
const menuList = computed(() => {
  switch (userRole.value) {
    case 'admin': return adminMenuList
    case 'teacher': return teacherMenuList
    default: return studentMenuList
  }
})

// 导航激活项（匹配当前路由）
const activeMenu = computed(() => route.path)

// 是否为首页
const isHomePage = computed(() => route.path === '/index')

// 欢迎标题
const welcomeTitle = computed(() => {
  switch (userRole.value) {
    case 'admin': return 'HI,欢迎进入管理后台'
    case 'teacher': return 'HI,欢迎进入您的教学空间'
    case 'student': return 'HI,欢迎进入学习空间'
    default: return 'HI,欢迎使用教务系统'
  }
})

// ====================== 2. 统计数据（仅首页） ======================
const studentTotal = ref(0)
const teacherTotal = ref(0)
const courseTotal = ref(0)
const classTotal = ref(0)
const loading = ref(false)

// ====================== 2.5. 加载统计数据函数（修复接口取值） ======================
const loadStatistics = async () => {
  // 防止重复请求
  if (loading.value) return
  
  loading.value = true
  try {
    const res = await axios.get('/api/home/statistics')
    studentTotal.value = res.data?.totalStudents ?? 0
    teacherTotal.value = res.data?.totalTeachers ?? 0
    courseTotal.value = res.data?.totalCourses ?? 0
    classTotal.value = res.data?.totalClasses ?? 0
  } catch (error) {
    console.error('❌ 加载统计数据失败:', error)
  } finally {
    loading.value = false
  }
}

// ====================== 3. 日期格式化 ======================
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

const currentWeek = computed(() => {
  const semesterStart = new Date('2026-03-02')
  const now = new Date()
  const diff = now.getTime() - semesterStart.getTime()
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  const week = Math.floor(days / 7) + 1
  return week < 1 ? 1 : week
})

// ====================== 4. 生命周期（彻底修复） ======================
onMounted(async () => {
  // 1. 立即加载用户信息
  const loginUser = JSON.parse(sessionStorage.getItem('user') || '{}')
  userRole.value = loginUser.role || 'student'
  userName.value = loginUser.name || '用户'
  const avatar = loginUser.avatar
  userAvatar.value = avatar && avatar.startsWith('http') ? avatar : 'https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png'

  // 2. 等待路由完全就绪后加载首页数据
  await nextTick()
  if (isHomePage.value) {
    loadStatistics()
  }
})

// ====================== 路由监听（彻底修复，必触发） ======================
watch(
  () => route.path,
  (newPath) => {
    if (newPath === '/index') {
      // 重新同步用户信息 + 加载数据
      const loginUser = JSON.parse(sessionStorage.getItem('user') || '{}')
      userRole.value = loginUser.role || 'student'
      loadStatistics()
    }
  },
  { immediate: true } 
)

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

// 菜单点击处理
const onMenuClick = (path: string) => {
  router.push(path)
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

/* 欢迎横幅 */
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
  overflow: hidden;
  padding: 0;
}

::v-deep .el-card__body {
  overflow: hidden;
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