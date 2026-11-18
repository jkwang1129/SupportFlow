-- tenant_db 初始化脚本（基线 + 增量），生成时间：2026-10-09 18:23
-- 由 backend/deploy/db-init/generate.sh 从仓库 schema/ 与 scripts/migrate-*.sh 机械拼接，请勿手改。
-- ===========================================================================
-- 库：tenant_db   （基线 schema/tenant_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · tenant_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "enterprise" (
  "id" BIGINT NOT NULL,
  "enterprise_code" VARCHAR(16) NOT NULL,
  "tenant_code" VARCHAR(16) DEFAULT NULL,
  "company_name" VARCHAR(128) NOT NULL,
  "industry" VARCHAR(32) DEFAULT NULL,
  "scale" VARCHAR(32) DEFAULT NULL,
  "license_no" VARCHAR(64) DEFAULT NULL,
  "register_address" VARCHAR(255) DEFAULT NULL,
  "legal_person" VARCHAR(64) DEFAULT NULL,
  "license_file_id" BIGINT DEFAULT NULL,
  "contact_name" VARCHAR(64) DEFAULT NULL,
  "contact_phone" VARCHAR(20) DEFAULT NULL,
  "contact_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_enterprise_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_enterprise_code_len CHECK (char_length("enterprise_code") = 16),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_enterprise_code" UNIQUE ("enterprise_code")
);
COMMENT ON TABLE "enterprise" IS '企业表（企业主数据）';
COMMENT ON COLUMN "enterprise"."enterprise_code" IS '企业编码（对外，定长 16，如 E202608280001）';
COMMENT ON COLUMN "enterprise"."tenant_code" IS '租户编码（审核通过创建租户后回填，可空；未开通前企业数据仅申请人可见）';
COMMENT ON COLUMN "enterprise"."company_name" IS '企业名称';
COMMENT ON COLUMN "enterprise"."industry" IS '所属行业';
COMMENT ON COLUMN "enterprise"."scale" IS '团队规模';
COMMENT ON COLUMN "enterprise"."license_no" IS '营业执照号';
COMMENT ON COLUMN "enterprise"."register_address" IS '注册地址';
COMMENT ON COLUMN "enterprise"."legal_person" IS '法人代表';
COMMENT ON COLUMN "enterprise"."license_file_id" IS '营业执照附件文件ID（file_meta）';
COMMENT ON COLUMN "enterprise"."contact_name" IS '管理员姓名';
COMMENT ON COLUMN "enterprise"."contact_phone" IS '联系电话（脱敏存储）';
COMMENT ON COLUMN "enterprise"."contact_email" IS '企业邮箱';
COMMENT ON COLUMN "enterprise"."status" IS '状态码：1-待审核、2-正常、3-已驳回、4-已注销';
CREATE INDEX "idx_enterprise_status_create_time" ON "enterprise" ("status", "create_time");

CREATE TABLE "tenant" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "enterprise_id" BIGINT NOT NULL,
  "plan_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "isolation_mode" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_tenant_isolation_mode CHECK ("isolation_mode" IN (1,2,3)),
  CONSTRAINT ck_tenant_code_len CHECK (char_length("tenant_code") = 16),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_code" UNIQUE ("tenant_code")
);
COMMENT ON TABLE "tenant" IS '租户表';
COMMENT ON COLUMN "tenant"."tenant_code" IS '租户编码（对外编码，定长 16，如 T202608280001）';
COMMENT ON COLUMN "tenant"."enterprise_id" IS '关联企业ID（enterprise 表）';
COMMENT ON COLUMN "tenant"."plan_id" IS '当前套餐ID';
COMMENT ON COLUMN "tenant"."status" IS '状态码：1-正常、2-冻结、3-到期、4-注销';
COMMENT ON COLUMN "tenant"."expire_time" IS '套餐到期时间';
COMMENT ON COLUMN "tenant"."isolation_mode" IS '隔离模式码：1-共享库、2-独立Schema、3-独立实例';
CREATE INDEX "idx_tenant_enterprise" ON "tenant" ("enterprise_id");
CREATE INDEX "idx_tenant_status_create_time" ON "tenant" ("status", "create_time");

