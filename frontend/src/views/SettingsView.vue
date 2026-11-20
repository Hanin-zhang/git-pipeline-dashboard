<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { reposApi, settingsApi } from '@/api'
import { ApiError } from '@/api/client'
import { copyText } from '@/utils/format'
import type { GateState, Repo, Settings } from '@/api/types'

const settings = ref<Settings | null>(null)
const loading = ref(true)
const savingChannel = ref<string | null>(null)

/* ---------- 二次验证门禁 ---------- */
const gate = ref<GateState | null>(null)
const gatePassword = ref('')
const gateError = ref('')
const unlocking = ref(false)
let gateTimer: number | null = null

const remainingMinutes = computed(() => {
  if (!gate.value?.expiresAt) return null
  return Math.max(0, dayjs(gate.value.expiresAt).diff(dayjs(), 'minute'))
})

const form = reactive<Record<string, { webhookUrl: string; enabled: boolean; notifyOn: string }>>({})

const repoDialog = ref(false)
const newRepo = reactive({ fullName: '', defaultBranch: 'main', secret: '', autoSecret: false })
const creating = ref(false)

const secretVisible = ref<Record<number, boolean>>({})

function lockLocally() {
  gate.value = { locked: true, expiresAt: null, ttlMinutes: gate.value?.ttlMinutes ?? 30 }
  settings.value = null
  secretVisible.value = {}
  gatePassword.value = ''
}

async function checkGate() {
  loading.value = true
  try {
    gate.value = await settingsApi.state()
  } catch {
    gate.value = { locked: true, expiresAt: null, ttlMinutes: 30 }
  } finally {
    loading.value = false
  }
  if (gate.value && !gate.value.locked) {
    await load()
  }
}

async function unlock() {
  if (!gatePassword.value) {
    gateError.value = '请输入访问密码'
    return
  }
  unlocking.value = true
  gateError.value = ''
  try {
    gate.value = await settingsApi.unlock(gatePassword.value)
    gatePassword.value = ''
    ElMessage.success('已解锁，设置内容已加载')
    await load()
  } catch (e) {
    gateError.value = (e as Error).message
  } finally {
    unlocking.value = false
  }
}

async function lockNow() {
  try {
    gate.value = await settingsApi.lock()
  } catch {
    /* 即便请求失败，本地也要回到锁定态 */
  }
  lockLocally()
  ElMessage.success('已锁定设置页')
}

async function load() {
  loading.value = true
  try {
    const res = await settingsApi.get()
    settings.value = res
    res.notifications.forEach((n) => {
      form[n.channel] = { webhookUrl: '', enabled: n.enabled, notifyOn: n.notifyOn || 'failure' }
    })
  } catch (e) {
    // 解锁过期：后端返回 403，直接回到密码页而不是报一个看不懂的错误
    if (e instanceof ApiError && e.status === 403) {
      lockLocally()
      return
    }
    throw e
  } finally {
    loading.value = false
  }
}

async function copy(text?: string | null) {
  if (!text) return
  const ok = await copyText(text)
  ElMessage[ok ? 'success' : 'warning'](ok ? '已复制到剪贴板' : '复制失败，请手动选中文本复制')
}

/** 各渠道的机器人地址格式不同，占位文案要跟着渠道走，否则用户会照着钉钉地址去填飞书。 */
function webhookPlaceholder(channel: string): string {
  switch (channel) {
    case 'FEISHU':
      return 'https://open.feishu.cn/open-apis/bot/v2/hook/...'
    case 'WEBHOOK':
      return 'https://your-domain.com/hooks/github-look'
    default:
      return 'https://oapi.dingtalk.com/robot/send?access_token=...'
  }
}

async function saveNotification(channel: string) {
  savingChannel.value = channel
  try {
    const payload = form[channel]
    await settingsApi.updateNotification(channel, {
      enabled: payload.enabled,
      notifyOn: payload.notifyOn,
      ...(payload.webhookUrl ? { webhookUrl: payload.webhookUrl } : {}),
    })
    ElMessage.success('通知配置已保存')
    await load()
  } finally {
    savingChannel.value = null
  }
}

async function toggleRepo(repo: Repo, enabled: boolean) {
  await reposApi.update(repo.id, { enabled })
  ElMessage.success(enabled ? '已启用' : '已停用')
  await load()
}

