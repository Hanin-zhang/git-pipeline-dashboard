# 部署手册（Docker Compose）

目标：在一台干净的 Linux 服务器上，用一条命令拉起 **MySQL + Redis + 后端 + 前端 Nginx**。

```
浏览器 ──▶ web(nginx:80) ──┬── 静态资源 / SPA
                            └── /api/*  ──▶ backend:8080 ──▶ mysql / redis
          GitHub ──▶ http://<服务器>/api/webhooks/github ──▶ backend
```

---

## 0. 前置条件

| 项   | 要求                                                           |
| --- | ------------------------------------------------------------ |
| 服务器 | 2C4G 起（MySQL 占大头），Linux x86_64                               |
| 软件  | Docker 24+、Docker Compose v2（`docker compose version` 能输出即可） |
| 网络  | 对外放行 80（用 HTTPS 时再放行 443）                                    |
| 端口  | 80 空闲；若被占，把 `.env` 里的 `WEB_PORT` 改掉                          |

```bash
# 检查
docker --version && docker compose version
```

## 1. 上传代码

```bash
git clone <你的仓库> github-look && cd github-look
# 或者 rsync/scp 整个目录上去，注意不要带 backend/data（本地 H2 文件）
```

## 2. 准备环境变量

```bash
cp .env.docker.example .env

# 生成两个强密钥
openssl rand -hex 32   # → APP_JWT_SECRET
openssl rand -hex 16   # → MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD 等

vi .env
```

**必须改的 6 项**（其余可用默认值）：

| 变量                                       | 说明                                                     |
| ---------------------------------------- | ------------------------------------------------------ |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | 数据库密码                                                  |
| `APP_JWT_SECRET`                         | ≥32 字符，泄露等于会话可伪造                                       |
| `ADMIN_PASSWORD`                         | 登录密码                                                   |
| `SETTINGS_ACCESS_PASSWORD`               | 设置页二次密码（与登录密码不同）                                       |
| `GITHUB_WEBHOOK_SECRET`                  | 必须与 GitHub 上填的一致                                       |
| `PUBLIC_BASE_URL`                        | 例如 `http://123.56.106.44` 或 `https://look.example.com` |

> `.env` 已在 `.gitignore` 里，不会被提交。  
> 这些值全部通过环境变量注入，配置文件里只有占位默认值。

## 3. 构建并启动

```bash
docker compose up -d --build
```

首次会拉取镜像 + 编译前后端，约 3~6 分钟（取决于网络）。之后：

```bash
docker compose ps          # 4 个容器都应 healthy / running
docker compose logs -f backend
```

后端启动日志里应该能看到：

```
GitHub API disabled (no GITHUB_TOKEN): ...        # 或 enabled
Migrating schema "public" to version "2 - job dependencies"
Successfully applied 2 migrations
Started GithubLookApplication
```

Flyway 会自动在 MySQL 里建表，**不需要手工执行任何 SQL**。

## 4. 验收

```bash
# ① 健康检查
curl -s http://127.0.0.1/actuator/health
# {"status":"UP",...}

# ② 登录拿 Cookie
curl -s -c /tmp/ck.txt -X POST http://127.0.0.1/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<ADMIN_PASSWORD>"}'

# ③ 未解锁访问设置应为 403（门禁生效）
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/ck.txt http://127.0.0.1/api/settings

# ④ 用设置页密码解锁
curl -s -b /tmp/ck.txt -c /tmp/ck.txt -X POST http://127.0.0.1/api/settings/unlock \
  -H 'Content-Type: application/json' \
  -d '{"password":"<SETTINGS_ACCESS_PASSWORD>"}'

# ⑤ 再访问应为 200
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/ck.txt http://127.0.0.1/api/settings
```

浏览器打开 `http://<服务器IP>/`，用 `ADMIN_USERNAME/ADMIN_PASSWORD` 登录。

## 5. 接入 GitHub

在目标仓库 → **Settings → Webhooks → Add webhook**：

| 字段               | 值                                                        |
| ---------------- | -------------------------------------------------------- |
| Payload URL      | `${PUBLIC_BASE_URL}/api/webhooks/github`                 |
| Content type     | `application/json`                                       |
| Secret           | 与 `.env` 的 `GITHUB_WEBHOOK_SECRET` 一致                    |
| SSL verification | 用 HTTPS 就启用；纯 HTTP 只能禁用                                  |
| Events           | **Workflow runs** + **Pushes**（要 job 明细再加 Workflow jobs） |

保存后 GitHub 会发一次 `ping`，看板「设置与接入 → 已消费」计数会 +1。

多个仓库：每个仓库各建一个 Webhook，URL 都填同一个，Secret 可相同也可不同（不同的话到「设置与接入 → 仓库管理 → 重新生成」拿各自的 Secret）。

> 生产建议保持 `WEBHOOK_AUTO_REGISTER=false`，未知仓库先到设置页显式接入。

## 6. 配 HTTPS（强烈建议）

**方案 A：Caddy 反代（最省事，自动证书）**

```bash
# 宿主机装 caddy，/etc/caddy/Caddyfile
look.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

并把 `.env` 改为 `WEB_PORT=8080`、`COOKIE_SECURE=true`、`PUBLIC_BASE_URL=https://look.example.com`、`ALLOWED_ORIGINS=https://look.example.com`，然后 `docker compose up -d`。

**方案 B：宿主机 Nginx + certbot**

```nginx
server {
  listen 443 ssl http2;
  server_name look.example.com;
  ssl_certificate     /etc/letsencrypt/live/look.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/look.example.com/privkey.pem;

  location / {
    proxy_pass http://127.0.0.1:8080;   # 映射 WEB_PORT=8080
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;                # SSE 必须关
    proxy_read_timeout 3600s;
  }
}
```

