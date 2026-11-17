<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)
const errorMessage = ref('')

async function submit() {
  if (!form.username || !form.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  } catch (e) {
    errorMessage.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <div class="login-aside">
      <div class="login-brand">
        <div class="brand-mark" style="width: 40px; height: 40px; font-size: 17px">GL</div>
        <div>
          <div style="font-size: 17px; font-weight: 680">GitHub Look</div>
          <div style="font-size: 12px; color: rgba(255, 255, 255, 0.72)">CI/CD 观测看板</div>
        </div>
      </div>

      <div class="login-copy">
        <h1>一眼看清代码健康度</h1>
        <p>Webhook 实时接入 GitHub Actions，构建成功率、耗时与失败根因都在同一屏。</p>
        <ul>
          <li><el-icon><Check /></el-icon> HMAC-SHA256 验签 + Delivery 幂等去重</li>
          <li><el-icon><Check /></el-icon> Redis 队列 + JDK 21 虚拟线程异步落库</li>
          <li><el-icon><Check /></el-icon> SSE 秒级推送，无需轮询</li>
          <li><el-icon><Check /></el-icon> 多仓库、多分支聚合统计</li>
        </ul>
      </div>

      <div class="login-foot">v1.0.0 · Spring Boot 3.3 + Vue 3</div>
    </div>

    <div class="login-main">
      <div class="login-card">
        <h2>登录看板</h2>
        <p class="text-muted" style="margin: 6px 0 22px; font-size: 13px">
          使用管理员账号登录，凭据由后端 <span class="mono">ADMIN_USERNAME / ADMIN_PASSWORD</span> 决定
        </p>

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" size="large" placeholder="admin" autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              placeholder="请输入密码"
              autocomplete="current-password"
              @keyup.enter="submit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-alert
            v-if="errorMessage"
            type="error"
            :closable="false"
            show-icon
            :title="errorMessage"
            style="margin-bottom: 14px"
          />

          <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">
            登录
          </el-button>
        </el-form>

        <div class="text-muted" style="margin-top: 18px; font-size: 12px; text-align: center">
          默认账号 admin / admin123（首次启动自动创建，生产环境请用环境变量覆盖）
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-shell {
  display: flex;
  min-height: 100vh;
}

.login-aside {
  flex: 1.05;
  background: radial-gradient(circle at 20% 10%, #2f5bff 0%, #1b2a6b 45%, #0d1326 100%);
  color: #fff;
  padding: 44px 52px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
  overflow: hidden;
}

.login-aside::after {
  content: '';
  position: absolute;
  width: 520px;
  height: 520px;
  right: -180px;
  bottom: -220px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(120, 160, 255, 0.34), transparent 68%);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-copy {
  max-width: 460px;
  position: relative;
  z-index: 1;
}

.login-copy h1 {
  font-size: 34px;
  line-height: 1.25;
  letter-spacing: -0.8px;
  margin: 0 0 14px;
}

.login-copy p {
  color: rgba(255, 255, 255, 0.78);
  font-size: 14.5px;
  line-height: 1.8;
  margin: 0 0 22px;
}

.login-copy ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login-copy li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.88);
}

.login-foot {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.login-main {
  flex: 1;
  display: grid;
  place-items: center;
  background: var(--bg);
  padding: 40px;
}

.login-card {
  width: 100%;
  max-width: 396px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 18px;
  padding: 32px 30px;
  box-shadow: 0 18px 48px -24px rgba(16, 24, 40, 0.28);
}

.login-card h2 {
  margin: 0;
  font-size: 20px;
  letter-spacing: -0.3px;
}

@media (max-width: 900px) {
  .login-aside {
    display: none;
  }
}
</style>
