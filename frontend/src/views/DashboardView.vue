<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import { dashboardApi, runsApi } from '@/api'
import type { DashboardSummary, FailureReasonStats, RunSummary, TrendPoint, WorkflowHealth } from '@/api/types'
import BaseChart from '@/components/BaseChart.vue'
import { useRunStream } from '@/composables/useRunStream'
import { chartTheme } from '@/utils/echarts'
import { formatDuration, formatDurationShort, formatTime, fromNow, percent, shortenMessage } from '@/utils/format'

const router = useRouter()
const { events, connected } = useRunStream()

const summary = ref<DashboardSummary | null>(null)
const trend = ref<TrendPoint[]>([])
const workflows = ref<WorkflowHealth[]>([])
const failureStats = ref<FailureReasonStats | null>(null)
const recent = ref<RunSummary[]>([])
const repos = ref<string[]>([])
const branches = ref<string[]>([])

const repo = ref('')
const branch = ref('')
const days = ref(7)
const granularity = ref<'day' | 'week'>('day')

const loading = ref(true)
const chartsLoading = ref(true)

interface FeedItem {
  key: string
  /** 真实运行主键；此前是从 key 里 split 出来的，seed- 前缀会解析成 NaN 导致点击无效 */
  runId: number
  event: string
  title: string
  repo: string
  branch?: string
  actor?: string
  status: string
  conclusion?: string
  durationMs?: number
  message?: string
  at: number
  fresh?: boolean
}

const feed = ref<FeedItem[]>([])

function toFeedFromRun(run: RunSummary): FeedItem {
  const at = run.completedAt || run.startedAt || new Date().toISOString()
  return {
    key: `seed-${run.id}`,
    runId: run.id,
    event: run.conclusion ? `run.${run.conclusion === 'success' ? 'completed' : 'failed'}` : 'run.started',
    title: run.workflowName || '未命名工作流',
    repo: run.repoFullName,
    branch: run.branch,
    actor: run.actor,
    status: run.status,
    conclusion: run.conclusion,
    durationMs: run.durationMs,
    message: run.commitMessage,
    at: new Date(at).getTime(),
  }
}

const trendOption = computed<EChartsOption>(() => {
  const labels = trend.value.map((p) => p.label)
  return {
    grid: { left: 8, right: 18, top: 44, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#ffffff',
      borderColor: '#e6e9f0',
      textStyle: { color: chartTheme.text, fontSize: 12 },
      formatter: (params: unknown) => {
        const rows = params as Array<{ axisValue: string; seriesName: string; value: number; marker: string }>
        if (!rows.length) return ''
        const head = `<div style="font-weight:600;margin-bottom:4px">${rows[0].axisValue}</div>`
        // 当天没有运行时成功率按 0% 画，提示里补一句说明，避免被读成「全军覆没」
        const point = trend.value.find((p) => p.label === rows[0].axisValue)
        const emptyDay = !!point && point.total === 0
        return (
          head +
          rows
            .map((r) => {
              const suffix = r.seriesName === '成功率' ? '%' : ''
              const value = r.value
              const missing = value === null || value === undefined || Number.isNaN(value)
              let text = missing ? '—' : `${value}${suffix}`
              if (!missing && r.seriesName === '成功率' && emptyDay) {
                text = `${value}%（当天无运行）`
              }
              return `<div style="display:flex;justify-content:space-between;gap:16px">${r.marker}${r.seriesName}<b>${text}</b></div>`
            })
            .join('')
        )
      },
    },
    // 图例居中放顶部：原来贴右边缘，会和右轴刻度（100% 那行）叠在一起
    legend: {
      top: 0,
      left: 'center',
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 18,
      textStyle: { color: chartTheme.muted, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: chartTheme.grid } },
      axisTick: { show: false },
      axisLabel: { color: chartTheme.muted, fontSize: 11.5 },
    },
    yAxis: [
      {
        type: 'value',
        // 不再画轴名：图例已经标明系列含义，轴名挤在左上角会和图例/刻度打架
        splitLine: { lineStyle: { color: chartTheme.grid, type: 'dashed' } },
        axisLabel: { color: chartTheme.muted, fontSize: 11.5 },
        minInterval: 1,
      },
      {
        type: 'value',
        min: 0,
        max: 100,
        splitLine: { show: false },
        axisLabel: { color: chartTheme.muted, fontSize: 11.5, formatter: '{value}%' },
      },
    ],
    series: [
      {
        name: '成功',
        type: 'bar',
        stack: 'total',
        barMaxWidth: 26,
        itemStyle: { color: chartTheme.success, borderRadius: [0, 0, 0, 0] },
        data: trend.value.map((p) => p.success),
      },
      {
        name: '失败',
        type: 'bar',
        stack: 'total',
        barMaxWidth: 26,
        itemStyle: { color: chartTheme.danger, borderRadius: [4, 4, 0, 0] },
        data: trend.value.map((p) => p.failed),
      },
      {
        name: '成功率',
        type: 'line',
        yAxisIndex: 1,
        // 直线而不是平滑曲线：成功率是离散的当日统计，曲线插值会让人以为中间的
        // 下降是「逐渐恶化」，实际只是两天无数据
        smooth: false,
        symbol: 'circle',
        symbolSize: 6,
        connectNulls: true,
        lineStyle: { width: 2.2, color: chartTheme.primary },
        itemStyle: { color: chartTheme.primary },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(47,91,255,0.16)' },
              { offset: 1, color: 'rgba(47,91,255,0)' },
            ],
          },
        },
        // 当天没有运行按 0% 画：折线不会出现断口，读者也能一眼看出那里是空的
        data: trend.value.map((p) => (p.total > 0 ? p.successRate : 0)),
      },
    ],
  }
})

