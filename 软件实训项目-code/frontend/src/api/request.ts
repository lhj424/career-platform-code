import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'

// Axios 实例 - 统一请求封装
const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// 防止 401 跳转风暴（3 秒内不重复处理）
let last401Handled = 0

/**
 * 判断当前 401 是否应该静默忽略
 * - 登录成功后 3 秒内的首次 401：可能是首页并发请求的时序竞争，完全静默
 * - 已在登录页：不再重复跳转/弹窗
 * - 3 秒内已处理过：防抖
 */
function shouldSuppress401(): boolean {
  const isLoginPage = window.location.hash.includes('/login')
  if (isLoginPage) return true
  const justLoggedIn = sessionStorage.getItem('just_logged_in')
  if (justLoggedIn) {
    sessionStorage.removeItem('just_logged_in')
    console.warn('[Auth] 登录后首次 401，静默忽略（防止并发请求弹多遍）')
    return true
  }
  const now = Date.now()
  if (now - last401Handled < 3000) return true
  last401Handled = now
  return false
}

/** 安全跳转登录页：清除所有状态并跳转 */
function safeRedirectToLogin() {
  // 同时清除 localStorage 和 Pinia 状态
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  localStorage.removeItem('userId')
  sessionStorage.removeItem('role')
  try {
    const userStore = useUserStore()
    userStore.token = ''
    userStore.username = ''
    userStore.role = ''
    userStore.userId = 0
  } catch { /* store 可能未初始化 */ }
  router.push('/login')
}

// 请求拦截器：添加 JWT Token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一错误处理
request.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data
    if (res.code === 200) return res
    // 业务错误：MODE_SWITCH_REQUIRED 由组件自行处理，不弹 Toast
    if (res.message?.includes('MODE_SWITCH_REQUIRED')) return Promise.reject(new Error(res.message))
    ElMessage.error(res.message || '请求失败')
    if (res.code === 401 && !shouldSuppress401()) {
      safeRedirectToLogin()
    }
    return Promise.reject(new Error(res.message))
  },
  (error) => {
    const serverMsg = error.response?.data?.message
    if (error.response?.status === 401) {
      if (!shouldSuppress401()) {
        ElMessage.error(serverMsg || '认证失败，请重新登录')
        safeRedirectToLogin()
      }
    } else if (error.response?.status === 403) {
      ElMessage.error(serverMsg || '权限不足')
    } else {
      ElMessage.error(serverMsg || '网络错误，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default request
