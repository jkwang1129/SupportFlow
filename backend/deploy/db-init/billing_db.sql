-- billing_db 初始化脚本（基线 + 增量），生成时间：2026-10-09 18:23
-- 由 backend/deploy/db-init/generate.sh 从仓库 schema/ 与 scripts/migrate-*.sh 机械拼接，请勿手改。
-- ===========================================================================
-- 库：billing_db   （基线 schema/billing_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · billing_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "plan" (
  "id" BIGINT NOT NULL,
  "plan_code" VARCHAR(32) NOT NULL,
  "plan_name" VARCHAR(64) NOT NULL,
  "price_yearly" NUMERIC(12,2) NOT NULL,
  "price_monthly" NUMERIC(12,2) NOT NULL,
  "quota_robot_sessions" INT NOT NULL DEFAULT 0,
  "quota_channels" INT NOT NULL DEFAULT 0,
  "quota_ai_qa" BOOLEAN NOT NULL DEFAULT FALSE,
  "quota_vision" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_plan_code" UNIQUE ("plan_code")
);
COMMENT ON TABLE "plan" IS '套餐表';
COMMENT ON COLUMN "plan"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "plan"."plan_code" IS '套餐编码：标准版、专业版、企业版';
COMMENT ON COLUMN "plan"."plan_name" IS '套餐名称';
COMMENT ON COLUMN "plan"."price_yearly" IS '年付价格';
COMMENT ON COLUMN "plan"."price_monthly" IS '月付价格';
COMMENT ON COLUMN "plan"."quota_robot_sessions" IS '月机器人会话配额';
COMMENT ON COLUMN "plan"."quota_channels" IS '渠道数配额';
COMMENT ON COLUMN "plan"."quota_ai_qa" IS '是否含全量质检';
COMMENT ON COLUMN "plan"."quota_vision" IS '是否含视觉识别';

CREATE TABLE "billing_order" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "order_no" VARCHAR(40) NOT NULL,
  "plan_id" BIGINT NOT NULL,
  "cycle" SMALLINT NOT NULL,
  "amount" NUMERIC(12,2) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "pay_method" SMALLINT DEFAULT NULL,
  "pay_time" TIMESTAMP DEFAULT NULL,
  "invoice_status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_billing_order_cycle CHECK ("cycle" IN (1,2)),
  CONSTRAINT ck_billing_order_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_billing_order_pay_method CHECK ("pay_method" IN (1,2,3)),
  CONSTRAINT ck_billing_order_invoice_status CHECK ("invoice_status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_order_no" UNIQUE ("tenant_code", "order_no")
);
COMMENT ON TABLE "billing_order" IS '订单表';
COMMENT ON COLUMN "billing_order"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "billing_order"."order_no" IS '订单号';
COMMENT ON COLUMN "billing_order"."plan_id" IS '套餐ID';
COMMENT ON COLUMN "billing_order"."cycle" IS '周期码：1-年付、2-月付';
COMMENT ON COLUMN "billing_order"."amount" IS '订单金额';
COMMENT ON COLUMN "billing_order"."status" IS '状态码：1-待支付、2-已支付、3-已取消、4-已退款';
COMMENT ON COLUMN "billing_order"."pay_method" IS '支付方式码：1-支付宝、2-微信、3-对公转账';
COMMENT ON COLUMN "billing_order"."pay_time" IS '支付时间';
COMMENT ON COLUMN "billing_order"."invoice_status" IS '发票状态码：1-未申请、2-已申请、3-已开具';
CREATE INDEX "idx_billing_order_tenant_status" ON "billing_order" ("tenant_code", "status", "create_time");

CREATE TABLE "payment_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "order_id" BIGINT NOT NULL,
  "pay_no" VARCHAR(64) NOT NULL,
  "pay_method" VARCHAR(20) NOT NULL,
  "amount" NUMERIC(12,2) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "pay_time" TIMESTAMP DEFAULT NULL,
  "callback_raw" TEXT,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_payment_record_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_pay_no" UNIQUE ("pay_no")
);
COMMENT ON TABLE "payment_record" IS '支付流水表';
COMMENT ON COLUMN "payment_record"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "payment_record"."order_id" IS '订单ID';
COMMENT ON COLUMN "payment_record"."pay_no" IS '支付流水号（三方）';
COMMENT ON COLUMN "payment_record"."pay_method" IS '支付方式';
COMMENT ON COLUMN "payment_record"."amount" IS '支付金额';
COMMENT ON COLUMN "payment_record"."status" IS '状态码：1-处理中、2-成功、3-失败';
COMMENT ON COLUMN "payment_record"."pay_time" IS '支付时间';
COMMENT ON COLUMN "payment_record"."callback_raw" IS '三方回调原文';
CREATE INDEX "idx_payment_record_tenant_order" ON "payment_record" ("tenant_code", "order_id");

