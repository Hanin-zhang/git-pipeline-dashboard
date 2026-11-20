<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { RunJob } from '@/api/types'
import { formatDurationShort, toneLabel, toneOf, type Tone } from '@/utils/format'

/**
 * 流水线 DAG。
 *
 * 依赖来源：后端 run_job.needs（GitHub Actions 的 needs）。
 * - 有依赖数据：按「最长路径」分层，边只画 needs 指向的关系，同一层即并行。
 * - 没有依赖数据（真实 workflow_run 事件不携带 needs）：降级为按执行时间分列，
 *   并在图例处明确标注「未提供依赖信息」，绝不按名称猜测依赖。
 */
const props = defineProps<{
  jobs: RunJob[]
  selectedId?: number | null
}>()

const emit = defineEmits<{ select: [jobId: number] }>()

const CARD_W = 232
const CARD_H = 88
const GAP_X = 76
const GAP_Y = 22
const PAD = 18
const MIN_ZOOM = 0.55
const MAX_ZOOM = 1.6

function ts(value?: string | null): number | null {
  if (!value) return null
  const t = new Date(value).getTime()
  return Number.isNaN(t) ? null : t
}

const duplicateNames = computed(() => {
  const seen = new Set<string>()
  for (const job of props.jobs) {
    const name = job.name ?? ''
    if (seen.has(name)) return true
    seen.add(name)
  }
  return false
})

/**
 * 是否存在真实的依赖数据：只要有一个 job 的 needs 不是 null，
 * 就说明数据源提供了依赖信息（空数组代表「已知是根节点」，同样算提供了数据）。
 */
const hasRealDeps = computed(
  () => props.jobs.some((job) => job.needs !== null && job.needs !== undefined) && !duplicateNames.value,
)

/** 按 needs 计算层级：depth = 1 + max(depth(needs))，带环保护。 */
function layersFromNeeds(): Map<number, number> {
  const byName = new Map<string, RunJob>()
  props.jobs.forEach((job) => byName.set(job.name ?? '', job))

  const depth = new Map<number, number>()
  const visiting = new Set<number>()

  const resolve = (job: RunJob): number => {
    const cached = depth.get(job.id)
    if (cached !== undefined) return cached
    if (visiting.has(job.id)) return 0 // 环：当成根节点，避免死循环
    visiting.add(job.id)
    const needs = (job.needs ?? []).map((name) => byName.get(name)).filter((j): j is RunJob => !!j)
    const value = needs.length === 0 ? 0 : 1 + Math.max(...needs.map(resolve))
    visiting.delete(job.id)
    depth.set(job.id, value)
    return value
  }

  props.jobs.forEach(resolve)
  return depth
}

/** 降级布局：开始时间早于当前列结束时间 → 同列并行，否则新开一列。 */
function layersFromTimeline(): Map<number, number> {
  const sorted = [...props.jobs].sort((a, b) => {
    const ta = ts(a.startedAt) ?? Number.MAX_SAFE_INTEGER
    const tb = ts(b.startedAt) ?? Number.MAX_SAFE_INTEGER
    return ta - tb
  })

  const result = new Map<number, number>()
  const columnEnd: number[] = []

  for (const job of sorted) {
    const start = ts(job.startedAt)
    const end = ts(job.completedAt) ?? (start !== null ? start + (job.durationMs ?? 0) : null)
    const lastIndex = columnEnd.length - 1

    if (lastIndex === -1 || start === null || start >= columnEnd[lastIndex]) {
      columnEnd.push(end ?? start ?? 0)
      result.set(job.id, columnEnd.length - 1)
    } else {
      result.set(job.id, lastIndex)
      columnEnd[lastIndex] = Math.max(columnEnd[lastIndex], end ?? start ?? 0)
    }
  }
  return result
}

interface Placed {
  job: RunJob
  x: number
  y: number
  layer: number
}

