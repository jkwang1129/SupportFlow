-- ops_db 初始化脚本（基线 + 增量），生成时间：2026-10-09 18:23
-- 由 backend/deploy/db-init/generate.sh 从仓库 schema/ 与 scripts/migrate-*.sh 机械拼接，请勿手改。
-- ===========================================================================
-- 库：ops_db   （基线 schema/ops_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · ops_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "site_config" (
  "id" BIGINT NOT NULL,
  "section_key" VARCHAR(32) NOT NULL,
  "config_json" JSON NOT NULL,
  "version_no" INT NOT NULL DEFAULT 1,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "published_at" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_site_config_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_section_version" UNIQUE ("section_key", "version_no")
);
COMMENT ON TABLE "site_config" IS '官网配置表';
COMMENT ON COLUMN "site_config"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "site_config"."section_key" IS '区块标识：首屏、功能、定价、常见问题等';
COMMENT ON COLUMN "site_config"."config_json" IS '区块配置内容（JSON）';
COMMENT ON COLUMN "site_config"."version_no" IS '配置版本';
COMMENT ON COLUMN "site_config"."status" IS '状态码：1-草稿、2-已发布';
COMMENT ON COLUMN "site_config"."published_at" IS '发布时间';

CREATE TABLE "announcement" (
  "id" BIGINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "content" TEXT,
  "announce_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_announcement_announce_type CHECK ("announce_type" IN (1,2,3)),
  CONSTRAINT ck_announcement_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "announcement" IS '平台公告表';
COMMENT ON COLUMN "announcement"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "announcement"."title" IS '公告标题';
COMMENT ON COLUMN "announcement"."content" IS '公告内容';
COMMENT ON COLUMN "announcement"."announce_type" IS '类型码：1-维护通知、2-功能公告、3-其他';
COMMENT ON COLUMN "announcement"."status" IS '状态码：1-草稿、2-已发布、3-已下线';
COMMENT ON COLUMN "announcement"."publish_time" IS '发布时间';
CREATE INDEX "idx_announcement_status_time" ON "announcement" ("status", "publish_time");

CREATE TABLE "audit_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "module" VARCHAR(32) NOT NULL,
  "action" VARCHAR(64) NOT NULL,
  "target_type" VARCHAR(64) DEFAULT NULL,
  "target_id" VARCHAR(64) DEFAULT NULL,
  "result" SMALLINT NOT NULL,
  "detail" VARCHAR(1024) DEFAULT NULL,
  "ip" VARCHAR(64) DEFAULT NULL,
  "request_id" VARCHAR(64) DEFAULT NULL,
  "audit_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_audit_log_result CHECK ("result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "audit_log" IS '审计日志表（只追加，不更新不删除）';
COMMENT ON COLUMN "audit_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "audit_log"."tenant_code" IS '租户编码（平台操作为 PLATFORM）';
COMMENT ON COLUMN "audit_log"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "audit_log"."module" IS '模块';
COMMENT ON COLUMN "audit_log"."action" IS '操作';
COMMENT ON COLUMN "audit_log"."target_type" IS '对象类型';
COMMENT ON COLUMN "audit_log"."target_id" IS '对象标识';
COMMENT ON COLUMN "audit_log"."result" IS '结果码：1-成功、2-失败';
COMMENT ON COLUMN "audit_log"."detail" IS '详情';
COMMENT ON COLUMN "audit_log"."ip" IS 'IP';
COMMENT ON COLUMN "audit_log"."request_id" IS '请求号';
COMMENT ON COLUMN "audit_log"."audit_time" IS '审计时间';
CREATE INDEX "idx_audit_log_tenant_time" ON "audit_log" ("tenant_code", "audit_time");
CREATE INDEX "idx_audit_log_operator_time" ON "audit_log" ("operator_id", "audit_time");

CREATE TABLE "api_key" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "key_name" VARCHAR(64) NOT NULL,
  "api_key" VARCHAR(128) NOT NULL,
  "permissions" VARCHAR(255) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "last_used_at" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_api_key_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_api_key_value" UNIQUE ("tenant_code", "api_key")
);
COMMENT ON TABLE "api_key" IS '开放API密钥表';
COMMENT ON COLUMN "api_key"."key_name" IS '密钥名称';
COMMENT ON COLUMN "api_key"."api_key" IS '密钥值（加密存储）';
COMMENT ON COLUMN "api_key"."permissions" IS '权限范围（JSON）';
COMMENT ON COLUMN "api_key"."status" IS '状态码：1-生效、2-已吊销';
COMMENT ON COLUMN "api_key"."last_used_at" IS '最近使用时间';

