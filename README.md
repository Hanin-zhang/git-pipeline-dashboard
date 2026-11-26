# GitHub Look · CI/CD 观测看板

把 GitHub Actions 的构建过程变成一块可看的实时看板：**Webhook 进来 → 验签 → 幂等去重 → Redis 队列 → 虚拟线程异步落库 → SSE 秒级推送到浏览器**。

不再靠刷新 gh 页面猜哪次构建卡住了——流水线拓扑、job/step 耗时、失败定位、执行日志都在一屏。

| | |
| --- | --- |
| **当前版本** | `1.9.2` |
| **技术栈** | JDK 21（虚拟线程）· Spring Boot 3.3 · MyBatis-Plus 3.5 · Flyway · Redis 7 · MySQL 8 / H2 · Vue 3 + Vite 6 + Element Plus + ECharts |
| **部署形态** | Docker Compose（MySQL + Redis + 后端 + Nginx），也支持本地直跑 |

---

## 一、它能做什么

| 能力 | 说明 |
| --- | --- |
| **实时看板** | 今日/昨日成功率、活跃运行、队列深度、7~90 天趋势（按天/按周）、工作流健康度排名 |
| **流水线拓扑** | 节点=job、并行分支自动分列，运行中的节点转圈并实时计时 |
| **三级明细** | run → job → step，任一层都能定位到耗时与结论 |
| **执行日志** | 抽屉里按需从 GitHub 拉取；默认展示**摘要**（错误块 + 行号 + 折叠统计），可切全文并跳转到对应行；5 分钟缓存、失败自动重试 |
| **失败定位** | 直接指出哪个 job 的哪个 step 失败，附带历史成功率对比 |
| **失败原因归类** | 从日志自动判定 测试/构建/依赖/超时/权限/网络；摘要落库，详情页红底高亮展示，看板按类别分布 |
| **操作** | 重新运行 / 取消运行（需 `GITHUB_TOKEN`），补拉历史运行的 job 明细 |
| **接入管理** | 多仓库、独立 Secret、二次验证保护、钉钉/飞书/Webhook 告警 |

---

## 二、快速开始（本地开发）

前置：JDK 21、Maven 3.9+、Node 18+、Redis。

```bash
# 方式 A：一键脚本
./scripts/dev.sh

# 方式 B：分别启动
redis-server --daemonize yes --port 6379 --save '' --appendonly no
cd backend  && mvn spring-boot:run          # http://localhost:8080
cd frontend && npm install && npm run dev    # http://localhost:5173
```

打开 <http://localhost:5173>，默认账号 **admin / admin123**（`ADMIN_USERNAME` / `ADMIN_PASSWORD` 可覆盖）。

首次启动自动完成：Flyway 建表 → 创建管理员 → 灌入 7 天演示数据，打开就有完整图表。

### 灌演示事件

```bash
python3 scripts/simulate_webhook.py --mode live      # 运行中 → 成功
python3 scripts/simulate_webhook.py --mode failure    # 一次失败运行
python3 scripts/simulate_webhook.py --mode burst --count 10
python3 scripts/simulate_webhook.py --repo pcxx/data-sync --branch develop --secret <仓库 Secret>
```

模拟器按 GitHub 真实格式构造 payload 并做 HMAC-SHA256 签名，走的是和线上完全一致的验签链路。

---

## 三、部署（线上）

两条路径，**产物部署**是推荐方式——服务器上不需要 JDK / Maven / Node。

### 方式 A：产物部署（推荐）

交付包结构（每次发版一个，带版本号与构建信息）：

```
github-look-<版本>-<时间戳>/
├── backend/github-look-backend-<版本>.jar
├── frontend-dist/
├── Dockerfile.backend      # 只 COPY 产物，不编译
├── Dockerfile.web
├── nginx.conf
├── docker-compose.yml
├── .env.example
├── VERSION
└── BUILD-INFO.txt          # 版本 / 构建时间 / sha256 / 本版变更
```

