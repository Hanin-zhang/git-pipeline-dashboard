-- 失败原因归类结果。
-- 由「拉日志 → 解析 → 判定类别」得出，属于可推导的派生数据：
--   test / build / dependency / timeout / permission / network / unknown
-- 为空表示还没归类过（没配 GitHub Token，或没人点开过那次运行的日志）。
-- 之所以落库而不是实时算：归类需要下载日志（几 MB + 网络往返），
-- 聚合视图不可能对每条失败运行现场拉一遍。

ALTER TABLE workflow_run ADD COLUMN failure_class VARCHAR(32);

CREATE INDEX idx_workflow_run_failure_class ON workflow_run (failure_class);