CREATE TABLE "enterprise_review" (
  "id" BIGINT NOT NULL,
  "enterprise_id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) DEFAULT NULL,
  "apply_no" VARCHAR(32) NOT NULL,
  "version_no" SMALLINT NOT NULL DEFAULT 1,
  "applicant_id" BIGINT NOT NULL,
  "company_name" VARCHAR(128) NOT NULL,
  "industry" VARCHAR(32) DEFAULT NULL,
  "scale" VARCHAR(32) DEFAULT NULL,
  "contact_name" VARCHAR(64) NOT NULL,
  "contact_phone" VARCHAR(20) DEFAULT NULL,
  "contact_email" VARCHAR(128) DEFAULT NULL,
  "license_no" VARCHAR(64) DEFAULT NULL,
  "register_address" VARCHAR(255) DEFAULT NULL,
  "legal_person" VARCHAR(64) DEFAULT NULL,
  "license_file_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reviewer_id" BIGINT DEFAULT NULL,
  "review_time" TIMESTAMP DEFAULT NULL,
  "reject_reason" VARCHAR(512) DEFAULT NULL,
  "submit_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_enterprise_review_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_enterprise_review_enterprise_version" UNIQUE ("enterprise_id", "version_no")
);
COMMENT ON TABLE "enterprise_review" IS '企业审核表';
COMMENT ON COLUMN "enterprise_review"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "enterprise_review"."enterprise_id" IS '关联企业ID（enterprise 表，申请主体）';
COMMENT ON COLUMN "enterprise_review"."tenant_code" IS '租户编码（审核通过创建租户后回填，可空）';
COMMENT ON COLUMN "enterprise_review"."apply_no" IS '申请编号';
COMMENT ON COLUMN "enterprise_review"."version_no" IS '申请版本（驳回重提递增）';
COMMENT ON COLUMN "enterprise_review"."applicant_id" IS '申请人用户ID';
COMMENT ON COLUMN "enterprise_review"."company_name" IS '企业名称';
COMMENT ON COLUMN "enterprise_review"."industry" IS '所属行业';
COMMENT ON COLUMN "enterprise_review"."scale" IS '团队规模';
COMMENT ON COLUMN "enterprise_review"."contact_name" IS '管理员姓名';
COMMENT ON COLUMN "enterprise_review"."contact_phone" IS '联系电话（脱敏存储）';
COMMENT ON COLUMN "enterprise_review"."contact_email" IS '企业邮箱';
COMMENT ON COLUMN "enterprise_review"."license_no" IS '营业执照号';
COMMENT ON COLUMN "enterprise_review"."register_address" IS '注册地址';
COMMENT ON COLUMN "enterprise_review"."legal_person" IS '法人代表';
COMMENT ON COLUMN "enterprise_review"."license_file_id" IS '营业执照附件文件ID（file_meta）';
COMMENT ON COLUMN "enterprise_review"."status" IS '状态码：1-待审核、2-已通过、3-已驳回';
COMMENT ON COLUMN "enterprise_review"."reviewer_id" IS '审核人用户ID';
COMMENT ON COLUMN "enterprise_review"."review_time" IS '审核时间';
COMMENT ON COLUMN "enterprise_review"."reject_reason" IS '驳回原因';
COMMENT ON COLUMN "enterprise_review"."submit_time" IS '提交时间';
CREATE INDEX "idx_enterprise_review_tenant_status" ON "enterprise_review" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/tenant_db_isolation.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · tenant_db 多租户深度隔离增量脚本（PostgreSQL 17）
-- 用途：把"隔离"从"代码约定"变成"平台可管、可查、可升级"的三件事：
--   1. **租户级限流配额**（tenant_quota）：每个租户多少 QPS、每天多少请求 / 会话 / AI 调用；
--   2. **独立库升级**（tenant.db_version + tenant_schema_upgrade）：谁在什么时候升到第几版、结果如何；
--   3. **越权与篡改留痕**（tenant_security_event）：租户头与令牌不一致、跨租户访问被拒都记一条；
--   另附 tenant_usage_daily：网关批量上报的日用量（请求数 / 被限流数），平台侧和后续计费都用它。
--
-- 幂等：可重复执行（ADD COLUMN IF NOT EXISTS + CREATE TABLE IF NOT EXISTS + 索引 IF NOT EXISTS）。

