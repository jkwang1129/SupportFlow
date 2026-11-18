-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 小程序「订阅消息」
--
-- 公众号那套是「模板消息」（关注即长期可发），小程序没有关注概念，用的是
-- 「订阅消息」：**用户点一次授权、平台才能发一次**（一次性订阅），所以：
--   * 模板 ID 与公众号的模板 ID 不是一套，单独两列存（工单进展 / 客服接入）；
--   * 每次发送都要落一条日志——订阅消息失败是常态（没订阅、次数用完、模板字段不符），
--     "到底发了没有、为什么没发"必须能查，否则只能靠猜（见 subscribe_message_log）。
-- ---------------------------------------------------------------------------

ALTER TABLE "channel_wechat_config"
    ADD COLUMN IF NOT EXISTS "subscribe_ticket" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "channel_wechat_config"
    ADD COLUMN IF NOT EXISTS "subscribe_service" VARCHAR(64) DEFAULT NULL;

COMMENT ON COLUMN "channel_wechat_config"."subscribe_ticket"
    IS '小程序订阅消息：工单进展模板 ID（与公众号模板消息不是一套，单独申请）';
COMMENT ON COLUMN "channel_wechat_config"."subscribe_service"
    IS '小程序订阅消息：客服接入模板 ID';

CREATE TABLE IF NOT EXISTS "subscribe_message_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT DEFAULT NULL,
  "app_id" VARCHAR(64) DEFAULT NULL,
  "openid" VARCHAR(64) DEFAULT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  -- 场景：1-工单进展、2-客服接入
  "scene" SMALLINT NOT NULL,
  "template_id" VARCHAR(64) DEFAULT NULL,
  -- 业务号：工单号 / 会话号。与场景一起做幂等键（同一单同一场景只发一次）
  "biz_no" VARCHAR(64) DEFAULT NULL,
  -- 结果：1-发送成功、2-发送失败、3-跳过（没配模板 / 没绑定 openid / 模拟身份）
  "status" SMALLINT NOT NULL,
  "errcode" INTEGER DEFAULT NULL,
  "message" VARCHAR(512) DEFAULT NULL,
  "payload_json" TEXT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_subscribe_message_log_scene CHECK ("scene" IN (1,2)),
  CONSTRAINT ck_subscribe_message_log_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "subscribe_message_log" IS '小程序订阅消息发送日志（每次尝试一条，成功/失败/跳过都能查）';
COMMENT ON COLUMN "subscribe_message_log"."status" IS '1-发送成功、2-发送失败、3-跳过（未配模板 / 未绑定 openid / 模拟身份）';
COMMENT ON COLUMN "subscribe_message_log"."errcode" IS '微信返回的错误码（0 或 NULL 表示成功）';

-- 幂等：同一租户 + 同一场景 + 同一业务号**只成功发一次**（重复点"流转"不该重复打扰客户）。
-- 用部分唯一索引：只约束成功的那条，失败/跳过的记录允许保留多条供排查。
CREATE UNIQUE INDEX IF NOT EXISTS "uk_subscribe_message_sent"
    ON "subscribe_message_log" ("tenant_code", "scene", "biz_no")
    WHERE "status" = 1;

CREATE INDEX IF NOT EXISTS "idx_subscribe_message_log_time"
    ON "subscribe_message_log" ("tenant_code", "create_time" DESC);
