<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { RunDetail, RunJob, RunStep } from '@/api/types'
import { formatDateTime, formatDuration } from '@/utils/format'

/**
 * 顶部状态摘要。失败时给红色横幅 + 定位入口；运行中/成功/取消时给对应色调的轻量提示，
 * 不再出现「成功也挂着红色报错」的情况。
 *
 * 错误文本来自 GitHub 事件？不会。workflow_run 事件不包含错误信息，所以这里明确给出
 * 空状态并引导去 GitHub 看日志，而不是编造一条错误原因。
 */
const props = defineProps<{
  run: RunDetail | null
  failedJob?: RunJob
  failedStep?: RunStep
}>()

const emit = defineEmits<{ locate: []; openGithub: [url?: string] }>()

const failedJobIndex = computed(() => {
  const jobs = props.run?.jobs ?? []
  const index = jobs.findIndex((job) => job.id === props.failedJob?.id)
  return index >= 0 ? index + 1 : 0
})

const totalJobs = computed(() => props.run?.jobs.length ?? 0)

const runningJob = computed(() =>
  (props.run?.jobs ?? []).find((job) => job.status === 'in_progress'),
)

const tone = computed(() => {
  const conclusion = props.run?.conclusion
  if (conclusion === 'failure' || conclusion === 'timed_out' || conclusion === 'startup_failure') return 'failure'
  if (conclusion === 'cancelled') return 'cancelled'
  if (props.run?.status === 'in_progress' || props.run?.status === 'queued') return 'running'
  if (conclusion === 'success') return 'success'
  return 'neutral'
})

const passedJobs = computed(
  () => (props.run?.jobs ?? []).filter((job) => job.conclusion === 'success').length,
)

/* 运行中时显示实时累加的总耗时，不用刷新页面也能看到时间在走 */
const now = ref(Date.now())
const ticker = window.setInterval(() => {
  if (tone.value === 'running') now.value = Date.now()
}, 1000)
onUnmounted(() => window.clearInterval(ticker))

const liveElapsed = computed(() => {
  if (tone.value !== 'running' || !props.run?.startedAt) return null
  const started = new Date(props.run.startedAt).getTime()
  if (Number.isNaN(started)) return null
  return Math.max(0, now.value - started)
})
</script>

<template>
  <div class="run-alert" :class="tone">
    <!-- 失败 -->
    <template v-if="tone === 'failure'">
      <div class="alert-row">
        <el-icon class="alert-icon"><CircleCloseFilled /></el-icon>
        <div class="alert-body">
          <div class="alert-title">
            运行失败
            <template v-if="failedJob">
              ：job「{{ failedJob.name }}」
              <template v-if="failedStep">在步骤「{{ failedStep.name }}」中断</template>
            </template>
          </div>
          <div class="alert-detail">
            <span v-if="failedJob">第 {{ failedJobIndex }} / {{ totalJobs }} 个 job</span>
            <span v-if="failedStep?.completedAt">失败时间 {{ formatDateTime(failedStep.completedAt) }}</span>
            <span v-if="failedStep">该步骤耗时 {{ formatDuration(
              failedStep.startedAt && failedStep.completedAt
                ? new Date(failedStep.completedAt).getTime() - new Date(failedStep.startedAt).getTime()
                : undefined,
            ) }}</span>
          </div>
        </div>
        <div class="alert-actions">
          <el-button size="small" type="danger" plain @click="emit('locate')">定位失败步骤</el-button>
          <el-button size="small" text @click="emit('openGithub', failedJob?.htmlUrl || run?.htmlUrl)">
            查看日志
          </el-button>
        </div>
      </div>

    </template>

    <!-- 运行中 -->
    <template v-else-if="tone === 'running'">
      <div class="alert-row">
        <el-icon class="alert-icon spinning"><Loading /></el-icon>
        <div class="alert-body">
          <div class="alert-title">
            {{ run?.status === 'queued' ? '排队中，等待 runner 分配' : '正在执行' }}
            <template v-if="runningJob">：当前 job「{{ runningJob.name }}」</template>
          </div>
          <div class="alert-detail">
            <span>{{ passedJobs }} / {{ totalJobs }} 个 job 已完成</span>
            <span v-if="liveElapsed !== null" class="live">已运行 {{ formatDuration(liveElapsed) }}</span>
            <span v-if="run?.startedAt">开始于 {{ formatDateTime(run.startedAt) }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 成功 -->
    <template v-else-if="tone === 'success'">
      <div class="alert-row">
        <el-icon class="alert-icon"><CircleCheckFilled /></el-icon>
        <div class="alert-body">
          <div class="alert-title">运行成功</div>
          <div class="alert-detail">
            <span>全部 {{ totalJobs }} 个 job 通过</span>
            <span>总耗时 {{ formatDuration(run?.durationMs) }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 取消 / 其它 -->
    <template v-else>
      <div class="alert-row">
        <el-icon class="alert-icon"><RemoveFilled /></el-icon>
        <div class="alert-body">
          <div class="alert-title">运行已结束：{{ run?.conclusion || '未产生结论' }}</div>
          <div class="alert-detail">
            <span>{{ passedJobs }} / {{ totalJobs }} 个 job 成功</span>
            <span v-if="run?.completedAt">结束于 {{ formatDateTime(run.completedAt) }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.run-alert {
  border-radius: 12px;
  border: 1px solid #e5e6eb;
  background: #fff;
  padding: 12px 16px;
  margin-bottom: 16px;
}

.run-alert.failure {
  background: #fff8f8;
  border-color: #ffccc7;
}

.run-alert.running {
  background: #f5f9ff;
  border-color: #d6e4ff;
}

.run-alert.success {
  background: #f6ffed;
  border-color: #d9f7be;
}

.run-alert.cancelled,
.run-alert.neutral {
  background: #fafbfd;
}

.alert-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.alert-icon {
  font-size: 17px;
  margin-top: 1px;
  flex: none;
}

.alert-icon.spinning {
  animation: alert-spin 1s linear infinite;
}

@keyframes alert-spin {
  to {
    transform: rotate(360deg);
  }
}

.alert-detail .live {
  color: #1677ff;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}

.failure .alert-icon {
  color: #f5222d;
}
.running .alert-icon {
  color: #1677ff;
}
.success .alert-icon {
  color: #52c41a;
}
.cancelled .alert-icon,
.neutral .alert-icon {
  color: #86909c;
}

.alert-body {
  flex: 1;
  min-width: 0;
}

.alert-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #1d2129;
  line-height: 1.5;
}

.alert-detail {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #86909c;
  margin-top: 4px;
}

.alert-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

</style>
