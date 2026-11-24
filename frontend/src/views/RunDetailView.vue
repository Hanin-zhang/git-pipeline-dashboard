<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { capabilitiesApi, runsApi } from '@/api'
import type { Capabilities, RunDetail, RunJob, RunStep, RunSummary } from '@/api/types'
import { useRunStream } from '@/composables/useRunStream'
import PipelineDag from '@/components/PipelineDag.vue'
import JobAccordion from '@/components/run/JobAccordion.vue'
import RunAlert from '@/components/run/RunAlert.vue'
import RunHeader from '@/components/run/RunHeader.vue'
import RunInfoPanel from '@/components/run/RunInfoPanel.vue'
import RunStats from '@/components/run/RunStats.vue'
import StepDrawer from '@/components/run/StepDrawer.vue'

const route = useRoute()
const router = useRouter()

const run = ref<RunDetail | null>(null)
const history = ref<RunSummary[]>([])
const capabilities = ref<Capabilities | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const expanded = ref<Record<number, boolean>>({})
const selectedJobId = ref<number | null>(null)
const highlightedStep = ref<string | null>(null)

const drawerOpen = ref(false)
const drawerLine = ref<number | undefined>()
const drawerEndLine = ref<number | undefined>()
/** 失败原因默认只展示 2 条，避免红卡片把页面撑长 */
const showAllFailureItems = ref(false)
/** 旧版结论默认折叠，展开后才看细节 */
const staleExpanded = ref(false)
const reanalyzing = ref(false)
const drawerJob = ref<RunJob | undefined>()
const drawerStep = ref<RunStep | undefined>()

const jobs = computed(() => run.value?.jobs ?? [])

const failedJob = computed(() => jobs.value.find((job) => job.conclusion === 'failure'))
const failedStep = computed(() => failedJob.value?.steps.find((step) => step.conclusion === 'failure'))
const totalSteps = computed(() => jobs.value.reduce((sum, job) => sum + job.steps.length, 0))

/** 没有 job 明细且配了 token 时，允许手动从 GitHub 拉一次 */
const canSyncJobs = computed(
  () => (capabilities.value?.githubActions ?? false) && jobs.value.length === 0,
)
const syncing = ref(false)

async function syncJobs() {
  if (!run.value) return
  syncing.value = true
  try {
    const result = await runsApi.syncJobs(run.value.id)
    ElMessage[result.jobs > 0 ? 'success' : 'warning'](result.message)
    if (result.jobs > 0) await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    syncing.value = false
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const detail = await runsApi.detail(route.params.id as string)
    run.value = detail
    lastSyncAt.value = new Date()

    const next: Record<number, boolean> = {}
    detail.jobs.forEach((job) => {
      // 失败 job 默认展开，其余收起，避免页面过长
      next[job.id] = job.conclusion === 'failure'
    })
    expanded.value = next
    selectedJobId.value = detail.jobs.find((job) => job.conclusion === 'failure')?.id ?? detail.jobs[0]?.id ?? null

    await loadHistory(detail)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
    syncPolling()
    syncPageBadge()
  }
}

/** 同 workflow 的历史运行，用于成功率与耗时对比；排除当前这条。 */
async function loadHistory(detail: RunDetail) {
  try {
    const page = await runsApi.list({ workflow: detail.workflowName, page: 1, size: 11 })
    history.value = page.items.filter((item) => item.id !== detail.id).slice(0, 10)
  } catch {
    history.value = []
  }
}

function toggleJob(jobId: number) {
  expanded.value = { ...expanded.value, [jobId]: !expanded.value[jobId] }
}

