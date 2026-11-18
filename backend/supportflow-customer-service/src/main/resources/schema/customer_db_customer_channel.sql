-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 客户来源渠道列加宽（VARCHAR(20) → VARCHAR(64)）
--
-- 为什么：访客建档时，customer.channel 写的是**渠道名**（SessionService 里写
-- channel.getName()），而渠道名最长 64 字符。邮件渠道的名字常常直接是收信邮箱地址
-- （service@xinghai-edu.example.com，30 字符），一插就报：
--     ERROR: value too long for type character varying(20)
-- 用户从官网/公众号进来时通常没事（名字短），一旦走邮件或名字起得长就必炸——
-- 属于"平时看不出来、上线才踩"的那种坑，所以这里把列宽对齐到 channel.name 的 64，
-- 并在 SchemaGuard 里加一条列宽自检（缺了自动补，避免老库上继续报这个错）。
--
-- 可重复执行：改到同样的类型是空操作。
-- ---------------------------------------------------------------------------

ALTER TABLE "customer" ALTER COLUMN "channel" TYPE VARCHAR(64);

COMMENT ON COLUMN "customer"."channel"
    IS '来源渠道（建档时写入渠道名快照，长度与 channel.name 对齐，最长 64 字符）';
