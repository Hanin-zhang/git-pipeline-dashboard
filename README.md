# GitHub Look · CI/CD 观测看板

对接 GitHub Actions 的实时构建看板：Webhook 进来 → 验签 → 幂等去重 → Redis 队列 → 虚拟线程异步落库 → SSE 秒级推到前端。

**技术栈**：JDK 21（虚拟线程）· Spring Boot 3.3 · MyBatis-Plus 3.5 · Redis 8 · Flyway · H2/MySQL · Vue 3 + Vite 6 + Element Plus + ECharts
**部署**：本期不做 Docker / Nginx / SSL（按你的要求跳过），本地直跑。

---

## 一、快速开始

前置：JDK 21、Maven 3.9+、Node 18+、Redis（`brew install redis`）。

```bash
# 方式 A：一键脚本（推荐）
./scripts/dev.sh

# 方式 B：分别启动
redis-server --daemonize yes --port 6379 --save '' --appendonly no
cd backend && mvn spring-boot:run          # http://localhost:8080
cd frontend && npm install && npm run dev   # http://localhost:5173
```

打开 <http://localhost:5173>，默认账号 **admin / admin123**（`ADMIN_USERNAME` / `ADMIN_PASSWORD` 可覆盖）。

首次启动会自动完成：Flyway 建表 → 创建管理员 → 灌入 7 天演示数据（3 个仓库 / 105 次运行），所以打开就有完整图表可看。

## 二、验收路径

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 登录 | 进入全局概览，4 张指标卡 + 7 天趋势 + 实时事件流 |
| 2 | 切换仓库 / 分支 / 7-14-30 天 / 按天按周 | 图表与卡片联动刷新 |
| 3 | 开两个标签页都停在概览页 | 执行下面第 4 步，两边**同时**出现新事件（SSE 广播） |
| 4 | `python3 scripts/simulate_webhook.py --mode live` | 先出现「运行中」，4 秒后同一行变「成功」，无需刷新 |
| 5 | `python3 scripts/simulate_webhook.py --mode failure` | 出现红色失败事件；流水线列表多一条失败记录 |
| 6 | 点流水线列表任意一行 | 进入运行详情，顶部是**流水线 DAG 视图**（节点=job，并行分支自动分列），下方是 job/step 明细，点节点会展开并定位到对应 job |
| 7 | 设置与接入页 | Payload URL 可复制（默认 `http://123.56.106.44:8188/api/webhooks/github`）；仓库 Secret 可显示/重新生成；通知配置可保存 |

GitHub 上填的 Security 值：**`hanin-github`**（三个演示仓库的 Secret 已统一改成这个值；也可用 `GITHUB_WEBHOOK_SECRET` 环境变量覆盖）。

模拟器会按 GitHub 真实格式构造 payload 并用 HMAC-SHA256 签名，走的是和线上完全一致的验签链路。

```bash
python3 scripts/simulate_webhook.py --mode live       # 开始 -> 成功
python3 scripts/simulate_webhook.py --mode failure    # 一次失败运行
python3 scripts/simulate_webhook.py --mode burst --count 10
python3 scripts/simulate_webhook.py --repo pcxx/data-sync --branch develop --secret <某个仓库的 Secret>
```

## 三、接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/webhooks/github` | GitHub Webhook 入口（HMAC 验签，免登录） |
| POST | `/api/auth/login` `/logout` | 登录 / 退出（JWT 写入 httpOnly Cookie） |
| GET | `/api/auth/me` | 当前会话 |
| GET | `/api/dashboard/summary` | 今日/昨日指标窗口、活跃运行、队列深度 |
| GET | `/api/dashboard/trend?days=&granularity=day\|week` | 趋势聚合 |
| GET | `/api/dashboard/workflows` | 工作流失败率与耗时排名 |
| GET | `/api/dashboard/recent` | 实时事件流首屏填充 |
| GET | `/api/runs?page=&size=&status=&repo=&branch=&q=&workflow=&from=&to=` | 分页 + 多维筛选（`workflow` 用于详情页拉同工作流历史） |
| GET | `/api/runs/{id}` | 运行详情（job / step） |
| GET | `/api/runs/filters` | 筛选项候选值 |
| GET/POST/PUT/DELETE | `/api/repos` `/api/repos/{id}` | 仓库接入管理 |
| GET | `/api/settings` · PUT `/api/settings/notifications/{channel}` | 接入信息 / 告警配置 |
| GET | `/api/stream` | SSE 事件流（`ready` / `run.started` / `run.updated` / `run.completed` / `run.failed`） |
| GET | `/actuator/health` | 健康检查（含 Redis 探针） |

