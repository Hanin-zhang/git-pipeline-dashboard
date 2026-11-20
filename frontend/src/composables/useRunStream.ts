import { onScopeDispose, ref, shallowRef } from 'vue'
import { API_BASE } from '@/api/client'
import type { RunEvent, StreamEvent } from '@/api/types'

/**
 * One shared SSE connection for the whole app: the dashboard and the pipelines list both
 * subscribe to it, but the browser only holds a single EventSource.
 *
 * Reconnect uses exponential backoff (1s -> 15s) and keeps the last events so a remount does
 * not lose the feed. Events are capped in memory: this is a live ticker, not a log archive.
 */
const MAX_EVENTS = 40
const MAX_BACKOFF_MS = 15_000

const connected = ref(false)
const reconnecting = ref(false)
const events = shallowRef<StreamEvent[]>([])
const lastEventAt = ref<number | null>(null)

let source: EventSource | null = null
let attempt = 0
let timer: number | null = null
let subscribers = 0

function push(event: string, data: RunEvent) {
  const enriched: StreamEvent = { event, data, receivedAt: Date.now() }
  events.value = [enriched, ...events.value].slice(0, MAX_EVENTS)
  lastEventAt.value = enriched.receivedAt
}

/**
 * 每个标签页一个稳定标识：存在 sessionStorage 里，刷新不变、关掉标签页自动消失。
 * 服务端用它做连接键，同一标签页重连时顶掉旧连接，连接数不会越刷越多。
 */
function tabId(): string {
  const KEY = 'gl_tab_id'
  try {
    const existing = sessionStorage.getItem(KEY)
    if (existing) return existing
    const created = Math.random().toString(36).slice(2, 10)
    sessionStorage.setItem(KEY, created)
    return created
  } catch {
    // 隐私模式下 sessionStorage 可能不可用，退回一次性 id
    return Math.random().toString(36).slice(2, 10)
  }
}

function open() {
  if (source) return
  source = new EventSource(`${API_BASE}/stream?cid=${tabId()}`)

  source.addEventListener('ready', () => {
    connected.value = true
    reconnecting.value = false
    attempt = 0
  })

  const names = ['run.started', 'run.updated', 'run.completed', 'run.failed']
  names.forEach((name) => {
    source!.addEventListener(name, (raw) => {
      try {
        const payload = JSON.parse((raw as MessageEvent).data) as RunEvent
        push(name, { ...payload, type: name })
      } catch {
        /* ignore a malformed frame, the stream stays alive */
      }
    })
  })

  source.onerror = () => {
    connected.value = false
    close()
    if (subscribers === 0) return
    reconnecting.value = true
    const delay = Math.min(1000 * Math.pow(2, attempt), MAX_BACKOFF_MS)
    attempt += 1
    timer = window.setTimeout(open, delay)
  }
}

function close() {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
  if (source) {
    source.close()
    source = null
  }
  connected.value = false
}

export function useRunStream() {
  subscribers += 1
  open()

  onScopeDispose(() => {
    subscribers -= 1
    if (subscribers === 0) {
      close()
      reconnecting.value = false
    }
  })

  return { connected, reconnecting, events, lastEventAt }
}
