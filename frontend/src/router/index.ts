import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '全局概览', subtitle: '代码健康度一眼看清' },
        },
        {
          path: 'pipelines',
          name: 'pipelines',
          component: () => import('@/views/PipelinesView.vue'),
          meta: { title: '流水线列表', subtitle: '所有构建运行记录' },
        },
        {
          path: 'runs/:id',
          name: 'run-detail',
          component: () => import('@/views/RunDetailView.vue'),
          meta: { title: '运行详情', subtitle: '定位失败的根因' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '设置与接入', subtitle: '仓库、Webhook 与通知' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * The session lives in an httpOnly cookie, so the router asks the backend once per page load
 * instead of trusting anything stored in the browser.
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.checked) {
    await auth.fetchMe()
  }
  if (!auth.session && !to.meta.public) {
    return { name: 'login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (auth.session && to.name === 'login') {
    return { name: 'dashboard' }
  }
  return true
})

export default router