const layout = computed(() => {
  const layerOf = hasRealDeps.value ? layersFromNeeds() : layersFromTimeline()
  const grouped = new Map<number, RunJob[]>()
  props.jobs.forEach((job) => {
    const layer = layerOf.get(job.id) ?? 0
    const bucket = grouped.get(layer)
    if (bucket) bucket.push(job)
    else grouped.set(layer, [job])
  })

  const layerKeys = [...grouped.keys()].sort((a, b) => a - b)
  const maxRows = Math.max(1, ...layerKeys.map((key) => grouped.get(key)!.length))
  const fullHeight = maxRows * CARD_H + (maxRows - 1) * GAP_Y

  const nodes: Placed[] = []
  layerKeys.forEach((key, columnIndex) => {
    const jobsInLayer = [...grouped.get(key)!].sort((a, b) => {
      const ta = ts(a.startedAt) ?? Number.MAX_SAFE_INTEGER
      const tb = ts(b.startedAt) ?? Number.MAX_SAFE_INTEGER
      return ta === tb ? (a.name ?? '').localeCompare(b.name ?? '') : ta - tb
    })
    const height = jobsInLayer.length * CARD_H + (jobsInLayer.length - 1) * GAP_Y
    const offsetY = (fullHeight - height) / 2
    jobsInLayer.forEach((job, rowIndex) => {
      nodes.push({
        job,
        layer: columnIndex,
        x: PAD + columnIndex * (CARD_W + GAP_X),
        y: PAD + offsetY + rowIndex * (CARD_H + GAP_Y),
      })
    })
  })

  return { nodes, layerCount: layerKeys.length }
})

const nodes = computed(() => layout.value.nodes)

interface Edge {
  id: string
  d: string
  ports: { cx: number; cy: number }[]
  tone: Tone
}

const edges = computed<Edge[]>(() => {
  const list: Edge[] = []
  const byName = new Map<string, Placed>()
  nodes.value.forEach((node) => byName.set(node.job.name ?? '', node))

  const connect = (from: Placed, to: Placed) => {
    const sx = from.x + CARD_W
    const sy = from.y + CARD_H / 2
    const tx = to.x
    const ty = to.y + CARD_H / 2
    const mx = sx + GAP_X / 2
    list.push({
      id: `${from.job.id}-${to.job.id}`,
      d: `M ${sx} ${sy} H ${mx} V ${ty} H ${tx}`,
      ports: [
        { cx: sx, cy: sy },
        { cx: tx, cy: ty },
      ],
      tone: toneOf(to.job.status, to.job.conclusion),
    })
  }

  if (hasRealDeps.value) {
    nodes.value.forEach((node) => {
      ;(node.job.needs ?? []).forEach((need) => {
        const from = byName.get(need)
        if (from) connect(from, node)
      })
    })
  } else {
    // 降级：相邻层之间连线，仅表达执行先后，不声称是依赖
    const byLayer = new Map<number, Placed[]>()
    nodes.value.forEach((node) => {
      const bucket = byLayer.get(node.layer)
      if (bucket) bucket.push(node)
      else byLayer.set(node.layer, [node])
    })
    const keys = [...byLayer.keys()].sort((a, b) => a - b)
    for (let i = 0; i < keys.length - 1; i += 1) {
      byLayer.get(keys[i])!.forEach((from) => {
        byLayer.get(keys[i + 1])!.forEach((to) => connect(from, to))
      })
    }
  }
  return list
})

const canvasWidth = computed(() =>
  nodes.value.length ? Math.max(...nodes.value.map((n) => n.x + CARD_W)) + PAD : 0,
)
const canvasHeight = computed(() =>
  nodes.value.length ? Math.max(...nodes.value.map((n) => n.y + CARD_H)) + PAD : 0,
)

/* ---------------- 缩放与拖拽 ---------------- */

const zoom = ref(1)
const scroller = ref<HTMLDivElement | null>(null)

function setZoom(next: number) {
  zoom.value = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Number(next.toFixed(2))))
}

function onWheel(event: WheelEvent) {
  if (!event.ctrlKey && !event.metaKey) return
  event.preventDefault()
  setZoom(zoom.value + (event.deltaY > 0 ? -0.1 : 0.1))
}

