-- 会话消息的客户端幂等键加宽：VARCHAR(64) → VARCHAR(255)
--
-- 为什么：
--   client_msg_no 是"同一条消息重发只落一条"的幂等键。它有两个来源——
--   ① App / 小程序端自己生成（一般是 app-时间戳-随机数，很短）；
--   ② 原生渠道由平台生成，比如邮件渠道是「mail-」+「mail:」+ 邮件的 Message-ID。
--   真实 Message-ID（网易/腾讯/阿里云这些邮件列表、带长域名的企业邮箱）常有 50~90 个字符，
--   加上前缀就超过 64，插入时直接报：
--     ERROR: value too long for type character varying(64)
--
-- 影响：
--   幂等唯一索引 uk_ 建在同一列上（部分索引），列加宽不影响它；
--   btree 索引行有 2704 字节上限，VARCHAR(255) 远没到。
--
-- 本脚本可重复执行。

ALTER TABLE "session_message"
    ALTER COLUMN "client_msg_no" TYPE VARCHAR(255);

COMMENT ON COLUMN "session_message"."client_msg_no"
    IS '客户端消息号（幂等键，同一条消息重发只落一条；邮件渠道是 mail- + Message-ID，可能上百字符）';
