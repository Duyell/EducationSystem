import axios from 'axios'

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
    return response.data
  },
  error => {
    // 网络错误或 HTTP 状态码非 2xx 时触发
    console.error('请求错误:', error)
    // 可以在这里统一弹出提示，例如：
    // ElMessage.error(error.message || '请求失败')
    return Promise.reject(error)
  }
)

export default service