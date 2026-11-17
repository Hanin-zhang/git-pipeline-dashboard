<script setup lang="ts">
import { computed } from 'vue'
import { statusMeta } from '@/utils/format'

const props = defineProps<{ status?: string | null; conclusion?: string | null }>()

const meta = computed(() => statusMeta(props.status, props.conclusion))
</script>

<template>
  <span class="status-pill" :class="meta.className">
    <span class="dot" />
    {{ meta.label }}
  </span>
</template>
