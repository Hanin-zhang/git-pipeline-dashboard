/**
 * Typed API client.
 *
 * - base URL comes from VITE_API_BASE (never hard coded per endpoint)
 * - cookies are sent, because the session lives in an httpOnly cookie (never localStorage)
 * - 5xx and network errors are retried up to 3 times with backoff; 4xx never retried
 * - every failure is mapped to an ApiError with a human readable Chinese message
 */

export interface ApiErrorBody {
  error?: string
  message?: string
  requestId?: string
  details?: Record<string, string>
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string
  readonly details?: Record<string, string>

  constructor(status: number, code: string, message: string, requestId?: string, details?: Record<string, string>) {
    super(message)
    this.status = status
    this.code = code
    this.requestId = requestId
    this.details = details
  }

  get isAuthError(): boolean {
    return this.status === 401
  }

  get isRetryable(): boolean {
    return this.status === 0 || this.status >= 500
  }
}

const BASE = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '')
/** Same base as the REST client; the SSE endpoint hangs off it. */
export const API_BASE = BASE
const TIMEOUT_MS = 15_000
const MAX_RETRIES = 3

type Listener = (error: ApiError | null) => void
const errorListeners = new Set<Listener>()

/** Lets the shell show a global "offline / backend down" banner. */
export function onApiError(listener: Listener): () => void {
  errorListeners.add(listener)
  return () => errorListeners.delete(listener)
}

function notify(error: ApiError | null) {
  errorListeners.forEach((l) => l(error))
}

function friendlyMessage(status: number, body: ApiErrorBody | null, fallback: string): string {
  if (body?.message) return body.message
  if (status === 0) return '无法连接后端服务，请确认服务已启动'
  if (status === 400) return '请求参数有误'
  if (status === 401) return '登录已过期，请重新登录'
  if (status === 403) return '没有权限访问该资源'
  if (status === 404) return '资源不存在'
  if (status === 409) return '操作冲突，请刷新后重试'
  if (status === 413) return '请求内容过大'
  if (status === 429) return '操作过于频繁，请稍后再试'
  if (status >= 500) return '服务暂时不可用，请稍后重试'
  return fallback
}

async function once<T>(path: string, init: RequestInit): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), TIMEOUT_MS)

  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
      ...init,
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...(init.headers || {}),
      },
      signal: controller.signal,
    })
  } catch (e) {
    window.clearTimeout(timer)
    const aborted = (e as Error).name === 'AbortError'
    throw new ApiError(0, aborted ? 'TIMEOUT' : 'NETWORK_ERROR',
      aborted ? '请求超时，请稍后重试' : '无法连接后端服务，请确认服务已启动')
  } finally {
    window.clearTimeout(timer)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  const body = text ? safeJson(text) : null

  if (!response.ok) {
    throw new ApiError(
      response.status,
      (body as ApiErrorBody)?.error || 'ERROR',
      friendlyMessage(response.status, body as ApiErrorBody, `请求失败 (${response.status})`),
      (body as ApiErrorBody)?.requestId,
      (body as ApiErrorBody)?.details,
    )
  }
  return body as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let lastError: ApiError | undefined
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    try {
      const result = await once<T>(path, init)
      notify(null)
      return result
    } catch (e) {
      const error = e as ApiError
      lastError = error
      if (!(error instanceof ApiError) || !error.isRetryable || attempt === MAX_RETRIES) {
        break
      }
      // 200ms, 600ms, 1200ms
      await new Promise((r) => setTimeout(r, 200 * Math.pow(3, attempt)))
    }
  }
  notify(lastError!)
  throw lastError!
}

export const api = {
  get: <T>(path: string) => request<T>(path, { method: 'GET' }),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}` !== '') {
      search.set(key, String(value))
    }
  })
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}