```bash
# 1. 首次：拷到服务器并初始化配置
scp -r github-look-1.2.2-* user@host:/home/app/github-look/
ssh user@host 'cd /home/app/github-look && cp .env.example .env'

# 2. 改 .env（6 个必填项，见第六节）
vi .env

# 3. 启动
docker compose up -d --build
docker compose ps
```

**升级**（只换产物，数据库卷不动）：

```bash
# ⚠️ 先删旧 jar：镜像构建靠 backend/*.jar 通配符，两个 jar 并存会构建失败
ssh user@host 'rm -f /home/app/github-look/backend/github-look-backend-<旧版本>.jar'

scp backend/github-look-backend-<新版本>.jar user@host:/home/app/github-look/backend/
tar czf - frontend-dist | ssh user@host 'cd /home/app/github-look && rm -rf frontend-dist && tar xzf -'

ssh user@host 'cd /home/app/github-look && docker compose up -d --build backend web'
```

验证版本：

```bash
docker compose logs backend | grep "Starting GithubLookApplication"
# → Starting GithubLookApplication v1.2.2 ...
```

### 方式 B：源码部署（CI / 开发机）

```bash
cp .env.docker.example .env
docker compose up -d --build          # 根目录 compose 会用 Maven/Node 多阶段镜像
```

| 文件 | 用途 |
| --- | --- |
| `docker-compose.yml`（根） | 源码构建版：Maven → JRE、Node → Nginx |
| `deploy/artifacts/` | 产物部署版：只 COPY jar 与 dist |
| `deploy/nginx.conf` | SPA 回退 + `/api` 反代（**SSE 关闭缓冲**）+ 安全头 |
| `deploy/README.md` | 完整部署手册：HTTPS、备份、排错清单 |

---

## 四、接入 GitHub

1. 设置与接入 → 解锁（`SETTINGS_ACCESS_PASSWORD`）→ 接入仓库
   - 地址支持 `owner/repo`、`https://github.com/owner/repo`、`.../repo.git`、`.../tree/main`、`git@github.com:owner/repo.git`
   - **Webhook Secret 必填**，留空需勾选「自动生成」
2. GitHub 仓库 → Settings → Webhooks → Add webhook
   - Payload URL：`https://你的域名/api/webhooks/github`（**末尾不要加 `/`**）
   - Content type：`application/json`
   - Secret：与上一步完全一致
   - 事件：勾 **Workflow runs**（数据全部来自这个事件）
3. `GITHUB_TOKEN` 建议同时配置——没有它，看板只有 run 没有 job 明细，也拉不到日志

---

## 五、接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/webhooks/github` | Webhook 入口（HMAC 验签，免登录） |
| POST | `/api/auth/login` `/logout` | 登录 / 退出（JWT 写 httpOnly Cookie） |
| GET | `/api/auth/me` | 当前会话 |
| GET | `/api/capabilities` | 能力开关（是否配了 Token / 是否解锁设置） |
| GET | `/api/dashboard/summary` | 今日/昨日窗口、活跃运行、队列深度、SSE 连接数 |
| GET | `/api/dashboard/trend?days=&granularity=day\|week` | 趋势聚合 |
| GET | `/api/dashboard/workflows` | 工作流失败率与耗时排名 |
| GET | `/api/dashboard/failure-reasons?days=&repo=&branch=` | 失败原因分布（含未归类计数） |
| GET | `/api/dashboard/recent` | 实时事件流首屏 |
| GET | `/api/runs?page=&size=&status=&repo=&branch=&q=&workflow=&from=&to=` | 分页 + 多维筛选 |
| GET | `/api/runs/{id}` | 运行详情（job / step） |
| GET | `/api/runs/filters` | 筛选项候选值 |
| POST | `/api/runs/{id}/rerun` | 重新运行（需 Token + Actions:write） |
| POST | `/api/runs/{id}/cancel` | 取消运行（需 Token + Actions:write） |
| POST | `/api/runs/{id}/sync-jobs` | 从 Actions API 补拉 job/step 明细 |
| GET | `/api/runs/{runId}/jobs/{jobId}/logs` | 执行日志（纯文本，2MB 截断） |
| GET/POST/PUT/DELETE | `/api/repos` `/api/repos/{id}` | 仓库接入管理 |
| GET | `/api/settings` · POST `/api/settings/unlock` | 接入信息 / 二次验证 |
| PUT | `/api/settings/notifications/{channel}` | 告警配置 |
| GET | `/api/stream?cid=` | SSE 事件流（`ready`/`run.*`） |
| POST | `/api/admin/backfill-digests?limit=` | 手动触发历史摘要回刷（默认 20，上限 200） |
| GET | `/actuator/health` | 健康检查（含 Redis 探针） |

