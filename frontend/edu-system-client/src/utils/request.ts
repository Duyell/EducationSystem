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

export default service