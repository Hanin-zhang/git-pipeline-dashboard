#!/usr/bin/env bash
# 一键启动本地开发环境：Redis -> 后端 -> 前端。
# 用法： ./scripts/dev.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> 1/3 检查 Redis"
if redis-cli ping >/dev/null 2>&1; then
  echo "    Redis 已在运行"
else
  echo "    Redis 未运行，正在后台启动 (端口 6379)"
  redis-server --daemonize yes --port 6379 --save '' --appendonly no --logfile /tmp/redis-githublook.log
  sleep 1
  redis-cli ping >/dev/null 2>&1 || { echo "    Redis 启动失败，请先安装 redis (brew install redis)"; exit 1; }
fi

echo "==> 2/3 启动后端 (http://localhost:8080)"
( cd "$ROOT/backend" && mvn -B spring-boot:run ) &
BACKEND_PID=$!

echo "==> 3/3 启动前端 (http://localhost:5173)"
( cd "$ROOT/frontend" && [ -d node_modules ] || npm install )
( cd "$ROOT/frontend" && npm run dev ) &
FRONTEND_PID=$!

echo
echo "   后端 PID $BACKEND_PID / 前端 PID $FRONTEND_PID"
echo "   打开 http://localhost:5173 登录（默认 admin / admin123）"
echo "   按 Ctrl+C 停止两者"

trap 'kill $BACKEND_PID $FRONTEND_PID 2>/dev/null || true' INT TERM
wait
