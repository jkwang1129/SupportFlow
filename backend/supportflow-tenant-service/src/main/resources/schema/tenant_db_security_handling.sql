-- ---------------------------------------------------------------------------
-- tenant_db 增量脚本 · 安全事件处置（平台「安全中心 → 安全事件」）
--
-- tenant_security_event 是"探测侧"写的（网关与数据面：租户头不一致、跨租户访问、
-- 被限流、RLS 拦截）。平台侧需要能**跟进**这些事件：谁处置的、怎么处置的。
-- 这里只加四列，事件本身由探测侧继续追加，不覆盖。
-- ---------------------------------------------------------------------------

ALTER TABLE "tenant_security_event" ADD COLUMN IF NOT EXISTS "handle_status" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "tenant_security_event" ADD COLUMN IF NOT EXISTS "handle_remark" VARCHAR(512) DEFAULT NULL;
ALTER TABLE "tenant_security_event" ADD COLUMN IF NOT EXISTS "handler_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "tenant_security_event" ADD COLUMN IF NOT EXISTS "handle_time" TIMESTAMP DEFAULT NULL;

COMMENT ON COLUMN "tenant_security_event"."handle_status" IS '处置状态：1-待处理、2-已确认、3-已忽略';
COMMENT ON COLUMN "tenant_security_event"."handle_remark" IS '处置说明（例如：确认是客户自己在改请求头，已通知）';

-- 约束单独加（ADD CONSTRAINT 不是幂等的，用 DO 块判断一次）
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tenant_security_event_handle') THEN
    ALTER TABLE "tenant_security_event"
      ADD CONSTRAINT ck_tenant_security_event_handle CHECK ("handle_status" IN (1, 2, 3));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS "idx_tenant_security_event_handle"
    ON "tenant_security_event" ("handle_status", "event_time" DESC);
