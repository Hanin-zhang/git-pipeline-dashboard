<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { RunDetail } from '@/api/types'
import { copyText, formatDateTime } from '@/utils/format'

const props = defineProps<{ run: RunDetail | null }>()

const runners = computed(() => {
  const set = new Set<string>()
  ;(props.run?.jobs ?? []).forEach((job) => {
    if (job.runnerName) set.add(job.runnerName)
  })
  return [...set]
})

async function copy(value: string | undefined | null, label: string) {
  if (!value) return
  const ok = await copyText(value)
  ElMessage[ok ? 'success' : 'warning'](ok ? `${label}已复制` : '浏览器拒绝了剪贴板访问，请手动复制')
}
</script>

<template>
  <div class="info-panel">
    <section class="info-group">
      <h4>仓库与工作流</h4>
      <div class="info-row">
        <span class="k">仓库</span>
        <span class="v">{{ run?.repoFullName || '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">工作流</span>
        <span class="v">{{ run?.workflowName || '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">运行编号</span>
        <span class="v">#{{ run?.runNumber ?? '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">GitHub Run ID</span>
        <span class="v mono">
          {{ run?.externalRunId ?? '—' }}
          <button class="copy" title="复制 Run ID" @click="copy(String(run?.externalRunId ?? ''), 'Run ID')">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </span>
      </div>
    </section>

    <section class="info-group">
      <h4>提交</h4>
      <div class="info-row">
        <span class="k">SHA</span>
        <span class="v mono">
          {{ run?.commitSha ? run.commitSha.slice(0, 12) : '—' }}
          <button v-if="run?.commitSha" class="copy" title="复制完整 SHA" @click="copy(run?.commitSha, 'Commit SHA')">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </span>
      </div>
      <div class="info-row">
        <span class="k">提交信息</span>
        <span class="v wrap">{{ run?.commitMessage || '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">提交作者</span>
        <span class="v">{{ run?.commitAuthor || run?.actor || '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">分支</span>
        <span class="v"><span class="branch-chip">{{ run?.branch || '—' }}</span></span>
      </div>
    </section>

    <section class="info-group">
      <h4>运行环境</h4>
      <div class="info-row">
        <span class="k">触发人</span>
        <span class="v">{{ run?.actor || '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">触发事件</span>
        <span class="v">{{ run?.eventName || '—' }}</span>
      </div>
      <div class="info-row">
        <span class="k">Runner</span>
        <span class="v">
          <template v-if="runners.length">{{ runners.join(' / ') }}</template>
          <template v-else>—</template>
        </span>
      </div>
      <div class="info-row">
        <span class="k">开始</span>
        <span class="v">{{ formatDateTime(run?.startedAt) }}</span>
      </div>
      <div class="info-row">
        <span class="k">结束</span>
        <span class="v">{{ formatDateTime(run?.completedAt) }}</span>
      </div>
    </section>

    <section class="info-group">
      <h4>构建产物</h4>
      <div class="info-empty">
        <el-icon><Box /></el-icon>
        <div>
          当前数据源不含 Artifact 信息（GitHub 的 workflow_run 事件不携带产物列表）。
          接入 Actions API 后，这里会展示名称、大小、上传/过期时间与下载入口。
        </div>
      </div>
    </section>

    <section v-if="run?.deliveryId" class="info-group">
      <h4>数据链路</h4>
      <div class="info-row">
        <span class="k">Delivery ID</span>
        <span class="v mono small wrap">{{ run.deliveryId }}</span>
      </div>
      <div class="info-row">
        <span class="k">最后更新</span>
        <span class="v">{{ formatDateTime(run.updatedAt) }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.info-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.info-group h4 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: #86909c;
  letter-spacing: 0.3px;
}

.info-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 4px 0;
  font-size: 12.5px;
  line-height: 1.6;
}

.info-row .k {
  flex: none;
  width: 82px;
  color: #86909c;
}

.info-row .v {
  flex: 1;
  min-width: 0;
  color: #1d2129;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  word-break: break-word;
}

.info-row .v.wrap {
  display: block;
  overflow-wrap: anywhere;
}

.info-row .v.small {
  font-size: 11.5px;
  color: #4e5969;
}

.mono {
  font-family: 'SF Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}

.copy {
  border: none;
  background: transparent;
  color: #c0c6d4;
  cursor: pointer;
  padding: 0 2px;
  display: inline-flex;
  transition: color 0.14s ease;
}

.copy:hover {
  color: #1677ff;
}

.info-empty {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fafbfd;
  border: 1px dashed #e5e6eb;
  font-size: 11.5px;
  color: #86909c;
  line-height: 1.7;
}
</style>
