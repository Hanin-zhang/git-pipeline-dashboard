<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { LogDigest, RunJob, RunStep } from '@/api/types'
import { runsApi } from '@/api'
import StatusPill from '@/components/StatusPill.vue'
import { formatDateTime, formatDuration, stepDuration, toneLabel, toneOf } from '@/utils/format'

const props = defineProps<{
  modelValue: boolean
  runId?: number
  job?: RunJob
  step?: RunStep
  /** 从详情页摘要点进来时，打开后直接定位到这一行 */
  initialLine?: number
  /** 错误块结束行，配合 initialLine 把整段圈出来 */
  initialEndLine?: number
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const tone = computed(() => (props.step ? toneOf(props.step.status, props.step.conclusion) : 'neutral'))

/* ---------- 执行日志：摘要优先，全文按需 ---------- */
const viewMode = ref<'summary' | 'full'>('summary')
const digest = ref<LogDigest | null>(null)
const logText = ref('')
const logError = ref('')
const loadingLog = ref(false)
const targetRange = ref<{ from: number; to: number } | null>(null)
const MAX_LINES = 2000

const logLines = computed(() => {
  if (!logText.value) return []
  return logText.value.split('\n').slice(0, MAX_LINES)
})

const verdictIcon = computed(() => {
  if (!digest.value) return '·'
  return digest.value.verdict === 'success' ? '✓' : digest.value.verdict === 'failure' ? '✕' : '·'
})

/** 点「加载日志」先取摘要：后端顺带缓存全文，切到全文不会再打一次 GitHub */
async function loadSummary() {
  if (!props.runId || !props.job?.id) return
  loadingLog.value = true
  logError.value = ''
  try {
    digest.value = await runsApi.jobLogSummary(props.runId, props.job.id)
    viewMode.value = 'summary'
  } catch (e) {
    logError.value = (e as Error).message
  } finally {
    loadingLog.value = false
  }
}

async function loadFull() {
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

// 外部要求定位（详情页摘要点击）：等抽屉打开、日志加载完再滚过去
watch(
  [() => props.modelValue, () => props.initialLine, () => props.initialEndLine],
  ([open, line, end]) => {
    if (open && line) {
      void jumpToLine(line, end ?? undefined)
    }
  },
  { immediate: true },
)

// 切视图时按需取数：全文 / 摘要各自懒加载
watch(viewMode, async (mode) => {
  if (mode === 'full' && !logText.value && !loadingLog.value && !logError.value) {
    await loadFull()
  }
  if (mode === 'summary' && !digest.value && !loadingLog.value && !logError.value) {
    await loadSummary()
  }
})

/** 摘要 → 全文：定位并高亮整段错误（不止首行，省得用户再往下翻） */
async function jumpToLine(lineNo: number, endLine?: number) {
  viewMode.value = 'full'
  if (!logText.value) await loadFull()
  await nextTick()
  const to = endLine && endLine >= lineNo ? endLine : lineNo
  targetRange.value = { from: lineNo, to }
  document.querySelector(`[data-line="${lineNo}"]`)?.scrollIntoView({ block: 'center' })
  window.setTimeout(() => {
    if (targetRange.value?.from === lineNo) targetRange.value = null
  }, 4000)
}

/** 这一行是否落在高亮区间内 */
function isHit(index: number) {
  const range = targetRange.value
  return !!range && index + 1 >= range.from && index + 1 <= range.to
}

/** 正在选文字时不触发跳转，否则没法复制 */
function hasTextSelection() {
  return (window.getSelection()?.toString().trim().length ?? 0) > 0
}

function onDigestClick(item: { lineNo: number; endLine?: number }) {
  if (hasTextSelection()) return
  void jumpToLine(item.lineNo, item.endLine)
}

// 切换 job 时清空，避免张冠李戴
watch(
  () => props.job?.id,
  () => {
    digest.value = null
    logText.value = ''
    logError.value = ''
    targetRange.value = null
    viewMode.value = 'summary'
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
            <div class="log-head-right">
              <div v-if="digest || logText" class="mode-switch">
                <el-button
                  size="small"
                  :type="viewMode === 'summary' ? 'primary' : 'default'"
                  @click="viewMode = 'summary'"
                >
                  摘要
                </el-button>
                <el-button
                  size="small"
                  :type="viewMode === 'full' ? 'primary' : 'default'"
                  @click="viewMode = 'full'"
                >
                  全文
                </el-button>
              </div>
              <el-button
                v-else
                size="small"
                text
                type="primary"
                :loading="loadingLog"
                :disabled="!runId || !job?.id"
                @click="loadSummary"
              >
                加载日志
              </el-button>
            </div>
          </div>

          <div v-if="logError" class="log-body">
            <div class="log-line error">{{ logError }}</div>
          </div>

          <!-- 注意：从详情页摘要点进来时只加载了全文，digest 为 null，
               所以这里不能用 digest 单独判断，否则会落进空状态分支 -->
          <template v-else-if="digest || logLines.length">
            <!-- 摘要视图 -->
            <div v-if="viewMode === 'summary' && digest" class="digest">
              <div class="digest-verdict" :class="digest.verdict">
                <span class="dv-icon">{{ verdictIcon }}</span>
                <span class="dv-text">{{ digest.headline }}</span>
              </div>

              <ul v-if="digest.highlights.length" class="digest-list">
                <li
                  v-for="(item, idx) in digest.highlights"
                  :key="idx"
                  @click="onDigestClick(item)"
                >
                  <div class="dl-head">
                    <span class="dl-title">{{ item.title }}</span>
                    <span v-if="item.occurrences > 1" class="dl-count">×{{ item.occurrences }}</span>
                    <span class="dl-line">第 {{ item.lineNo }} 行</span>
                  </div>
                  <div class="dl-detail">{{ item.detail }}</div>
                </li>
              </ul>
              <div v-else class="digest-empty">没有可展示的错误块</div>

              <div class="digest-foot">
                <span>
                  共 {{ digest.stats.totalLines }} 行 · 折叠 {{ digest.stats.collapsedLines }} 行无关输出
                </span>
                <span class="digest-more" @click="viewMode = 'full'">查看全文 →</span>
              </div>
            </div>

            <!-- 全文视图 -->
            <pre v-else-if="logLines.length" class="log-pre"><div
              v-for="(line, i) in logLines"
              :key="i"
              :data-line="i + 1"
              class="log-row"
              :class="[lineTone(line), { 'log-hit': isHit(i) }]"
            >{{ line || ' ' }}</div></pre>
            <div v-else class="log-body">
              <div class="log-line comment">// 日志加载中…</div>
            </div>
          </template>

          <div v-else class="log-body">
            <div class="log-line comment">// 点右上角「加载日志」，先出摘要，需要时再看全文</div>
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

/* ---------- 摘要视图 ---------- */
.log-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mode-switch {
  display: flex;
  gap: 6px;
}

.mode-switch .el-button + .el-button {
  margin-left: 0;
}

.digest {
  padding: 12px;
}

.digest-verdict {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 550;
  line-height: 1.5;
}

.digest-verdict.success {
  background: #f6ffed;
  border: 1px solid #d9f7be;
  color: #12a150;
}

.digest-verdict.failure {
  background: #fff1f0;
  border: 1px solid #ffccc7;
  color: #cf1322;
}

.digest-verdict.unknown {
  background: #f7f8fa;
  border: 1px solid #e5e6eb;
  color: #86909c;
}

.dv-icon {
  flex: none;
}

.digest-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
}

.digest-list li {
  padding: 9px 10px;
  margin-bottom: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.digest-list li:hover {
  border-color: #4d7cff;
  background: rgba(77, 124, 255, 0.12);
}

.dl-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dl-title {
  font-size: 12px;
  font-weight: 600;
  color: #e8eefc;
}

.dl-count {
  padding: 0 5px;
  border-radius: 4px;
  background: rgba(255, 180, 168, 0.18);
  color: #ffb4a8;
  font-size: 11px;
}

.dl-line {
  margin-left: auto;
  color: #8194b5;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.dl-detail {
  margin-top: 4px;
  color: #a9bcd9;
  font-family: 'SF Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.6;
  word-break: break-all;
}

.digest-empty {
  padding: 18px;
  text-align: center;
  color: #6b7a96;
  font-size: 12px;
}

.digest-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: #8ea6cc;
  font-size: 11px;
}

.digest-more {
  color: #7cc4ff;
  cursor: pointer;
  white-space: nowrap;
}

.digest-more:hover {
  text-decoration: underline;
}

/* 从摘要跳转过来的那一行 */
.log-hit {
  background: #fffbe6 !important;
  box-shadow: inset 2px 0 0 #faad14;
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
