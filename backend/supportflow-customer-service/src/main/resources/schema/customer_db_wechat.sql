-- SupportFlow 智能客服平台 · 微信公众号原生接入（服务器回调 + 网页授权 + 模板消息）
-- 依赖：channel / channel_key / customer 已存在（见 customer_db.sql）

CREATE TABLE IF NOT EXISTS "channel_wechat_config" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "app_id" VARCHAR(64) NOT NULL,
  "app_secret" VARCHAR(128) NOT NULL,
  "token" VARCHAR(64) NOT NULL,
  "aes_key" VARCHAR(64) DEFAULT NULL,
  "welcome_text" VARCHAR(255) DEFAULT NULL,
  "template_logistics" VARCHAR(64) DEFAULT NULL,
  "template_ticket" VARCHAR(64) DEFAULT NULL,
  "template_coupon" VARCHAR(64) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT "ck_wechat_config_status" CHECK ("status" IN (1, 2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_wechat_config" IS '微信公众号渠道配置（服务器配置 + 模板消息）';
COMMENT ON COLUMN "channel_wechat_config"."app_id" IS '公众号 AppID（wx 开头）';
COMMENT ON COLUMN "channel_wechat_config"."app_secret" IS '公众号 AppSecret（只回显是否已配置，不回显明文）';
COMMENT ON COLUMN "channel_wechat_config"."token" IS '服务器配置 Token（验签用）';
COMMENT ON COLUMN "channel_wechat_config"."aes_key" IS 'EncodingAESKey（43 位，安全模式加解密用）';
COMMENT ON COLUMN "channel_wechat_config"."template_logistics" IS '物流提醒模板 ID';
COMMENT ON COLUMN "channel_wechat_config"."template_ticket" IS '工单进展模板 ID';
COMMENT ON COLUMN "channel_wechat_config"."template_coupon" IS '优惠券到账模板 ID';
CREATE UNIQUE INDEX IF NOT EXISTS "uk_wechat_config_channel"
  ON "channel_wechat_config" ("tenant_code", "channel_id");
-- 回调是按 AppID 反查的：同一个公众号只能绑一个渠道，否则消息不知道该进哪家企业
CREATE UNIQUE INDEX IF NOT EXISTS "uk_wechat_config_app"
  ON "channel_wechat_config" ("app_id") WHERE "is_deleted" = FALSE;

CREATE TABLE IF NOT EXISTS "wechat_customer_bind" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "app_id" VARCHAR(64) NOT NULL,
  "openid" VARCHAR(64) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "customer_no" VARCHAR(32) NOT NULL,
  "customer_name" VARCHAR(64) DEFAULT NULL,
  "session_no" VARCHAR(32) DEFAULT NULL,
  "subscribe_status" SMALLINT NOT NULL DEFAULT 1,
  "bind_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT "ck_wechat_bind_subscribe" CHECK ("subscribe_status" IN (1, 2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "wechat_customer_bind" IS '微信 openid 与客户档案的绑定（同一个人才是同一条客户轨迹）';
COMMENT ON COLUMN "wechat_customer_bind"."session_no" IS '最近一次进线的会话号（复用未结束会话）';
COMMENT ON COLUMN "wechat_customer_bind"."subscribe_status" IS '1-已关注 2-已取关';
CREATE UNIQUE INDEX IF NOT EXISTS "uk_wechat_bind_openid"
  ON "wechat_customer_bind" ("app_id", "openid") WHERE "is_deleted" = FALSE;
CREATE INDEX IF NOT EXISTS "idx_wechat_bind_customer"
  ON "wechat_customer_bind" ("tenant_code", "customer_id");

CREATE TABLE IF NOT EXISTS "wechat_inbound_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "app_id" VARCHAR(64) NOT NULL,
  "msg_id" VARCHAR(64) NOT NULL,
  "openid" VARCHAR(64) DEFAULT NULL,
  "msg_type" VARCHAR(32) DEFAULT NULL,
  "event_type" VARCHAR(32) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "wechat_inbound_log" IS '微信上行消息去重日志（微信 5 秒未收到 success 会重推）';
CREATE UNIQUE INDEX IF NOT EXISTS "uk_wechat_inbound_msg"
  ON "wechat_inbound_log" ("app_id", "msg_id");
