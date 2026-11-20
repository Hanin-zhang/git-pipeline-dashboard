<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { runsApi } from '@/api'
import type { RunFilters, RunSummary } from '@/api/types'
import StatusPill from '@/components/StatusPill.vue'
import MiniPipeline from '@/components/MiniPipeline.vue'
import { useRunStream } from '@/composables/useRunStream'
import { formatDateTime, formatDurationShort, shortSha, shortenMessage } from '@/utils/format'

const router = useRouter()
const { events } = useRunStream()

const rows = ref<RunSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const keyword = ref('')
const status = ref('all')
const repo = ref('')
const branch = ref('')
const range = ref<[string, string] | null>(null)

const filters = ref<RunFilters>({ repos: [], branches: [], statuses: [] })
const isFiltered = computed(
  () => !!(keyword.value || repo.value || branch.value || status.value !== 'all' || range.value),
)

let searchTimer: number | null = null

async function load() {
  loading.value = true
  try {
    const res = await runsApi.list({
      page: page.value,
      size: size.value,
      status: status.value,
      repo: repo.value || undefined,
      branch: branch.value || undefined,
      q: keyword.value || undefined,
      from: range.value?.[0],
      to: range.value?.[1],
    })
    rows.value = res.items
    total.value = res.total
  } finally {
    loading.value = false
  }
}

async function loadFilters() {
  filters.value = await runsApi.filters()
}

function reset() {
  keyword.value = ''
  status.value = 'all'
  repo.value = ''
  branch.value = ''
  range.value = null
  page.value = 1
  load()
}

function openRun(row: RunSummary) {
  router.push(`/runs/${row.id}`)
}

function openGithub(url?: string) {
  if (url) window.open(url, '_blank', 'noopener')
}

function onSizeChange() {
  page.value = 1
  load()
}

watch([status, repo, branch], () => {
  page.value = 1
  load()
})

watch(range, () => {
  page.value = 1
  load()
})

watch(keyword, () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 350)
})

watch(page, () => load())

// A new run arrived: refresh only when the user is looking at the newest page unfiltered,
// otherwise the table would jump under their cursor.
let refreshTimer: number | null = null
watch(events, (list) => {
  if (!list.length || page.value !== 1) return
  if (refreshTimer !== null) return
  refreshTimer = window.setTimeout(() => {
    refreshTimer = null
    load()
  }, 1500)
})

onMounted(async () => {
  await Promise.all([load(), loadFilters()])
})

onUnmounted(() => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
})
</script>

<template>
  <div>
    <div class="page-intro">
      <div>
        <h1 class="page-title">流水线列表</h1>
        <div class="page-desc">共 {{ total }} 条运行记录，点击任意一行查看详情</div>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="card">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="搜索工作流 / 提交信息 / 触发人 / 分支"
          clearable
          style="width: 280px"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-select v-model="status" style="width: 132px">
          <el-option v-for="item in filters.statuses" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-select v-model="repo" placeholder="全部仓库" clearable style="width: 180px">
          <el-option label="全部仓库" value="" />
          <el-option v-for="item in filters.repos" :key="item" :label="item" :value="item" />
        </el-select>

        <el-select v-model="branch" placeholder="全部分支" clearable style="width: 150px">
          <el-option label="全部分支" value="" />
          <el-option v-for="item in filters.branches" :key="item" :label="item" :value="item" />
        </el-select>

        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 240px"
        />

        <div class="spacer" />
        <el-button v-if="isFiltered" text @click="reset">清除筛选</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        style="width: 100%"
        row-class-name="clickable"
        empty-text="没有符合条件的运行记录"
        @row-click="openRun"
      >
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <StatusPill :status="row.status" :conclusion="row.conclusion" />
          </template>
        </el-table-column>

        <el-table-column label="工作流" min-width="190">
          <template #default="{ row }">
            <div style="font-weight: 560">{{ row.workflowName || '未命名工作流' }}</div>
            <div class="text-muted" style="font-size: 12px; margin-top: 2px">
              #{{ row.runNumber ?? '—' }} · {{ row.eventName || 'unknown' }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="流水线" width="150">
          <template #default="{ row }">
            <MiniPipeline :stages="row.stages" />
          </template>
        </el-table-column>

        <el-table-column label="仓库" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-soft">{{ row.repoFullName }}</span>
          </template>
        </el-table-column>

        <el-table-column label="分支" min-width="120">
          <template #default="{ row }">
            <span class="branch-chip">{{ row.branch || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="触发人" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.actor || '—' }}</template>
        </el-table-column>

        <el-table-column label="提交" min-width="200">
          <template #default="{ row }">
            <div class="mono" style="color: var(--muted)">{{ shortSha(row.commitSha) }}</div>
            <div class="text-muted" style="font-size: 12px; margin-top: 2px">
              {{ shortenMessage(row.commitMessage, 34) }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="耗时" width="92" align="right">
          <template #default="{ row }">
            <span style="font-variant-numeric: tabular-nums">{{ formatDurationShort(row.durationMs) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="开始时间" width="150">
          <template #default="{ row }">
            <span class="text-muted" style="font-variant-numeric: tabular-nums">
              {{ formatDateTime(row.startedAt) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="" width="70" align="right">
          <template #default="{ row }">
            <el-button text size="small" @click.stop="openGithub(row.htmlUrl)">
              <el-icon><TopRight /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="between" style="padding: 14px 20px; border-top: 1px solid var(--border)">
        <span class="text-muted" style="font-size: 12.5px">
          第 {{ page }} 页 · 每页 {{ size }} 条
        </span>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="sizes, prev, pager, next, jumper"
          background
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>