CREATE TABLE "invoice_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "order_id" BIGINT NOT NULL,
  "invoice_no" VARCHAR(40) DEFAULT NULL,
  "invoice_type" SMALLINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "amount" NUMERIC(12,2) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "file_id" BIGINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_invoice_record_invoice_type CHECK ("invoice_type" IN (1,2)),
  CONSTRAINT ck_invoice_record_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "invoice_record" IS '发票记录表';
COMMENT ON COLUMN "invoice_record"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "invoice_record"."order_id" IS '订单ID';
COMMENT ON COLUMN "invoice_record"."invoice_no" IS '发票号码';
COMMENT ON COLUMN "invoice_record"."invoice_type" IS '类型码：1-电子发票、2-增值税专用发票';
COMMENT ON COLUMN "invoice_record"."title" IS '发票抬头';
COMMENT ON COLUMN "invoice_record"."amount" IS '开票金额';
COMMENT ON COLUMN "invoice_record"."status" IS '状态码：1-已申请、2-已开具、3-已驳回';
COMMENT ON COLUMN "invoice_record"."file_id" IS '发票文件ID';
CREATE INDEX "idx_invoice_record_tenant_order" ON "invoice_record" ("tenant_code", "order_id");

CREATE TABLE "payment_config" (
  "id" BIGINT NOT NULL,
  "pay_channel" SMALLINT NOT NULL,
  "merchant_id" VARCHAR(64) DEFAULT NULL,
  "config_json" JSON NOT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_payment_config_channel CHECK ("pay_channel" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_payment_config_channel" UNIQUE ("pay_channel")
);
COMMENT ON TABLE "payment_config" IS '支付配置表';
COMMENT ON COLUMN "payment_config"."pay_channel" IS '支付渠道码：1-支付宝、2-微信、3-对公转账';
COMMENT ON COLUMN "payment_config"."merchant_id" IS '商户号';
COMMENT ON COLUMN "payment_config"."config_json" IS '商户参数（加密存储，JSON）';

-- ---------------------------------------------------------------------------
-- 增量：schema/billing_db_billing.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · billing_db 计费增量脚本（PostgreSQL 17）
-- 用途：把"套餐 → 用量计量 → 账单结算"这条链路补齐：
--   1. plan 补上"可计量的配额"（坐席数 / 存储 / AI 调用），并预置四档套餐（免费版 / 标准版 / 专业版 / 企业版）；
--   2. billing_usage_daily：按天记用量（会话 / 消息 / AI 调用 / 坐席 / 存储），
--      账期汇总与"超量计费"都读它；
--   3. billing_statement：账期账单（套餐费 + 超量费 = 应付），带生成用的用量快照，
--      重算幂等（同账期同一个租户只有一条）。
--
-- 幂等：可重复执行。

-- ---------------------------------------------------------------------------
-- 1. plan：补可计量的配额列
-- ---------------------------------------------------------------------------
ALTER TABLE "plan" ADD COLUMN IF NOT EXISTS "quota_seats" INT NOT NULL DEFAULT 0;
ALTER TABLE "plan" ADD COLUMN IF NOT EXISTS "quota_storage_gb" INT NOT NULL DEFAULT 0;
ALTER TABLE "plan" ADD COLUMN IF NOT EXISTS "quota_ai_calls" INT NOT NULL DEFAULT 0;
-- 超量单价（按"每 1000 次会话"计），0 表示不允许超量（超了就该升级套餐）
ALTER TABLE "plan" ADD COLUMN IF NOT EXISTS "overage_price_per_k" NUMERIC(12,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN "plan"."quota_seats" IS '坐席数配额（0 = 不限）';
COMMENT ON COLUMN "plan"."quota_storage_gb" IS '知识库/附件存储配额（GB，0 = 不限）';
COMMENT ON COLUMN "plan"."quota_ai_calls" IS '每月 AI 调用配额（0 = 不限）';
COMMENT ON COLUMN "plan"."overage_price_per_k" IS '超量单价：每 1000 次会话多少钱（0 = 不允许超量）';

-- ---------------------------------------------------------------------------
-- 2. billing_usage_daily：按天用量（计量的事实表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "billing_usage_daily" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "stat_date" DATE NOT NULL,
  -- 会话数：客户进线开一次算一次（机器人 + 人工都算）
  "sessions" BIGINT NOT NULL DEFAULT 0,
  -- 消息数：客户 + 坐席 + 机器人发的消息
  "messages" BIGINT NOT NULL DEFAULT 0,
  -- AI 调用次数：客服大脑、质检、检索、视觉识别
  "ai_calls" BIGINT NOT NULL DEFAULT 0,
  -- 席位与存储是"快照值"（每天记一次当天峰值）
  "seats" INT NOT NULL DEFAULT 0,
  "storage_gb" NUMERIC(12,2) NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_billing_usage_daily" UNIQUE ("tenant_code", "stat_date")
);

COMMENT ON TABLE "billing_usage_daily" IS '租户按天用量（计量事实表：账期汇总与超量计费都读它）';
COMMENT ON COLUMN "billing_usage_daily"."sessions" IS '会话数（机器人 + 人工）';
COMMENT ON COLUMN "billing_usage_daily"."ai_calls" IS 'AI 调用次数（大脑 / 质检 / 检索 / 视觉）';
COMMENT ON COLUMN "billing_usage_daily"."seats" IS '坐席数快照（当天峰值）';

CREATE INDEX IF NOT EXISTS "idx_billing_usage_tenant_date"
    ON "billing_usage_daily" ("tenant_code", "stat_date" DESC);

-- ---------------------------------------------------------------------------
-- 3. billing_statement：账期账单
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "billing_statement" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "statement_no" VARCHAR(40) NOT NULL,
  -- 账期：YYYY-MM
  "cycle_key" VARCHAR(7) NOT NULL,
  "plan_id" BIGINT DEFAULT NULL,
  "plan_name" VARCHAR(64) DEFAULT NULL,
  -- 套餐费（元）
  "plan_amount" NUMERIC(12,2) NOT NULL DEFAULT 0,
  -- 超量费（元）
  "overage_amount" NUMERIC(12,2) NOT NULL DEFAULT 0,
  -- 应付合计
  "total_amount" NUMERIC(12,2) NOT NULL DEFAULT 0,
  -- 1-待结算、2-已结算、3-已逾期、4-已作废
  "status" SMALLINT NOT NULL DEFAULT 1,
  "due_date" DATE DEFAULT NULL,
  "settle_time" TIMESTAMP DEFAULT NULL,
  -- 生成账单时的用量快照（JSON）：账单金额改了也能回答"当时按什么算的"
  "usage_snapshot" JSON DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_billing_statement_status CHECK ("status" IN (1, 2, 3, 4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_statement_no" UNIQUE ("tenant_code", "statement_no"),
  -- 同一个租户同一个账期只允许一张账单：生成/重算靠它幂等
  CONSTRAINT "uk_tenant_statement_cycle" UNIQUE ("tenant_code", "cycle_key")
);

COMMENT ON TABLE "billing_statement" IS '账期账单（套餐费 + 超量费，重算幂等）';
COMMENT ON COLUMN "billing_statement"."cycle_key" IS '账期：YYYY-MM';
COMMENT ON COLUMN "billing_statement"."status" IS '状态码：1-待结算、2-已结算、3-已逾期、4-已作废';
COMMENT ON COLUMN "billing_statement"."usage_snapshot" IS '生成账单时的用量快照（JSON），用于解释金额';

CREATE INDEX IF NOT EXISTS "idx_billing_statement_cycle"
    ON "billing_statement" ("cycle_key", "status");

-- ---------------------------------------------------------------------------
-- 4. 预置四档套餐（固定主键，幂等：存在就不动，避免覆盖运营改过的价格）
--    套餐是"配额 + 价格"的唯一来源：下单按它算钱，租户限流配额也按它下发。
--    为什么有免费版：原型里的"标准版"就是免费档（价格写的是"免费"）。
--    免费版给"能用起来"的最小配额（2 个渠道 / 3 个坐席 / 500 次会话），
--    超量不允许（overage 单价 0），想继续用就得升级——这是 SaaS 的标准漏斗。
-- ---------------------------------------------------------------------------
INSERT INTO "plan" ("id", "plan_code", "plan_name", "price_yearly", "price_monthly",
                    "quota_robot_sessions", "quota_channels", "quota_ai_qa", "quota_vision",
                    "quota_seats", "quota_storage_gb", "quota_ai_calls", "overage_price_per_k",
                    "is_enabled", "creator")
VALUES
  (940000000000000000, 'FREE', '免费版', 0.00, 0.00, 500, 2, FALSE, FALSE,
   3, 1, 100, 0, TRUE, 'system'),
  (940000000000000001, 'STANDARD', '标准版', 5980.00, 598.00, 5000, 2, FALSE, FALSE,
   5, 10, 500, 0, TRUE, 'system'),
  (940000000000000002, 'PROFESSIONAL', '专业版', 19800.00, 1980.00, 30000, 5, TRUE, FALSE,
   20, 50, 5000, 9.90, TRUE, 'system'),
  (940000000000000003, 'ENTERPRISE', '企业版', 59800.00, 5980.00, 200000, 20, TRUE, TRUE,
   100, 500, 50000, 6.60, TRUE, 'system')
ON CONFLICT ("id") DO NOTHING;

-- ---------------------------------------------------------------------------
-- 增量：schema/billing_db_payment_record.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- billing_db 增量脚本 · 收款流水（payment_record 真正用起来）
--
-- 背景：`payment_record` 这张表在基线 billing_db.sql 里就建好了，但**一行都没写过**——
-- 平台「收款流水」页一直是从订单表聚合的。对账口径和订单口径是两件事：
--   · 订单：一笔"要收多少钱"的意向（可能是待支付、可能取消）；
--   · 收款流水：**真的收到的钱**，一笔一条，带支付方式、流水号、到账时间。
-- 所以这一版把收款写成流水，页面改成读流水。
--
-- 三处改动：
--   1. `order_id` 放开 NOT NULL：后付费（账期账单）收款没有订单，硬塞一个假订单号更糟；
--   2. 补 `biz_type` / `biz_no`：说清这笔钱对应"订单"（预付费套餐）还是"账期账单"（后付费超量）；
--   3. 补 `pay_method_code`：基线里的 `pay_method` 是 VARCHAR（存三方名称，如 ALIPAY），
--      而订单表用的是 SMALLINT 码（1-支付宝、2-微信、3-对公）。对账页要按同一套码筛选与统计，
--      所以加一列码，两列共存并各写各的口径（注释里写清，避免以后有人以为重复了）。
--
-- 幂等：可重复执行。
-- ---------------------------------------------------------------------------

ALTER TABLE "payment_record" ALTER COLUMN "order_id" DROP NOT NULL;

ALTER TABLE "payment_record" ADD COLUMN IF NOT EXISTS "biz_type" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "payment_record" ADD COLUMN IF NOT EXISTS "biz_no" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "payment_record" ADD COLUMN IF NOT EXISTS "pay_method_code" SMALLINT NOT NULL DEFAULT 3;
ALTER TABLE "payment_record" ADD COLUMN IF NOT EXISTS "remark" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "payment_record" ADD COLUMN IF NOT EXISTS "creator" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "payment_record" ADD COLUMN IF NOT EXISTS "editor" VARCHAR(64) DEFAULT NULL;

COMMENT ON COLUMN "payment_record"."biz_type" IS '业务类型：1-订单（预付费套餐）、2-账期账单（后付费超量）';
COMMENT ON COLUMN "payment_record"."biz_no" IS '业务号：订单号 / 账单号（与 biz_type 一起唯一，保证一笔业务只落一条收款）';
COMMENT ON COLUMN "payment_record"."pay_method" IS '支付方式名称（三方口径，如 ALIPAY / WECHAT / CORP / MANUAL）';
COMMENT ON COLUMN "payment_record"."pay_method_code" IS '支付方式码：1-支付宝、2-微信、3-对公（与 billing_order.pay_method 同一套码，对账按它统计）';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_payment_record_biz_type') THEN
    ALTER TABLE "payment_record"
      ADD CONSTRAINT ck_payment_record_biz_type CHECK ("biz_type" IN (1, 2));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_payment_record_method') THEN
    ALTER TABLE "payment_record"
      ADD CONSTRAINT ck_payment_record_method CHECK ("pay_method_code" IN (1, 2, 3));
  END IF;
END $$;

-- 一笔业务只落一条收款：重复支付、三方回调重推都靠它兜住
CREATE UNIQUE INDEX IF NOT EXISTS "uk_payment_record_biz"
    ON "payment_record" ("biz_type", "biz_no")
    WHERE "biz_no" IS NOT NULL;

-- 对账页的主查询：按时间倒序翻页 + 按支付方式/业务类型筛选
CREATE INDEX IF NOT EXISTS "idx_payment_record_pay_time"
    ON "payment_record" ("pay_time" DESC, "id" DESC);

-- ---------------------------------------------------------------------------
-- 历史回填：把「上线收款流水之前」已经收过的钱补成流水。
--
-- 为什么需要：页面改成读 payment_record 之后，历史上已经支付成功的订单、已经结算的账期账单
-- **没有对应的流水行**，对账页会看起来"一笔收款都没有"。这里把这两类历史业务各补一条。
--
-- 回填口径与 PaymentRecordService.record 保持一致：
--   · 订单 status=2（已支付）  → biz_type=1、status=2、pay_time=COALESCE(pay_time, update_time)
--   · 账单 status=2（已结算）  → biz_type=2、status=2、pay_time=COALESCE(settle_time, update_time)
--   · 支付方式码沿用订单口径（1-支付宝、2-微信、3-对公），名称按码取 ALIPAY / WECHAT / CORP
--   · pay_no 统一加 "PAY-L" 前缀（L = legacy），既不撞三方流水号，又保证唯一
--   · id 用保留段（订单 91…、账单 92…），避开历史与未来的雪花 ID
--
-- 幂等：靠 uk_payment_record_biz 兜底（一笔业务只补一条），可重复执行；ON CONFLICT DO NOTHING
-- 不带冲突目标，连保留段 id 万一被占也一并跳过，不会让整段回填失败。
-- 顺序按业务表 id 升序，保证重复执行时同一笔业务拿到的保留段 id 稳定不变。
-- ---------------------------------------------------------------------------

INSERT INTO "payment_record"
    (id, tenant_code, order_id, pay_no, pay_method, pay_method_code, biz_type, biz_no,
     amount, status, pay_time, remark, create_time, creator)
SELECT 910000000000000000 + row_number() OVER (ORDER BY o.id),
       o.tenant_code,
       o.id,
       'PAY-L' || o.order_no,
       CASE o.pay_method WHEN 1 THEN 'ALIPAY' WHEN 2 THEN 'WECHAT' ELSE 'CORP' END,
       COALESCE(o.pay_method, 3),
       1,
       o.order_no,
       o.amount,
       2,
       COALESCE(o.pay_time, o.update_time),
       '历史订单回填（上线收款流水前已支付）',
       COALESCE(o.pay_time, o.update_time),
       COALESCE(o.creator, '系统')
FROM "billing_order" o
WHERE o.status = 2 AND o.is_deleted = false
ON CONFLICT DO NOTHING;

INSERT INTO "payment_record"
    (id, tenant_code, order_id, pay_no, pay_method, pay_method_code, biz_type, biz_no,
     amount, status, pay_time, remark, create_time, creator)
SELECT 920000000000000000 + row_number() OVER (ORDER BY s.id),
       s.tenant_code,
       NULL,
       'PAY-L' || s.statement_no,
       'CORP',
       3,
       2,
       s.statement_no,
       s.total_amount,
       2,
       COALESCE(s.settle_time, s.update_time),
       '历史账期账单回填（上线收款流水前已结算）',
       COALESCE(s.settle_time, s.update_time),
       COALESCE(s.creator, '系统')
FROM "billing_statement" s
WHERE s.status = 2 AND s.is_deleted = false
ON CONFLICT DO NOTHING;
