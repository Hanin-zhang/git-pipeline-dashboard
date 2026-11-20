<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { onApiError, ApiError } from '@/api/client'
import { useRunStream } from '@/composables/useRunStream'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { connected, reconnecting } = useRunStream()

const offline = ref(false)
let unsubscribe: (() => void) | null = null

const title = computed(() => (route.meta.title as string) || 'GitHub Look')
const subtitle = computed(() => (route.meta.subtitle as string) || '')

const streamState = computed(() => {
  if (connected.value) return { text: '实时通道已连接', cls: 'live-dot pulse' }
  if (reconnecting.value) return { text: '实时通道重连中', cls: 'live-dot off' }
  return { text: '实时通道未连接', cls: 'live-dot off' }
})

async function handleLogout() {
  await auth.logout()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}

onMounted(() => {
  unsubscribe = onApiError((error: ApiError | null) => {
    if (!error) {
      offline.value = false
      return
    }
    offline.value = error.status === 0
    // Session expired while the page was open: drop straight back to the login form.
    if (error.status === 401 && route.name !== 'login') {
      auth.clear()
      router.replace({ name: 'login', query: { redirect: route.fullPath } })
    }
  })
})

onUnmounted(() => unsubscribe?.())
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">GL</div>
        <div>
          <div class="brand-title">GitHub Look</div>
          <div class="brand-sub">CI/CD 观测看板</div>
        </div>
      </div>

      <nav class="nav">
        <RouterLink to="/" class="nav-item" :class="{ active: route.name === 'dashboard' }">
          <el-icon><Odometer /></el-icon>
          <span>全局概览</span>
        </RouterLink>
        <RouterLink
          to="/pipelines"
          class="nav-item"
          :class="{ active: route.name === 'pipelines' || route.name === 'run-detail' }"
        >
          <el-icon><Files /></el-icon>
          <span>流水线列表</span>
        </RouterLink>
        <RouterLink to="/settings" class="nav-item" :class="{ active: route.name === 'settings' }">
          <el-icon><Setting /></el-icon>
          <span>设置与接入</span>
        </RouterLink>
      </nav>

      <div class="sidebar-footer">
        <div class="row">
          <span :class="streamState.cls" />
          <span>{{ streamState.text }}</span>
        </div>
        <div style="margin-top: 6px; opacity: 0.75">v1.0.0 · SSE 驱动</div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div>
          <div class="topbar-title">{{ title }}</div>
          <div class="topbar-sub">{{ subtitle }}</div>
        </div>
        <div class="topbar-actions">
          <el-dropdown trigger="click">
            <span class="user-chip">
              <span class="avatar">{{ (auth.session?.displayName || auth.session?.username || 'A').slice(0, 1) }}</span>
              <span>{{ auth.session?.displayName || auth.session?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.session?.role }}</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <el-alert
        v-if="offline"
        type="error"
        :closable="false"
        show-icon
        style="margin: 16px 28px -8px"
        title="无法连接后端服务"
        description="请确认后端已在 8080 端口启动，页面会自动重试。"
      />

      <div class="content">
        <RouterView />
      </div>
    </div>
  </div>
</template>
