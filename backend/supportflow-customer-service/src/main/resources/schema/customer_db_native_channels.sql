-- SupportFlow 智能客服平台 · 原生渠道接入（微信小程序 / 官方 App / 400 热线 / 邮件工单）
-- 依赖：channel / channel_key / customer 已存在（见 customer_db.sql）
-- 说明：channel.channel_type 已支持 1-网站 2-公众号 3-小程序 4-App 5-400热线 6-邮件 7-自定义，
--       这里只补「原生通道"跑起来"需要的东西」：渠道配置、外部身份绑定、入站去重。

-- 渠道原生配置（小程序之外的 App / 400 / 邮件；配置项各家差异大，用 JSON 存）
CREATE TABLE IF NOT EXISTS "channel_native_config" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "config_json" JSONB NOT NULL DEFAULT '{}'::jsonb,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT "ck_native_config_status" CHECK ("status" IN (1, 2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_native_config" IS '渠道原生接入配置（App 推送 / 400 语音导航 / 邮件网关）';
COMMENT ON COLUMN "channel_native_config"."config_json" IS '按渠道类型的配置项（字段定义由服务端给出，密钥类字段不回显）';
CREATE UNIQUE INDEX IF NOT EXISTS "uk_native_config_channel"
  ON "channel_native_config" ("tenant_code", "channel_id") WHERE "is_deleted" = FALSE;

-- 外部身份绑定：把「端上的身份」和「客户档案」绑起来
-- 微信生态（公众号 / 小程序）的身份是 openid，沿用 wechat_customer_bind；
-- App / 400 / 邮件 用这张表统一登记，出站推送时按 (渠道 + 身份) 找回客户。
CREATE TABLE IF NOT EXISTS "channel_identity" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "identity_type" VARCHAR(24) NOT NULL,
  "external_id" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "customer_no" VARCHAR(32) NOT NULL,
  "customer_name" VARCHAR(64) DEFAULT NULL,
  "session_no" VARCHAR(32) DEFAULT NULL,
  "extra_json" JSONB DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "bind_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT "ck_channel_identity_status" CHECK ("status" IN (1, 2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_identity" IS '外部身份与客户档案的绑定（App 用户 ID / 手机号 / 邮箱）';
COMMENT ON COLUMN "channel_identity"."identity_type" IS 'app_user-App用户 / phone-来电号码 / mail_addr-发件邮箱';
COMMENT ON COLUMN "channel_identity"."session_no" IS '最近一次进线的会话号（复用未结束会话）';
CREATE UNIQUE INDEX IF NOT EXISTS "uk_channel_identity_external"
  ON "channel_identity" ("channel_id", "identity_type", "external_id") WHERE "is_deleted" = FALSE;
CREATE INDEX IF NOT EXISTS "idx_channel_identity_customer"
  ON "channel_identity" ("tenant_code", "customer_id");

-- 原生渠道入站去重（邮件 Message-ID、电话 callId+事件、App clientMsgNo 都会重推）
CREATE TABLE IF NOT EXISTS "channel_inbound_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "identity_type" VARCHAR(24) DEFAULT NULL,
  "dedupe_key" VARCHAR(160) NOT NULL,
  "event_type" VARCHAR(40) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_inbound_log" IS '原生渠道入站去重日志（网关/端侧重推时保证幂等）';
CREATE UNIQUE INDEX IF NOT EXISTS "uk_channel_inbound_dedupe"
  ON "channel_inbound_log" ("channel_id", "dedupe_key");

-- 微信公众号配置表加一列：同一套 AppID/Secret/Token/AESKey 也适用于微信小程序
ALTER TABLE "channel_wechat_config" ADD COLUMN IF NOT EXISTS "platform" SMALLINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN "channel_wechat_config"."platform" IS '1-公众号 2-小程序（小程序的客服消息回调是 JSON 格式）';