> 无论哪种方案，`/api/stream` 所在 location 都必须 `proxy_buffering off`，否则实时推送会被缓冲。

## 7. 日常运维

```bash
# 日志
docker compose logs -f backend --tail=200

# 重启单个服务
docker compose restart backend

# 升级（改完代码）
git pull && docker compose up -d --build backend web

# 回滚：切回上一个 tag/commit 后同样执行上面的命令

# 备份数据库
docker compose exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" github_look' > backup-$(date +%F).sql

# 恢复
cat backup-2026-11-17.sql | docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" github_look'
```

**别做**：`docker compose down -v` —— 会连 `mysql-data` / `redis-data` 卷一起删掉。

## 8. 排错清单

| 现象                            | 排查                                                           |
| ----------------------------- | ------------------------------------------------------------ |
| 页面能开但接口 502                   | `docker compose logs backend`，多数是 MySQL 未就绪或 `DB_*` 填错       |
| 登录成功刷新就掉线                     | `COOKIE_SECURE=true` 但用 HTTP 访问，浏览器不回传 Cookie                |
| 实时事件不刷新                       | Nginx 缺 `proxy_buffering off`；或公司代理把 `text/event-stream` 缓冲了 |
| 设置页一直 403                     | 没走解锁流程，或解锁已过期（默认 30 分钟）                                      |
| 应用起不来，日志说 jwt-secret          | `APP_JWT_SECRET` 少于 32 字符，或 prod profile 下用了默认值              |
| Webhook 401 SIGNATURE_INVALID | GitHub 上填的 Secret 与 `GITHUB_WEBHOOK_SECRET` 不一致              |
| 时间是 UTC                       | 确认容器 `TZ=Asia/Shanghai` 且 `APP_ZONE=Asia/Shanghai`           |
| 内存吃紧                          | 调小 `DB_POOL_SIZE`（默认 10）和 `QUEUE_CONSUMERS`（默认 8）            |

## 9. 生产检查清单

- [ ] `APP_JWT_SECRET` 已换成随机值（≥32 字符）
- [ ] `ADMIN_PASSWORD` / `SETTINGS_ACCESS_PASSWORD` 均非默认值
- [ ] `WEBHOOK_AUTO_REGISTER=false`
- [ ] `DEMO_DATA_ENABLED=false`
- [ ] 全站 HTTPS + `COOKIE_SECURE=true`
- [ ] `/api/webhooks/github` 之外的接口都不可匿名访问（默认即如此）
- [ ] MySQL / Redis 端口未映射到宿主机
- [ ] 已配置数据库定时备份

---

## 10. 低配服务器（2 核 2G）

**结论：能跑，但必须用压缩配置，并且不要在服务器上构建镜像。**

### 10.1 内存预算（压缩后）

| 组件 | 常驻占用 | 配置手段 |
| --- | --- | --- |
| MySQL | 250~350MB | `--innodb-buffer-pool-size=128M`、`--performance-schema=OFF`、`--max-connections=60` |
| Redis | 20~40MB | `--maxmemory 128mb`（实测数据量只有 1MB） |
| 后端 JVM | 350~450MB | `-Xms192m -Xmx320m -XX:MaxMetaspaceSize=160m` |
| Nginx | 10~20MB | 静态托管，忽略不计 |
| OS + Docker daemon | 250~300MB | — |
| **合计** | **约 0.9~1.2GB** | 余量够，但构建阶段会吃满 |

> MySQL 8 本身建议 ≥512MB，2G 整机是这套技术栈的**下限**。个人/小团队用没问题；如果 QPS 高或想留缓冲，上 4G 更稳。

### 10.2 先加 2G swap（必做）

构建和 GC 尖峰靠它兜底，否则很容易被内核 OOM Kill：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h   # 确认 Swap 有 2.0Gi
```

### 10.3 启动

```bash
cp .env.docker.example .env && vi .env
docker compose -f docker-compose.yml -f deploy/docker-compose.2c2g.yml up -d
```

`docker-compose.2c2g.yml` 覆盖了 MySQL 启动参数、Redis 内存策略、JVM 堆上限和连接池大小，主 compose 文件保持通用。

### 10.4 构建不要在 2C2G 上做

Maven 打包 + Vite 构建的内存峰值会到 1.5~2GB，容易把 MySQL 挤死。

**方案 A（推荐）：本机构建，传镜像**

```bash
# 本地机器
docker compose build
docker save github-look-backend:latest github-look-web:latest -o ghl-images.tar
scp ghl-images.tar user@server:/tmp/

# 服务器
docker load -i /tmp/ghl-images.tar
docker compose -f docker-compose.yml -f deploy/docker-compose.2c2g.yml up -d
```

**方案 B：服务器上串行构建（慢，但有 swap 也能过）**

```bash
docker compose -f docker-compose.yml -f deploy/docker-compose.2c2g.yml build backend
docker compose -f docker-compose.yml -f deploy/docker-compose.2c2g.yml build web
docker compose -f docker-compose.yml -f deploy/docker-compose.2c2g.yml up -d
```

### 10.5 跑起来后核对

```bash
free -h
docker stats --no-stream      # 看每个容器的 MEM USAGE / LIMIT
docker compose -f docker-compose.yml -f deploy/docker-compose.2c2g.yml logs -f backend | grep -E "Started|ERROR"
```

正常状态下 `docker stats` 里 mysql 约 300MB、backend 约 400MB、redis < 50MB。

如果 mysql 反复重启，说明 600m 上限不够，把 `deploy/docker-compose.2c2g.yml` 里 mysql 的 `mem_limit` 调到 `768m`，并确认 swap 已生效。
