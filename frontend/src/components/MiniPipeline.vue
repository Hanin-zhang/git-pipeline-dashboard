<script setup lang="ts">
import { computed } from 'vue'
import type { StageBrief } from '@/api/types'
import { formatDurationShort, statusMeta } from '@/utils/format'

/**
 * 列表页用的流水线缩略图：把每个 job 画成一个小节点，按顺序用连线串起来。
 * 完整 DAG 在运行详情页，这里只回答「这条流水线大概长什么样、断在哪」。
 */
const props = defineProps<{ stages?: StageBrief[] }>()

const MAX_VISIBLE = 6

function stateOf(stage: StageBrief): 'ok' | 'fail' | 'run' | 'idle' {
  if (stage.conclusion === 'success') return 'ok'
  if (stage.conclusion === 'failure' || stage.conclusion === 'timed_out') return 'fail'
  if (stage.status === 'in_progress' || stage.status === 'queued') return 'run'
  return 'idle'
}

const visible = computed(() => (props.stages ?? []).slice(0, MAX_VISIBLE))
const overflow = computed(() => Math.max(0, (props.stages?.length ?? 0) - MAX_VISIBLE))

const tooltip = computed(() => {
  const stages = props.stages ?? []
  if (!stages.length) return '暂无作业明细'
  return stages
    .map((s) => `${s.name}: ${statusMeta(s.status, s.conclusion).label} (${formatDurationShort(s.durationMs)})`)
    .join('\n')
})
</script>

<template>
  <el-tooltip v-if="stages?.length" placement="top" effect="dark">
    <template #content>
      <div style="white-space: pre-line; line-height: 1.7">{{ tooltip }}</div>
    </template>
    <div class="mini-pipeline">
      <span
        v-for="(stage, index) in visible"
        :key="stage.id"
        class="mini-node"
        :class="stateOf(stage)"
      >
        <i v-if="index > 0" class="mini-link" :class="stateOf(stage)" />
        <span class="mini-dot" />
      </span>
      <span v-if="overflow" class="mini-more">+{{ overflow }}</span>
    </div>
  </el-tooltip>
  <span v-else class="text-muted" style="font-size: 12px">—</span>
</template>

<style scoped>
.mini-pipeline {
  display: inline-flex;
  align-items: center;
  height: 18px;
}

.mini-node {
  display: inline-flex;
  align-items: center;
}

.mini-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
  background: #c9cdd4;
}

.mini-node.ok .mini-dot {
  background: #52c41a;
}

.mini-node.fail .mini-dot {
  background: #f5222d;
  box-shadow: 0 0 0 3px rgba(245, 34, 45, 0.12);
}

.mini-node.run .mini-dot {
  background: #1677ff;
  animation: mini-pulse 1.6s infinite;
}

.mini-link {
  width: 12px;
  height: 2px;
  background: #e5e6eb;
  display: inline-block;
}

.mini-link.ok {
  background: #b7eb8f;
}

.mini-link.fail {
  background: #ffa39e;
}

.mini-link.run {
  background: #91caff;
}

.mini-more {
  margin-left: 5px;
  font-size: 11.5px;
  color: #86909c;
}

@keyframes mini-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(22, 119, 255, 0.35);
  }
  50% {
    box-shadow: 0 0 0 4px rgba(22, 119, 255, 0.06);
  }
}
</style>
