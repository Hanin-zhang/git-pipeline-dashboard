<script setup lang="ts">
import type { RunJob, RunStep } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime, formatDuration, formatDurationShort, stepDuration, toneOf } from '@/utils/format'

const props = defineProps<{
  jobs: RunJob[]
  expanded: Record<number, boolean>
  selectedId?: number | null
  /** `${jobId}-${stepNumber}`，用于从失败摘要跳转时高亮目标步骤 */
  highlightedStep?: string | null
}>()

const emit = defineEmits<{
  toggle: [jobId: number]
  select: [jobId: number]
  openStep: [job: RunJob, step: RunStep]
}>()

function isFailed(step: RunStep): boolean {
  return step.conclusion === 'failure' || step.conclusion === 'timed_out'
}

function stepTone(step: RunStep) {
  return toneOf(step.status, step.conclusion)
}

function openGithub(url?: string) {
  if (url) window.open(url, '_blank', 'noopener')
}
</script>

<template>
  <div>
    <div v-if="!jobs.length" class="empty">
      暂无作业明细。真实 GitHub 的 workflow_run 事件不携带 jobs，接入 workflow_job 事件后会补齐。
    </div>

    <div
      v-for="job in jobs"
      :id="`job-${job.id}`"
      :key="job.id"
      class="job"
      :class="{ 'job-selected': selectedId === job.id }"
    >
      <div class="job-head" :class="{ failed: job.conclusion === 'failure' }" @click="emit('toggle', job.id)">
        <div class="job-name">
          <el-icon class="chevron">
            <ArrowRight v-if="!expanded[job.id]" />
            <ArrowDown v-else />
          </el-icon>
          <span class="name-text">{{ job.name }}</span>
          <StatusPill :status="job.status" :conclusion="job.conclusion" />
        </div>
        <div class="job-meta">
          <span v-if="job.runnerName" class="meta-strong">{{ job.runnerName }}</span>
          <span>{{ job.steps.length }} 个步骤</span>
          <span class="dur">{{ formatDurationShort(job.durationMs) }}</span>
          <span v-if="job.startedAt" class="time">
            {{ formatDateTime(job.startedAt) }}
            <span class="text-muted">→</span>
            {{ job.completedAt ? formatDateTime(job.completedAt).slice(6) : '进行中' }}
          </span>
        </div>
      </div>

      <el-collapse-transition>
        <div v-show="expanded[job.id]">
          <div
            v-for="step in job.steps"
            :key="`${job.id}-${step.number}`"
            class="step-row"
            :class="{ failed: isFailed(step), 'step-flash': highlightedStep === `${job.id}-${step.number}` }"
            @click="emit('openStep', job, step)"
          >
            <div class="step-main">
              <el-icon
                class="step-icon"
                :class="`tone-${stepTone(step)}`"
              >
                <CircleCheck v-if="stepTone(step) === 'success'" />
                <CircleClose v-else-if="stepTone(step) === 'failure'" />
                <Loading v-else-if="stepTone(step) === 'running'" />
                <RemoveFilled v-else-if="stepTone(step) === 'cancelled'" />
                <Clock v-else />
              </el-icon>
              <span class="step-name">{{ step.name }}</span>
              <span class="step-dur">{{ formatDurationShort(stepDuration(step)) }}</span>
              <el-icon class="step-open"><ArrowRight /></el-icon>
            </div>

            <!-- 失败步骤默认展开：直接给出时间线与日志入口，避免再点一次 -->
            <div v-if="isFailed(step)" class="step-fail-panel" @click.stop>
              <div class="fail-line">
                <span>失败步骤</span>
                <span>{{ formatDateTime(step.startedAt) }} → {{ formatDateTime(step.completedAt) }}</span>
                <span>耗时 {{ formatDuration(stepDuration(step)) }}</span>
              </div>
              <div class="fail-note">
                <el-icon><InfoFilled /></el-icon>
                日志文本未接入（GitHub 事件不包含日志），请通过下方按钮到 GitHub 查看该步骤输出。
              </div>
              <div class="fail-actions">
                <el-button size="small" text type="primary" @click="emit('openStep', job, step)">
                  查看步骤详情
                </el-button>
                <el-button
                  v-if="job.htmlUrl"
                  size="small"
                  text
                  @click="openGithub(job.htmlUrl)"
                >
                  在 GitHub 打开
                </el-button>
              </div>
            </div>
          </div>

          <div v-if="!job.steps.length" class="step-row muted">该 job 没有步骤明细</div>
        </div>
      </el-collapse-transition>
    </div>
  </div>
</template>

<style scoped>
.job {
  border: 1px solid #e5e6eb;
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 10px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
  background: #fff;
}

.job-selected {
  border-color: #91caff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.08);
}

.job-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 14px;
  background: #fafbfd;
  cursor: pointer;
  transition: background 0.15s ease;
}

.job-head:hover {
  background: #f4f6fa;
}

.job-head.failed {
  background: #fff8f8;
}

.job-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 600;
  font-size: 13.5px;
  color: #1d2129;
}

.chevron {
  color: #86909c;
  flex: none;
}

.name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.job-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #86909c;
  flex: none;
  font-variant-numeric: tabular-nums;
}

.meta-strong {
  color: #4e5969;
}

.dur {
  color: #4e5969;
  font-weight: 550;
}

.step-row {
  padding: 7px 14px 7px 18px;
  border-top: 1px solid #f4f6fa;
  cursor: pointer;
  transition: background 0.14s ease;
}

.step-row:hover {
  background: #fafbfd;
}

.step-row.failed {
  background: #fffafa;
}

.step-flash {
  animation: step-flash 1.4s ease-in-out 2;
}

@keyframes step-flash {
  0%,
  100% {
    background: transparent;
  }
  40% {
    background: #fff7e6;
  }
}

.step-main {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12.5px;
}

.step-icon {
  font-size: 14px;
  flex: none;
}

.step-icon.tone-success {
  color: #12a150;
}
.step-icon.tone-failure {
  color: #e5484d;
}
.step-icon.tone-running {
  color: #1677ff;
}
.step-icon.tone-cancelled {
  color: #fa8c16;
}
.step-icon.tone-queued,
.step-icon.tone-skipped,
.step-icon.tone-neutral {
  color: #a9aeb8;
}

.step-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1d2129;
}

.step-row.failed .step-name {
  color: #e5484d;
  font-weight: 550;
}

.step-dur {
  color: #86909c;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  flex: none;
}

.step-open {
  color: #c9cdd4;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.14s ease;
}

.step-row:hover .step-open {
  opacity: 1;
}

.step-fail-panel {
  margin: 6px 0 2px 23px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #ffccc7;
  cursor: default;
}

.fail-line {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 11.5px;
  color: #a8716f;
  font-variant-numeric: tabular-nums;
}

.fail-note {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 6px;
  font-size: 11.5px;
  color: #86909c;
  line-height: 1.6;
}

.fail-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}

.step-row.muted {
  color: #86909c;
  font-size: 12px;
  cursor: default;
  padding-left: 18px;
}
</style>
