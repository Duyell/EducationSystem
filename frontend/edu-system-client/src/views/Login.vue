<template>
  <div class="login-page">
    <el-card class="login-card" shadow="hover">
      <template #header>
        <span class="login-title">用户登录</span>
      </template>
      <el-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        label-width="72px"
        @submit.prevent="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="loginForm.username" placeholder="请输入用户名" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            show-password
            clearable
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="loginForm.remember">记住我</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" native-type="submit" style="width: 100%">
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import type { FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'

const loginFormRef = ref<FormInstance>()
const loading = ref(false)
const router = useRouter()

const loginForm = reactive({
  username: '',
  password: '',
  remember: false
})

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  try {
    await loginFormRef.value.validate()
  } catch {
    return
  }

  loading.value = true

  try {
    const res = await axios.post('/api/login', {
      username: loginForm.username,
      password: loginForm.password
    })

    const token = res.data.token
    sessionStorage.setItem('token', token)
    ElMessage.success('登录成功')

    router.push('/Index')
  } catch (err: any) {
    ElMessage.error('登录失败：' + (err.response?.data || '用户名或密码错误'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(145deg, #f0f4f8 0%, #e2e8f0 100%);
}

.login-card {
  width: 100%;
  max-width: 400px;
}

.login-title {
  font-size: 1.125rem;
  font-weight: 600;
}
</style>