async function createRepo() {
  const raw = newRepo.fullName.trim()
  if (!raw) {
    ElMessage.warning('请填写仓库地址')
    return
  }
  // 允许 owner/repo 或完整 GitHub 链接；真正的归一化与校验由后端负责
  if (!/^[\w.-]+\/[\w.-]+$/.test(raw) && !/^(https?:\/\/|git@)/i.test(raw)) {
    ElMessage.warning('请填写 owner/repo 或 https://github.com/owner/repo')
    return
  }
  // Secret 默认必填：留空 => 前端拦截。
  // 只有显式勾选「自动生成」才允许留空，避免悄悄生成一个与 GitHub 不一致的值。
  if (!newRepo.autoSecret && !newRepo.secret.trim()) {
    ElMessage.warning('请填写 Webhook Secret，或勾选下方的「自动生成随机 Secret」')
    return
  }
  if (!newRepo.autoSecret && newRepo.secret.trim().length < 6) {
    ElMessage.warning('Secret 长度至少 6 位，且必须与 GitHub 上填写的完全一致')
    return
  }

  creating.value = true
  try {
    const created = await reposApi.create({
      fullName: raw,
      defaultBranch: newRepo.defaultBranch,
      // 只有勾选自动生成时才不带 secret，交给后端生成 ghl_xxx
      ...(newRepo.autoSecret ? {} : { secret: newRepo.secret.trim() }),
    })
    repoDialog.value = false
    const generated = newRepo.autoSecret
    newRepo.fullName = ''
    newRepo.secret = ''
    newRepo.autoSecret = false
    ElMessage.success(
      generated
        ? `已接入 ${created.fullName}，Secret 已生成，请复制后填到 GitHub`
        : `已接入 ${created.fullName}，请确认 GitHub 上填写的 Secret 与刚才输入的一致`,
    )
    await load()
  } finally {
    creating.value = false
  }
}

async function regenerateSecret(repo: Repo) {
  await ElMessageBox.confirm(
    `重新生成 ${repo.fullName} 的 Secret 后，需要同步更新 GitHub 上的 Webhook 配置，否则验签会失败。`,
    '重新生成 Secret',
    { type: 'warning', confirmButtonText: '确认生成', cancelButtonText: '取消' },
  )
  const updated = await reposApi.update(repo.id, { secret: `ghl_${randomHex(24)}` })
  secretVisible.value[repo.id] = true
  ElMessage.success('已生成新 Secret，请复制到 GitHub')
  if (settings.value) {
    settings.value.repos = settings.value.repos.map((r) => (r.id === updated.id ? updated : r))
  }
}

async function removeRepo(repo: Repo) {
  await ElMessageBox.confirm(`确认移除仓库 ${repo.fullName}？历史运行记录会保留。`, '移除仓库', {
    type: 'warning',
    confirmButtonText: '移除',
    cancelButtonText: '取消',
  })
  await reposApi.remove(repo.id)
  ElMessage.success('已移除')
  await load()
}

