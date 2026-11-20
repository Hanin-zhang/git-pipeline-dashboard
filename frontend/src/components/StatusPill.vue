<script setup lang="ts">
import { computed } from 'vue'
import { statusMeta } from '@/utils/format'

const props = defineProps<{ status?: string | null; conclusion?: string | null }>()

const meta = computed(() => statusMeta(props.status, props.conclusion))

/** 运行中/排队中的徽标带呼吸点，让「正在进行」有动感 */
const isLive = computed(
  () => !props.conclusion && (props.status === 'in_progress' || props.status === 'queued'),
)
</script>

<template>
  <span class="status-pill" :class="[meta.className, { 'is-live': isLive }]">
    <span class="dot" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.status-pill.is-live .dot {
  animation: pill-breathe 1.2s ease-in-out infinite;
}

@keyframes pill-breathe {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.35;
    transform: scale(0.82);
  }
}
</style>
