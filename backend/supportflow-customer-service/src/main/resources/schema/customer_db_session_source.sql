-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 会话来源列加宽（VARCHAR(20) → VARCHAR(64)）
--
-- 和 customer.channel 是**同一个根因**：开会话时 session.source 写的是渠道名
-- （SessionService 里 .source(channel.getName())），而渠道名最长 64 字符——
-- 邮件渠道的名字常常直接是收信邮箱地址（service@xinghai-edu.example.com，31 字符）。
--
--      ERROR: value too long for type character varying(20)
--      ### The error may exist in io/supportflow/customer/mapper/SessionMapper.java
--
-- 上次只修了 customer.channel，漏了这一列，所以"客户能建、会话建不出来"。
-- 这次把两个列都对齐到 channel.name 的 64，并在 SchemaGuard 里各加一条列宽自检。
--
-- 可重复执行：改到同样的类型是空操作。
-- ---------------------------------------------------------------------------

ALTER TABLE "session" ALTER COLUMN "source" TYPE VARCHAR(64);

COMMENT ON COLUMN "session"."source"
    IS '会话来源（建档时写入渠道名快照，长度与 channel.name 对齐，最长 64 字符）';
