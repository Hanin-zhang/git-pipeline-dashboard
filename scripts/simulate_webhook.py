#!/usr/bin/env python3
"""
GitHub Actions Webhook 模拟器 —— 用于本地验收，无需公网 IP、无需 ngrok。

它会按 GitHub 真实格式构造 workflow_run 事件，用 HMAC-SHA256 签名后投递到
POST /api/webhooks/github，从而走完「验签 → 幂等 → Redis 队列 → 虚拟线程消费 → SSE 推送」全链路。

用法示例：
  # 依次演示：开始运行 -> 成功
  python3 scripts/simulate_webhook.py --mode live

  # 只发一条失败运行
  python3 scripts/simulate_webhook.py --mode failure

  # 连续发 10 条已完成运行（压一下列表页与趋势图）
  python3 scripts/simulate_webhook.py --mode burst --count 10

  # 指定仓库与 secret（Secret 可在看板「设置与接入」页复制）
  python3 scripts/simulate_webhook.py --repo pcxx/web-portal --secret ghl_xxx --mode success
"""

import argparse
import hashlib
import hmac
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

AUTHORS = ["li.wei", "zhang.min", "chen.hao", "wang.fang", "octocat"]
MESSAGES = [
    "fix: 修复订单金额四舍五入精度问题",
    "feat: 新增优惠券叠加校验",
    "refactor: 拆分结算服务领域模型",
    "perf: 减少列表接口 N+1 查询",
    "test: 补充支付回调幂等用例",
    "chore: 升级依赖到最新补丁版本",
]
WORKFLOWS = {
    "ci": ("CI / Build & Test", ["lint", "test (jdk21)", "build"]),
    "deploy": ("Deploy / Production", ["build", "deploy"]),
    "e2e": ("Nightly / E2E", ["e2e (chromium)", "e2e (firefox)"]),
}


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def random_sha() -> str:
    return "".join(random.choice("0123456789abcdef") for _ in range(40))


def build_steps(job_name: str, started: datetime, status: str, conclusion: str | None, fail_at: int) -> list:
    names = ["Set up job", "Checkout code"]
    if "e2e" in job_name:
        names += ["Setup Node", "Install dependencies", "Build", "Run E2E suite", "Upload report"]
    elif job_name == "deploy":
        names += ["Configure credentials", "Build image", "Push image", "Rollout"]
    else:
        names += ["Setup JDK 21", "Cache maven", "Compile", "Unit tests", "Package"]
    names.append("Complete job")

    steps, cursor = [], started
    for idx, name in enumerate(names):
        duration = random.randint(3, 40)
        step_failed = idx == fail_at
        pending = status != "completed" and idx >= len(names) - 2
        steps.append(
            {
                "name": name,
                "number": idx + 1,
                "status": "pending" if pending else "completed",
                "conclusion": None if pending else ("failure" if step_failed else "success"),
                "started_at": None if pending else iso(cursor),
                "completed_at": None if pending else iso(cursor + timedelta(seconds=duration)),
            }
        )
        cursor += timedelta(seconds=duration)
    return steps


