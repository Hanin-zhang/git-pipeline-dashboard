-- Job 依赖关系（GitHub Actions 的 needs）。
-- 事件本身不携带 needs，接入方可以在 payload 的每个 job 上带 needs 数组；
-- 没有该字段时前端会明确降级为「按执行顺序」而不是猜测依赖。
-- 存储形式：逗号分隔的 job 名称，例如 "lint,test (jdk21)"。

ALTER TABLE run_job ADD COLUMN needs VARCHAR(1000);