function randomHex(bytes: number): string {
  const arr = new Uint8Array(bytes)
  crypto.getRandomValues(arr)
  return Array.from(arr)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

onMounted(async () => {
  await checkGate()
  // 每 30 秒核对一次解锁是否过期，过期自动回到密码页
  gateTimer = window.setInterval(() => {
    if (gate.value && !gate.value.locked && gate.value.expiresAt) {
      if (dayjs(gate.value.expiresAt).isBefore(dayjs())) {
        lockLocally()
        ElMessage.warning('解锁已过期，请重新输入访问密码')
      }
    }
  }, 30_000)
})

onUnmounted(() => {
  if (gateTimer !== null) window.clearInterval(gateTimer)
})
</script>

<template>
  <div v-loading="loading">
    <!-- 未解锁：只渲染密码门禁，不请求任何敏感数据 -->
    <div v-if="gate?.locked" class="gate-wrap">
      <div class="gate-card">
        <div class="gate-icon">
          <el-icon><Lock /></el-icon>
        </div>
        <h2>此页面受密码保护</h2>
        <p class="gate-desc">
          设置与接入包含 Webhook Secret、仓库管理与告警配置，需要额外验证一次。
          解锁后 {{ gate?.ttlMinutes ?? 30 }} 分钟内有效。
        </p>

        <el-input
          v-model="gatePassword"
          type="password"
          size="large"
          show-password
          placeholder="请输入访问密码"
          autocomplete="off"
          @keyup.enter="unlock"
        >
          <template #prefix><el-icon><Key /></el-icon></template>
        </el-input>

        <el-alert
          v-if="gateError"
          type="error"
          :closable="false"
          show-icon
          :title="gateError"
          style="margin-top: 12px"
        />

        <el-button
          type="primary"
          size="large"
          style="width: 100%; margin-top: 14px"
          :loading="unlocking"
          @click="unlock"
        >
          解锁设置页
        </el-button>

        <div class="gate-foot">
          密码由后端环境变量 <span class="mono">SETTINGS_ACCESS_PASSWORD</span> 配置，与登录密码相互独立
        </div>
      </div>
    </div>

    <template v-else>
    <div class="page-intro">
      <div>
        <h1 class="page-title">设置与接入</h1>
        <div class="page-desc">把 Webhook 指向下面这个地址，构建数据就会自动出现在看板上</div>
      </div>
      <div class="row">
        <span class="unlock-badge">
          <el-icon><Unlock /></el-icon>
          已解锁<template v-if="remainingMinutes !== null"> · 剩余 {{ remainingMinutes }} 分钟</template>
        </span>
        <el-button @click="load">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新状态
        </el-button>
        <el-button @click="lockNow">
          <el-icon style="margin-right: 4px"><Lock /></el-icon>锁定
        </el-button>
      </div>
    </div>

    <div class="stack">
      <!-- webhook 接入 -->
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">Webhook 接入</div>
            <div class="card-hint">GitHub → Settings → Webhooks → Add webhook</div>
          </div>
          <el-tag type="success" effect="light" round size="small">
            {{ settings?.runtime.deliveries.processed ?? 0 }} 条已消费
          </el-tag>
        </div>
        <div class="card-pad stack" style="gap: 14px">
          <div>
            <div class="meta-label">Payload URL</div>
            <div class="code-box">
              <span>{{ settings?.webhookUrl }}</span>
              <el-button text size="small" style="color: #9fb7ff" @click="copy(settings?.webhookUrl)">
                <el-icon><CopyDocument /></el-icon>
              </el-button>
            </div>
          </div>
          <div>
            <div class="meta-label">Content type</div>
            <div class="code-box light"><span>application/json</span></div>
          </div>
          <div>
            <div class="meta-label">默认 Secret（也可为每个仓库单独生成）</div>
            <div class="code-box light">
              <span class="mono">{{ settings?.secretHint || '未配置' }}</span>
              <span class="text-muted" style="font-size: 12px">仅展示首尾，完整值见下方仓库列表</span>
            </div>
          </div>
          <el-alert type="info" :closable="false" show-icon>
            <template #title>接入步骤</template>
            <div style="font-size: 12.5px; line-height: 1.9; margin-top: 4px">
              1. 在 GitHub 仓库 Settings → Webhooks 中添加上述 Payload URL<br />
              2. Secret 填写任意仓库的 Secret（推荐用下方“重新生成”得到的值）<br />
              3. 事件选择 <b>Workflow runs</b> 与 <b>Pushes</b>（可选 Workflow jobs 补齐步骤明细）<br />
              4. 保存后 GitHub 会发一次 ping 事件，本页“已消费”计数会立即变化
            </div>
          </el-alert>
        </div>
      </div>

      <!-- 仓库管理 -->
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">仓库管理</div>
            <div class="card-hint">多仓库、多分支数据自动聚合</div>
          </div>
          <el-button type="primary" size="small" @click="repoDialog = true">
            <el-icon style="margin-right: 4px"><Plus /></el-icon>接入仓库
          </el-button>
        </div>
        <el-table :data="settings?.repos ?? []" style="width: 100%" empty-text="还没有接入任何仓库">
          <el-table-column prop="fullName" label="仓库" min-width="200">
            <template #default="{ row }">
              <div style="font-weight: 560">{{ row.fullName }}</div>
              <div class="text-muted" style="font-size: 12px">默认分支 {{ row.defaultBranch || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="Secret" min-width="260">
            <template #default="{ row }">
              <div class="row">
                <span class="mono" style="color: var(--muted)">
                  {{ secretVisible[row.id] ? row.secret : '••••••••••••••••' }}
                </span>
                <el-button text size="small" @click="secretVisible[row.id] = !secretVisible[row.id]">
                  {{ secretVisible[row.id] ? '隐藏' : '显示' }}
                </el-button>
                <el-button text size="small" @click="copy(row.secret)">
                  <el-icon><CopyDocument /></el-icon>
                </el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                size="small"
                @change="(val: string | number | boolean) => toggleRepo(row, Boolean(val))"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }">
              <el-button text size="small" @click="regenerateSecret(row)">重新生成</el-button>
              <el-button text size="small" type="danger" @click="removeRepo(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 通知 -->
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">失败告警</div>
            <div class="card-hint">构建失败时推送钉钉 / 飞书 / 自定义机器人</div>
          </div>
        </div>
        <div class="card-pad stack" style="gap: 18px">
          <div v-for="item in settings?.notifications ?? []" :key="item.channel">
            <div class="between" style="margin-bottom: 8px">
              <div class="row">
                <span style="font-weight: 600; font-size: 13.5px">{{ item.label }}</span>
                <el-tag size="small" effect="plain" round>{{ item.channel }}</el-tag>
                <span v-if="item.webhookUrl" class="text-muted mono" style="font-size: 11.5px">
                  {{ item.webhookUrl }}
                </span>
              </div>
              <el-switch v-model="form[item.channel].enabled" />
            </div>
            <div class="row" style="flex-wrap: wrap">
              <el-input
                v-model="form[item.channel].webhookUrl"
                :placeholder="item.enabled ? '不修改请留空' : webhookPlaceholder(item.channel)"
                style="flex: 1; min-width: 320px"
                show-password
              />
              <el-select v-model="form[item.channel].notifyOn" style="width: 170px">
                <el-option label="仅失败时" value="failure" />
                <el-option label="失败与恢复" value="failure_and_recovery" />
                <el-option label="全部事件" value="all" />
              </el-select>
              <el-button
                type="primary"
                plain
                :loading="savingChannel === item.channel"
                @click="saveNotification(item.channel)"
              >
                保存
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 运行时 -->
      <div class="card">
        <div class="card-head">
          <div>
            <div class="card-title">运行时状态</div>
            <div class="card-hint">队列、消费者与实时连接</div>
          </div>
        </div>
        <div class="card-pad">
          <div class="meta-grid">
            <div>
              <div class="meta-label">队列深度</div>
              <div class="meta-value">{{ settings?.runtime.queueDepth ?? 0 }}</div>
            </div>
            <div>
              <div class="meta-label">虚拟线程消费者</div>
              <div class="meta-value">{{ settings?.runtime.consumers ?? 0 }}</div>
            </div>
            <div>
              <div class="meta-label">已处理 / 失败</div>
              <div class="meta-value">
                {{ settings?.runtime.processed ?? 0 }} / {{ settings?.runtime.failed ?? 0 }}
              </div>
            </div>
            <div>
              <div class="meta-label">交付状态</div>
              <div class="meta-value" style="font-size: 12.5px">
                已处理 {{ settings?.runtime.deliveries.processed ?? 0 }} ·
                排队 {{ settings?.runtime.deliveries.queued ?? 0 }} ·
                失败 {{ settings?.runtime.deliveries.failed ?? 0 }} ·
                忽略 {{ settings?.runtime.deliveries.ignored ?? 0 }}
              </div>
            </div>
            <div>
              <div class="meta-label">SSE 连接数</div>
              <div class="meta-value">{{ settings?.runtime.liveConnections ?? 0 }}</div>
            </div>
            <div>
              <div class="meta-label">服务时间 ({{ settings?.runtime.zone }})</div>
              <div class="meta-value">{{ settings?.runtime.serverTime }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="repoDialog" title="接入新仓库" width="440px">
      <el-form label-position="top">
        <el-form-item label="仓库地址">
          <el-input
            v-model="newRepo.fullName"
            placeholder="Hanin-zhang/git-pipeline-dashboard"
          />
          <div class="form-hint">
            支持 <span class="mono">owner/repo</span> 或完整链接
            <span class="mono">https://github.com/owner/repo</span>，后端会自动解析成 owner/repo
          </div>
        </el-form-item>
        <el-form-item label="默认分支">
          <el-input v-model="newRepo.defaultBranch" placeholder="main" />
        </el-form-item>
        <el-form-item label="Webhook Secret" required>
          <el-input
            v-model="newRepo.secret"
            :disabled="newRepo.autoSecret"
            placeholder="与 GitHub Webhook 里填的完全一致，例如 hanin-github"
            show-password
          />
          <el-checkbox v-model="newRepo.autoSecret" style="margin-top: 6px">
            自动生成随机 Secret（勾选后无需填写）
          </el-checkbox>
          <div class="form-hint">
            Secret 必须与 GitHub 仓库 Webhook 里填的完全一致，否则投递会 401 验签失败。
            不确定填什么就用 <span class="mono">hanin-github</span>，两边保持一致即可。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="repoDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createRepo">确认接入</el-button>
      </template>
    </el-dialog>
    </template>
  </div>
</template>

<style scoped>
.gate-wrap {
  display: grid;
  place-items: center;
  min-height: calc(100vh - 220px);
}

.gate-card {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border: 1px solid #e5e6eb;
  border-radius: 16px;
  padding: 30px 28px 24px;
  box-shadow: 0 18px 48px -26px rgba(16, 24, 40, 0.28);
  text-align: center;
}

.gate-icon {
  width: 46px;
  height: 46px;
  margin: 0 auto 14px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #eef2ff, #e3ecff);
  color: #2f5bff;
  font-size: 21px;
}

.gate-card h2 {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 660;
  letter-spacing: -0.2px;
  color: #1d2129;
}

.gate-desc {
  margin: 0 0 18px;
  font-size: 12.5px;
  line-height: 1.75;
  color: #86909c;
}

.gate-foot {
  margin-top: 16px;
  font-size: 11.5px;
  color: #a9aeb8;
  line-height: 1.7;
}

.unlock-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  background: #f6ffed;
  border: 1px solid #d9f7be;
  color: #12a150;
  font-size: 12px;
  font-weight: 550;
  white-space: nowrap;
}

.form-hint {
  margin-top: 6px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #86909c;
}
</style>