const durationOption = computed<EChartsOption>(() => {
  const rows = [...workflows.value].sort((a, b) => a.avgDurationMs - b.avgDurationMs)
  return {
    grid: { left: 8, right: 40, top: 10, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#ffffff',
      borderColor: '#e6e9f0',
      textStyle: { color: chartTheme.text, fontSize: 12 },
      formatter: (params: unknown) => {
        const rows2 = params as Array<{ name: string; value: number }>
        if (!rows2.length) return ''
        return `<b>${rows2[0].name}</b><br/>平均耗时 ${formatDurationShort(rows2[0].value)}`
      },
    },
    xAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: chartTheme.grid, type: 'dashed' } },
      axisLabel: {
        color: chartTheme.muted,
        fontSize: 11,
        formatter: (value: number) => formatDurationShort(value),
      },
    },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.workflowName),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: chartTheme.text, fontSize: 11.5, width: 150, overflow: 'truncate' },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 16,
        data: rows.map((r) => ({
          value: r.avgDurationMs,
          itemStyle: {
            borderRadius: [0, 4, 4, 0],
            color: r.failureRate > 20 ? chartTheme.danger : r.failureRate > 10 ? chartTheme.warning : chartTheme.primary,
          },
        })),
        label: {
          show: true,
          position: 'right',
          color: chartTheme.muted,
          fontSize: 11,
          formatter: (p: { value?: unknown }) => formatDurationShort(Number(p.value)),
        },
      },
    ],
  }
})

function deltaValue(current: number, previous: number): number {
  if (!previous) return current ? 100 : 0
  return ((current - previous) / previous) * 100
}

function deltaClass(delta: number, lowerIsBetter = false): string {
  if (Math.abs(delta) < 0.05) return 'delta flat'
  const good = lowerIsBetter ? delta < 0 : delta > 0
  return good ? 'delta up' : 'delta down'
}

function deltaText(delta: number): string {
  if (Math.abs(delta) < 0.05) return '持平'
  return `${delta > 0 ? '+' : ''}${delta.toFixed(1)}%`
}

async function loadAll() {
  loading.value = true
  chartsLoading.value = true
  try {
    const [summaryRes, trendRes, workflowRes, failureRes, recentRes] = await Promise.all([
      dashboardApi.summary({ repo: repo.value || undefined, branch: branch.value || undefined }),
      dashboardApi.trend({
        days: days.value,
        repo: repo.value || undefined,
        branch: branch.value || undefined,
        granularity: granularity.value,
      }),
      dashboardApi.workflows({
        days: days.value,
        repo: repo.value || undefined,
        branch: branch.value || undefined,
      }),
      dashboardApi.failureReasons({
        days: days.value,
        repo: repo.value || undefined,
        branch: branch.value || undefined,
      }),
      dashboardApi.recent(12),
    ])
    summary.value = summaryRes
    trend.value = trendRes
    workflows.value = workflowRes
    failureStats.value = failureRes
    recent.value = recentRes
    if (!feed.value.length) {
      feed.value = recentRes.map(toFeedFromRun)
    }
  } finally {
    loading.value = false
    chartsLoading.value = false
  }
}

