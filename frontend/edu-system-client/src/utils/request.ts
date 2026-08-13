import axios from 'axios'
import { ElMessage } from 'element-plus'

// 创建 axios 实例
const service = axios.create({

})



// 请求拦截器：每次发请求，自动带上 token
service.interceptors.request.use(config => {
  // 从 sessionStorage 拿 token
  const token = sessionStorage.getItem("token")

  if (token) {
    // 把 token 放到请求头（后端就是从这里取）
    config.headers.token = token
  }
  return config
})

// 响应拦截器：自动解包 + 统一错误处理
service.interceptors.response.use(
  response => {
    //直接返回 response.data，业务代码拿到就是 { code, msg, data }
    const res = response.data
    // 业务错误：后端返回 HTTP 200 但 code != '200'（如"该课程名额已满"）
    if (res && typeof res === 'object' && 'code' in res && res.code !== '200') {
      ElMessage.error(res.msg || '操作失败')
      return Promise.reject(new Error(res.msg || '操作失败'))
    }
    return res
  },
  error => {
    // 如果是 401（未登录/ token 过期），跳转到登录页
    if (error.response?.status === 401) {
      sessionStorage.removeItem('token')
      sessionStorage.removeItem('user')
      window.location.href = '/login'
      return Promise.reject(error)
    }
    // 403：登录了但无权限（角色/归属校验失败）
    if (error.response?.status === 403) {
      ElMessage.error(error.response?.data?.msg || '无权限执行该操作')
      return Promise.reject(error)
    }
    // 网络错误或其他 HTTP 状态码
    ElMessage.error(error.response?.data?.msg || error.message || '请求失败')
    return Promise.reject(error)
  }
)

export default service
