<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { RunJob, RunStep } from '@/api/types'
import { runsApi } from '@/api'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime, formatDuration, stepDuration, toneLabel, toneOf } from '@/utils/format'

const props = defineProps<{
  modelValue: boolean
  runId?: number
  job?: RunJob
  step?: RunStep
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const tone = computed(() => (props.step ? toneOf(props.step.status, props.step.conclusion) : 'neutral'))

/* ---------- 执行日志：按需从 GitHub Actions 拉取 ---------- */
const logText = ref('')
const logError = ref('')
const loadingLog = ref(false)
const MAX_LINES = 2000

const logLines = computed(() => {
  if (!logText.value) return []
  return logText.value.split('\n').slice(0, MAX_LINES)
})

async function loadLogs() {
  if (!props.runId || !props.job?.id) return
  loadingLog.value = true
  logError.value = ''
  try {
    logText.value = await runsApi.jobLogs(props.runId, props.job.id)
  } catch (e) {
    logError.value = (e as Error).message
  } finally {
    loadingLog.value = false
  }
}

// 切换步骤时清空上一条日志，避免张冠李戴
watch(
  () => props.job?.id,
  () => {
    logText.value = ''
    logError.value = ''
  },
)

function lineTone(line: string): string {
  if (/##\[error\]|(^|\s)(error|ERROR)[:\s]/.test(line)) return 'error'
  if (/##\[warning\]|(^|\s)warn(ing)?[:\s]/i.test(line)) return 'warn'
  if (/^##\[group\]|^##\[section\]/.test(line)) return 'group'
  return ''
}

function openGithub(url?: string) {
  if (url) window.open(url, '_blank', 'noopener')
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="520px"
    :with-header="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="drawer">
      <div class="drawer-head">
        <div class="drawer-title-row">
          <span class="drawer-kind">步骤详情</span>
          <StatusPill v-if="step" :status="step.status" :conclusion="step.conclusion" />
          <el-button text size="small" class="close-btn" @click="emit('update:modelValue', false)">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
        <div class="drawer-title">{{ step?.name || '未选择步骤' }}</div>
        <div class="drawer-sub">所属 job：{{ job?.name || '—' }}</div>
      </div>

      <div class="drawer-body">
        <div class="meta-grid-2">
          <div>
            <div class="meta-label">序号</div>
            <div class="meta-value">#{{ step?.number ?? '—' }}</div>
          </div>
          <div>
            <div class="meta-label">状态</div>
            <div class="meta-value">{{ toneLabel(tone) }}</div>
          </div>
          <div>
            <div class="meta-label">开始时间</div>
            <div class="meta-value">{{ formatDateTime(step?.startedAt) }}</div>
          </div>
          <div>
            <div class="meta-label">结束时间</div>
            <div class="meta-value">{{ formatDateTime(step?.completedAt) }}</div>
          </div>
          <div>
            <div class="meta-label">耗时</div>
            <div class="meta-value">{{ formatDuration(stepDuration(step || {}) as number | undefined) }}</div>
          </div>
          <div>
            <div class="meta-label">Runner</div>
            <div class="meta-value">{{ job?.runnerName || '—' }}</div>
          </div>
        </div>

        <div class="log-block">
          <div class="log-head">
            <span class="log-title">
              <el-icon><Monitor /></el-icon>
              执行日志
            </span>
            <el-button
              v-if="!logText && !logError"
              size="small"
              text
              type="primary"
              :loading="loadingLog"
              :disabled="!runId || !job?.id"
              @click="loadLogs"
            >
              加载日志
            </el-button>
            <span v-else class="log-badge">GitHub Actions · 整个 job</span>
          </div>

          <div v-if="logError" class="log-body">
            <div class="log-line error">{{ logError }}</div>
          </div>

          <pre v-else-if="logLines.length" class="log-pre"><div
            v-for="(line, i) in logLines"
            :key="i"
            class="log-row"
            :class="lineTone(line)"
          >{{ line || ' ' }}</div></pre>

          <div v-else class="log-body">
            <div class="log-line comment">// 点右上角「加载日志」从 GitHub 拉取该 job 的标准输出</div>
            <div class="log-line comment">// 日志粒度是 job（GitHub 不提供单步日志），超过 2MB 会自动截断</div>
            <div v-if="tone === 'failure'" class="log-line error">
              <span class="log-tag">ERROR</span>
              该步骤以 {{ toneLabel(tone) }} 结束，可加载日志定位具体报错
            </div>
          </div>
        </div>

        <div class="drawer-actions">
          <el-button v-if="job?.htmlUrl" type="primary" @click="openGithub(job?.htmlUrl)">
            <el-icon style="margin-right: 4px"><Link /></el-icon>在 GitHub 查看日志
          </el-button>
          <span class="hint">接入 GitHub Token 后可在此直接展开日志、搜索关键字并定位错误行</span>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.drawer-head {
  padding: 4px 4px 14px;
  border-bottom: 1px solid #f0f2f7;
}

.drawer-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-kind {
  font-size: 12px;
  color: #86909c;
}

.close-btn {
  margin-left: auto;
}

.drawer-title {
  font-size: 17px;
  font-weight: 660;
  color: #1d2129;
  margin-top: 8px;
  letter-spacing: -0.2px;
  word-break: break-word;
}

.drawer-sub {
  font-size: 12.5px;
  color: #86909c;
  margin-top: 4px;
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 4px 4px;
}

.meta-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
  margin-bottom: 18px;
}

.meta-label {
  font-size: 11.5px;
  color: #86909c;
  margin-bottom: 3px;
}

.meta-value {
  font-size: 13px;
  color: #1d2129;
  font-variant-numeric: tabular-nums;
  word-break: break-word;
}

.log-block {
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #1f2a44;
  background: #0f1729;
}

.log-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #16203a;
  color: #c9d4ee;
  font-size: 12px;
}

.log-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.log-badge {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: #9fb0d0;
}

.log-body {
  padding: 12px;
  max-height: 280px;
  overflow: auto;
  font-family: 'SF Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.75;
}

/* 日志正文：等宽、可滚动，错误行高亮 */
.log-pre {
  margin: 0;
  padding: 10px 12px;
  max-height: 320px;
  overflow: auto;
  font-family: 'SF Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  color: #4e5969;
  background: #fbfcfe;
}

.log-row {
  padding: 0 2px;
  border-radius: 3px;
}

.log-row.error {
  background: rgba(245, 34, 45, 0.08);
  color: #cf1322;
}

.log-row.warn {
  background: rgba(250, 173, 20, 0.12);
  color: #ad6800;
}

.log-row.group {
  color: #1677ff;
  font-weight: 550;
}

.log-line {
  color: #d6e2ff;
  white-space: pre-wrap;
  word-break: break-word;
}

.log-line.comment {
  color: #6b7a99;
}

.log-line.error {
  color: #ffb3b8;
}

.log-tag {
  display: inline-block;
  padding: 0 5px;
  margin-right: 6px;
  border-radius: 4px;
  background: rgba(245, 34, 45, 0.18);
  color: #ff7875;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.drawer-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 16px;
}

.hint {
  font-size: 11.5px;
  color: #86909c;
  line-height: 1.6;
}
</style>
