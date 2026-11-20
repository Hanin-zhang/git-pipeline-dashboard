import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export function formatDuration(ms?: number | null): string {
  if (ms === undefined || ms === null || ms <= 0) return '—'
  const totalSeconds = Math.round(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60)
    return `${hours}时${minutes % 60}分`
  }
  if (minutes > 0) return `${minutes}分${seconds.toString().padStart(2, '0')}秒`
  return `${seconds}秒`
}

export function formatDurationShort(ms?: number | null): string {
  if (ms === undefined || ms === null || ms <= 0) return '—'
  const totalSeconds = Math.round(ms / 1000)
  if (totalSeconds < 60) return `${totalSeconds}s`
  const minutes = Math.floor(totalSeconds / 60)
  if (minutes < 60) return `${minutes}m${(totalSeconds % 60).toString().padStart(2, '0')}s`
  return `${Math.floor(minutes / 60)}h${(minutes % 60).toString().padStart(2, '0')}m`
}

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  return dayjs(value).format('MM-DD HH:mm:ss')
}

export function formatTime(value?: string | null): string {
  if (!value) return '—'
  return dayjs(value).format('HH:mm:ss')
}

export function fromNow(value?: string | null): string {
  if (!value) return '—'
  return dayjs(value).fromNow()
}

export function shortSha(sha?: string | null): string {
  return sha ? sha.slice(0, 7) : '—'
}

export function shortenMessage(message?: string | null, max = 60): string {
  if (!message) return '无提交信息'
  return message.length <= max ? message : `${message.slice(0, max)}…`
}

export type StatusTone = 'success' | 'failure' | 'running' | 'cancelled' | 'neutral'

export interface StatusMeta {
  label: string
  tone: StatusTone
  className: string
}

export function statusMeta(status?: string | null, conclusion?: string | null): StatusMeta {
  if (status === 'in_progress' || status === 'queued' || status === 'requested' || status === 'waiting') {
    const label = status === 'queued' || status === 'requested' || status === 'waiting' ? '排队中' : '运行中'
    return { label, tone: 'running', className: 'status-running' }
  }
  switch (conclusion) {
    case 'success':
      return { label: '成功', tone: 'success', className: 'status-success' }
    case 'failure':
      return { label: '失败', tone: 'failure', className: 'status-failure' }
    case 'timed_out':
      return { label: '超时', tone: 'failure', className: 'status-failure' }
    case 'startup_failure':
      return { label: '启动失败', tone: 'failure', className: 'status-failure' }
    case 'cancelled':
      return { label: '已取消', tone: 'cancelled', className: 'status-cancelled' }
    case 'skipped':
      return { label: '已跳过', tone: 'cancelled', className: 'status-cancelled' }
    default:
      return { label: status === 'completed' ? '已完成' : '未知', tone: 'neutral', className: 'status-neutral' }
  }
}

export function percent(value?: number | null, digits = 1): string {
  if (value === undefined || value === null) return '—'
  return `${value.toFixed(digits)}%`
}

/** 状态语义色标：DAG、节点、统计卡共用一套，避免各处各写一份判断。 */
export type Tone = 'success' | 'failure' | 'running' | 'queued' | 'skipped' | 'cancelled' | 'neutral'

export function toneOf(status?: string | null, conclusion?: string | null): Tone {
  if (conclusion === 'success') return 'success'
  if (conclusion === 'failure' || conclusion === 'timed_out' || conclusion === 'startup_failure') return 'failure'
  if (conclusion === 'cancelled') return 'cancelled'
  if (conclusion === 'skipped' || conclusion === 'neutral') return 'skipped'
  if (status === 'in_progress') return 'running'
  if (status === 'queued' || status === 'requested' || status === 'waiting' || status === 'pending') return 'queued'
  return 'neutral'
}

export function toneLabel(tone: Tone): string {
  switch (tone) {
    case 'success':
      return '成功'
    case 'failure':
      return '失败'
    case 'running':
      return '运行中'
    case 'queued':
      return '排队中'
    case 'skipped':
      return '已跳过'
    case 'cancelled':
      return '已取消'
    default:
      return '未开始'
  }
}

export function stepDuration(step: { startedAt?: string; completedAt?: string }): number | undefined {
  if (!step.startedAt || !step.completedAt) return undefined
  const start = new Date(step.startedAt).getTime()
  const end = new Date(step.completedAt).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) return undefined
  return Math.max(0, end - start)
}

/**
 * 复制到剪贴板。
 *
 * 注意：`navigator.clipboard` 只在安全上下文（HTTPS 或 localhost）可用。
 * 用 HTTP + IP 访问时它根本不存在，所以必须回退到 `execCommand('copy')`，
 * 否则点击复制只会弹「浏览器拒绝」。
 */
export async function copyText(text?: string | null): Promise<boolean> {
  if (!text) return false

  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 继续走兜底方案
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    // 放在视口外，避免页面跳动
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    area.setSelectionRange(0, text.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}