interface DragState {
  x: number
  y: number
  left: number
  top: number
}
const dragging = ref(false)
let dragState: DragState | null = null

function onPointerDown(event: PointerEvent) {
  if (!scroller.value) return
  const target = event.target as HTMLElement
  if (target.closest('.dag-node')) return
  dragging.value = true
  dragState = {
    x: event.clientX,
    y: event.clientY,
    left: scroller.value.scrollLeft,
    top: scroller.value.scrollTop,
  }
}

function onPointerMove(event: PointerEvent) {
  if (!dragState || !scroller.value) return
  scroller.value.scrollLeft = dragState.left - (event.clientX - dragState.x)
  scroller.value.scrollTop = dragState.top - (event.clientY - dragState.y)
}

function onPointerUp() {
  dragging.value = false
  dragState = null
}

watch(
  () => props.selectedId,
  (id) => {
    if (!id || !scroller.value) return
    const node = nodes.value.find((n) => n.job.id === id)
    if (!node) return
    scroller.value.scrollTo({
      left: Math.max(0, (node.x - 24) * zoom.value),
      behavior: 'smooth',
    })
  },
)

const EDGE_COLORS: Record<string, string> = {
  success: '#b7eb8f',
  failure: '#ffa39e',
  running: '#91caff',
  cancelled: '#ffd591',
  queued: '#d9dce3',
  skipped: '#d9dce3',
  neutral: '#d9dce3',
}

const PORT_COLORS: Record<string, string> = {
  success: '#52c41a',
  failure: '#f5222d',
  running: '#1677ff',
  cancelled: '#fa8c16',
  queued: '#c9cdd4',
  skipped: '#c9cdd4',
  neutral: '#c9cdd4',
}

function tone(job: RunJob): Tone {
  return toneOf(job.status, job.conclusion)
}

/* ---------------- 运行中的实时耗时 ---------------- */

const now = ref(Date.now())
let ticker: number | null = null

const hasRunning = computed(() => props.jobs.some((job) => tone(job) === 'running'))