## 四、关键设计

**Webhook 快进快出**：请求线程只做「验签 → 占用 delivery id → 推 Redis」，耗时几毫秒，GitHub 侧永远看到 202，解析与落库全部交给消费者。

**幂等双保险**：Redis `SET NX EX`（24h）挡重复投递；`webhook_delivery.delivery_id` 唯一索引做持久化兜底。Redis 挂了会自动降级到数据库约束，不会丢事件。

**虚拟线程消费**：8 个消费者跑在 `Thread.ofVirtual()` 上，空闲时 `parkNanos` 让出，队列深了自动加并发也不会撑爆线程栈。失败重试 3 次后进死信 Hash + 审计表标记 FAILED。

**统计缓存**：`StatsCache` 用版本号而不是 `SCAN`/`KEYS` 失效——每次写入 run 就 `INCR` 版本号，旧 key 自然过期，Redis 里不会堆积野键。

**认证**：JWT 存 httpOnly + SameSite=Lax Cookie（不进 localStorage、不进 URL），所以 `EventSource` 天然带凭据；登录失败 5 分钟 10 次上限。

**设置页二次验证**：`/api/settings/**` 与 `/api/repos/**` 需要额外权限 `ROLE_SETTINGS_ADMIN`，它只由「解锁」签发的短期令牌提供。

```
登录（你是谁）→ 输入访问密码 → 种 gl_settings Cookie（默认 30 分钟）
             → 期间可读写 Secret / 仓库 / 通知 → 过期自动回到密码页
```

密码来自 `app.security.settings-password`，最终由环境变量 `SETTINGS_ACCESS_PASSWORD` 注入；与登录密码相互独立，改密码不用改代码。输错同样受限流保护（5 分钟 10 次）。

## 五、目录结构

```
backend/src/main/java/com/githublook/
├── auth/        登录、JWT、限流、管理员初始化
├── common/      配置校验、类型化错误、全局异常、请求 ID、时间映射
├── repo/        仓库接入（含 Secret 解析）
├── run/         运行聚合的读写（RunWriteService / RunQueryService）
├── stats/       看板聚合与缓存
├── settings/    接入信息、通知配置、失败告警
├── stream/      SSE Hub
├── webhook/     入口、验签、幂等、队列、消费者、事件处理器
└── seed/        演示数据
frontend/src/
├── api/         类型化 fetch 客户端（401 跳登录、5xx 重试、错误中文化）
├── components/  StatusPill、BaseChart、PipelineDag（DAG 画布）、MiniPipeline（列表缩略流水线）
├── composables/ useRunStream（单例 SSE + 退避重连）
├── layouts/     AppLayout
└── views/       Dashboard / Pipelines / RunDetail / Settings / Login
scripts/         dev.sh、simulate_webhook.py
```

## 六、配置与切换 MySQL
所有配置集中在 `backend/src/main/resources/application.yml`，启动时由 `ConfigValidator` 集中校验（JWT Secret 不足 32 位、prod profile 用默认密码等直接拒绝启动）。可覆盖的环境变量见根目录 `.env.example`。

切 MySQL 只需三行环境变量（Flyway 迁移 SQL 是双兼容的）：

```bash
DB_URL="jdbc:mysql://localhost:3306/github_look?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8" \
DB_USERNAME=github_look DB_PASSWORD=*** DB_DRIVER=com.mysql.cj.jdbc.Driver \
mvn spring-boot:run
```

分页方言按实际 JDBC 连接自动选择，不需要改代码。

### GitHub Token（可选，用于重跑 / 取消）

```bash
export GITHUB_TOKEN=ghp_xxxxxxxx        # 经典 PAT 或 fine-grained PAT
export GITHUB_API_URL=https://api.github.com   # GHE 部署时改成自己的地址
```

- 配置读取位置：`app.github.token`；启动日志只打印 `GitHub API enabled/disabled`，**从不打印 token**
- 前端通过 `GET /api/capabilities` 拿能力开关，决定按钮能否点击
- 未配置时：按钮置灰 + 悬浮说明，接口返回 `503`，不会伪造成功

| 操作 | GitHub API | 所需权限 |
| --- | --- | --- |
| 重新运行 | `POST /repos/{o}/{r}/actions/runs/{id}/rerun` | Actions: write |
| 取消运行 | `POST /repos/{o}/{r}/actions/runs/{id}/cancel` | Actions: write |
| Job 明细（补充 steps） | `GET .../runs/{id}/jobs` | Actions: read |
| 日志正文 | `GET .../jobs/{id}/logs` | Actions: read |
| Artifact | `GET .../runs/{id}/artifacts` | Actions: read |