---

## 六、配置项

全部来自环境变量，启动时由 `ConfigValidator` 集中校验（JWT Secret 不足 32 位、生产环境用默认密码等直接拒绝启动）。

**必改 6 项**：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`APP_JWT_SECRET`、`ADMIN_PASSWORD`、`SETTINGS_ACCESS_PASSWORD`、`GITHUB_WEBHOOK_SECRET`，外加 `PUBLIC_BASE_URL` 指向公网地址。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `QUEUE_CONSUMERS` | `8`（compose 里给 `16`） | 队列消费者数（虚拟线程），**必须 ≤ `DB_POOL_SIZE`** |
| `DB_POOL_SIZE` | `10`（compose 里给 `16`） | Hikari 连接池上限 |
| `GITHUB_TOKEN` | 空 | 配了才能同步 job 明细、拉日志、重跑/取消 |
| `GITHUB_API_URL` | `https://api.github.com` | GitHub Enterprise 改这里 |
| `DEMO_DATA_ENABLED` | `false`（prod） | 是否灌演示数据 |
| `WEBHOOK_AUTO_REGISTER` | `true` | 未知仓库首次投递时自动登记 |
| `COOKIE_SECURE` | `false` | 上 HTTPS 后必须改 `true` |
| `ALLOWED_ORIGINS` | — | 与浏览器地址一致（协议+域名+端口） |
| `IMAGE_TAG` / `BACKEND_IMAGE` / `WEB_IMAGE` | — | 走镜像部署时由 CD 写入 |
| `DIGEST_BACKFILL_ENABLED` | `true` | 是否启用夜间摘要回刷 |
| `DIGEST_BACKFILL_LIMIT` | `50` | 单轮最多处理条数 |
| `DIGEST_BACKFILL_CRON` | `0 30 2 * * ?` | 回刷时间（默认凌晨 2:30） |

切换 MySQL 只需覆盖 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_DRIVER`，Flyway 迁移 SQL 双兼容，分页方言自动选择。

---

## 七、核心链路

```
GitHub POST /api/webhooks/github
   │
   ├─ 验签：按 repository.full_name 找仓库 Secret，HMAC-SHA256 比对原始 body
   ├─ 幂等：Redis SETNX(delivery_id, 24h) + webhook_delivery 唯一索引（双保险）
   ├─ 入队：LPUSH ghl:queue，立即 202（全程 5~15ms，不受 GitHub 10s 超时影响）
   │
   └─ 消费者（虚拟线程 × N）
        ├─ workflow_run → upsert workflow_run（唯一键 repo + external_run_id）
        ├─ 补 job/step → 调 Actions API GET /runs/{id}/jobs，按 external_job_id 增量 upsert
        ├─ push 事件 → 补齐 commit message
        ├─ 作废统计缓存 + SSE 广播
        └─ 审计状态置 PROCESSED / IGNORED / FAILED