onMounted(() => {
  ticker = window.setInterval(() => {
    // 没有运行中的节点就不触发重渲染
    if (hasRunning.value) now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (ticker !== null) window.clearInterval(ticker)
})

/** 运行中的 job 用「现在 - 开始时间」实时累加，已结束的用落库的耗时 */
function durationOf(job: RunJob): number | undefined {
  if (tone(job) === 'running' && job.startedAt) {
    const started = new Date(job.startedAt).getTime()
    if (!Number.isNaN(started)) return Math.max(0, now.value - started)
  }
  return job.durationMs
}

function tooltipOf(job: RunJob): string {
  const parts = [
    `状态：${toneLabel(tone(job))}`,
    `耗时：${formatDurationShort(job.durationMs)}`,
    `Step：${job.steps?.length ?? 0} 个`,
  ]
  if (job.runnerName) parts.push(`Runner：${job.runnerName}`)
  if (job.needs === null || job.needs === undefined) {
    parts.push('依赖：未知')
  } else if (job.needs.length) {
    parts.push(`依赖：${job.needs.join('、')}`)
  } else {
    parts.push('依赖：无（根节点）')
  }
  if (job.startedAt) parts.push(`开始：${job.startedAt.replace('T', ' ').slice(5, 19)}`)
  return parts.join('\n')
}
</script>

<template>
  <div class="dag-wrap">
    <div
      ref="scroller"
      class="dag-scroll"
      :class="{ dragging }"
      @wheel="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointerleave="onPointerUp"
    >
      <div v-if="!nodes.length" class="empty">
        这次运行还没有 job 明细，无法绘制流水线图。
        <br />
        GitHub 的 workflow_run 事件不携带 jobs，需要在服务器配置 GITHUB_TOKEN 后同步一次。
      </div>
      <div
        v-else
        class="dag-holder"
        :style="{ width: `${canvasWidth * zoom}px`, height: `${canvasHeight * zoom}px` }"
      >
        <div
          class="dag-stage"
          :style="{
            width: `${canvasWidth}px`,
            height: `${canvasHeight}px`,
            transform: `scale(${zoom})`,
          }"
        >
          <svg class="dag-edges" :width="canvasWidth" :height="canvasHeight">
            <g v-for="edge in edges" :key="edge.id">
              <path
                :d="edge.d"
                fill="none"
                :stroke="EDGE_COLORS[edge.tone]"
                stroke-width="1.6"
              />
              <circle
                v-for="(port, index) in edge.ports"
                :key="index"
                :cx="port.cx"
                :cy="port.cy"
                r="3.5"
                :fill="PORT_COLORS[edge.tone]"
                stroke="#fff"
              />
            </g>
          </svg>

          <el-tooltip
            v-for="node in nodes"
            :key="node.job.id"
            placement="top"
            effect="dark"
            :show-after="220"
          >
            <template #content>
              <div style="white-space: pre-line; line-height: 1.7">{{ tooltipOf(node.job) }}</div>
            </template>
            <div
              class="dag-node"
              :class="[tone(node.job), { selected: selectedId === node.job.id }]"
              :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${CARD_W}px`, height: `${CARD_H}px` }"
              @click="emit('select', node.job.id)"
            >
              <span class="dag-bar" />
              <div class="dag-head">
                <span class="dag-dot" :class="{ spinning: tone(node.job) === 'running' }">
                  <svg v-if="tone(node.job) === 'running'" viewBox="0 0 20 20" class="spinner">
                    <circle cx="10" cy="10" r="7.4" fill="none" stroke="rgba(255,255,255,0.35)" stroke-width="2.4" />
                    <path
                      d="M10 2.6 A7.4 7.4 0 0 1 17.4 10"
                      fill="none"
                      stroke="#ffffff"
                      stroke-width="2.4"
                      stroke-linecap="round"
                    />
                  </svg>
                  <template v-else-if="tone(node.job) === 'success'">✓</template>
                  <template v-else-if="tone(node.job) === 'failure'">✕</template>
                  <template v-else-if="tone(node.job) === 'cancelled'">–</template>
                  <template v-else-if="tone(node.job) === 'skipped'">»</template>
                  <template v-else-if="tone(node.job) === 'queued'">·</template>
                  <template v-else>·</template>
                </span>
                <span class="dag-name">{{ node.job.name }}</span>
              </div>
              <div class="dag-status">
                {{ toneLabel(tone(node.job)) }}
                <span v-if="tone(node.job) === 'running'" class="live-hint">执行中</span>
              </div>
              <div class="dag-foot">
                <span class="dag-dur" :class="{ live: tone(node.job) === 'running' }">
                  {{ formatDurationShort(durationOf(node.job)) }}
                </span>
                <span class="dag-steps">{{ node.job.steps?.length ?? 0 }} 步</span>
              </div>
            </div>
          </el-tooltip>
        </div>
      </div>
    </div>

    <div v-if="nodes.length" class="dag-footer">
      <div class="dag-legend">
        <span><i class="lg success" />成功</span>
        <span><i class="lg failure" />失败</span>
        <span><i class="lg running" />运行中</span>
        <span><i class="lg queued" />排队</span>
        <span><i class="lg cancelled" />已取消</span>
        <span><i class="lg skipped" />跳过</span>
      </div>
      <div class="dag-tools">
        <span v-if="hasRealDeps" class="dag-source">按 needs 依赖分层</span>
        <span v-else class="dag-source warn">未提供依赖数据，按执行顺序展示</span>
        <el-button text size="small" @click="setZoom(zoom - 0.1)">−</el-button>
        <span class="dag-zoom">{{ Math.round(zoom * 100) }}%</span>
        <el-button text size="small" @click="setZoom(zoom + 0.1)">＋</el-button>
        <el-button text size="small" @click="setZoom(1)">重置</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dag-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dag-scroll {
  overflow: auto;
  max-height: 420px;
  padding: 2px 0;
  cursor: grab;
}

.dag-scroll.dragging {
  cursor: grabbing;
}

.dag-holder {
  position: relative;
}

.dag-stage {
  position: relative;
  transform-origin: top left;
}

.dag-edges {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.dag-node {
  position: absolute;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(31, 35, 41, 0.07);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 14px 13px 8px;
  cursor: pointer;
  transition: box-shadow 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}

.dag-node:hover {
  box-shadow: 0 8px 22px rgba(31, 35, 41, 0.13);
  transform: translateY(-1px);
}

.dag-node.selected {
  border-color: #1677ff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.12), 0 8px 22px rgba(31, 35, 41, 0.1);
}

.dag-node.failure {
  border-color: #ffccc7;
  box-shadow: 0 0 0 3px rgba(245, 34, 45, 0.08), 0 2px 10px rgba(245, 34, 45, 0.12);
}

.dag-bar {
  position: absolute;
  top: -1px;
  left: 10px;
  right: 10px;
  height: 3px;
  border-radius: 2px;
  background: #c9cdd4;
}

.dag-node.success .dag-bar {
  background: #52c41a;
}
.dag-node.failure .dag-bar {
  background: #f5222d;
}
.dag-node.running .dag-bar {
  background: #1677ff;
}
.dag-node.cancelled .dag-bar {
  background: #fa8c16;
}
.dag-node.queued .dag-bar,
.dag-node.skipped .dag-bar {
  background: #c9cdd4;
}

.dag-head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 600;
  font-size: 13px;
  color: #1d2129;
  width: 100%;
}

.dag-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dag-dot {
  width: 17px;
  height: 17px;
  border-radius: 50%;
  color: #fff;
  font-size: 10.5px;
  display: grid;
  place-items: center;
  flex: none;
  background: #c9cdd4;
}

.dag-node.success .dag-dot {
  background: #52c41a;
}
.dag-node.failure .dag-dot {
  background: #f5222d;
}
.dag-node.running .dag-dot {
  background: #1677ff;
}

/* 运行中的节点：转圈 + 淡蓝描边，一眼能看出「正在跑」 */
.dag-node.running {
  border-color: #bedaff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.08), 0 2px 10px rgba(22, 119, 255, 0.12);
}

.dag-dot.spinning {
  overflow: hidden;
  padding: 0;
}

.spinner {
  width: 100%;
  height: 100%;
  animation: dag-spin 0.9s linear infinite;
}

@keyframes dag-spin {
  to {
    transform: rotate(360deg);
  }
}

.live-hint {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: rgba(22, 119, 255, 0.08);
  color: #1677ff;
  font-size: 10.5px;
  font-weight: 500;
}

.dag-dur.live {
  color: #1677ff;
  font-weight: 600;
}
.dag-node.cancelled .dag-dot {
  background: #fa8c16;
}

.dag-status {
  font-size: 12px;
  color: #86909c;
  margin-top: 3px;
}

.dag-node.failure .dag-status {
  color: #f5222d;
  font-weight: 550;
}
.dag-node.running .dag-status {
  color: #1677ff;
}
.dag-node.cancelled .dag-status {
  color: #fa8c16;
}

.dag-foot {
  margin-top: auto;
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11.5px;
  color: #86909c;
}

.dag-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding-top: 4px;
}

.dag-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: #86909c;
}

.dag-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.lg {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.lg.success {
  background: #52c41a;
}
.lg.failure {
  background: #f5222d;
}
.lg.running {
  background: #1677ff;
}
.lg.cancelled {
  background: #fa8c16;
}
.lg.queued,
.lg.skipped {
  background: #c9cdd4;
}

.dag-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #86909c;
}

.dag-source {
  margin-right: 6px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #f2f4f9;
  font-size: 11.5px;
}

.dag-source.warn {
  background: #fff7e6;
  color: #ad6800;
}

.dag-zoom {
  min-width: 38px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
</style>