已实现的是「重新运行 / 取消运行」；后三项客户端方法尚未接入页面（见第八节）。

## 七、运行详情页（可视化与排查）

详情页由 7 个独立组件拼装，页面本身只做数据编排：

| 区域 | 组件 | 数据来源 |
| --- | --- | --- |
| 顶部信息与操作 | `run/RunHeader.vue` | run 详情，SHA/链接可复制 |
| 状态摘要 / 失败定位 | `run/RunAlert.vue` | 失败 job + 失败 step 实时计算 |
| 运行统计 + 历史对比 | `run/RunStats.vue` | 本次 job/step + 同 workflow 最近 10 次 |
| 流水线拓扑 | `PipelineDag.vue` | `run_job.needs` 真实依赖 |
| Job / Step 明细 | `run/JobAccordion.vue` | job + steps |
| 步骤详情抽屉 | `run/StepDrawer.vue` | 步骤元数据 |
| 右侧信息面板 | `run/RunInfoPanel.vue` | commit / runner / 链路信息 |

**DAG 依赖是真的**：`workflow_run` 事件本身不带 `needs`，所以后端在 `run_job` 上新增了 `needs` 列（V2 迁移），由事件 payload 里的 `jobs[].needs` 写入，前端按「最长路径」分层：同一层即并行。
如果某次运行的 job 没有任何 `needs`，前端**不会**按名称猜依赖，而是降级为「按执行顺序」布局并在图例处写明 `未提供依赖数据，按执行顺序展示`。

## 八、受后端能力限制、暂未实现的功能

这些入口都做了「明确标记或隐藏」，没有伪造成功反馈：

| 功能 | 现状 | 原因 |
| --- | --- | --- |
| 重新运行 / 取消运行 | **已实现**，需配置 `GITHUB_TOKEN`；未配置时按钮置灰、接口 503 | 依赖 GitHub Actions API |
| 日志正文 / 关键字高亮 / 日志下载 | 抽屉里给出状态与时间线，日志区标注「未接入」 | GitHub 事件不含日志文本，需调 Actions Logs API |
| 错误信息摘要 | 失败横幅给出 job/step/时间，错误文本处为空状态说明 | 同上，事件不携带错误文本 |
| Artifact 产物列表 | 右侧面板预留分组，展示空状态说明 | 事件不携带 artifacts |
| 运行中状态实时刷新 | 未做（详情页数据在进入时加载一次） | 需要按 runId 的 SSE 增量事件，当前 SSE 只推列表级事件 |

## 九、已知边界

1. **job/step 明细来源**：GitHub 的 `workflow_run` 事件本身不带 jobs。真实接入时要么额外订阅 `workflow_job` 事件（当前代码会记为 IGNORED，未落库），要么调 REST API 补拉；本地演示由模拟器直接带上 jobs，所以详情页是完整的。
2. **部署未包含**：Dockerfile / Nginx / HTTPS 按你的要求本轮跳过，上线前还需要：`COOKIE_SECURE=true`、`PUBLIC_BASE_URL` 换成公网域名、反向代理对 `/api/stream` 关闭缓冲（`proxy_buffering off`）。
3. **告警只做单向通知**：钉钉/飞书/通用 Webhook 发送失败只记 warn 日志，不重试，避免阻塞主链路。

## 十、部署（Docker Compose）

一条命令拉起 MySQL + Redis + 后端 + Nginx：

```bash
cp .env.docker.example .env   # 填 6 个必填项（见下表）
docker compose up -d --build
```

| 目录/文件 | 作用 |
| --- | --- |
| `docker-compose.yml` | 4 个服务编排（mysql / redis / backend / web）+ 健康检查 + 依赖顺序 |
| `deploy/Dockerfile.backend` | 多阶段构建：Maven 打包 → JRE 21 Alpine 运行（非 root 用户） |
| `deploy/Dockerfile.frontend` | Node 构建 → Nginx 托管静态资源 |
| `deploy/nginx.conf` | SPA 回退 + `/api` 反代（**SSE 关闭缓冲**）+ 安全头 |
| `deploy/README.md` | 完整部署手册：环境变量、验收命令、HTTPS、备份、排错清单 |

必改的 6 个环境变量：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`APP_JWT_SECRET`、`ADMIN_PASSWORD`、`SETTINGS_ACCESS_PASSWORD`、`GITHUB_WEBHOOK_SECRET`，外加 `PUBLIC_BASE_URL` 指向你的公网地址。

生产建议：`WEBHOOK_AUTO_REGISTER=false`、`DEMO_DATA_ENABLED=false`、全站 HTTPS 后 `COOKIE_SECURE=true`。