-- ---------------------------------------------------------------------------
-- 1. tenant：独立库 / 版本相关列
-- ---------------------------------------------------------------------------
-- isolation_mode（1-共享库、2-独立 Schema、3-独立实例）在第 4 篇建表时就有了，这里补它周边：
--   升级到独立 Schema 时记录 schema 名；db_version 记录这个租户的库当前是第几版。
ALTER TABLE "tenant" ADD COLUMN IF NOT EXISTS "schema_name" VARCHAR(63) DEFAULT NULL;
ALTER TABLE "tenant" ADD COLUMN IF NOT EXISTS "db_version" INT NOT NULL DEFAULT 0;
ALTER TABLE "tenant" ADD COLUMN IF NOT EXISTS "upgraded_at" TIMESTAMP DEFAULT NULL;

COMMENT ON COLUMN "tenant"."schema_name" IS '独立 Schema 模式下的库 schema 名（共享库模式为空，用 public）';
COMMENT ON COLUMN "tenant"."db_version" IS '该租户的库当前版本号（与 TenantSchemaCatalog 的版本一致；0 表示还没登记过）';
COMMENT ON COLUMN "tenant"."upgraded_at" IS '最近一次成功升级的时间';

-- ---------------------------------------------------------------------------
-- 2. tenant_quota：租户级限流与配额
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "tenant_quota" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  -- 令牌桶速率：每秒允许多少个请求（网关按租户限流；0 表示不限流）
  "qps_limit" INT NOT NULL DEFAULT 20,
  -- 每天允许的请求总数（0 表示不限）
  "daily_request_limit" INT NOT NULL DEFAULT 200000,
  -- 每天允许新开的会话数（访客侧）
  "daily_session_limit" INT NOT NULL DEFAULT 20000,
  -- 每天允许的 AI 调用次数（机器人 / 质检 / 检索）
  "ai_daily_limit" INT NOT NULL DEFAULT 5000,
  -- 是否启用限流（冻结租户时置 false 并配合租户状态）
  "enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_quota_positive CHECK (
      "qps_limit" >= 0 AND "daily_request_limit" >= 0
      AND "daily_session_limit" >= 0 AND "ai_daily_limit" >= 0),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_quota" UNIQUE ("tenant_code")
);

COMMENT ON TABLE "tenant_quota" IS '租户限流与配额（网关按它限流，平台侧可调）';
COMMENT ON COLUMN "tenant_quota"."qps_limit" IS '每秒请求上限（0 = 不限流）';
COMMENT ON COLUMN "tenant_quota"."daily_request_limit" IS '每天请求总上限（0 = 不限）';
COMMENT ON COLUMN "tenant_quota"."daily_session_limit" IS '每天新开会话上限（0 = 不限）';
COMMENT ON COLUMN "tenant_quota"."ai_daily_limit" IS '每天 AI 调用上限（0 = 不限）';

-- ---------------------------------------------------------------------------
-- 3. tenant_schema_upgrade：独立库升级记录（控制面）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "tenant_schema_upgrade" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "target_schema" VARCHAR(63) NOT NULL DEFAULT 'public',
  "from_version" INT NOT NULL DEFAULT 0,
  "to_version" INT NOT NULL DEFAULT 0,
  -- 1-进行中、2-成功、3-失败
  "status" SMALLINT NOT NULL DEFAULT 1,
  "script_count" INT NOT NULL DEFAULT 0,
  "applied_scripts" VARCHAR(512) DEFAULT NULL,
  "message" VARCHAR(512) DEFAULT NULL,
  "duration_ms" INT NOT NULL DEFAULT 0,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "started_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "finished_at" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_tenant_schema_upgrade_status CHECK ("status" IN (1, 2, 3)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "tenant_schema_upgrade" IS '租户库升级记录（控制面：平台下发过哪些升级、结果如何）';
COMMENT ON COLUMN "tenant_schema_upgrade"."status" IS '状态码：1-进行中、2-成功、3-失败（失败可重试，脚本幂等）';
COMMENT ON COLUMN "tenant_schema_upgrade"."applied_scripts" IS '本次实际执行的脚本名（逗号分隔）；为空表示"已是最新版，无需执行"';

CREATE INDEX IF NOT EXISTS "idx_tenant_schema_upgrade_tenant"
    ON "tenant_schema_upgrade" ("tenant_code", "started_at" DESC);

