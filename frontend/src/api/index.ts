import { api, query } from './client'
import type {
  Capabilities,
  DashboardSummary,
  FailureReasonStats,
  GateState,
  LogDigest,
  Notification,
  PageResponse,
  Repo,
  RunActionResult,
  RunDetail,
  RunEvent,
  RunFilters,
  RunSummary,
  Session,
  Settings,
  TrendPoint,
  WorkflowHealth,
} from './types'

export const dashboardApi = {
  summary: (params: { repo?: string; branch?: string }) =>
    api.get<DashboardSummary>(`/dashboard/summary${query(params)}`),
  trend: (params: { days?: number; repo?: string; branch?: string; granularity?: 'day' | 'week' }) =>
    api.get<TrendPoint[]>(`/dashboard/trend${query(params)}`),
  workflows: (params: { days?: number; repo?: string; branch?: string }) =>
    api.get<WorkflowHealth[]>(`/dashboard/workflows${query(params)}`),
  failureReasons: (params: { days?: number; repo?: string; branch?: string }) =>
    api.get<FailureReasonStats>(`/dashboard/failure-reasons${query(params)}`),
  recent: (limit = 12) => api.get<RunSummary[]>(`/dashboard/recent${query({ limit })}`),
}

export const runsApi = {
  list: (params: {
    page?: number
    size?: number
    status?: string
    repo?: string
    branch?: string
    q?: string
    workflow?: string
    from?: string
    to?: string
  }) => api.get<PageResponse<RunSummary>>(`/runs${query(params)}`),
  detail: (id: number | string) => api.get<RunDetail>(`/runs/${id}`),
  /** 真实事件不带 jobs，这里按需从 Actions API 拉一次 */
  syncJobs: (id: number) =>
    api.post<{ runId: number; jobs: number; message: string }>(`/runs/${id}/sync-jobs`),
  /** 单个 job 的执行日志（纯文本） */
  jobLogs: (runId: number, jobId: number) => api.getText(`/runs/${runId}/jobs/${jobId}/logs`),
  /** 同一次日志的结构化摘要 */
  jobLogSummary: (runId: number, jobId: number) =>
    api.get<LogDigest>(`/runs/${runId}/jobs/${jobId}/logs/summary`),
  filters: () => api.get<RunFilters>('/runs/filters'),
  rerun: (id: number) => api.post<RunActionResult>(`/runs/${id}/rerun`),
  cancel: (id: number) => api.post<RunActionResult>(`/runs/${id}/cancel`),
}

export const capabilitiesApi = {
  get: () => api.get<Capabilities>('/capabilities'),
}

export const reposApi = {
  list: () => api.get<Repo[]>('/repos'),
  create: (body: { fullName: string; defaultBranch?: string; secret?: string }) =>
    api.post<Repo>('/repos', body),
  update: (id: number, body: { defaultBranch?: string; enabled?: boolean; secret?: string }) =>
    api.put<Repo>(`/repos/${id}`, body),
  remove: (id: number) => api.del<void>(`/repos/${id}`),
}

export const settingsApi = {
  get: () => api.get<Settings>('/settings'),
  /** 门禁：设置页需要额外一次密码验证 */
  state: () => api.get<GateState>('/settings/state'),
  unlock: (password: string) => api.post<GateState>('/settings/unlock', { password }),
  lock: () => api.post<GateState>('/settings/lock'),
  updateNotification: (channel: string, body: { webhookUrl?: string; enabled?: boolean; notifyOn?: string }) =>
    api.put<Notification>(`/settings/notifications/${channel}`, body),
}

export const authApi = {
  login: (body: { username: string; password: string }) => api.post<Session>('/auth/login', body),
  logout: () => api.post<void>('/auth/logout'),
  me: () => api.get<Session>('/auth/me'),
}

/** Kept for the "replay last event" dev affordance on the dashboard. */
export type { RunEvent }