function selectJob(jobId: number) {
  selectedJobId.value = jobId
  expanded.value = { ...expanded.value, [jobId]: true }
  requestAnimationFrame(() => {
    document.getElementById(`job-${jobId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

function openStep(job: RunJob, step: RunStep) {
  drawerJob.value = job
  drawerStep.value = step
  drawerLine.value = undefined
  drawerEndLine.value = undefined
  drawerOpen.value = true
}

/** 失败摘要 / 失败节点的快捷入口：展开 job、滚动定位，并直接打开失败步骤详情 */
function locateFailure() {
  if (!failedJob.value) return
  const job = failedJob.value
  const step = failedStep.value
  selectedJobId.value = job.id
  expanded.value = { ...expanded.value, [job.id]: true }
  highlightedStep.value = step ? `${job.id}-${step.number}` : null

  requestAnimationFrame(() => {
    document.getElementById(`job-${job.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    if (step) {
      window.setTimeout(() => openStep(job, step), 320)
    }
  })
}

function openGithub(url?: string) {
  if (url) window.open(url, '_blank', 'noopener')
}

/** 能力开关只取一次：决定「重新运行 / 取消」能不能点 */
async function loadCapabilities() {
  try {
    capabilities.value = await capabilitiesApi.get()
  } catch {
    capabilities.value = null
  }
}

/* ============ 实时更新 ============
 * 两条通道，缺一不可：
 *  1. SSE：run 状态变化时后端会广播，命中当前 run 就静默重拉（延迟 < 1s）
 *  2. 轮询：job 完成不会触发 workflow_run 事件（GitHub 发的是 workflow_job，
 *     当前未订阅），所以运行中的页面每 12 秒兜底拉一次，保证节点逐个变绿
 */
const { events } = useRunStream()

/** 任务是否还在进行：决定要不要继续轮询 */
const isLive = computed(() => {
  const status = run.value?.status
  return status === 'queued' || status === 'in_progress' || status === 'requested' || status === 'pending'
})

/** 失败原因类别的中文标签 */
const failureLabel = computed(() => {
  const map: Record<string, string> = {
    test: '测试失败',
    build: '构建失败',
    dependency: '依赖问题',
    timeout: '执行超时',
    permission: '权限不足',
    network: '网络异常',
    unknown: '未能识别',
  }
  return map[run.value?.failureClass ?? ''] ?? '失败'
})

const visibleFailureItems = computed(() => {
  const list = run.value?.failureHighlights ?? []
  return showAllFailureItems.value ? list : list.slice(0, 2)
})

/**
 * 点错误条目 → 打开该 job 的日志抽屉并定位到行。
 * 行号属于 failureJobName 那个 job 的日志，所以必须打开同一个 job，否则会指错位置。
 */
function openFailureLog(lineNo: number, endLine?: number) {
  // 用户正在选中文字想复制时，不要抢着打开抽屉
  if ((window.getSelection()?.toString().trim().length ?? 0) > 0) return
  const detail = run.value
  if (!detail) return
  const job =
    detail.jobs.find((item) => item.name === detail.failureJobName) ??
    detail.jobs.find((item) => item.conclusion === 'failure') ??
    detail.jobs[0]
  if (!job) return
  const step = job.steps.find((item) => item.conclusion === 'failure') ?? job.steps[0]
  drawerJob.value = job
  drawerStep.value = step
  drawerLine.value = lineNo
  drawerEndLine.value = endLine
  drawerOpen.value = true
}

/** 按 failureJobName 定位摘要来源的 job（重算时必须用同一个 job） */
function failureJob(): RunJob | undefined {
  const detail = run.value
  if (!detail) return undefined
  return (
    detail.jobs.find((item) => item.name === detail.failureJobName) ??
    detail.jobs.find((item) => item.conclusion === 'failure') ??
    detail.jobs[0]
  )
}

/**
 * 重新分析：复用侧边栏那条链路（summary 接口会回源解析并异步落库），
 * 然后轮询详情直到 stale 标记消失。
 */
async function reanalyze() {
  const detail = run.value
  if (!detail || reanalyzing.value) return
  const job = failureJob()
  if (!job) {
    ElMessage.warning('找不到对应的 job，无法重新分析')
    return
  }
  reanalyzing.value = true
  try {
    await runsApi.jobLogSummary(detail.id, job.id)
    for (let i = 0; i < 5; i++) {
      await new Promise((resolve) => setTimeout(resolve, 600))
      const next = await runsApi.detail(String(detail.id))
      run.value = next
      if (!next.failureStale) break
    }
    if (run.value?.failureStale) {
      ElMessage.warning('已重新解析，但结果尚未写入，请稍后刷新')
    } else {
      ElMessage.success('已按最新规则重新分析')
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    reanalyzing.value = false
  }
}

const isFailedRun = computed(
  () => !!run.value?.conclusion && ['failure', 'timed_out', 'startup_failure'].includes(run.value.conclusion),
)

/** 流水线整体结果：给拓扑卡片右上角用 */
const overall = computed(() => {
  const detail = run.value
  if (!detail) return null
  if (isLive.value) return { cls: 'running', icon: '◐', text: '执行中' }
  const list = detail.jobs
  if (!list.length) return { cls: 'neutral', icon: '·', text: '无作业明细' }
  const failed = list.some((job) =>
    ['failure', 'timed_out', 'startup_failure'].includes(job.conclusion ?? ''),
  )
  if (failed) return { cls: 'failure', icon: '✕', text: '存在失败' }
  const allOk = list.every((job) =>
    ['success', 'skipped', 'neutral'].includes(job.conclusion ?? ''),
  )
  if (allOk) return { cls: 'success', icon: '✓', text: '全部通过' }
  return { cls: 'neutral', icon: '·', text: '已结束' }
})

let refreshTimer: number | null = null
let pollTimer: number | null = null
let refreshing = false

/** 最近一次成功取到数据的时间，界面上用来证明「页面在自己刷新」 */
const lastSyncAt = ref<Date | null>(null)
const lastSyncLabel = computed(() =>
  lastSyncAt.value
    ? lastSyncAt.value.toLocaleTimeString('zh-CN', { hour12: false })
    : null,
)

/** 静默刷新：只换数据，不动展开状态、选中项与滚动位置 */
async function silentRefresh() {
  if (!run.value || refreshing || loading.value) return
  refreshing = true
  try {
    const detail = await runsApi.detail(String(run.value.id))
    lastSyncAt.value = new Date()
    if (JSON.stringify(detail) === JSON.stringify(run.value)) return

    const prevExpanded = expanded.value
    const prevSelected = selectedJobId.value
    run.value = detail
    // 保留用户已展开/收起的 job，新出现的 job 沿用默认规则
    const next: Record<number, boolean> = {}
    detail.jobs.forEach((job) => {
      next[job.id] = prevExpanded[job.id] ?? job.conclusion === 'failure'
    })
    expanded.value = next
    if (!detail.jobs.some((job) => job.id === prevSelected)) {
      selectedJobId.value = detail.jobs[0]?.id ?? null
    }
    syncPageBadge()
  } catch {
    // 静默失败：本轮忽略，下一轮继续
  } finally {
    refreshing = false
  }
}

/** SSE 命中当前 run 时合并触发，避免连续事件打爆接口 */
function scheduleRefresh() {
  if (refreshTimer !== null) return
  refreshTimer = window.setTimeout(() => {
    refreshTimer = null
    void silentRefresh()
  }, 400)
}

watch(
  () => events.value[0]?.receivedAt,
  () => {
    const latest = events.value[0]
    if (!latest) return
    if (Number(latest.data.runId) === Number(run.value?.id)) scheduleRefresh()
  },
)

/** 运行中才轮询；一旦结束立刻停掉。
 *  故意不做「页面不可见就暂停」——切到别的标签页时也需要更新标题与图标，
 *  浏览器本身会把后台定时器降频，不会造成负担。 */
function syncPolling() {
  if (isLive.value && pollTimer === null) {
    pollTimer = window.setInterval(() => void silentRefresh(), 12_000)
  } else if (!isLive.value && pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(isLive, syncPolling)

/* ============ 标签页状态标识（学 GitHub：完成时标题/图标带 ✅ ❌） ============ */
const DEFAULT_TITLE = document.title
const DEFAULT_ICON = document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.href ?? ''

function iconData(color: string, glyph: 'check' | 'cross' | 'dot'): string {
  const body =
    glyph === 'check'
      ? "<path d='M9 16.5l4.5 4.5L23 11.5' stroke='white' stroke-width='3' fill='none' stroke-linecap='round' stroke-linejoin='round'/>"
      : glyph === 'cross'
        ? "<path d='M11 11l10 10M21 11L11 21' stroke='white' stroke-width='3' fill='none' stroke-linecap='round'/>"
        : "<circle cx='16' cy='16' r='3.5' fill='white'/>"
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'><rect width='32' height='32' rx='8' fill='${color}'/>${body}</svg>`
  return `data:image/svg+xml,${encodeURIComponent(svg)}`
}

function setFavicon(href: string) {
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!link) {
    link = document.createElement('link')
    link.rel = 'icon'
    document.head.appendChild(link)
  }
  link.href = href
}

/** 把运行结果同步到标题与图标，切走的标签页也能一眼看出成败 */
function syncPageBadge() {
  const detail = run.value
  if (!detail) return
  const name = `${detail.workflowName} #${detail.runNumber}`

  if (isLive.value) {
    document.title = `⏳ ${name} · GitHub Look`
    setFavicon(iconData('#1677ff', 'dot'))
    return
  }
  if (['failure', 'timed_out', 'startup_failure'].includes(detail.conclusion ?? '')) {
    document.title = `❌ ${name} · GitHub Look`
    setFavicon(iconData('#f5222d', 'cross'))
    return
  }
  if (detail.conclusion === 'success') {
    document.title = `✅ ${name} · GitHub Look`
    setFavicon(iconData('#12a150', 'check'))
    return
  }
  document.title = `${name} · GitHub Look`
  setFavicon(iconData('#c9cdd4', 'dot'))
}

watch([() => run.value?.status, () => run.value?.conclusion], syncPageBadge)

onUnmounted(() => {
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  if (pollTimer !== null) window.clearInterval(pollTimer)
  document.title = DEFAULT_TITLE
  if (DEFAULT_ICON) setFavicon(DEFAULT_ICON)
})

onMounted(async () => {
  loadCapabilities()
  await load()
  syncPolling()
  syncPageBadge()
})
watch(() => route.params.id, () => {
  staleExpanded.value = false
  void load()
})
</script>

<template>
  <div v-loading="loading">
    <el-button text class="back-link" @click="router.back()">
      <el-icon><ArrowLeft /></el-icon> 返回流水线列表
    </el-button>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <RunHeader
      :run="run"
      :actions-enabled="capabilities?.githubActions ?? false"
      @locate="locateFailure"
      @refresh="load"
    />

    <RunAlert
      :run="run"
      :failed-job="failedJob"
      :failed-step="failedStep"
      @locate="locateFailure"
      @open-github="openGithub"
    />

    <!-- 旧版结论：默认折叠成一行，展开才看细节；右侧可一键按新规则重算 -->
    <div v-if="run?.failureSummary && run.failureStale" class="failure-panel stale">
      <div class="fp-stale-row">
        <span class="fp-tag stale">旧版结论</span>
        <span class="fp-stale-text">{{ run.failureSummary }}</span>
        <div class="fp-stale-actions">
          <button type="button" class="fp-link" @click="staleExpanded = !staleExpanded">
            {{ staleExpanded ? '收起' : '展开' }}
          </button>
          <el-button
            size="small"
            type="warning"
            plain
            :loading="reanalyzing"
            @click="reanalyze"
          >
            重新分析
          </el-button>
        </div>
      </div>

      <div v-if="staleExpanded" class="fp-stale-body">
        <div class="fp-hint">这条结论由旧版规则生成，可能与实际原因有偏差。</div>
        <ul v-if="run.failureHighlights?.length" class="fp-list">
          <li
            v-for="(item, idx) in visibleFailureItems"
            :key="idx"
            class="fp-item"
            title="点击查看日志原文"
            @click="openFailureLog(item.lineNo, item.endLine)"
          >
            <div class="fp-line-head">
              <span class="fp-title">{{ item.title }}</span>
              <span v-if="item.occurrences > 1" class="fp-count">×{{ item.occurrences }}</span>
              <span class="fp-lineno">第 {{ item.lineNo }} 行</span>
            </div>
            <div class="fp-detail">{{ item.detail }}</div>
          </li>
        </ul>
      </div>
    </div>

    <!-- 失败原因：红底高亮，来自后端已落库的摘要，不触发任何网络请求 -->
    <div v-else-if="run?.failureSummary" class="failure-panel">
      <div class="fp-head">
        <span class="fp-tag">{{ failureLabel }}</span>
        <span v-if="run.failureJobName" class="fp-job">来自 job：{{ run.failureJobName }}</span>
      </div>
      <div class="fp-summary">{{ run.failureSummary }}</div>

      <ul v-if="run.failureHighlights?.length" class="fp-list">
        <li
          v-for="(item, idx) in visibleFailureItems"
          :key="idx"
          class="fp-item"
          title="点击查看日志原文"
          @click="openFailureLog(item.lineNo, item.endLine)"
        >
          <div class="fp-line-head">
            <span class="fp-title">{{ item.title }}</span>
            <span v-if="item.occurrences > 1" class="fp-count">×{{ item.occurrences }}</span>
            <span class="fp-lineno">第 {{ item.lineNo }} 行</span>
          </div>
          <div class="fp-detail">{{ item.detail }}</div>
        </li>
      </ul>

      <div class="fp-foot">
        <span class="fp-hint">点错误条目可跳转到日志原文</span>
        <button
          v-if="(run.failureHighlights?.length ?? 0) > 2"
          type="button"
          class="fp-toggle"
          @click="showAllFailureItems = !showAllFailureItems"
        >
          {{ showAllFailureItems ? '收起' : `展开全部 ${run.failureHighlights?.length} 条` }}
        </button>
      </div>
    </div>

    <!-- 失败但还没分析完：说明清楚，避免用户以为功能坏了 -->
    <div v-else-if="isFailedRun && run" class="failure-panel pending">
      <div class="fp-head">
        <span class="fp-tag muted">分析中</span>
      </div>
      <div class="fp-summary">正在分析失败原因，首次需要下载该 job 的日志，通常几秒内完成。</div>
    </div>

    <div class="stats-wrap">
      <RunStats :run="run" :history="history" />
    </div>

    <div class="card" style="margin: 16px 0">
      <div class="card-head">
        <div>
          <div class="card-title">流水线拓扑</div>
          <div class="card-hint">
            点击节点定位到下方对应 job · Ctrl/⌘ + 滚轮缩放 · 空白处拖拽平移
            <span v-if="isLive" class="sync-tag live">
              <span class="sync-dot" />实时更新中<template v-if="lastSyncLabel"> · {{ lastSyncLabel }}</template>
            </span>
            <span v-else-if="lastSyncLabel" class="sync-tag">已停止自动刷新</span>
          </div>
        </div>
        <div class="head-right">
          <span v-if="overall" class="run-badge" :class="overall.cls">
            <span class="badge-icon">{{ overall.icon }}</span>{{ overall.text }}
          </span>
          <el-button
            v-if="canSyncJobs"
            size="small"
            type="primary"
            plain
            :loading="syncing"
            @click="syncJobs"
          >
            <el-icon style="margin-right: 4px"><Refresh /></el-icon>从 GitHub 同步 Job 明细
          </el-button>
          <span class="text-muted" style="font-size: 12px">
            {{ jobs.length }} 个节点 · {{ totalSteps }} 个步骤
          </span>
        </div>
      </div>
      <div class="card-pad" style="padding: 10px 18px 6px">
        <PipelineDag :jobs="jobs" :selected-id="selectedJobId" @select="selectJob" />
      </div>
    </div>

    <div class="detail-grid">
      <div class="card">
        <div class="card-head">
          <div class="card-title">执行作业</div>
          <div class="card-hint">点击步骤查看详情 · 失败步骤默认展开</div>
        </div>
        <div class="card-pad">
          <JobAccordion
            :jobs="jobs"
            :expanded="expanded"
            :selected-id="selectedJobId"
            :highlighted-step="highlightedStep"
            @toggle="toggleJob"
            @select="selectJob"
            @open-step="openStep"
          />
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <div class="card-title">运行信息</div>
        </div>
        <div class="card-pad">
          <RunInfoPanel :run="run" />
        </div>
      </div>
    </div>

    <StepDrawer
      v-model="drawerOpen"
      :run-id="run?.id"
      :job="drawerJob"
      :step="drawerStep"
      :initial-line="drawerLine"
      :initial-end-line="drawerEndLine"
    />
  </div>
</template>

<style scoped>
.back-link {
  padding-left: 0;
  margin-bottom: 2px;
  color: #86909c;
}

.back-link:hover {
  color: #1677ff;
}

.stats-wrap {
  margin-top: 0;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.run-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 550;
  border: 1px solid transparent;
}

.run-badge .badge-icon {
  font-size: 11px;
  line-height: 1;
}

.run-badge.success {
  background: #f6ffed;
  border-color: #d9f7be;
  color: #12a150;
}

.run-badge.failure {
  background: #fff1f0;
  border-color: #ffccc7;
  color: #f5222d;
}

.run-badge.running {
  background: #eef4ff;
  border-color: #bedaff;
  color: #1677ff;
}

.run-badge.running .badge-icon {
  animation: badge-pulse 1.2s ease-in-out infinite;
}

.run-badge.neutral {
  background: #f7f8fa;
  border-color: #e5e6eb;
  color: #86909c;
}

@keyframes badge-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.sync-tag {
  margin-left: 6px;
  font-size: 11px;
  color: #86909c;
}

.sync-tag.live {
  color: #1677ff;
}

.sync-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #1677ff;
  margin-right: 4px;
  vertical-align: 1px;
  animation: badge-pulse 1.2s ease-in-out infinite;
}

@media (max-width: 1080px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 失败原因高亮 ---------- */
.failure-panel {
  margin: 12px 0 4px;
  padding: 14px 16px;
  border-radius: 10px;
  border: 1px solid #ffccc7;
  background: #fff1f0;
}

.failure-panel.pending {
  border-color: #ffe0b2;
  background: #fffaf0;
}

.fp-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.fp-tag {
  padding: 1px 8px;
  border-radius: 999px;
  background: #cf1322;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
}

.fp-tag.muted {
  background: #d46b08;
}

.fp-job {
  font-size: 11.5px;
  color: #a8071a;
  opacity: 0.75;
}

.fp-summary {
  font-size: 13px;
  font-weight: 600;
  color: #a8071a;
  line-height: 1.6;
}

.fp-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fp-list li {
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.75);
  border: 1px solid #ffd6d3;
}

.fp-line-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.fp-title {
  font-size: 12px;
  font-weight: 600;
  color: #a8071a;
}

.fp-count {
  padding: 0 5px;
  border-radius: 4px;
  background: #ffe7e5;
  color: #cf1322;
  font-size: 11px;
}

.fp-lineno {
  margin-left: auto;
  font-size: 11px;
  color: #d4787c;
  font-variant-numeric: tabular-nums;
}

.fp-detail {
  margin-top: 4px;
  font-family: 'SF Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  color: #8c3b40;
  line-height: 1.6;
  word-break: break-all;
}

.fp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  font-size: 11px;
  color: #d4787c;
}

.fp-toggle {
  padding: 2px 10px;
  border: 1px solid #ffb3ae;
  border-radius: 999px;
  background: #fff;
  color: #cf1322;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.fp-toggle:hover {
  background: #ffe7e5;
  border-color: #f57b7e;
}

.fp-item {
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  user-select: text;
}

.fp-item:hover {
  background: #fff;
  border-color: #f57b7e;
}

/* ---------- 旧版结论（stale） ---------- */
.failure-panel.stale {
  border-color: #ffe0b2;
  background: #fffaf0;
}

.fp-tag.stale {
  background: #d46b08;
}

.fp-stale-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.fp-stale-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  color: #ad6800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fp-stale-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.fp-link {
  padding: 0 2px;
  border: none;
  background: transparent;
  color: #d48806;
  font-size: 12px;
  cursor: pointer;
}

.fp-link:hover {
  text-decoration: underline;
}

.fp-stale-body {
  margin-top: 10px;
}

.fp-hint {
  margin-bottom: 8px;
  font-size: 11.5px;
  color: #b07b1e;
}
</style>