-- ---------------------------------------------------------------------------
-- 4. tenant_security_event：越权 / 篡改租户头 / 跨租户访问留痕
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "tenant_security_event" (
  "id" BIGINT NOT NULL,
  -- 请求方声明的租户（可能被篡改）：平台级事件写 PLATFORM
  "tenant_code" VARCHAR(16) NOT NULL,
  -- 1-租户头与令牌不一致、2-跨租户资源访问被拒、3-被限流拒绝、4-RLS 拦截（应用层探测到）
  "event_type" SMALLINT NOT NULL,
  "detail" VARCHAR(512) DEFAULT NULL,
  "request_uri" VARCHAR(255) DEFAULT NULL,
  "request_method" VARCHAR(8) DEFAULT NULL,
  "client_ip" VARCHAR(64) DEFAULT NULL,
  "token_tenant" VARCHAR(16) DEFAULT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_tenant_security_event_type CHECK ("event_type" IN (1, 2, 3, 4)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "tenant_security_event" IS '租户安全事件（篡改租户头 / 跨租户访问 / 被限流 / RLS 拦截）';
COMMENT ON COLUMN "tenant_security_event"."event_type" IS '事件类型码：1-租户头与令牌不一致、2-跨租户资源访问、3-被限流拒绝、4-RLS 拦截';
COMMENT ON COLUMN "tenant_security_event"."token_tenant" IS '令牌里解析出的租户（权威值），用于和请求头对比';

CREATE INDEX IF NOT EXISTS "idx_tenant_security_event_time"
    ON "tenant_security_event" ("event_time" DESC);
CREATE INDEX IF NOT EXISTS "idx_tenant_security_event_tenant_type"
    ON "tenant_security_event" ("tenant_code", "event_type", "event_time" DESC);

-- ---------------------------------------------------------------------------
-- 5. tenant_usage_daily：网关批量上报的日用量
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "tenant_usage_daily" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "stat_date" DATE NOT NULL,
  "request_count" BIGINT NOT NULL DEFAULT 0,
  "rejected_count" BIGINT NOT NULL DEFAULT 0,
  "session_count" BIGINT NOT NULL DEFAULT 0,
  "ai_call_count" BIGINT NOT NULL DEFAULT 0,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_usage_daily" UNIQUE ("tenant_code", "stat_date")
);

COMMENT ON TABLE "tenant_usage_daily" IS '租户日用量（网关每 5 秒批量上报，平台侧展示 + 后续计费）';
COMMENT ON COLUMN "tenant_usage_daily"."rejected_count" IS '被限流拒绝的请求数（429）';

CREATE INDEX IF NOT EXISTS "idx_tenant_usage_daily_date"
    ON "tenant_usage_daily" ("stat_date" DESC, "tenant_code");

-- ---------------------------------------------------------------------------
-- 6. 为已有租户补一条默认配额（新开通的租户由 tenant-service 在开通时写入）
--    默认值刻意保守：单租户 20 QPS、每天 20 万请求——够中小客户用，
--    真跑起来按套餐在平台侧调（第 28 篇的计费会接管这里）。
-- ---------------------------------------------------------------------------
INSERT INTO "tenant_quota" ("id", "tenant_code", "qps_limit", "daily_request_limit",
                            "daily_session_limit", "ai_daily_limit", "enabled",
                            "remark", "creator")
SELECT (910000000000000000 + row_number() OVER (ORDER BY t."tenant_code"))::BIGINT,
       t."tenant_code", 20, 200000, 20000, 5000, TRUE,
       '默认配额（脚本初始化）', 'system'
  FROM "tenant" t
 WHERE t."is_deleted" = FALSE
   AND NOT EXISTS (SELECT 1 FROM "tenant_quota" q WHERE q."tenant_code" = t."tenant_code");

-- ---------------------------------------------------------------------------
-- 7. 回填 enterprise_review.tenant_code：审核通过时创建的租户号
--    早期版本的 approve() 只把 tenant_code 写进了 enterprise，漏写了审核记录，
--    导致审核列表/详情里"该申请对应的租户"是空的。这里按企业关联补一遍历史数据。
--    幂等：只更新 tenant_code 为空、且企业已开通的审核记录。
-- ---------------------------------------------------------------------------
UPDATE "enterprise_review" r
   SET "tenant_code" = e."tenant_code",
       "update_time" = CURRENT_TIMESTAMP
  FROM "enterprise" e
 WHERE e."id" = r."enterprise_id"
   AND e."is_deleted" = FALSE
   AND e."tenant_code" IS NOT NULL
   AND e."tenant_code" <> ''
   AND (r."tenant_code" IS NULL OR r."tenant_code" = '')
   AND r."is_deleted" = FALSE;

-- ---------------------------------------------------------------------------
-- 增量：schema/tenant_db_security_handling.sql
-- ---------------------------------------------------------------------------
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
