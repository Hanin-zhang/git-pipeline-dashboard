<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { capabilitiesApi, runsApi } from '@/api'
import type { Capabilities, RunDetail, RunJob, RunStep, RunSummary } from '@/api/types'
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

onMounted(async () => {
  loadCapabilities()
  await load()
})
watch(() => route.params.id, load)
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

    <div class="stats-wrap">
      <RunStats :run="run" :history="history" />
    </div>

    <div class="card" style="margin: 16px 0">
      <div class="card-head">
        <div>
          <div class="card-title">流水线拓扑</div>
          <div class="card-hint">
            点击节点定位到下方对应 job · Ctrl/⌘ + 滚轮缩放 · 空白处拖拽平移
          </div>
        </div>
        <div class="head-right">
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

    <StepDrawer v-model="drawerOpen" :run-id="run?.id" :job="drawerJob" :step="drawerStep" />
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

@media (max-width: 1080px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
