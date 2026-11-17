<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import echarts from '@/utils/echarts'

const props = withDefaults(defineProps<{ option: EChartsOption; height?: number; loading?: boolean }>(), {
  height: 280,
  loading: false,
})

const el = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) return
  chart.value = echarts.init(el.value, undefined, { renderer: 'canvas' })
  chart.value.setOption(props.option)
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(el.value)
})

watch(
  () => props.option,
  (option) => {
    chart.value?.setOption(option, { notMerge: true })
  },
  { deep: true },
)

watch(
  () => props.loading,
  (loading) => {
    if (loading) chart.value?.showLoading('default', { text: '', color: '#2f5bff', maskColor: 'rgba(255,255,255,0.7)' })
    else chart.value?.hideLoading()
  },
)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div ref="el" :style="{ height: `${height}px`, width: '100%' }" />
</template>
