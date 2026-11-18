-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 400 热线通话记录
--
-- 为什么要有这张表：呼入 / 按键 / 接听 / 挂机这些事件以前只散落在会话消息里，
-- 运营看不到"今天来了几个电话、谁接的、等了多久、有没有录音、结成了哪张工单"。
-- 一条呼叫一行（call_id 唯一），事件一条条往上叠，挂机后补时长、录音与小结。
--
-- 幂等：话务厂商的回调会重试（网络抖动、超时重发），所以按 (租户, call_id) 做唯一键，
-- 重复推送只更新状态与字段、不重复建单。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "channel_call_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "call_id" VARCHAR(64) NOT NULL,
  "caller" VARCHAR(32) DEFAULT NULL,
  "caller_name" VARCHAR(64) DEFAULT NULL,
  "customer_no" VARCHAR(32) DEFAULT NULL,
  "session_no" VARCHAR(40) DEFAULT NULL,
  "ticket_no" VARCHAR(32) DEFAULT NULL,
  -- 1-呼入中、2-已转人工、3-已完成、4-未接通（未接/放弃）
  "status" SMALLINT NOT NULL DEFAULT 1,
  -- IVR 按键路径（如 "1 · 订单查询"），方便复盘客户为什么转人工
  "ivr_path" VARCHAR(255) DEFAULT NULL,
  "ivr_label" VARCHAR(128) DEFAULT NULL,
  "duration_seconds" INT DEFAULT NULL,
  "recording_url" VARCHAR(512) DEFAULT NULL,
  "agent_name" VARCHAR(64) DEFAULT NULL,
  "transcript" VARCHAR(1024) DEFAULT NULL,
  "event_count" INT NOT NULL DEFAULT 0,
  "first_event_time" TIMESTAMP DEFAULT NULL,
  "answer_time" TIMESTAMP DEFAULT NULL,
  "hangup_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_call_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_channel_call_record" UNIQUE ("tenant_code", "call_id")
);

COMMENT ON TABLE "channel_call_record" IS '400 热线通话记录（一条呼叫一行，事件叠加更新）';
COMMENT ON COLUMN "channel_call_record"."call_id" IS '话务侧呼叫唯一号（幂等键）';
COMMENT ON COLUMN "channel_call_record"."status" IS '状态：1-呼入中、2-已转人工、3-已完成、4-未接通';
COMMENT ON COLUMN "channel_call_record"."ivr_path" IS 'IVR 按键路径（客户按了哪些键）';
COMMENT ON COLUMN "channel_call_record"."duration_seconds" IS '通话时长（秒，挂机事件上报）';
COMMENT ON COLUMN "channel_call_record"."recording_url" IS '录音地址（话务侧回传，页面可直接播放）';
COMMENT ON COLUMN "channel_call_record"."transcript" IS '通话小结（按渠道配置的模板生成，也可由话务侧回传）';
COMMENT ON COLUMN "channel_call_record"."ticket_no" IS '挂机后自动建的工单号（未接不建单）';

CREATE INDEX IF NOT EXISTS "idx_channel_call_tenant_time"
    ON "channel_call_record" ("tenant_code", "create_time" DESC);

CREATE INDEX IF NOT EXISTS "idx_channel_call_channel_status"
    ON "channel_call_record" ("tenant_code", "channel_id", "status");
