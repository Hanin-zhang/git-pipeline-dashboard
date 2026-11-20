# 裸机部署手册（不用 Docker）

适用场景：你手上只有构建产物（jar + 前端静态文件），服务器上没有 Docker。

```
浏览器 ──▶ Nginx :8188 ──┬── /opt/github-look/frontend  （静态文件）
                          └── /api/* → 127.0.0.1:8080   （Java 进程）
GitHub ──▶ :8188/api/webhooks/github ──▶ 127.0.0.1:8080
Java 进程 ──▶ MySQL :3306、Redis :6379（均只监听 127.0.0.1）
```

最终目录：

```
/opt/github-look/
├── backend/github-look-backend-1.1.0.jar
├── frontend/index.html + assets/
├── .env                 (chmod 600)
└── logs/
```

---

## 1. 前置检查

```bash
uname -m                      # 需要 x86_64
cat /etc/os-release           # 确认发行版
free -h                       # 确认内存
ss -lntp | grep -E ':8188|:8080|:3306|:6379'   # 确认端口没被占
```

## 2. 创建用户与目录

```bash
sudo useradd -r -s /sbin/nologin -m -d /opt/github-look ghl
sudo mkdir -p /opt/github-look/{backend,frontend,logs}
sudo chown -R ghl:ghl /opt/github-look
```

## 3. 安装依赖

### Ubuntu / Debian

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless redis-server nginx mysql-server unzip curl