```

**幂等双保险**：Redis `SET NX EX` 挡重复投递；`webhook_delivery.delivery_id` 唯一索引做持久化兜底。Redis 挂了会降级到数据库约束，不丢事件。

**状态单调性**：一次运行会收到 `queued → in_progress → completed` 多个事件，并发消费时可能乱序。写入用 `UPDATE ... WHERE` 条件保证「已产出结论的行不允许被非完成态覆盖」，从数据库层杜绝状态回退。

**job 增量 upsert**：按 `external_job_id` 就地更新，主键稳定、不制造 DELETE 风暴，并发也不会产生重复行。

**统计缓存**：`StatsCache` 用版本号失效而非 `SCAN`/`KEYS`——每次写入 `INCR` 版本号，旧 key 自然过期，Redis 里不堆野键。

**SSE 连接管理**：浏览器用 `sessionStorage` 里的标签页 id 作 `cid`，服务端以它为键**顶替**旧连接；30 分钟兜底超时 + 身份校验式删除。刷新多少次都只算一条连接。

**失败摘要**：分类与摘要由后台在运行失败时算出并落库（V5），读取顺序为「数据库 → 进程内缓存 → GitHub」；只有从未分析过的运行才会回源下载日志。

**执行日志**：全文不落库，按需代理 GitHub Logs API（302 跳转手动跟随，跳转后**不带 token**）；60 秒专用超时、3 次重试、2MB 截断、5 分钟内存缓存。

---

## 八、认证与权限

JWT 存 httpOnly + SameSite=Lax Cookie（不进 localStorage、不进 URL），所以 `EventSource` 天然带凭据；登录失败 5 分钟 10 次上限。

设置页二次验证：`/api/settings/**` 与 `/api/repos/**` 需要 `ROLE_SETTINGS_ADMIN`，只由「解锁」签发的短期令牌（默认 30 分钟）提供。与登录密码相互独立。

---

## 九、CI / CD

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `.github/workflows/ci.yml` | push / PR / 手动 | 探测模块 → JDK 21 `mvn verify` → Node 22 `npm ci` + 类型检查 + 单测 + 构建 → 真启动冒烟（Redis + 5 个接口断言）→ `CI gate` 汇总 |
| `.github/workflows/cd.yml` | 打 `v*` 标签 / 手动 | 构建镜像推 GHCR → SSH 部署 → 健康探针（`/api/auth/me` 返回 401 视为就绪）→ 80 秒内未就绪自动回滚上一版 |

CI 里的「模块探测」会先判断 `backend/`、`frontend/` 是否存在、npm script 是否齐全，缺失就跳过对应任务而不是报红。

---

## 十、目录结构

```
backend/src/main/java/com/githublook/
├── auth/        登录、JWT、限流、管理员初始化
├── common/      配置校验、类型化错误、全局异常、请求 ID、时间映射
├── github/      Actions API 客户端、job 明细同步
├── repo/        仓库接入（含地址归一化、Secret 解析）
├── run/         运行聚合读写、日志代理、重跑/取消
├── stats/       看板聚合与缓存
├── settings/    接入信息、通知配置、能力开关
├── stream/      SSE Hub
├── webhook/     入口、验签、幂等、队列、消费者、事件处理器
└── seed/        演示数据

frontend/src/
├── api/         类型化 fetch 客户端（401 跳登录、5xx 重试、错误中文化）
├── components/  StatusPill、PipelineDag、MiniPipeline、run/* 七个详情组件
├── composables/ useRunStream（单例 SSE + 退避重连 + 标签页 cid）
├── layouts/     AppLayout
└── views/       Dashboard / Pipelines / RunDetail / Settings / Login

deploy/          Dockerfile（源码版/产物版）、nginx.conf、部署手册
release/         发版产物（带版本号与 BUILD-INFO）
scripts/         dev.sh、simulate_webhook.py
```

数据库三张主表：`workflow_run`（一次构建）→ `run_job`（一个 job）→ `run_step`（一个步骤），另有三张辅助表 `monitored_repo` / `webhook_delivery` / `notification_setting`。迁移由 Flyway 在**后端启动时**执行，MySQL 容器只负责首次建库建用户。

---

## 十一、版本记录

| 版本 | 内容 |
| --- | --- |
| `1.9.2` | 依赖准备类失败（锁文件缺失 / npm ci 不同步）正确归类；unknown 不再拿噪声行当结论；摘要不再吞 runner 收尾命令 |
| `1.9.1` | 点击摘要高亮整段错误（含 Mockito 说明与堆栈）；选中文字不再误触抽屉 |
| `1.9.0` | 旧版摘要降级展示（stale 标记 + 折叠卡片 + 重新分析）；历史摘要回刷任务（夜间 + 手动接口） |
| `1.8.1` | 删除过时的「事件不携带错误文本」提示；修复摘要跨 job 串用（通过 job 的抽屉不再显示失败 job 的结论） |
| `1.8.0` | 摘要指向具体失败方法而非 Maven 记账行；Maven 总数取最大值不再累加；抽屉修复空白与深色配色 |
| `1.7.0` | 摘要准确性修复（三级证据模型：测试汇总行权威化、测试输出噪声排除）+ 摘要条目点击跳转日志 |
| `1.6.0` | 失败摘要落库（V5，含解析规则版本）+ 详情页红底高亮；读取改为「数据库 → 内存 → GitHub」三层 |
| `1.5.0` | 失败原因自动归类（Flyway V4 + 异步归类 + 日志摘要回写）与看板分布视图 |
| `1.4.0` | 执行日志摘要：规则识别 + 主次错误区分 + 去重 + 行号溯源；前端「摘要/全文」双视图 |
| `1.3.0` | 详情页实时更新（SSE + 12s 轮询兜底，不重置展开态）、完成状态标识（拓扑徽标 + 标题/favicon 的 ✅ ❌） |
| `1.2.2` | 执行日志加稳定性：60s 专用超时、3 次重试（blob 存储偶发断连） |
| `1.2.1` | 修复日志为空：GitHub 返回 302，客户端未跟随且跳转后会泄露 token |
| `1.2.0` | 状态回退修复（`UPDATE ... WHERE` + Flyway V3 修数据）、趋势图字段映射、图例重叠、进度条宽度、job 增量 upsert、连接池/消费者可配、执行日志接入 |

---

## 十二、排错

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| GitHub 显示 **404** | Payload URL 末尾多了 `/` | 删掉斜杠（后端已做容错，建议仍用标准形式） |
| GitHub 显示 **401** | 仓库 Secret 与 GitHub 填的不一致 | 设置页「显示」Secret 后两边对齐 |
| **202 但页面没数据** | 事件类型没勾 / 队列积压 | 勾上 Workflow runs；查 `webhook_delivery` 状态 |
| **有 run 没有流水线图** | 缺 `GITHUB_TOKEN` 或 token 无该仓库权限 | 配 Token 后点「同步 Job 明细」 |
| **运行一直显示「运行中」** | 1.2.0 之前的状态回退问题 | 升级到 1.2.0+（V3 迁移会自动修历史数据） |
| **日志返回 32 字节** | 还没升级到 1.2.1+ | 升级后应为几万字节的真实文本 |
| 复制按钮无效 | 非安全上下文没有 clipboard API | 1.1.0+ 已用 `execCommand` 兜底 |
| 构建报 `more than one source file` | `backend/` 里有多个 jar | 删掉旧 jar |

```bash
# 投递处理状态
docker compose exec mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" github_look \
  -e "SELECT delivery_id,event_name,status,attempts,last_error FROM webhook_delivery ORDER BY id DESC LIMIT 10;"'

# 迁移版本
docker compose exec mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" github_look \
  -e "SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;"'
```

---

## 十三、已知边界

1. **Artifact 产物列表**未接入（事件不携带 artifacts，需要额外调 API 并落库）
2. **详情页不做逐条事件刷新**：SSE 只推列表级事件，详情页在进入时加载一次（运行中的耗时靠前端本地计时）
3. **告警单向通知**：钉钉/飞书发送失败只记 warn，不重试，避免阻塞主链路
4. **失败原因归类的覆盖率取决于 Token 与日志保留期**：没配 `GITHUB_TOKEN`、或日志已过 90 天，都会归不了类（显示为「未归类」而不是猜一个）
5. **DB 数据不可随镜像回滚**：Flyway 已执行的「改数据」型迁移（如 V3）不可逆，上线前先备份
6. **不支持多副本 backend**：SSE 是进程内广播，横向扩容需先接 Redis Pub/Sub