def build_payload(repo: str, branch: str, mode: str, run_id: int | None, attempt: int) -> dict:
    kind = random.choice(list(WORKFLOWS.keys())) if mode == "burst" else "ci"
    workflow_name, job_names = WORKFLOWS[kind]
    now = datetime.now(timezone.utc)
    started = now if mode in ("start",) else now - timedelta(seconds=random.randint(60, 420))
    completed = mode != "start"
    failed = mode == "failure"
    status = "completed" if completed else "in_progress"
    conclusion = ("failure" if failed else "success") if completed else None

    jobs = []
    fail_job_index = random.randrange(len(job_names)) if failed else -1
    cursor = started
    for idx, job_name in enumerate(job_names):
        job_failed = idx == fail_job_index
        duration = random.randint(20, 150)
        steps = build_steps(job_name, cursor, status, conclusion, 3 if job_failed else -1)
        # 真实依赖关系：矩阵类 job（e2e）并行，其余串行依赖上一个 job
        needs = [] if (kind == "e2e" or idx == 0) else [job_names[idx - 1]]
        jobs.append(
            {
                "id": random.randint(10_000, 999_999),
                "name": job_name,
                "status": status,
                "conclusion": None if not completed else ("failure" if job_failed else "success"),
                "started_at": iso(cursor),
                "completed_at": None if not completed else iso(cursor + timedelta(seconds=duration)),
                "runner_name": f"GitHub Actions {random.choice([4, 8])}-core",
                "html_url": f"https://github.com/{repo}/actions",
                "needs": needs,
                "steps": steps,
            }
        )
        cursor += timedelta(seconds=duration)

    sha = random_sha()
    author = random.choice(AUTHORS)
    run_id = run_id or random.randint(1_000_000, 999_999_999)

    return {
        "action": "completed" if completed else "in_progress",
        "workflow_run": {
            "id": run_id,
            "name": workflow_name,
            "head_branch": branch,
            "head_sha": sha,
            "run_number": random.randint(100, 999),
            "run_attempt": attempt,
            "event": "push",
            "status": status,
            "conclusion": conclusion,
            "run_started_at": iso(started),
            "created_at": iso(started - timedelta(seconds=3)),
            "updated_at": iso(cursor if completed else now),
            "html_url": f"https://github.com/{repo}/actions/runs/{run_id}",
            "actor": {"login": author, "type": "User"},
            "triggering_actor": {"login": author, "type": "User"},
            "head_commit": {
                "id": sha,
                "message": random.choice(MESSAGES),
                "timestamp": iso(started),
                "author": {"name": author, "email": f"{author}@example.com"},
            },
            "jobs": jobs,
        },
        "workflow": {"id": 1, "name": workflow_name, "path": ".github/workflows/ci.yml"},
        "repository": {
            "id": 1,
            "name": repo.split("/")[-1],
            "full_name": repo,
            "owner": {"login": repo.split("/")[0]},
            "default_branch": "main",
            "html_url": f"https://github.com/{repo}",
            "private": False,
        },
        "sender": {"login": author},
    }


def post(url: str, secret: str, payload: dict, event: str = "workflow_run") -> tuple[int, str]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    signature = "sha256=" + hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-GitHub-Event": event,
            "X-GitHub-Delivery": str(uuid.uuid4()),
            "X-Hub-Signature-256": signature,
            "User-Agent": "GitHub-Hookshot/ghl-simulator",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as e:
        return 0, f"连接失败: {e.reason}"


def main() -> int:
    parser = argparse.ArgumentParser(description="向 GitHub Look 投递带签名的 workflow_run 事件")
    parser.add_argument("--url", default="http://localhost:8080/api/webhooks/github")
    parser.add_argument("--repo", default="pcxx/web-portal")
    parser.add_argument("--branch", default="main")
    parser.add_argument("--secret", default="hanin-github")
    parser.add_argument("--mode", choices=["start", "success", "failure", "live", "burst"], default="live")
    parser.add_argument("--count", type=int, default=5, help="burst 模式下的条数")
    parser.add_argument("--run-id", type=int, default=None, help="复用同一次运行（live 模式自动生成）")
    args = parser.parse_args()

    if args.mode == "live":
        run_id = args.run_id or random.randint(1_000_000, 999_999_999)
        print("① 发送 in_progress 事件（观察看板实时事件流出现“运行中”）")
        status, body = post(args.url, args.secret, build_payload(args.repo, args.branch, "start", run_id, 1))
        print(f"   HTTP {status} {body}")
        time.sleep(4)
        print("② 发送 completed 事件（观察同一行变为成功）")
        status, body = post(args.url, args.secret, build_payload(args.repo, args.branch, "success", run_id, 1))
        print(f"   HTTP {status} {body}")
        return 0

    total = args.count if args.mode == "burst" else 1
    for i in range(total):
        payload = build_payload(args.repo, args.branch, args.mode, args.run_id, 1)
        status, body = post(args.url, args.secret, payload)
        print(f"[{i + 1}/{total}] HTTP {status} {body}")
        if status >= 300:
            return 1
        time.sleep(0.4)
    return 0


if __name__ == "__main__":
    sys.exit(main())