CREATE TABLE "data_export" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "export_type" SMALLINT NOT NULL,
  "scope_json" JSON DEFAULT NULL,
  "file_id" BIGINT DEFAULT NULL,
  "requester_id" BIGINT NOT NULL,
  "approver_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reject_reason" VARCHAR(512) DEFAULT NULL,
  "request_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "approve_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_data_export_type CHECK ("export_type" IN (1,2,3,4)),
  CONSTRAINT ck_data_export_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "data_export" IS '数据导出审批表';
COMMENT ON COLUMN "data_export"."export_type" IS '类型码：1-会话、2-客户、3-报表、4-审计';
COMMENT ON COLUMN "data_export"."scope_json" IS '导出范围（JSON）';
COMMENT ON COLUMN "data_export"."file_id" IS '导出文件ID（file_meta）';
COMMENT ON COLUMN "data_export"."requester_id" IS '申请人用户ID';
COMMENT ON COLUMN "data_export"."approver_id" IS '审批人用户ID';
COMMENT ON COLUMN "data_export"."status" IS '状态码：1-待审批、2-已通过、3-已驳回';
COMMENT ON COLUMN "data_export"."reject_reason" IS '驳回原因';

CREATE TABLE "customer_success" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "health_score" SMALLINT DEFAULT NULL,
  "renew_risk" SMALLINT NOT NULL DEFAULT 1,
  "expand_opportunity" SMALLINT NOT NULL DEFAULT 2,
  "cs_owner" BIGINT DEFAULT NULL,
  "last_visit_time" TIMESTAMP DEFAULT NULL,
  "next_visit_time" TIMESTAMP DEFAULT NULL,
  "remark" VARCHAR(512) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_success_risk CHECK ("renew_risk" IN (1,2,3)),
  CONSTRAINT ck_customer_success_expand CHECK ("expand_opportunity" IN (1,2)),
  CONSTRAINT ck_customer_success_health CHECK ("health_score" IS NULL OR ("health_score" BETWEEN 0 AND 100)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_customer_success_tenant" UNIQUE ("tenant_code")
);
COMMENT ON TABLE "customer_success" IS '客户成功回访表';
COMMENT ON COLUMN "customer_success"."health_score" IS '健康度评分 0-100';
COMMENT ON COLUMN "customer_success"."renew_risk" IS '续费风险码：1-低、2-中、3-高';
COMMENT ON COLUMN "customer_success"."expand_opportunity" IS '扩展商机码：1-有、2-无';
COMMENT ON COLUMN "customer_success"."cs_owner" IS '客户成功经理用户ID';
COMMENT ON COLUMN "customer_success"."last_visit_time" IS '上次回访时间';
COMMENT ON COLUMN "customer_success"."next_visit_time" IS '下次回访时间';

