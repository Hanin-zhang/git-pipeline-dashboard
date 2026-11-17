<script setup lang="ts">
import { computed } from 'vue'
import type { RunJob, RunStep } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime, formatDuration, stepDuration, toneLabel, toneOf } from '@/utils/format'

const props = defineProps<{
  modelValue: boolean
  job?: RunJob
  step?: RunStep
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const tone = computed(() => (props.step ? toneOf(props.step.status, props.step.conclusion) : 'neutral'))

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
            <span class="log-badge">未接入</span>
          </div>
          <div class="log-body">
            <div class="log-line comment">// 日志文本来自 GitHub Actions Logs API，需配置只读 Token 后才可拉取</div>
            <div class="log-line comment">// 当前数据源（workflow_run 事件）只包含步骤状态与时间，不含标准输出</div>
            <div v-if="tone === 'failure'" class="log-line error">
              <span class="log-tag">ERROR</span>
              该步骤以 {{ toneLabel(tone) }} 结束，请前往 GitHub 查看完整错误输出
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
