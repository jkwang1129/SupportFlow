import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearToken, getToken, getTenantCode } from '../utils/auth'
import { markReported } from '../utils/message'
import type { ApiResponse } from '../types'
import { TRACE_HEADER, newTraceId, setLastTraceId, withTraceId } from '../utils/trace'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

/** 请求配置：silent=true 时失败不弹全局提示（用于权限探测等可预期的失败） */
export interface RequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

let unauthorizedHandling = false

/** 登录失效统一处理：专业提示 → 清空登录态 → 跳转登录页 */
function handleUnauthorized(message?: string) {
  if (unauthorizedHandling) return
  unauthorizedHandling = true
  ElMessageBox.alert(
    message === '未认证或登录已过期' ? '当前登录状态已过期或账号已在其他设备登录，请重新登录后继续使用。' : (message || '登录状态已失效，请重新登录'),
    '登录状态已失效',
    {
      type: 'warning',
      confirmButtonText: '重新登录',
      closeOnClickModal: false,
      showClose: false,
    },
  )
    .finally(() => {
      clearToken()
      localStorage.removeItem('supportflow_admin_tenant')
      localStorage.removeItem('supportflow_onboard_done')
      // 注意：不清「开通引导卡片」的关闭标记——它是按租户存的长期偏好，
      // 登录态过期后重新登录不该把它再弹回来（那正是"关掉过一会儿又出现"的原因）。
      window.location.href = '/login'
    })
}

// 请求拦截：携带 token 与租户上下文
service.interceptors.request.use((config) => {
  const token = getToken()
  const tenantCode = getTenantCode()
  // 调用链号：没有就现生成（网关也会兜底生成，但前端先带上才能"请求发出前就有号"）
  const traceId = (config.headers?.[TRACE_HEADER] as string) || newTraceId()
  config.headers[TRACE_HEADER] = traceId
  setLastTraceId(traceId)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  if (tenantCode) {
    config.headers['X-Tenant-Code'] = tenantCode
  }
  return config
})

// 响应拦截（成功分支）：以服务端回写的号为准——网关可能替我们生成了一个
service.interceptors.response.use((response) => {
  setLastTraceId(response.headers?.[TRACE_HEADER.toLowerCase()] as string | undefined)
  return response
})

// 响应拦截：仅处理 HTTP 层错误
service.interceptors.response.use(
  (response) => response,
  (error) => {
    setLastTraceId(error?.response?.headers?.[TRACE_HEADER.toLowerCase()])
    if (error?.response?.status === 401) {
      handleUnauthorized()
      // 登录失效已经用弹窗说清楚了，页面 catch 不必再补一条红色提示
      markReported(error)
      return Promise.reject(error)
    }
    const raw = error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    // HTTP 404 也补上"该由哪个服务提供"：这条以前只在业务码 40400 那条分支里拼，
    // 结果服务没重启时（网关/服务直接回 404 状态码）用户只看到一句"资源不存在"，
    // 还得靠猜是哪个服务旧了（可观测页第一次上线就撞上这个）。
    const missing = error?.response?.status === 404 || error?.response?.data?.code === 40400
    const detailed = missing ? `${raw}（${serviceHintOf(error?.config?.url || '')}）` : raw
    // 错误提示带上请求号：用户复制给运维就能查到这次请求在 AI 侧花了多少、慢在哪
    const message = error?.response?.status ? withTraceId(detailed) : detailed
    if (!(error?.config as RequestConfig | undefined)?.silent) {
      ElMessage.error(message)
      markReported(error)
    }
    return Promise.reject(error)
  },
)

/** 统一请求方法：自动解包业务码，成功返回 data */
export async function request<T = unknown>(config: RequestConfig): Promise<T> {
  const response = await service.request<ApiResponse<T>>(config)
  const body = response.data
  if (body.code !== 0) {
    if (body.code === 40100 || body.code === 40110) {
      handleUnauthorized(body.message)
      const unauthorized = new Error(body.message || 'request failed')
      markReported(unauthorized)
      throw unauthorized
    }
    // 40400「资源不存在」几乎只有一个原因：这个接口在目标服务里还没有 —— 也就是
    // 代码改了但服务没重启（踩过两次：tenant-service 的 /active、user-service 的平台账号接口）。
    // 网关/服务返回的原始提示太笼统，这里补一句"该找哪个服务"，别让人去猜精度或者数据。
    const message = body.code === 40400
      ? `${body.message || '资源不存在'}（${serviceHintOf(config.url || '')}）`
      : body.message || '请求失败'
    const failure = new Error(message)
    if (!config.silent) {
      ElMessage.error(message)
      // 打标记：页面 catch 里用 errorMessage() 就不会把同一条消息再弹一遍
      markReported(failure)
    }
    throw failure
  }
  return body.data
}

/**
 * 按接口前缀指出"这个接口该由哪个服务提供"。
 *
 * <p>只在 40400 时拼进提示里：正常业务报错不该带这些噪音，而 404 恰好是最需要
 * 一句"去重启哪个服务"的场景。</p>
 */
function serviceHintOf(url: string): string {
  if (url.startsWith('/user/')) return '由 user-service(9091) 提供，改了代码记得重启它'
  if (url.startsWith('/tenant/')) return '由 tenant-service(9092) 提供，改了代码记得重启它'
  if (url.startsWith('/billing/')) return '由 billing-service(9094) 提供，改了代码记得重启它'
  if (url.startsWith('/realtime/')) return '由 realtime-service(9096) 提供，改了代码记得重启它'
  if (url.startsWith('/ops/')) return '由 ops-service(9095) 提供，改了代码记得重启它'
  if (url.startsWith('/customer/') || url.startsWith('/platform/')) {
    return '由 customer-service(9093) 提供，改了代码记得重启它'
  }
  return '接口不存在：先确认对应服务是否已重启'
}

export default service