CREATE TABLE "register_config" (
  "id" BIGINT NOT NULL,
  "allow_register" BOOLEAN NOT NULL DEFAULT TRUE,
  "need_review" BOOLEAN NOT NULL DEFAULT TRUE,
  "trial_days" SMALLINT NOT NULL DEFAULT 14,
  "config_json" JSON DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "register_config" IS '注册策略配置表（单行配置）';
COMMENT ON COLUMN "register_config"."allow_register" IS '是否开放注册';
COMMENT ON COLUMN "register_config"."need_review" IS '新注册是否需企业审核';
COMMENT ON COLUMN "register_config"."trial_days" IS '试用天数';

-- ---------------------------------------------------------------------------
-- 增量：schema/ops_db_audit.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · ops_db 审计日志增量脚本（PostgreSQL 17）
-- 用途：把「审计日志」用起来。audit_log 表在 ops_db.sql 里就建好了（一路没人写），
--      这里只补一个列和一个索引，不做结构重设计。
--
-- 为什么补 operator_name：表里原本只有 operator_id，而审计日志是给人看的——
-- 操作人姓名要能直接读，不能要求看日志的人再去 user_db 反查（跨库也查不了）。
-- 名字在写日志的那一刻就能拿到（JWT 里带 name），顺手存下来最省事也最准确
-- （账号改过名字之后，历史日志仍保留当时的操作人）。
--
-- 幂等：可重复执行。

ALTER TABLE "audit_log" ADD COLUMN IF NOT EXISTS "operator_name" VARCHAR(64) DEFAULT NULL;

COMMENT ON COLUMN "audit_log"."operator_name" IS '操作人姓名（写日志时的快照，避免改名后对不上）';

-- 按模块筛选是审计页最常用的条件（原型上的筛选器就是"全部模块 / 企业审核 / 用户管理 / …"）
CREATE INDEX IF NOT EXISTS "idx_audit_log_module_time"
    ON "audit_log" ("module", "audit_time" DESC);

-- 「只看失败」也是常见诉求：result=2 的那批要能快速捞出来
CREATE INDEX IF NOT EXISTS "idx_audit_log_result_time"
    ON "audit_log" ("result", "audit_time" DESC);

-- ---------------------------------------------------------------------------
-- 增量：schema/ops_db_customer_success.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ops_db 增量脚本 · 客户成功（平台「客户成功」页）
--
-- 两张表的职责分开：
--   * customer_success        —— **随访状态**（一租户一行）：谁负责、上次/下次回访、人工覆盖的风险与商机。
--                                这张表在 ops_db.sql 基线里就有，本脚本只做幂等兜底（老库可能有）。
--   * customer_success_task   —— **回访任务**：运营一条条建、完成或取消，带到期时间与结论。
--
-- 为什么健康度不落库：健康分是"算出来的"（用量 / 活跃 / 到期 / 趋势，数据在 tenant_db），
-- 落库就会立刻过期；库里只存"人决定的那些事"（负责人、回访时间、人工判断与备注）。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "customer_success" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "health_score" SMALLINT DEFAULT NULL,
  "renew_risk" SMALLINT NOT NULL DEFAULT 1,
  "expand_opportunity" SMALLINT NOT NULL DEFAULT 2,
  "cs_owner" BIGINT DEFAULT NULL,
  "last_visit_time" TIMESTAMP DEFAULT NULL,
  "next_visit_time" TIMESTAMP DEFAULT NULL,
  "remark" VARCHAR(512) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_success_risk CHECK ("renew_risk" IN (1,2,3)),
  CONSTRAINT ck_customer_success_expand CHECK ("expand_opportunity" IN (1,2)),
  CONSTRAINT ck_customer_success_health CHECK ("health_score" IS NULL OR ("health_score" BETWEEN 0 AND 100)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_customer_success_tenant" UNIQUE ("tenant_code")
);

-- 客户成功经理的姓名快照：cs_owner 存的是平台账号 ID，
-- 但列表要显示"谁负责"，每次回访再去 user-service 查一次不划算，直接存一份快照。
ALTER TABLE "customer_success" ADD COLUMN IF NOT EXISTS "cs_owner_name" VARCHAR(64) DEFAULT NULL;
COMMENT ON COLUMN "customer_success"."cs_owner_name" IS '客户成功经理姓名快照（cs_owner 是平台账号 ID）';

CREATE TABLE IF NOT EXISTS "customer_success_task" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "company_name" VARCHAR(128) DEFAULT NULL,
  -- 1-续费、2-健康、3-扩展、4-其它
  "task_type" SMALLINT NOT NULL DEFAULT 2,
  "title" VARCHAR(128) NOT NULL,
  "owner_id" BIGINT DEFAULT NULL,
  "owner_name" VARCHAR(64) DEFAULT NULL,
  "due_time" TIMESTAMP DEFAULT NULL,
  -- 1-待回访、2-已完成、3-已取消
  "status" SMALLINT NOT NULL DEFAULT 1,
  "result" VARCHAR(512) DEFAULT NULL,
  "finish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_cs_task_type CHECK ("task_type" IN (1,2,3,4)),
  CONSTRAINT ck_cs_task_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "customer_success_task" IS '客户成功回访任务';
COMMENT ON COLUMN "customer_success_task"."task_type" IS '类型码：1-续费、2-健康、3-扩展、4-其它';
COMMENT ON COLUMN "customer_success_task"."status" IS '状态码：1-待回访、2-已完成、3-已取消';
COMMENT ON COLUMN "customer_success_task"."company_name" IS '企业名快照（租户改名也能看懂历史任务）';

-- 列表按"待回访 + 到期时间"排，逾期要能一眼筛出来
CREATE INDEX IF NOT EXISTS "idx_cs_task_status_due"
    ON "customer_success_task" ("status", "due_time" DESC);

CREATE INDEX IF NOT EXISTS "idx_cs_task_tenant"
    ON "customer_success_task" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/ops_db_security.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ops_db 增量脚本 · 安全中心（平台「安全中心」页）
--
-- 三块：
--   * security_policy   —— 平台安全策略（全平台一行）。**每一项都要有执行点**：
--                          登录失败锁定 / IP 白名单 / 导出审批 / 水印 / 空闲登出 / 双因素，
--                          双因素的执行点在 user-service 的登录链路（第二步校验邮箱验证码）。
--   * api_key           —— 对外开放 API 的密钥（基线里就有这张表，本脚本补两列：
--                          存哈希而不是明文 + 展示用前缀）。
--   * data_export       —— 数据导出审批（基线表已有，本脚本补"实际导出"的两列：
--                          导出时间与条数，让"申请 → 批准 → 实际导出"闭环在同一行上）。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "security_policy" (
  "id" BIGINT NOT NULL,
  -- 登录连续失败锁定：达到阈值后锁定多少分钟（0 = 不锁定）
  "login_fail_threshold" SMALLINT NOT NULL DEFAULT 5,
  "login_lock_minutes" SMALLINT NOT NULL DEFAULT 15,
  -- 管理端登录 IP 白名单（只在登录接口生效，避免把自己锁在门外）
  "ip_whitelist_enabled" BOOLEAN NOT NULL DEFAULT FALSE,
  "ip_whitelist" VARCHAR(512) NOT NULL DEFAULT '127.0.0.1',
  -- 敏感导出需要审批（客户 360 等含个人信息的导出）
  "export_need_approval" BOOLEAN NOT NULL DEFAULT TRUE,
  -- 管理端水印与空闲自动登出
  "watermark_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "session_timeout_minutes" SMALLINT NOT NULL DEFAULT 120,
  -- 双因素（登录二次验证）：开关 + 覆盖范围
  --   范围 1-仅平台账号与企业管理员（默认，打扰最小）；2-全部账号（含坐席）
  "two_factor_required" BOOLEAN NOT NULL DEFAULT FALSE,
  "two_factor_scope" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_security_policy_fail CHECK ("login_fail_threshold" BETWEEN 0 AND 20),
  CONSTRAINT ck_security_policy_lock CHECK ("login_lock_minutes" BETWEEN 0 AND 1440),
  CONSTRAINT ck_security_policy_timeout CHECK ("session_timeout_minutes" BETWEEN 5 AND 1440),
  PRIMARY KEY ("id")
);

-- 老库（表已建过）：先补列，再写注释——CREATE TABLE IF NOT EXISTS 不会改已存在的表，
-- 顺序反了的话老库会在 COMMENT 那一行报"列不存在"，整个迁移脚本直接中断。
ALTER TABLE "security_policy" ADD COLUMN IF NOT EXISTS "two_factor_scope" SMALLINT NOT NULL DEFAULT 1;

COMMENT ON TABLE "security_policy" IS '平台安全策略（全平台一行）';
COMMENT ON COLUMN "security_policy"."two_factor_required" IS '双因素开关：开启后登录分两步（密码通过后再校验邮箱验证码）';
COMMENT ON COLUMN "security_policy"."two_factor_scope" IS '双因素覆盖范围：1-仅平台账号与企业管理员、2-全部账号（含坐席）';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_security_policy_two_factor_scope') THEN
    ALTER TABLE "security_policy"
      ADD CONSTRAINT ck_security_policy_two_factor_scope CHECK ("two_factor_scope" IN (1,2));
  END IF;
END $$;

-- 密钥只存哈希：库里被拖走也不能直接拿去调接口；前缀用于页面上辨认是哪一把
ALTER TABLE "api_key" ADD COLUMN IF NOT EXISTS "key_prefix" VARCHAR(24) DEFAULT NULL;
ALTER TABLE "api_key" ADD COLUMN IF NOT EXISTS "call_count" BIGINT NOT NULL DEFAULT 0;
ALTER TABLE "api_key" ADD COLUMN IF NOT EXISTS "remark" VARCHAR(255) DEFAULT NULL;
COMMENT ON COLUMN "api_key"."api_key" IS '密钥的 SHA-256 哈希（不存明文，只在生成时回显一次）';
COMMENT ON COLUMN "api_key"."key_prefix" IS '密钥前缀（如 yk_ab12cd34），页面上用来辨认';

-- 导出的"实际发生"信息：批准之后真导的时候才写
ALTER TABLE "data_export" ADD COLUMN IF NOT EXISTS "export_rows" INT DEFAULT NULL;
ALTER TABLE "data_export" ADD COLUMN IF NOT EXISTS "export_time" TIMESTAMP DEFAULT NULL;
COMMENT ON COLUMN "data_export"."export_rows" IS '实际导出的行数（批准后真导出时写）';
COMMENT ON COLUMN "data_export"."export_time" IS '实际导出时间';

CREATE INDEX IF NOT EXISTS "idx_data_export_status_time"
    ON "data_export" ("status", "request_time" DESC);

CREATE INDEX IF NOT EXISTS "idx_api_key_tenant_status"
    ON "api_key" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/ops_db_observability.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ops_db 增量脚本 · 全链路可观测与 AI 成本治理（平台「可观测」页）
--
-- 三块：
--   * observability_alert_rule   —— 阈值告警规则（指标 / 窗口 / 阈值 / 级别 / 范围）
--   * observability_alert_event  —— 命中规则产生的事件（可确认 / 可忽略，事件本身不改）
--   * tenant_cost_budget         —— 每租户的 AI 成本预算与超限动作
--
-- 为什么告警与预算放在 ops_db：这是**平台侧的经营动作**（谁花超了、要不要降级），
-- 与租户自己的业务数据分域；AI 的用量数字来自 ai_db（由 AI 服务出内部接口），
-- 平台只在自己库里存"规则 / 预算 / 事件"。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "observability_alert_rule" (
  "id" BIGINT NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  -- 指标：1-调用失败率(%)、2-平均耗时(ms)、3-单日成本(元)、4-单日调用量(次)、5-单日 Token 数
  "metric" SMALLINT NOT NULL,
  -- 统计窗口：看最近几天（1 = 今天）
  "window_days" SMALLINT NOT NULL DEFAULT 1,
  -- 比较符：1-大于等于、2-小于等于
  "compare_op" SMALLINT NOT NULL DEFAULT 1,
  "threshold" NUMERIC(14,4) NOT NULL,
  -- 级别：1-提示、2-警告、3-严重
  "level" SMALLINT NOT NULL DEFAULT 2,
  -- 范围：1-全平台、2-按租户（按租户的规则会通知到该企业的消息中心）
  "scope" SMALLINT NOT NULL DEFAULT 1,
  "notify_tenant" BOOLEAN NOT NULL DEFAULT TRUE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_observability_alert_rule_metric CHECK ("metric" BETWEEN 1 AND 5),
  CONSTRAINT ck_observability_alert_rule_op CHECK ("compare_op" IN (1,2)),
  CONSTRAINT ck_observability_alert_rule_level CHECK ("level" BETWEEN 1 AND 3),
  CONSTRAINT ck_observability_alert_rule_scope CHECK ("scope" IN (1,2)),
  CONSTRAINT ck_observability_alert_rule_window CHECK ("window_days" BETWEEN 1 AND 90),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "observability_alert_rule" IS '可观测告警规则（阈值 + 窗口 + 级别 + 范围）';
COMMENT ON COLUMN "observability_alert_rule"."metric" IS '指标：1-失败率%、2-平均耗时ms、3-单日成本元、4-单日调用量、5-单日Token';
COMMENT ON COLUMN "observability_alert_rule"."scope" IS '范围：1-全平台、2-按租户（按租户会通知该企业的消息中心）';

CREATE TABLE IF NOT EXISTS "observability_alert_event" (
  "id" BIGINT NOT NULL,
  "rule_id" BIGINT NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "metric" SMALLINT NOT NULL,
  "level" SMALLINT NOT NULL,
  "tenant_code" VARCHAR(16) DEFAULT NULL,
  "metric_value" NUMERIC(14,4) NOT NULL DEFAULT 0,
  "threshold" NUMERIC(14,4) NOT NULL DEFAULT 0,
  "window_days" SMALLINT NOT NULL DEFAULT 1,
  "stat_date" DATE NOT NULL,
  "detail" VARCHAR(512) DEFAULT NULL,
  -- 处置：1-待处理、2-已确认、3-已忽略
  "handle_status" SMALLINT NOT NULL DEFAULT 1,
  "handle_remark" VARCHAR(512) DEFAULT NULL,
  "handler_name" VARCHAR(64) DEFAULT NULL,
  "handle_time" TIMESTAMP DEFAULT NULL,
  -- 是否已经把消息推到企业消息中心（按租户规则才有）
  "notified" BOOLEAN NOT NULL DEFAULT FALSE,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_observability_alert_event_status CHECK ("handle_status" IN (1,2,3)),
  CONSTRAINT ck_observability_alert_event_level CHECK ("level" BETWEEN 1 AND 3),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "observability_alert_event" IS '告警事件（命中规则产生，可确认/忽略，事件内容不可改）';
COMMENT ON COLUMN "observability_alert_event"."stat_date" IS '这条事件统计的日期（同规则同租户同一天只产生一条）';

-- 幂等靠它：同一条规则 + 同一个租户 + 同一天只落一条事件（否则每 5 分钟刷一条）
CREATE UNIQUE INDEX IF NOT EXISTS "uk_observability_alert_event_daily"
    ON "observability_alert_event" ("rule_id", COALESCE("tenant_code", ''), "stat_date");

CREATE INDEX IF NOT EXISTS "idx_observability_alert_event_status"
    ON "observability_alert_event" ("handle_status", "event_time" DESC);

CREATE TABLE IF NOT EXISTS "tenant_cost_budget" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  -- 月度 AI 成本预算（元）；0 表示不限
  "monthly_budget" NUMERIC(12,2) NOT NULL DEFAULT 0,
  -- 达到预算的百分之多少先告警（默认 80%）
  "alert_ratio" SMALLINT NOT NULL DEFAULT 80,
  -- 超限动作：1-仅告警、2-降级到规则/检索（不调模型）、3-暂停 AI 能力
  "over_action" SMALLINT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_cost_budget_action CHECK ("over_action" IN (1,2,3)),
  CONSTRAINT ck_tenant_cost_budget_ratio CHECK ("alert_ratio" BETWEEN 1 AND 100),
  CONSTRAINT ck_tenant_cost_budget_amount CHECK ("monthly_budget" >= 0),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "tenant_cost_budget" IS '租户 AI 成本预算与超限动作（一租户一行）';
COMMENT ON COLUMN "tenant_cost_budget"."over_action" IS '超限动作：1-仅告警、2-降级到规则/检索、3-暂停 AI';

-- 老库：表已建过时补列（CREATE TABLE IF NOT EXISTS 不会改已存在的表）
ALTER TABLE "observability_alert_event" ADD COLUMN IF NOT EXISTS "notified" BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS "uk_tenant_cost_budget_tenant"
    ON "tenant_cost_budget" ("tenant_code")
    WHERE "is_deleted" = FALSE;
