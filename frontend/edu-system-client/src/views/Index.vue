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
          >
            <el-menu-item index="1">首页</el-menu-item>
            <el-menu-item index="2">课程</el-menu-item>
            <el-menu-item index="3">成绩</el-menu-item>
            <el-menu-item index="4">教评</el-menu-item>
          </el-menu>
        </div>
  
        <div class="header-right">
          <!-- 搜索图标 -->
          <el-icon :size="24" color="#fff" class="search-icon"><Search /></el-icon>
  
          
          <!-- 用户信息下拉（核心需求） -->
          <el-dropdown class="user-dropdown" @command="handleUserCommand">
            <span class="user-info">
              <el-avatar :size="40" :src="userAvatar" />
              <span class="user-name">欢迎{{ userName }}</span>
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
          <h1 class="welcome-title">HI，欢迎进入您的教学空间</h1>
          <p class="security-tip">
            （本平台为互联网非涉密平台，严禁处理、传输国家秘密）
          </p>
          <div class="date-info">
            今天是 {{ currentDate }} 星期{{ weekDay }} &nbsp;&nbsp; 2025-2026-2 &nbsp;&nbsp; 第6周
          </div>
        </div>
  
        <!-- 后续可添加数据统计、功能卡片等 -->
        <div class="content-area">
          <el-row :gutter="20">
            <el-col :span="6">
              <el-card class="stat-card">
                <div class="stat-title">学生总数</div>
                <div class="stat-number">{{studentTotal}}</div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card class="stat-card">
                <div class="stat-title">课程总数</div>
                <div class="stat-number">56</div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card class="stat-card">
                <div class="stat-title">教师总数</div>
                <div class="stat-number">{{teacherTotal}}</div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card class="stat-card">
                <div class="stat-title">今日签到</div>
                <div class="stat-number">987</div>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </el-main>
    </div>
  </template>
  
  <script setup lang="ts">
  import { ref, computed, onMounted } from 'vue'
  import { useRouter } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { Search, ArrowDown , House} from '@element-plus/icons-vue'
  import axios from '@/utils/request'
  
  const router = useRouter()
  const studentTotal = ref(0)
  const teacherTotal = ref(0)
  onMounted(()=>{
    axios.get('/api/home/statistics').then(res =>{
      studentTotal.value = res.data.totalStudents
      teacherTotal.value = res.data.totalTeachers
    })
  })


  // 1. 用户信息（可从后端接口获取，这里模拟）
  const userName = ref('') // 可替换为登录返回的用户名
  const userAvatar = ref('https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png') // 默认头像
  
  // 2. 导航栏激活项
  const activeMenu = ref('1')
  
  // 3. 日期计算
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
  
  // 4. 用户下拉菜单事件
  const handleUserCommand = (command: string) => {
    switch (command) {
      case 'changePwd':
        ElMessage.info('跳转到修改密码页面')
        // router.push('/change-pwd') // 后续可配置路由
        break
      case 'logout':
        ElMessageBox.confirm('确定要退出登录吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        }).then( async () => {
          try {
            await axios.post('/api/logout')
          } catch (e) {
            
          }
          // 清除token
          sessionStorage.removeItem('token')
          ElMessage.success('退出成功')
          
          // 跳回登录页
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
  
  .student-group {
    color: #fff;
    font-size: 16px;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 5px;
    padding: 8px 12px;
    background: rgba(255, 255, 255, 0.1);
    border-radius: 20px;
  }
  
  /* 用户信息下拉 */
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