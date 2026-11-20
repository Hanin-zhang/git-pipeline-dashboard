-- 修复历史脏数据：并发/乱序事件曾把已结束的运行回写成 in_progress。
-- 判据：只要 completed_at 与 conclusion 都已落库，状态就应该是 completed。
-- 该语句幂等，可重复执行。
UPDATE workflow_run
SET status = 'completed'
WHERE status <> 'completed'
  AND completed_at IS NOT NULL
  AND conclusion IS NOT NULL;