async function loadFilters() {
  const res = await runsApi.filters()
  repos.value = res.repos
  branches.value = res.branches
}

let refreshTimer: number | null = null
function scheduleRefresh() {
  if (refreshTimer !== null) return
  refreshTimer = window.setTimeout(async () => {
    refreshTimer = null
    // 事件到达后要把「所有依赖同一次聚合的区块」一起刷新：
    // 只刷指标卡和趋势图会让「工作流健康度 / 耗时对比」停在旧数据，
    // 同一屏里出现「失败 1 次」但「失败率 0.0%」这种自相矛盾。
    const [summaryRes, trendRes, workflowRes, failureReasonRes] = await Promise.all([
      dashboardApi.summary({ repo: repo.value || undefined, branch: branch.value || undefined }),
      dashboardApi.trend({
        days: days.value,
        repo: repo.value || undefined,
        branch: branch.value || undefined,
        granularity: granularity.value,
      }),
      dashboardApi.workflows({
        days: days.value,
        repo: repo.value || undefined,
        branch: branch.value || undefined,
      }),
      dashboardApi.failureReasons({
        days: days.value,
        repo: repo.value || undefined,
        branch: branch.value || undefined,
      }),
    ])
    summary.value = summaryRes
    trend.value = trendRes
    workflows.value = workflowRes
    failureStats.value = failureReasonRes
  }, 1200)
}

watch(events, (list) => {
  if (!list.length) return
  const latest = list[0]
  if (feed.value.some((item) => item.key === `${latest.data.runId}-${latest.receivedAt}`)) return
  feed.value = [
    {
      key: `${latest.data.runId}-${latest.receivedAt}`,
      runId: latest.data.runId,
      event: latest.event,
      title: latest.data.workflowName || '未命名工作流',
      repo: latest.data.repoFullName,
      branch: latest.data.branch,
      actor: latest.data.actor,
      status: latest.data.status,
      conclusion: latest.data.conclusion,
      durationMs: latest.data.durationMs,
      message: latest.data.commitMessage,
      at: latest.receivedAt,
      fresh: true,
    },
    ...feed.value,
  ].slice(0, 40)
  scheduleRefresh()
})

watch([repo, branch], () => loadAll())
watch([days, granularity], () => loadAll())

onMounted(async () => {
  await Promise.all([loadAll(), loadFilters()])
})

onUnmounted(() => {
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
})

function openRun(runId?: number) {
  if (runId) router.push(`/runs/${runId}`)
}
</script>