# 如果 apt 里没有 openjdk-21，用 Adoptium：
# sudo apt install -y wget apt-transport-https gpg
# wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
# echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
# sudo apt update && sudo apt install -y temurin-21-jre
```

### CentOS / RHEL / Rocky

```bash
sudo dnf install -y java-21-openjdk-headless redis nginx mysql-server unzip curl
sudo systemctl enable --now redis mysqld nginx
```

### 校验

```bash
java -version          # 必须是 21.x
redis-cli ping         # PONG
systemctl is-active mysql || systemctl is-active mysqld
```

## 4. 建库建用户

```bash
sudo mysql -uroot -p
```

```sql
CREATE DATABASE github_look CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'githublook'@'localhost'  IDENTIFIED BY '换成强密码';
CREATE USER 'githublook'@'127.0.0.1'  IDENTIFIED BY '换成强密码';
GRANT ALL PRIVILEGES ON github_look.* TO 'githublook'@'localhost';
GRANT ALL PRIVILEGES ON github_look.* TO 'githublook'@'127.0.0.1';
FLUSH PRIVILEGES;
EXIT;
```

表结构由 Flyway 应用启动时自动创建，**不用手工执行任何建表 SQL**。

## 5. 上传产物

在你本机（产物所在目录）：

```bash
scp -r github-look-1.0.0/backend/github-look-backend-1.1.0.jar  user@123.56.106.44:/tmp/
scp -r github-look-1.0.0/frontend-dist/*                        user@123.56.106.44:/tmp/frontend-dist/
```

在服务器：

```bash
sudo mv /tmp/github-look-backend-1.1.0.jar /opt/github-look/backend/
sudo rm -rf /opt/github-look/frontend/* && sudo cp -r /tmp/frontend-dist/* /opt/github-look/frontend/
sudo chown -R ghl:ghl /opt/github-look
ls -l /opt/github-look/backend /opt/github-look/frontend
```

## 6. 写环境变量

```bash
sudo cp env.production.example /opt/github-look/.env
sudo nano /opt/github-look/.env      # 逐项替换「改成…」的占位值
sudo chmod 600 /opt/github-look/.env
sudo chown ghl:ghl /opt/github-look/.env
```

必改 6 项：

| 变量 | 说明 |
| --- | --- |
| `DB_PASSWORD` | 与第 4 步一致 |
| `APP_JWT_SECRET` | `openssl rand -hex 32` |
| `ADMIN_PASSWORD` | 登录密码 |
| `SETTINGS_ACCESS_PASSWORD` | 设置页二次密码 |
| `GITHUB_WEBHOOK_SECRET` | 与 GitHub 上填的一致（现在是 `hanin-github`） |
| `PUBLIC_BASE_URL` / `ALLOWED_ORIGINS` | `http://123.56.106.44:8188` |

## 7. 注册 systemd 服务

```bash
sudo cp github-look.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now github-look
sudo systemctl status github-look --no-pager
sudo journalctl -u github-look -f          # 看启动日志
```

日志里应出现：

```
Migrating schema ... to version "1 - init"
Migrating schema ... to version "2 - job dependencies"
Successfully applied 2 migrations
Tomcat started on port 8080
Started GithubLookApplication
```

## 8. 配置 Nginx

```bash
sudo cp nginx-github-look.conf /etc/nginx/conf.d/
# Debian/Ubuntu 若用 sites-available 结构：
# sudo mv /etc/nginx/conf.d/nginx-github-look.conf /etc/nginx/sites-available/github-look
# sudo ln -s /etc/nginx/sites-available/github-look /etc/nginx/sites-enabled/

sudo nginx -t          # 必须 syntax is ok
sudo systemctl reload nginx
```

## 9. 放行端口

```bash
# ufw（Ubuntu）
sudo ufw allow 8188/tcp
sudo ufw reload

# firewalld（CentOS）
sudo firewall-cmd --permanent --add-port=8188/tcp && sudo firewall-cmd --reload
```

只放 8188。8080 / 3306 / 6379 都不要对外开放。

## 10. 验收

```bash
# ① 后端健康
curl -s http://127.0.0.1:8080/actuator/health

# ② 前端页面
curl -sI http://127.0.0.1:8188/ | head -1

# ③ 登录 + 门禁
curl -s -c /tmp/ck.txt -X POST http://127.0.0.1:8188/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的ADMIN_PASSWORD"}'

curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/ck.txt http://127.0.0.1:8188/api/settings
# 期望 403（未解锁）

curl -s -b /tmp/ck.txt -c /tmp/ck.txt -X POST http://127.0.0.1:8188/api/settings/unlock \
  -H 'Content-Type: application/json' \
  -d '{"password":"你的SETTINGS_ACCESS_PASSWORD"}'

curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/ck.txt http://127.0.0.1:8188/api/settings
# 期望 200
```

浏览器打开 `http://123.56.106.44:8188`，用 `admin / ADMIN_PASSWORD` 登录。

## 11. 接 GitHub Webhook

仓库 → Settings → Webhooks → Add webhook：

| 字段 | 值 |
| --- | --- |
| Payload URL | `http://123.56.106.44:8188/api/webhooks/github` |
| Content type | `application/json` |
| Secret | `hanin-github`（与 .env 一致） |
| Events | Workflow runs + Pushes |

保存后到 Webhook 详情的 **Recent Deliveries** 点一次 **Redeliver**，看板「设置与接入 → 已消费」会 +1。

## 12. 日常运维

```bash
# 日志
sudo journalctl -u github-look -n 200 --no-pager
sudo journalctl -u github-look -f

# 重启 / 停止
sudo systemctl restart github-look
sudo systemctl stop github-look

# 升级：换 jar 后重启
sudo systemctl stop github-look
sudo cp 新jar /opt/github-look/backend/github-look-backend-1.1.0.jar
sudo chown ghl:ghl /opt/github-look/backend/*.jar
sudo systemctl start github-look

# 备份数据库
mysqldump -u githublook -p github_look > /opt/backup-$(date +%F).sql

# 定时备份（每天 3 点）
echo '0 3 * * * mysqldump -u githublook -pPASSWORD github_look | gzip > /opt/backup-$(date +\%F).sql.gz' | sudo crontab -
```

## 13. 排错

| 现象 | 处理 |
| --- | --- |
| 服务起不来 | `journalctl -u github-look -n 100 --no-pager`，多数是 .env 里的密码/URL 写错 |
| `Refusing to start with default ... password` | prod profile 下用了默认密码，改 .env |
| 502 Bad Gateway | 后端没起来，先 `curl 127.0.0.1:8080/actuator/health` |
| 页面白屏 | `frontend/index.html` 没拷进去，或 Nginx root 指错 |
| 登录后刷新掉线 | 用了 HTTPS 但 `COOKIE_SECURE=false`，或反之 |
| 实时事件不刷新 | Nginx 缺 `proxy_buffering off` |
| 设置页一直 403 | 没解锁或解锁过期（默认 30 分钟） |
| 时间差 8 小时 | `APP_ZONE=Asia/Shanghai`、`JAVA_OPTS` 里 `-Duser.timezone`、`timedatectl set-timezone Asia/Shanghai` |
| MySQL 连不上 | 确认 `bind-address`、用户 host（localhost 与 127.0.0.1 都建）、`allowPublicKeyRetrieval=true` |
