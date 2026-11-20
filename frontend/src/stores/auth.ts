import { ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import type { Session } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const session = ref<Session | null>(null)
  const checked = ref(false)
  const loading = ref(false)

  async function fetchMe(): Promise<void> {
    loading.value = true
    try {
      session.value = await authApi.me()
    } catch {
      session.value = null
    } finally {
      loading.value = false
      checked.value = true
    }
  }

  async function login(username: string, password: string): Promise<void> {
    session.value = await authApi.login({ username, password })
    checked.value = true
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      session.value = null
    }
  }

  /** Called when the API reports 401: drop local state without another round trip. */
  function clear(): void {
    session.value = null
    checked.value = true
  }

  return { session, checked, loading, fetchMe, login, logout, clear }
})
