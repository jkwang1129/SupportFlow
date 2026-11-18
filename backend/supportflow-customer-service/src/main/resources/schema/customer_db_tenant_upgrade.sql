-- SupportFlow 智能客服平台 · customer_db 租户库升级记录（PostgreSQL 17）
-- 用途：记录"每个租户的库（共享库 / 独立 Schema）升级到第几版、什么时候、谁发起的、结果如何"。
--       控制面（tenant_db 的 tenant_schema_upgrade）记"平台下发过什么"，
--       数据面（本表）记"这个库实际执行到哪一版"——两边都留痕，排查时能对上。
--
-- 幂等：可重复执行。

CREATE TABLE IF NOT EXISTS "schema_upgrade_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  -- 目标库：共享库写 customer_db；独立 Schema 模式写 schema 名（如 cc_t202609050000002）
  "target_schema" VARCHAR(63) NOT NULL DEFAULT 'public',
  "version" INT NOT NULL,
  "script" VARCHAR(128) NOT NULL,
  "title" VARCHAR(128) DEFAULT NULL,
  -- 1-成功、2-失败
  "status" SMALLINT NOT NULL DEFAULT 1,
  "duration_ms" INT NOT NULL DEFAULT 0,
  "message" VARCHAR(512) DEFAULT NULL,
  "tenant_db_version" INT NOT NULL DEFAULT 0,
  "applied_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_schema_upgrade_log_status CHECK ("status" IN (1, 2)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "schema_upgrade_log" IS '租户库升级记录（数据面：这个库实际执行到哪一版）';
COMMENT ON COLUMN "schema_upgrade_log"."target_schema" IS '目标 schema：共享库为 public，独立 Schema 为租户专属 schema 名';
COMMENT ON COLUMN "schema_upgrade_log"."version" IS '脚本版本号（与 TenantSchemaCatalog 的版本一致）';
COMMENT ON COLUMN "schema_upgrade_log"."script" IS '脚本文件名（resources/schema 下的运行期副本）';
COMMENT ON COLUMN "schema_upgrade_log"."status" IS '状态码：1-成功、2-失败（失败可重试，脚本本身幂等）';
COMMENT ON COLUMN "schema_upgrade_log"."tenant_db_version" IS '执行完这一步之后，该库的版本号';

CREATE INDEX IF NOT EXISTS "idx_schema_upgrade_log_tenant"
    ON "schema_upgrade_log" ("tenant_code", "target_schema", "version" DESC);
