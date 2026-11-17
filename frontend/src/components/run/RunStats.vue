<script setup lang="ts">
import { computed } from 'vue'
import type { RunDetail, RunSummary } from '@/api/types'
import { formatDuration, formatDurationShort, percent } from '@/utils/format'

const props = defineProps<{
  run: RunDetail | null
  /** 同 workflow 的历史运行（不含当前这条），用于成功率与耗时对比 */
  history: RunSummary[]
}>()

const jobs = computed(() => props.run?.jobs ?? [])

const counts = computed(() => {
  let success = 0
  let failure = 0
  let skipped = 0
  let cancelled = 0
  jobs.value.forEach((job) => {
    if (job.conclusion === 'success') success += 1
    else if (job.conclusion === 'failure' || job.conclusion === 'timed_out') failure += 1
    else if (job.conclusion === 'skipped' || job.conclusion === 'neutral') skipped += 1
    else if (job.conclusion === 'cancelled') cancelled += 1
  })
  return { success, failure, skipped, cancelled }
})

const slowestJob = computed(() => {
  const withDuration = jobs.value.filter((job) => (job.durationMs ?? 0) > 0)
  if (!withDuration.length) return null
  return withDuration.reduce((a, b) => ((a.durationMs ?? 0) >= (b.durationMs ?? 0) ? a : b))
})

const slowestStep = computed(() => {
  let best: { job: string; name: string; duration: number } | null = null
  jobs.value.forEach((job) => {
    job.steps.forEach((step) => {
      if (!step.startedAt || !step.completedAt) return
      const duration = new Date(step.completedAt).getTime() - new Date(step.startedAt).getTime()
      if (duration <= 0) return
      if (!best || duration > best.duration) {
        best = { job: job.name, name: step.name, duration }
      }
    })
  })
  return best as { job: string; name: string; duration: number } | null
})

const finishedHistory = computed(() =>
  props.history.filter((item) => item.conclusion === 'success' || item.conclusion === 'failure'),
)

const historyStats = computed(() => {
  const list = finishedHistory.value.slice(0, 10)
  if (!list.length) return null
  const success = list.filter((item) => item.conclusion === 'success').length
  const durations = list.filter((item) => (item.durationMs ?? 0) > 0).map((item) => item.durationMs!)
  const avg = durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : 0
  const previous = list.find((item) => item.id !== props.run?.id)
  const delta =
    previous?.durationMs && props.run?.durationMs
      ? ((props.run.durationMs - previous.durationMs) / previous.durationMs) * 100
      : null
  return {
    count: list.length,
    rate: (success / list.length) * 100,
    avg,
    delta,
  }
})
</script>

<template>
  <div class="stat-strip">
    <div class="stat-tile">
      <div class="tile-label">总耗时</div>
      <div class="tile-value">{{ formatDuration(run?.durationMs) }}</div>
    </div>
    <div class="stat-tile">
      <div class="tile-label">Job 总数</div>
      <div class="tile-value">{{ jobs.length }}</div>
    </div>
    <div class="stat-tile">
      <div class="tile-label">成功</div>
      <div class="tile-value ok">{{ counts.success }}</div>
    </div>
    <div class="stat-tile">
      <div class="tile-label">失败</div>
      <div class="tile-value" :class="counts.failure ? 'bad' : ''">{{ counts.failure }}</div>
    </div>
    <div class="stat-tile">
      <div class="tile-label">跳过 / 取消</div>
      <div class="tile-value muted">{{ counts.skipped }} / {{ counts.cancelled }}</div>
    </div>
    <div class="stat-tile wide">
      <div class="tile-label">最慢 Job</div>
      <div class="tile-value small" :title="slowestJob?.name">
        {{ slowestJob?.name || '—' }}
        <span class="tile-sub">{{ formatDurationShort(slowestJob?.durationMs) }}</span>
      </div>
    </div>
    <div class="stat-tile wide">
      <div class="tile-label">最慢 Step</div>
      <div class="tile-value small" :title="slowestStep?.name">
        {{ slowestStep?.name || '—' }}
        <span class="tile-sub">{{ formatDurationShort(slowestStep?.duration) }}</span>
      </div>
    </div>
  </div>

  <div class="history-strip">
    <template v-if="historyStats">
      <span class="hist-item">
        最近 {{ historyStats.count }} 次成功率
        <b :class="historyStats.rate >= 80 ? 'ok' : historyStats.rate >= 50 ? 'warn' : 'bad'">
          {{ percent(historyStats.rate) }}
        </b>
      </span>
      <span class="hist-divider" />
      <span class="hist-item">
        平均耗时 <b>{{ formatDuration(historyStats.avg) }}</b>
      </span>
      <span class="hist-divider" />
      <span class="hist-item">
        较上次
        <b v-if="historyStats.delta !== null" :class="historyStats.delta > 0 ? 'bad' : 'ok'">
          {{ historyStats.delta > 0 ? '+' : '' }}{{ historyStats.delta.toFixed(1) }}%
        </b>
        <b v-else class="muted">—</b>
      </span>
    </template>
    <span v-else class="hist-item muted">该 workflow 暂无更多历史运行，累计数据待积累</span>
  </div>
</template>

<style scoped>
.stat-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: 10px;
}

.stat-tile {
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  padding: 10px 12px;
  min-width: 0;
}

.stat-tile.wide {
  grid-column: span 2;
}

.tile-label {
  font-size: 11.5px;
  color: #86909c;
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tile-value {
  font-size: 18px;
  font-weight: 660;
  color: #1d2129;
  letter-spacing: -0.3px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tile-value.small {
  font-size: 12.5px;
  font-weight: 560;
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.tile-sub {
  font-weight: 500;
  color: #86909c;
  font-size: 11.5px;
  flex: none;
}

.tile-value.ok {
  color: #12a150;
}
.tile-value.bad {
  color: #e5484d;
}
.tile-value.muted {
  color: #86909c;
}

.history-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  background: #fafbfd;
  border: 1px solid #f0f2f7;
  font-size: 12.5px;
  color: #4e5969;
}

.hist-item b {
  font-variant-numeric: tabular-nums;
  font-weight: 620;
}

.hist-item b.ok {
  color: #12a150;
}
.hist-item b.warn {
  color: #d98200;
}
.hist-item b.bad {
  color: #e5484d;
}
.hist-item b.muted {
  color: #86909c;
}

.hist-divider {
  width: 1px;
  height: 12px;
  background: #e5e6eb;
}

.hist-item.muted {
  color: #86909c;
}

@media (max-width: 720px) {
  .stat-tile.wide {
    grid-column: span 3;
  }
}
</style>
