-- 失败原因摘要落库（L2 缓存）。
--
-- 目的：摘要此前只存在于进程内的 5 分钟缓存里，容器一重启就没了，
-- 用户再点开同一个失败运行还得重新下载几 MB 日志。
-- 落到库里之后，读取顺序变成「内存 → 数据库 → GitHub」，绝大多数打开都是纯 DB 查询。
--
-- failure_digest_version 记录生成这份摘要时的解析规则版本：
-- 规则迭代后旧数据版本不匹配，会被视为未命中并自动重算，不需要写迁移脚本去刷新。

ALTER TABLE workflow_run ADD COLUMN failure_job_name VARCHAR(200);
ALTER TABLE workflow_run ADD COLUMN failure_summary VARCHAR(500);
ALTER TABLE workflow_run ADD COLUMN failure_digest TEXT;
ALTER TABLE workflow_run ADD COLUMN failure_digest_version INT;
