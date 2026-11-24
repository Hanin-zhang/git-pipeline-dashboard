export interface MetricWindow {
  total: number
  success: number
  failed: number
  running: number
  successRate: number
  avgDurationMs: number
}

export interface DashboardSummary {
  today: MetricWindow
  yesterday: MetricWindow
  activeRuns: number
  repoCount: number
  queueDepth: number
  liveConnections: number
  deliveriesToday: number
  lastEventAt?: string
  generatedAt: string
}

export interface TrendPoint {
  key: string
  label: string
  total: number
  success: number
  failed: number
  successRate: number
  avgDurationMs: number
}

export interface WorkflowHealth {
  workflowName: string
  total: number
  failed: number
  failureRate: number
  avgDurationMs: number
}

export interface StageBrief {
  id: number
  name: string
  status: string
  conclusion?: string
  durationMs?: number
}

export interface RunSummary {
  id: number
  externalRunId: number
  repoFullName: string
  workflowName: string
  runNumber?: number
  branch?: string
  eventName?: string
  status: string
  conclusion?: string
  actor?: string
  commitSha?: string
  commitMessage?: string
  commitAuthor?: string
  htmlUrl?: string
  startedAt?: string
  completedAt?: string
  durationMs?: number
  /** Job-level summary, used to draw the mini pipeline in the list. */
  stages?: StageBrief[]
}

export interface RunStep {
  number: number
  name: string
  status: string
  conclusion?: string
  startedAt?: string
  completedAt?: string
}

export interface RunJob {
  id: number
  name: string
  status: string
  conclusion?: string
  startedAt?: string
  completedAt?: string
  durationMs?: number
  runnerName?: string
  htmlUrl?: string
  /**
   * 真实 GitHub Actions needs。
   * `null` = 数据源未提供依赖关系；`[]` = 已知无依赖（根节点）。
   */
  needs?: string[] | null
  steps: RunStep[]
}

export interface RunDetail extends RunSummary {
  createdAt?: string
  updatedAt?: string
  deliveryId?: string
  /** 失败原因类别，未归类时为 null */
  failureClass?: string | null
  /** 摘要来自哪个 job 的日志 */
  failureJobName?: string | null
  /** 一句话结论，详情页红底高亮展示 */
  failureSummary?: string | null
  /** 关键错误块（不含上下文） */
  failureHighlights?: FailureHighlight[]
  /** true = 该摘要由旧版解析规则生成，仅供参考 */
  failureStale?: boolean
  jobs: RunJob[]
}

export interface FailureHighlight {
  title: string
  detail: string
  lineNo: number
  /** 错误块结束行；等于 lineNo 表示只有一行 */
  endLine?: number
  occurrences: number
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface RunFilters {
  repos: string[]
  branches: string[]
  statuses: { value: string; label: string }[]
}

export interface Repo {
  id: number
  owner: string
  name: string
  fullName: string
  defaultBranch?: string
  enabled: boolean
  secret?: string
  createdAt?: string
}

export interface Notification {
  channel: string
  label: string
  webhookUrl?: string
  enabled: boolean
  notifyOn: string
  updatedAt?: string
}

export interface RuntimeInfo {
  serverTime: string
  zone: string
  queueDepth: number
  consumers: number
  processed: number
  failed: number
  deliveries: Record<string, number>
  liveConnections: number
}

export interface Settings {
  webhookPath: string
  webhookUrl: string
  secretHint?: string
  repos: Repo[]
  notifications: Notification[]
  runtime: RuntimeInfo
}

export interface Session {
  username: string
  displayName?: string
  role: string
}

/** Payload pushed by the backend on the SSE channel. */
/** 失败原因分布：百分比以「已归类」为分母 */
export interface FailureReasonStats {
  totalFailed: number
  classified: number
  unclassified: number
  /** 是否配置了 GitHub Token（决定能否自动归类） */
  known: boolean
  reasons: Array<{
    failureClass: string
    label: string
    count: number
    percent: number
  }>
}

/** 日志摘要：后端把原始日志结构化后的结果 */
export interface LogDigest {
  verdict: 'success' | 'failure' | 'unknown'
  failureClass: string
  headline: string
  stats: {
    totalLines: number
    errorLines: number
    collapsedLines: number
    blockCount: number
  }
  highlights: Array<{
    severity: string
    category: string
    title: string
    detail: string
    lineNo: number
    endLine?: number
    occurrences: number
    context: string[]
  }>
}

export interface RunEvent {
  type: string
  runId: number
  repoFullName: string
  workflowName: string
  branch?: string
  status: string
  conclusion?: string
  actor?: string
  commitMessage?: string
  durationMs?: number
  htmlUrl?: string
  at: string
}

export interface StreamEvent {
  event: string
  data: RunEvent
  receivedAt: number
}

/** 服务端能力开关：决定页面上哪些操作按钮可以点亮 */
export interface Capabilities {
  githubActions: boolean
  githubApiUrl: string
  enabledActions: string[]
  disabledReasons: string[]
}

export interface RunActionResult {
  runId: number
  action: string
  message: string
  githubRunUrl?: string
}

/** 设置页门禁状态：locked=true 时只渲染密码框 */
export interface GateState {
  locked: boolean
  expiresAt?: string | null
  ttlMinutes: number
}