<template>
  <div>
    <div class="page-intro">
      <div>
        <h1 class="page-title">全局概览</h1>
        <div class="page-desc">
          今日 {{ summary?.today.total ?? 0 }} 次运行，覆盖 {{ summary?.repoCount ?? 0 }} 个仓库
          <span v-if="summary?.activeRuns" style="color: var(--primary)">
            · {{ summary.activeRuns }} 个正在运行
          </span>
        </div>
      </div>
      <div class="row" style="flex-wrap: wrap">
        <el-select v-model="repo" placeholder="全部仓库" clearable style="width: 190px" size="default">
          <el-option label="全部仓库" value="" />
          <el-option v-for="item in repos" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="branch" placeholder="全部分支" clearable style="width: 150px">
          <el-option label="全部分支" value="" />
          <el-option v-for="item in branches" :key="item" :label="item" :value="item" />
        </el-select>
        <el-radio-group v-model="days" size="default">
          <el-radio-button :value="7">7 天</el-radio-button>
          <el-radio-button :value="14">14 天</el-radio-button>
          <el-radio-button :value="30">30 天</el-radio-button>
        </el-radio-group>
        <el-button-group>
          <el-button :type="granularity === 'day' ? 'primary' : 'default'" @click="granularity = 'day'">按天</el-button>
          <el-button :type="granularity === 'week' ? 'primary' : 'default'" @click="granularity = 'week'">按周</el-button>
        </el-button-group>
      </div>
    </div>

    <div class="grid stat-grid" style="margin-bottom: 16px">
      <div class="card stat-card">
        <div class="stat-label">
          <el-icon><Histogram /></el-icon> 今日构建总数
        </div>
        <div class="stat-value">
          {{ summary?.today.total ?? 0 }}<span class="stat-unit">次</span>
        </div>
        <div class="stat-foot">
          <span :class="deltaClass(deltaValue(summary?.today.total ?? 0, summary?.yesterday.total ?? 0))">
            {{ deltaText(deltaValue(summary?.today.total ?? 0, summary?.yesterday.total ?? 0)) }}
          </span>
          <span>较昨日</span>
        </div>
      </div>

      <div class="card stat-card">
        <div class="stat-label">
          <el-icon><CircleCheck /></el-icon> 今日成功率
        </div>
        <div class="stat-value">
          {{ summary ? summary.today.successRate.toFixed(1) : '0.0' }}<span class="stat-unit">%</span>
        </div>
        <div class="stat-foot">
          <span
            :class="
              deltaClass(
                (summary?.today.successRate ?? 0) - (summary?.yesterday.successRate ?? 0),
              )
            "
          >
            {{ deltaText((summary?.today.successRate ?? 0) - (summary?.yesterday.successRate ?? 0)) }}
          </span>
          <span>成功 {{ summary?.today.success ?? 0 }} / 失败 {{ summary?.today.failed ?? 0 }}</span>
        </div>
      </div>

      <div class="card stat-card">
        <div class="stat-label">
          <el-icon><Timer /></el-icon> 平均构建耗时
        </div>
        <div class="stat-value" style="font-size: 26px">
          {{ formatDuration(summary?.today.avgDurationMs) }}
        </div>
        <div class="stat-foot">
          <span class="delta flat">昨日 {{ formatDuration(summary?.yesterday.avgDurationMs) }}</span>
        </div>
      </div>

      <div class="card stat-card">
        <div class="stat-label">
          <el-icon><WarningFilled /></el-icon> 今日失败次数
        </div>
        <div class="stat-value" :style="{ color: (summary?.today.failed ?? 0) > 0 ? 'var(--danger)' : undefined }">
          {{ summary?.today.failed ?? 0 }}<span class="stat-unit">次</span>
        </div>
        <div class="stat-foot">
          <span :class="deltaClass(deltaValue(summary?.today.failed ?? 0, summary?.yesterday.failed ?? 0), true)">
            {{ deltaText(deltaValue(summary?.today.failed ?? 0, summary?.yesterday.failed ?? 0)) }}
          </span>
          <span>较昨日 · 队列 {{ summary?.queueDepth ?? 0 }}</span>
        </div>
      </div>
    </div>

    <div class="grid" style="grid-template-columns: minmax(0, 1.75fr) minmax(0, 1fr); margin-bottom: 16px">
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">构建趋势</div>
            <div class="card-hint">近 {{ days }} 天运行量与成功率</div>
          </div>
          <el-tag size="small" effect="plain" round>{{ granularity === 'day' ? '按天聚合' : '按周聚合' }}</el-tag>
        </div>
        <div class="card-pad">
          <BaseChart :option="trendOption" :height="300" :loading="chartsLoading" />
        </div>
      </div>

      <div class="card" style="display: flex; flex-direction: column">
        <div class="card-head">
          <div>
            <div class="card-title">实时事件流</div>
            <div class="card-hint">SSE 秒级推送</div>
          </div>
          <span class="row" style="font-size: 12px; color: var(--muted)">
            <span :class="connected ? 'live-dot pulse' : 'live-dot off'" />
            {{ connected ? '已连接' : '未连接' }}
          </span>
        </div>
        <div class="stream">
          <div v-if="!feed.length" class="empty">暂无事件，触发一次 Webhook 试试</div>
          <div
            v-for="item in feed"
            :key="item.key"
            class="stream-item"
            :class="{ fresh: item.fresh }"
            style="cursor: pointer"
            @click="openRun(item.runId)"
          >
            <div
              class="stream-icon"
              :style="{
                background:
                  item.conclusion === 'failure'
                    ? 'var(--danger-soft)'
                    : item.conclusion === 'success'
                      ? 'var(--success-soft)'
                      : 'var(--primary-soft)',
                color:
                  item.conclusion === 'failure'
                    ? 'var(--danger)'
                    : item.conclusion === 'success'
                      ? 'var(--success)'
                      : 'var(--primary)',
              }"
            >
              <el-icon>
                <CloseBold v-if="item.conclusion === 'failure'" />
                <Check v-else-if="item.conclusion === 'success'" />
                <Loading v-else />
              </el-icon>
            </div>
            <div class="stream-body">
              <div class="stream-title">
                <span>{{ item.title }}</span>
                <span class="text-muted" style="font-weight: 400; font-size: 12px">{{ item.repo }}</span>
              </div>
              <div class="stream-meta">
                <span v-if="item.branch" class="branch-chip">{{ item.branch }}</span>
                <span v-if="item.actor">{{ item.actor }}</span>
                <span v-if="item.durationMs">{{ formatDurationShort(item.durationMs) }}</span>
                <span>{{ shortenMessage(item.message, 34) }}</span>
              </div>
            </div>
            <div class="stream-time">{{ formatTime(new Date(item.at).toISOString()) }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="grid" style="grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr)">
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">工作流健康度</div>
            <div class="card-hint">失败率越高越需要关注</div>
          </div>
        </div>
        <el-table :data="workflows" style="width: 100%" :show-header="true" empty-text="暂无数据">
          <el-table-column prop="workflowName" label="工作流" min-width="150" show-overflow-tooltip />
          <el-table-column prop="total" label="运行" width="70" align="right" />
          <el-table-column label="失败率" width="150">
            <template #default="{ row }">
              <div class="row" style="gap: 8px">
                <div class="bar-track" style="width: 72px; flex: none">
                  <div
                    class="bar-fill"
                    :class="row.failureRate > 20 ? 'bad' : row.failureRate > 10 ? 'warn' : ''"
                    :style="{ width: `${Math.min(Math.max(row.failureRate, 0), 100)}%` }"
                  />
                </div>
                <span style="font-variant-numeric: tabular-nums; font-size: 12px; width: 44px; text-align: right">
                  {{ percent(row.failureRate, 1) }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="平均耗时" width="96" align="right">
            <template #default="{ row }">{{ formatDurationShort(row.avgDurationMs) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">耗时对比</div>
            <div class="card-hint">红色代表失败率偏高</div>
          </div>
        </div>
        <div class="card-pad">
          <BaseChart :option="durationOption" :height="260" :loading="chartsLoading" />
        </div>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-head">
        <div>
          <div class="card-title">失败原因分布</div>
          <div class="card-hint">近 {{ days }} 天 · 从失败的 job 日志自动归类</div>
        </div>
        <span v-if="failureStats && failureStats.totalFailed" class="text-muted" style="font-size: 12px">
          {{ failureStats.totalFailed }} 次失败
        </span>
      </div>

      <div v-if="!failureStats || failureStats.totalFailed === 0" class="reason-empty">
        近 {{ days }} 天没有失败记录
      </div>

      <div v-else-if="!failureStats.reasons.length" class="reason-empty">
        有 {{ failureStats.unclassified }} 次失败尚未归类
        <div class="reason-tip">
          {{
            failureStats.known
              ? '打开失败运行的 job 日志会自动归类'
              : '配置 GITHUB_TOKEN 后可自动归类'
          }}
        </div>
      </div>

      <div v-else class="card-pad">
        <div class="reason-list">
          <div v-for="reason in failureStats.reasons" :key="reason.failureClass" class="reason-row">
            <span class="reason-label">{{ reason.label }}</span>
            <div class="bar-track" style="flex: 1">
              <div
                class="bar-fill"
                :class="reason.failureClass === 'unknown' ? 'muted' : ''"
                :style="{ width: `${Math.min(Math.max(reason.percent, 0), 100)}%` }"
              />
            </div>
            <span class="reason-count">{{ reason.count }}</span>
            <span class="reason-percent">{{ percent(reason.percent, 1) }}</span>
          </div>
        </div>
      </div>

      <div
        v-if="failureStats && failureStats.unclassified > 0 && failureStats.reasons.length"
        class="reason-foot"
      >
        另有 {{ failureStats.unclassified }} 次未归类
        <span class="reason-tip">
          {{ failureStats.known ? '· 打开对应运行的日志会自动补上' : '· 配置 GITHUB_TOKEN 后可自动归类' }}
        </span>
      </div>
    </div>
  </div>
</template>
