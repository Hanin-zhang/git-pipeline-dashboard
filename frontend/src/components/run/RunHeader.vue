<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { runsApi } from '@/api'
import type { RunDetail } from '@/api/types'
import { copyText, formatDateTime, formatDuration, shortSha, statusMeta } from '@/utils/format'

const props = defineProps<{
  run: RunDetail | null
  /** 服务端能力开关：未配置 GITHUB_TOKEN 时按钮禁用，不发请求 */
  actionsEnabled: boolean
}>()

const emit = defineEmits<{ locate: []; refresh: [] }>()

const meta = computed(() => statusMeta(props.run?.status, props.run?.conclusion))
const isRunning = computed(
  () => props.run?.status === 'in_progress' || props.run?.status === 'queued',
)
const isFailed = computed(
  () => props.run?.conclusion === 'failure' || props.run?.conclusion === 'timed_out',
)
const busy = ref<'rerun' | 'cancel' | null>(null)

async function doRerun() {
  if (!props.run) return
  busy.value = 'rerun'
  try {
    const result = await runsApi.rerun(props.run.id)
    ElMessage.success(result.message)
    emit('refresh')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = null
  }
}

async function doCancel() {
  if (!props.run) return
  busy.value = 'cancel'
  try {
    const result = await runsApi.cancel(props.run.id)
    ElMessage.success(result.message)
    emit('refresh')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = null
  }
}

async function copy(value: string | undefined | null, label: string) {
  const ok = await copyText(value)
  ElMessage[ok ? 'success' : 'warning'](ok ? `${label}已复制` : '浏览器拒绝了剪贴板访问，请手动复制')
}

function openGithub(url?: string) {
  if (url) window.open(url, '_blank', 'noopener')
}

const DISABLED_HINT = '未配置 GITHUB_TOKEN：后端不会伪造成功，配置后即可使用'
</script>

<template>
  <div class="run-head">
    <div class="head-top">
      <div class="head-main">
        <div class="head-title">
          <h1 class="page-title">
            {{ run?.workflowName || '运行详情' }}
            <span v-if="run?.runNumber" class="run-no">#{{ run.runNumber }}</span>
          </h1>
          <span class="status-pill" :class="meta.className">
            <span class="dot" />{{ meta.label }}
          </span>
        </div>
        <div class="head-sub">
          <span>{{ run?.repoFullName }}</span>
          <span v-if="run?.branch" class="branch-chip">{{ run.branch }}</span>
          <span v-if="run?.eventName" class="text-muted">由 {{ run.eventName }} 触发</span>
        </div>
      </div>

      <div class="head-actions">
        <el-tooltip v-if="!actionsEnabled" :content="DISABLED_HINT" placement="bottom">
          <span>
            <el-button disabled>
              <el-icon style="margin-right: 4px">
                <CircleClose v-if="isRunning" />
                <RefreshRight v-else />
              </el-icon>
              {{ isRunning ? '取消运行' : '重新运行' }}
            </el-button>
          </span>
        </el-tooltip>
        <el-button
          v-else-if="isRunning"
          :loading="busy === 'cancel'"
          @click="doCancel"
        >
          <el-icon style="margin-right: 4px"><CircleClose /></el-icon>取消运行
        </el-button>
        <el-button v-else :loading="busy === 'rerun'" @click="doRerun">
          <el-icon style="margin-right: 4px"><RefreshRight /></el-icon>重新运行
        </el-button>

        <el-button v-if="isFailed" type="danger" plain @click="emit('locate')">
          <el-icon style="margin-right: 4px"><Aim /></el-icon>定位失败步骤
        </el-button>
        <el-button @click="copy(run?.htmlUrl, '运行链接')">
          <el-icon style="margin-right: 4px"><CopyDocument /></el-icon>复制链接
        </el-button>
        <el-button v-if="run?.htmlUrl" type="primary" @click="openGithub(run.htmlUrl)">
          <el-icon style="margin-right: 4px"><Link /></el-icon>在 GitHub 查看
        </el-button>
      </div>
    </div>

    <div class="head-meta">
      <button class="meta-chip" :title="'点击复制完整 SHA'" @click="copy(run?.commitSha, 'Commit SHA')">
        <el-icon><Commit /></el-icon>
        <span class="mono">{{ shortSha(run?.commitSha) }}</span>
        <el-icon class="chip-copy"><CopyDocument /></el-icon>
      </button>
      <span class="meta-item">
        <el-icon><User /></el-icon>{{ run?.actor || '—' }}
      </span>
      <span class="meta-item">
        <el-icon><Timer /></el-icon>{{ formatDuration(run?.durationMs) }}
      </span>
      <span class="meta-item">
        <el-icon><Calendar /></el-icon>
        {{ formatDateTime(run?.startedAt) }}
        <span class="text-muted" style="margin: 0 4px">→</span>
        {{ formatDateTime(run?.completedAt) }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.run-head {
  margin-bottom: 16px;
}

.head-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.head-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.run-no {
  font-weight: 500;
  font-size: 15px;
  color: #86909c;
  letter-spacing: 0;
}

.head-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #4e5969;
  margin-top: 6px;
  flex-wrap: wrap;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.head-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid #e5e6eb;
  background: #fff;
  color: #4e5969;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.meta-chip:hover {
  border-color: #c9cdd4;
  background: #fafbfd;
}

.chip-copy {
  opacity: 0;
  transition: opacity 0.15s ease;
}

.meta-chip:hover .chip-copy {
  opacity: 0.6;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  color: #86909c;
}
</style>
