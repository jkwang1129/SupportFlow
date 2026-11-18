-- ============================================================================
-- SupportFlow 智能客服 · 数据库一键初始化脚本（建库 + 结构 + 初始数据）
-- 生成时间：2026-10-09 18:23
-- 生成方式：backend/deploy/db-init/generate.sh 从仓库 schema/ + scripts/migrate-*.sh 机械拼接
--
-- 覆盖 6 个库：ai_db / billing_db / customer_db / ops_db / tenant_db / user_db
-- 内容：建库 + 建表/索引/约束/注释 + 初始数据（套餐 plan、平台角色与权限点）
--
-- 用法（需要 psql；库名如需不同，改下面的 CREATE DATABASE 与 \connect）：
--   PGPASSWORD=xxxx psql -h <host> -p 5432 -U <user> -d postgres -f db-init.sql
--
-- 前置条件：
--   1. 执行账号要有 CREATEDB 权限（建库），并且是目标对象属主；
--   2. 目标 PostgreSQL 已安装 pgvector 扩展（customer_db / ai_db 用到 vector 类型）；
--   3. 只用于**全新空库**：脚本按先建库再建表的顺序执行，重复执行会因表已存在而报错。
-- ============================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- 一、建库（已存在则跳过）
-- ---------------------------------------------------------------------------
SELECT 'CREATE DATABASE ai_db WITH ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ai_db')\gexec
SELECT 'CREATE DATABASE billing_db WITH ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'billing_db')\gexec
SELECT 'CREATE DATABASE customer_db WITH ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'customer_db')\gexec
SELECT 'CREATE DATABASE ops_db WITH ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ops_db')\gexec
SELECT 'CREATE DATABASE tenant_db WITH ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'tenant_db')\gexec
SELECT 'CREATE DATABASE user_db WITH ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'user_db')\gexec

\connect ai_db

-- ===========================================================================
-- 库：ai_db   （基线 schema/ai_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · ai_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "bot_intent" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "intent_code" VARCHAR(64) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "confidence" NUMERIC(5,2) DEFAULT NULL,
  "hit_count" INT NOT NULL DEFAULT 0,
  "samples" TEXT,
  "escalate" BOOLEAN NOT NULL DEFAULT FALSE,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_intent_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_intent" UNIQUE ("tenant_code", "intent_code")
);
COMMENT ON TABLE "bot_intent" IS '机器人意图表';
COMMENT ON COLUMN "bot_intent"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_intent"."intent_code" IS '意图编码';
COMMENT ON COLUMN "bot_intent"."name" IS '意图名称';
COMMENT ON COLUMN "bot_intent"."status" IS '状态码：1-启用、2-停用';
COMMENT ON COLUMN "bot_intent"."confidence" IS '平均置信度';
COMMENT ON COLUMN "bot_intent"."hit_count" IS '命中次数';
COMMENT ON COLUMN "bot_intent"."samples" IS '训练语料示例';
COMMENT ON COLUMN "bot_intent"."escalate" IS '命中该意图是否直接转人工（人工客服、投诉这类意图）';

CREATE TABLE "bot_model" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "model_key" VARCHAR(64) NOT NULL,
  "model_name" VARCHAR(64) NOT NULL,
  "provider" VARCHAR(32) NOT NULL,
  "model_type" SMALLINT NOT NULL,
  "temperature" NUMERIC(3,2) NOT NULL DEFAULT 0.30,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_model_model_type CHECK ("model_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_model" UNIQUE ("tenant_code", "model_key")
);

CREATE TABLE "bot_setting" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "bot_name" VARCHAR(64) NOT NULL DEFAULT '小云',
  "brand_name" VARCHAR(64) DEFAULT NULL,
  "avatar_url" VARCHAR(255) DEFAULT NULL,
  "welcome_message" VARCHAR(255) DEFAULT NULL,
  "fallback_message" VARCHAR(255) DEFAULT NULL,
  "transfer_prompt" VARCHAR(255) DEFAULT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "model_key" VARCHAR(64) DEFAULT NULL,
  "temperature" NUMERIC(3,2) NOT NULL DEFAULT 0.30,
  "reception_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "transfer_on_anger" BOOLEAN NOT NULL DEFAULT TRUE,
  "transfer_after_unresolved" INTEGER NOT NULL DEFAULT 2,
  "transfer_keywords" VARCHAR(255) DEFAULT NULL,
  "transfer_message" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_bot_setting" UNIQUE ("tenant_code")
);
COMMENT ON TABLE "bot_model" IS '机器人模型配置表';
COMMENT ON COLUMN "bot_model"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_model"."model_key" IS '模型标识';
COMMENT ON COLUMN "bot_model"."model_name" IS '模型名称';
COMMENT ON COLUMN "bot_model"."provider" IS '供应商：千问、DeepSeek等';
COMMENT ON COLUMN "bot_model"."model_type" IS '类型码：1-对话、2-视觉、3-轻量';
COMMENT ON COLUMN "bot_model"."temperature" IS '温度参数';
COMMENT ON COLUMN "bot_setting"."reception_enabled" IS '是否由机器人首轮接待：关闭后客户进线直接进人工队列';
COMMENT ON COLUMN "bot_setting"."brand_name" IS '租户品牌名：自我介绍与默认欢迎语里用它；留空用中性说法（不出现平台品牌）';
COMMENT ON COLUMN "bot_setting"."avatar_url" IS '机器人头像地址（留空用内置默认头像，不露出平台 logo）';
COMMENT ON COLUMN "bot_setting"."transfer_on_anger" IS '客户情绪激动时是否自动转人工';
COMMENT ON COLUMN "bot_setting"."transfer_after_unresolved" IS '连续多少轮没解决就自动转人工（0=从不）';
COMMENT ON COLUMN "bot_setting"."transfer_keywords" IS '命中即转人工的关键词，逗号分隔';
COMMENT ON COLUMN "bot_setting"."transfer_message" IS '转接中话术：客户要求人工后回的那一句（与"转人工提示"是两回事）';

CREATE TABLE "bot_dialogue" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "turn_count" INT NOT NULL DEFAULT 0,
  "unresolved_rounds" INT NOT NULL DEFAULT 0,
  "last_intent" VARCHAR(64) DEFAULT NULL,
  "last_intent_code" VARCHAR(64) DEFAULT NULL,
  "last_confidence" NUMERIC(5,2) DEFAULT NULL,
  "last_emotion" VARCHAR(20) DEFAULT NULL,
  "emotion_score" NUMERIC(5,2) DEFAULT NULL,
  "slots" TEXT,
  "transferred" BOOLEAN NOT NULL DEFAULT FALSE,
  "transfer_reason" VARCHAR(255) DEFAULT NULL,
  "last_message" VARCHAR(500) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "bot_dialogue" IS '机器人多轮对话状态表（一个会话一条）';
COMMENT ON COLUMN "bot_dialogue"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_dialogue"."session_no" IS '会话编号（对应 customer_db.session.session_no）';
COMMENT ON COLUMN "bot_dialogue"."turn_count" IS '机器人已经接待的轮次';
COMMENT ON COLUMN "bot_dialogue"."unresolved_rounds" IS '连续未解决轮次：答不上来/没查到就加一，答上了清零';
COMMENT ON COLUMN "bot_dialogue"."last_intent" IS '最近一次识别的意图名称';
COMMENT ON COLUMN "bot_dialogue"."last_intent_code" IS '最近一次识别的意图编码';
COMMENT ON COLUMN "bot_dialogue"."last_confidence" IS '最近一次意图置信度（0-100）';
COMMENT ON COLUMN "bot_dialogue"."last_emotion" IS '最近一次识别的情绪：中性/不满/愤怒/焦虑';
COMMENT ON COLUMN "bot_dialogue"."emotion_score" IS '负面情绪强度（0-100），越高越该转人工';
COMMENT ON COLUMN "bot_dialogue"."slots" IS '多轮槽位（JSON）：从对话里抽出来的订单号、手机号等';
COMMENT ON COLUMN "bot_dialogue"."transferred" IS '是否已经转人工';
COMMENT ON COLUMN "bot_dialogue"."transfer_reason" IS '转人工原因';
COMMENT ON COLUMN "bot_dialogue"."last_message" IS '客户最后一句话（截断保存，列表展示用）';
CREATE UNIQUE INDEX "uk_tenant_session_dialogue" ON "bot_dialogue" ("tenant_code", "session_no") WHERE "is_deleted" = FALSE;
CREATE INDEX "idx_bot_dialogue_tenant_update" ON "bot_dialogue" ("tenant_code", "update_time" DESC);

CREATE TABLE "bot_version" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "version_no" VARCHAR(32) NOT NULL,
  "train_type" SMALLINT NOT NULL,
  "base_model" VARCHAR(64) DEFAULT NULL,
  "accuracy" NUMERIC(5,2) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_version_train_type CHECK ("train_type" IN (1,2)),
  CONSTRAINT ck_bot_version_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_version" UNIQUE ("tenant_code", "version_no")
);
COMMENT ON TABLE "bot_version" IS '机器人模型版本表';
COMMENT ON COLUMN "bot_version"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_version"."version_no" IS '版本号（v9.2）';
COMMENT ON COLUMN "bot_version"."train_type" IS '训练类型码：1-全量、2-增量';
COMMENT ON COLUMN "bot_version"."base_model" IS '基座模型';
COMMENT ON COLUMN "bot_version"."accuracy" IS '识别率';
COMMENT ON COLUMN "bot_version"."status" IS '状态码：1-训练中、2-已上线、3-已回滚';
COMMENT ON COLUMN "bot_version"."publish_time" IS '上线时间';

CREATE TABLE "training_job" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "job_no" VARCHAR(40) NOT NULL,
  "job_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "progress" INT NOT NULL DEFAULT 0,
  "result_version" VARCHAR(32) DEFAULT NULL,
  "error_msg" VARCHAR(512) DEFAULT NULL,
  "start_time" TIMESTAMP DEFAULT NULL,
  "finish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_training_job_job_type CHECK ("job_type" IN (1,2)),
  CONSTRAINT ck_training_job_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_job_no" UNIQUE ("tenant_code", "job_no")
);
COMMENT ON TABLE "training_job" IS 'AI训练任务表';
COMMENT ON COLUMN "training_job"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "training_job"."job_no" IS '任务编号';
COMMENT ON COLUMN "training_job"."job_type" IS '类型码：1-意图训练、2-模型训练';
COMMENT ON COLUMN "training_job"."status" IS '状态码：1-等待中、2-执行中、3-成功、4-失败';
COMMENT ON COLUMN "training_job"."progress" IS '进度 0-100';
COMMENT ON COLUMN "training_job"."result_version" IS '产出版本';
COMMENT ON COLUMN "training_job"."error_msg" IS '失败原因';
COMMENT ON COLUMN "training_job"."start_time" IS '开始时间';
COMMENT ON COLUMN "training_job"."finish_time" IS '结束时间';
CREATE INDEX "idx_training_job_tenant_status" ON "training_job" ("tenant_code", "status");

CREATE TABLE "ai_usage_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "model_key" VARCHAR(64) NOT NULL,
  "scene" SMALLINT NOT NULL,
  "input_tokens" INT NOT NULL DEFAULT 0,
  "output_tokens" INT NOT NULL DEFAULT 0,
  "cost_amount" NUMERIC(12,4) NOT NULL DEFAULT 0,
  "latency_ms" INT DEFAULT NULL,
  "is_success" BOOLEAN NOT NULL DEFAULT TRUE,
  "request_id" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_usage_record_scene CHECK ("scene" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ai_usage_record" IS 'AI调用计量表';
COMMENT ON COLUMN "ai_usage_record"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ai_usage_record"."model_key" IS '模型标识';
COMMENT ON COLUMN "ai_usage_record"."scene" IS '场景码：1-对话、2-质检、3-视觉识别';
COMMENT ON COLUMN "ai_usage_record"."input_tokens" IS '输入 token';
COMMENT ON COLUMN "ai_usage_record"."output_tokens" IS '输出 token';
COMMENT ON COLUMN "ai_usage_record"."cost_amount" IS '成本金额';
COMMENT ON COLUMN "ai_usage_record"."latency_ms" IS '耗时（毫秒）';
COMMENT ON COLUMN "ai_usage_record"."is_success" IS '是否成功';
COMMENT ON COLUMN "ai_usage_record"."request_id" IS '关联请求号';
COMMENT ON COLUMN "ai_usage_record"."stat_date" IS '统计日期（用于聚合）';
CREATE INDEX "idx_ai_usage_record_tenant_model_date" ON "ai_usage_record" ("tenant_code", "model_key", "stat_date");

-- ---------------------------------------------------------------------------
-- 增量：schema/ai_db_bot_brain.sql
-- ---------------------------------------------------------------------------
-- AI 客服大脑 · 意图识别 / 多轮对话 / 情绪识别 / 自动转人工（可重复执行）：
--   1. bot_setting 补"接待策略"：机器人首轮接待、情绪激动转人工、连续未解决转人工、转人工关键词；
--   2. bot_intent 补 escalate：命中该意图直接转人工（不再让机器人硬答）；
--   3. 新增 bot_dialogue：一个会话的机器人对话状态（轮次 / 未解决次数 / 意图 / 情绪 / 槽位）；
--   4. 给已有租户补默认策略，并给"人工客服"这类意图打上直接转人工标记。
--
-- 为什么要独立一张 bot_dialogue：多轮对话必须有"记忆"。
-- 只靠把历史消息重新喂给模型是"无状态"的，判断不了"已经问了三轮还没解决，该转人工了"，
-- 也留不住"客户上一条报了订单号"这种槽位。状态放这里，会话一结束就跟着归档。

-- ---------- 1. bot_setting：接待策略 ----------
ALTER TABLE "bot_setting"
    ADD COLUMN IF NOT EXISTS "reception_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS "transfer_on_anger" BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS "transfer_after_unresolved" INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN IF NOT EXISTS "transfer_keywords" VARCHAR(255),
    ADD COLUMN IF NOT EXISTS "transfer_message" VARCHAR(255);

COMMENT ON COLUMN "bot_setting"."reception_enabled" IS '是否由机器人首轮接待：关闭后客户进线直接进人工队列';
COMMENT ON COLUMN "bot_setting"."transfer_on_anger" IS '客户情绪激动时是否自动转人工';
COMMENT ON COLUMN "bot_setting"."transfer_after_unresolved" IS '连续多少轮没解决就自动转人工（0=从不）';
COMMENT ON COLUMN "bot_setting"."transfer_keywords" IS '命中即转人工的关键词，逗号分隔';
COMMENT ON COLUMN "bot_setting"."transfer_message" IS '转接中话术：客户要求人工后回的那一句（与"转人工提示"是两回事）';

-- 转接中话术与"转人工提示"是两个字段，别混用：
--   transfer_prompt  —— 提示语："如需人工客服，请回复转人工…"，出现在机器人答不上来的回答里；
--   transfer_message —— 确认语："好的，正在为您转接人工客服，请稍候。"，真的开始转人工时说。
-- 早先把提示语当成了确认语，客户说完"转人工"，机器人回他"请回复转人工"，看起来就像没转。
UPDATE "bot_setting"
   SET "transfer_message" = '好的，正在为您转接人工客服，请稍候。'
 WHERE "transfer_message" IS NULL;

-- 老数据补一个默认关键词表（只在没配过的时候写，不覆盖用户已经改过的值）
UPDATE "bot_setting"
   SET "transfer_keywords" = '转人工,人工客服,找人工,要人工,人工'
 WHERE "transfer_keywords" IS NULL;

-- ---------- 2. bot_intent：直接转人工标记 ----------
ALTER TABLE "bot_intent" ADD COLUMN IF NOT EXISTS "escalate" BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN "bot_intent"."escalate" IS '命中该意图是否直接转人工（人工客服、投诉这类意图）';

-- 名称里带"人工 / 投诉"的意图默认直接转人工
UPDATE "bot_intent"
   SET "escalate" = TRUE
 WHERE "escalate" = FALSE
   AND ("name" LIKE '%人工%' OR "name" LIKE '%投诉%' OR "name" LIKE '%举报%');

-- ---------- 3. bot_dialogue：多轮对话状态 ----------
CREATE TABLE IF NOT EXISTS "bot_dialogue" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "turn_count" INT NOT NULL DEFAULT 0,
  "unresolved_rounds" INT NOT NULL DEFAULT 0,
  "last_intent" VARCHAR(64) DEFAULT NULL,
  "last_intent_code" VARCHAR(64) DEFAULT NULL,
  "last_confidence" NUMERIC(5,2) DEFAULT NULL,
  "last_emotion" VARCHAR(20) DEFAULT NULL,
  "emotion_score" NUMERIC(5,2) DEFAULT NULL,
  "slots" TEXT,
  "transferred" BOOLEAN NOT NULL DEFAULT FALSE,
  "transfer_reason" VARCHAR(255) DEFAULT NULL,
  "last_message" VARCHAR(500) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "bot_dialogue" IS '机器人多轮对话状态表（一个会话一条）';
COMMENT ON COLUMN "bot_dialogue"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_dialogue"."session_no" IS '会话编号（对应 customer_db.session.session_no）';
COMMENT ON COLUMN "bot_dialogue"."turn_count" IS '机器人已经接待的轮次';
COMMENT ON COLUMN "bot_dialogue"."unresolved_rounds" IS '连续未解决轮次：答不上来/没查到就加一，答上了清零';
COMMENT ON COLUMN "bot_dialogue"."last_intent" IS '最近一次识别的意图名称';
COMMENT ON COLUMN "bot_dialogue"."last_intent_code" IS '最近一次识别的意图编码';
COMMENT ON COLUMN "bot_dialogue"."last_confidence" IS '最近一次意图置信度（0-100）';
COMMENT ON COLUMN "bot_dialogue"."last_emotion" IS '最近一次识别的情绪：中性/不满/愤怒/焦虑';
COMMENT ON COLUMN "bot_dialogue"."emotion_score" IS '负面情绪强度（0-100），越高越该转人工';
COMMENT ON COLUMN "bot_dialogue"."slots" IS '多轮槽位（JSON）：从对话里抽出来的订单号、手机号等';
COMMENT ON COLUMN "bot_dialogue"."transferred" IS '是否已经转人工';
COMMENT ON COLUMN "bot_dialogue"."transfer_reason" IS '转人工原因';
COMMENT ON COLUMN "bot_dialogue"."last_message" IS '客户最后一句话（截断保存，列表展示用）';

-- 一个租户下一个会话只有一条状态（软删的不算，便于保留历史）
CREATE UNIQUE INDEX IF NOT EXISTS "uk_tenant_session_dialogue"
    ON "bot_dialogue" ("tenant_code", "session_no")
 WHERE "is_deleted" = FALSE;

CREATE INDEX IF NOT EXISTS "idx_bot_dialogue_tenant_update"
    ON "bot_dialogue" ("tenant_code", "update_time" DESC);

-- ---------------------------------------------------------------------------
-- 增量：schema/ai_db_observability.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ai_db 增量脚本 · AI 调用可观测（第 29 篇：AI 监控）
--
-- 建的是 ai_db.sql 基线里早就有的 ai_usage_record（AI 调用计量表），以前没人写数据。
-- 现在每次真实调模型（对话 / 质检 / 视觉）都落一条，平台「AI 监控」据此算：
--   调用量 · token · 成本 · 耗时 · 成功率，按天 / 模型 / 场景 / 租户任意聚合。
--
-- 为什么单独一个增量脚本（而不是直接跑 ai_db.sql 基线）：基线里的建表语句没有
-- IF NOT EXISTS，老库上再跑一次会报"表已存在"，脚本就没法幂等重跑。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "ai_usage_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "model_key" VARCHAR(64) NOT NULL,
  "scene" SMALLINT NOT NULL,
  "input_tokens" INT NOT NULL DEFAULT 0,
  "output_tokens" INT NOT NULL DEFAULT 0,
  "cost_amount" NUMERIC(12,4) NOT NULL DEFAULT 0,
  "latency_ms" INT DEFAULT NULL,
  "is_success" BOOLEAN NOT NULL DEFAULT TRUE,
  "request_id" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_usage_record_scene CHECK ("scene" IN (1,2,3)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "ai_usage_record" IS 'AI调用计量表（每次真实调模型一条）';
COMMENT ON COLUMN "ai_usage_record"."model_key" IS '模型标识（如 deepseek-chat / qwen-plus）';
COMMENT ON COLUMN "ai_usage_record"."scene" IS '场景码：1-对话（含问答/大脑）、2-质检、3-视觉识别';
COMMENT ON COLUMN "ai_usage_record"."cost_amount" IS '成本金额（元，按模型挂牌价估算）';
COMMENT ON COLUMN "ai_usage_record"."stat_date" IS '统计日期（按天聚合用）';

-- 平台监控的主查询是"最近 N 天 + 按天/模型/租户聚合"，索引按这个走
CREATE INDEX IF NOT EXISTS "idx_ai_usage_record_stat_date"
    ON "ai_usage_record" ("stat_date" DESC, "tenant_code");

CREATE INDEX IF NOT EXISTS "idx_ai_usage_record_tenant_model_date"
    ON "ai_usage_record" ("tenant_code", "model_key", "stat_date");

-- 降级原因：model_key='mock' 的行是"请求走了规则引擎、没真调模型"，
-- 记一句原因（未配置密钥 / 模型不可用）方便平台侧一眼定位。
ALTER TABLE "ai_usage_record" ADD COLUMN IF NOT EXISTS "remark" VARCHAR(64) DEFAULT NULL;

COMMENT ON COLUMN "ai_usage_record"."remark" IS '备注：降级原因等（如 未配置模型密钥）';

-- 调用链：每个请求一个 request_id（前端 → 网关 → Java 服务 → AI 服务一路透传），
-- 出了事拿这个号就能查到"这一次 AI 调用花了多少、慢在哪、成没成"。
CREATE INDEX IF NOT EXISTS "idx_ai_usage_record_request_id"
    ON "ai_usage_record" ("request_id");

COMMENT ON COLUMN "ai_usage_record"."request_id" IS '调用链号（X-Request-Id，三端透传）';

-- ---------------------------------------------------------------------------
-- 增量：schema/ai_db_training.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ai_db 增量脚本 · 模型训练中心（「智能机器人 → 模型训练中心」）
--
-- 训练到底训什么：把**意图语料真正向量化**，落成一个可以切换、可以回滚的版本。
--   · 语料来源①：租户在页面上维护的意图语料（bot_intent.samples）
--   · 语料来源②：历史真实会话里机器人判过意图的问题（bot_dialogue，弱标注）
--   · 产物：bot_intent_sample（每个版本、每条语料一行，带 1024 维向量）
--   · 评估：留一法（去掉它自己再匹配一次，看能不能回到同一个意图）算识别率
--   · 生效：机器人识别意图时优先用「线上版本」的向量；灰度版本按比例生效
--
-- 为什么单独一个增量脚本：ai_db.sql 是基线（CREATE TABLE 不带 IF NOT EXISTS），
-- 老库上重跑会报"表已存在"，所以增量部分一律放这里。
-- ---------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- 1. bot_version：补「灰度比例 / 语料统计 / 产出任务 / 备注」四类信息
--    状态语义一并校正为：1-训练中、2-灰度中、3-已上线、4-已下线
--    （这张表此前没有任何数据，改动语义是安全的；校验约束同步放开到 4）
-- ---------------------------------------------------------------------------
ALTER TABLE "bot_version" ADD COLUMN IF NOT EXISTS "gray_percent" SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE "bot_version" ADD COLUMN IF NOT EXISTS "sample_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "bot_version" ADD COLUMN IF NOT EXISTS "intent_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "bot_version" ADD COLUMN IF NOT EXISTS "job_no" VARCHAR(40) DEFAULT NULL;
ALTER TABLE "bot_version" ADD COLUMN IF NOT EXISTS "remark" VARCHAR(255) DEFAULT NULL;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_bot_version_status') THEN
    ALTER TABLE "bot_version" DROP CONSTRAINT ck_bot_version_status;
  END IF;
  ALTER TABLE "bot_version"
    ADD CONSTRAINT ck_bot_version_status CHECK ("status" IN (1,2,3,4));
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_bot_version_gray') THEN
    ALTER TABLE "bot_version"
      ADD CONSTRAINT ck_bot_version_gray CHECK ("gray_percent" BETWEEN 0 AND 100);
  END IF;
END $$;

COMMENT ON COLUMN "bot_version"."status" IS '状态码：1-训练中、2-灰度中、3-已上线、4-已下线';
COMMENT ON COLUMN "bot_version"."gray_percent" IS '灰度比例（0 = 全量；>0 表示按这个比例把请求打到这个版本）';
COMMENT ON COLUMN "bot_version"."sample_count" IS '本次训练用了多少条语料';
COMMENT ON COLUMN "bot_version"."intent_count" IS '覆盖多少个意图';
COMMENT ON COLUMN "bot_version"."job_no" IS '产出这个版本的任务编号';
COMMENT ON COLUMN "bot_version"."remark" IS '版本说明（训练类型 / 语料条数 / 识别率 / 与上一版的对比）';

-- ---------------------------------------------------------------------------
-- 2. training_job：补「全量还是增量 / 当前阶段 / 语料统计 / 计划灰度」
-- ---------------------------------------------------------------------------
ALTER TABLE "training_job" ADD COLUMN IF NOT EXISTS "mode" SMALLINT NOT NULL DEFAULT 2;
ALTER TABLE "training_job" ADD COLUMN IF NOT EXISTS "phase" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "training_job" ADD COLUMN IF NOT EXISTS "sample_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "training_job" ADD COLUMN IF NOT EXISTS "intent_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "training_job" ADD COLUMN IF NOT EXISTS "gray_percent" SMALLINT NOT NULL DEFAULT 0;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_training_job_mode') THEN
    ALTER TABLE "training_job"
      ADD CONSTRAINT ck_training_job_mode CHECK ("mode" IN (1,2));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_training_job_gray') THEN
    ALTER TABLE "training_job"
      ADD CONSTRAINT ck_training_job_gray CHECK ("gray_percent" BETWEEN 0 AND 100);
  END IF;
END $$;

COMMENT ON COLUMN "training_job"."mode" IS '训练模式：1-全量（重新训练全部语料）、2-增量（只补新语料，旧的复用）';
COMMENT ON COLUMN "training_job"."phase" IS '当前阶段文案（采集语料 / 向量化 / 评估识别率 / 落库版本）';
COMMENT ON COLUMN "training_job"."sample_count" IS '本次采集到的语料条数';
COMMENT ON COLUMN "training_job"."intent_count" IS '覆盖的意图数';
COMMENT ON COLUMN "training_job"."gray_percent" IS '训练完成后按这个比例灰度（0 = 训练完直接全量上线）';

-- ---------------------------------------------------------------------------
-- 3. bot_intent_sample：训练产物（每个版本一套样本向量）
--
-- 版本切换就是"换一套向量"：识别时按线上版本查这张表。
-- source 记清这条语料是人写的还是从真实会话里捞的（弱标注），
-- 页面上要能分辨"训练用的东西是不是真实的"。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bot_intent_sample" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "version_no" VARCHAR(32) NOT NULL,
  "intent_code" VARCHAR(64) NOT NULL,
  "intent_name" VARCHAR(64) NOT NULL,
  "sample" VARCHAR(200) NOT NULL,
  "source" SMALLINT NOT NULL DEFAULT 1,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_bot_intent_sample_source CHECK ("source" IN (1,2)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "bot_intent_sample" IS '意图训练产物（每个版本一套样本向量）';
COMMENT ON COLUMN "bot_intent_sample"."version_no" IS '所属版本号（v1.0 / v1.1 …）';
COMMENT ON COLUMN "bot_intent_sample"."sample" IS '语料原文（一条一个样本）';
COMMENT ON COLUMN "bot_intent_sample"."source" IS '语料来源：1-页面维护的意图语料、2-历史真实会话（弱标注）';
COMMENT ON COLUMN "bot_intent_sample"."embedding" IS '向量（1024 维，与知识库切片同一套 embedding 接口）';

CREATE INDEX IF NOT EXISTS "idx_bot_intent_sample_version"
    ON "bot_intent_sample" ("tenant_code", "version_no");

-- 识别时的主查询是"拿问题向量在这个版本的样本里找最近的"，
-- 用 HNSW + 余弦距离（和知识库切片同一套用法）
CREATE INDEX IF NOT EXISTS "idx_bot_intent_sample_embedding"
    ON "bot_intent_sample" USING hnsw ("embedding" vector_cosine_ops);

-- ---------------------------------------------------------------------------
-- 增量：schema/ai_db_bot_brand.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ai_db 增量脚本 · 机器人品牌名（多租户各配各的）
--
-- 背景：客户在访客端看到的第一句话，以前是写死在代码里的
--   「您好，我是SupportFlow 智能客服小云，很高兴为您服务。您可以直接描述问题，例如"查询物流""退款怎么办理"。」
-- 这句里同时固定了三样东西：平台品牌、机器人名、示例问题。
-- 机器人名本来就取自 bot_setting.bot_name（每租户一份），但品牌名没有租户维度，
-- 于是平台的品牌会出现在租户客户的对话里——这是不该越界的。
--
-- 处理：
--   1. 加一个租户级的 brand_name；
--   2. 默认话术改成模板（{bot_name} / {brand_name} 占位），由代码在读取时替换；
--      租户改过之后就是明文，不再套模板。
--
-- 可重复执行。
-- ---------------------------------------------------------------------------

ALTER TABLE "bot_setting" ADD COLUMN IF NOT EXISTS "brand_name" VARCHAR(64) DEFAULT NULL;

COMMENT ON COLUMN "bot_setting"."brand_name"
    IS '租户品牌名：自我介绍与默认欢迎语里用它，避免把平台品牌写进租户对客户的对话；留空则用中性说法';

-- 存量数据：以前自动建的那句默认欢迎语带着平台品牌，这里换成模板。
-- 只改「还等于那句老默认值」的行——租户自己改过的文案一个字都不动。
UPDATE "bot_setting"
   SET "welcome_message" = '您好，我是{bot_name}，很高兴为您服务。您可以直接描述问题。',
       "update_time" = CURRENT_TIMESTAMP
 WHERE "is_deleted" = FALSE
   AND "welcome_message" IN (
       '您好，我是SupportFlow 智能客服小云，很高兴为您服务。您可以直接描述问题，例如“查询物流”“退款怎么办理”。',
       '您好，我是SupportFlow 智能客服小云，很高兴为您服务。您可以直接描述问题。'
   );

-- ---------------------------------------------------------------------------
-- 增量：schema/ai_db_bot_avatar.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- ai_db 增量脚本 · 机器人头像（多租户各配各的）
--
-- 背景：访客端聊天里那个头像以前是**平台 logo**（前端写死 /supportflow-mark.svg）——
-- 租户的客户看到的却是平台的标，和「欢迎语里写死平台品牌」是同一类越界。
--
-- 处理：给 bot_setting 加一个租户级的 avatar_url：
--   · 留空 → 前端用内置的默认客服头像（/bot-avatar.svg，不露平台标）；
--   · 填了 → 用租户自己的图（企业自家 CDN / 对象存储地址都行）。
--
-- 可重复执行。
-- ---------------------------------------------------------------------------

ALTER TABLE "bot_setting" ADD COLUMN IF NOT EXISTS "avatar_url" VARCHAR(255) DEFAULT NULL;

COMMENT ON COLUMN "bot_setting"."avatar_url"
    IS '机器人头像地址（租户自己的图；留空用内置默认头像，不会露出平台 logo）';

\connect billing_db

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

\connect customer_db

-- ===========================================================================
-- 库：customer_db   （基线 schema/customer_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · customer_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "channel" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" VARCHAR(32) NOT NULL,
  "channel_type" SMALLINT NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "desc" VARCHAR(255) DEFAULT NULL,
  "skill_group_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 2,
  "stage" SMALLINT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "allowed_origins" VARCHAR(512) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_channel_type CHECK ("channel_type" IN (1,2,3,4,5,6,7)),
  CONSTRAINT ck_channel_status CHECK ("status" IN (1,2)),
  CONSTRAINT ck_channel_stage CHECK ("stage" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_channel" UNIQUE ("tenant_code", "channel_id")
);
COMMENT ON TABLE "channel" IS '渠道表';
COMMENT ON COLUMN "channel"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel"."channel_id" IS '渠道ID（SDK 用）';
COMMENT ON COLUMN "channel"."channel_type" IS '类型码：1-网站、2-公众号、3-小程序、4-App、5-400热线、6-邮件、7-自定义';
COMMENT ON COLUMN "channel"."name" IS '渠道名称';
COMMENT ON COLUMN "channel"."desc" IS '描述';
COMMENT ON COLUMN "channel"."skill_group_id" IS '绑定技能组ID';
COMMENT ON COLUMN "channel"."status" IS '状态码：1-启用、2-停用';
COMMENT ON COLUMN "channel"."stage" IS '阶段码：1-未配置、2-待上线、3-运行中';
COMMENT ON COLUMN "channel"."allowed_origins" IS '访客接入域名白名单（逗号分隔，支持 *.example.com；为空表示不限制）';

CREATE TABLE "channel_key" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "app_key" VARCHAR(64) NOT NULL,
  "key_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "rotated_at" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_key_key_type CHECK ("key_type" IN (1,2)),
  CONSTRAINT ck_channel_key_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_channel_key" UNIQUE ("tenant_code", "channel_id", "app_key")
);
COMMENT ON TABLE "channel_key" IS '渠道密钥表';
COMMENT ON COLUMN "channel_key"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel_key"."channel_id" IS '渠道ID';
COMMENT ON COLUMN "channel_key"."app_key" IS '密钥（加密存储）';
COMMENT ON COLUMN "channel_key"."key_type" IS '类型码：1-渠道密钥、2-租户主密钥';
COMMENT ON COLUMN "channel_key"."status" IS '状态码：1-生效、2-已吊销';
COMMENT ON COLUMN "channel_key"."rotated_at" IS '最近轮换时间';
COMMENT ON COLUMN "channel_key"."expire_time" IS '过期时间';

CREATE TABLE "skill_group" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "description" VARCHAR(255) DEFAULT NULL,
  "is_default" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "overflow_after_seconds" INTEGER NOT NULL DEFAULT 60,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_name" UNIQUE ("tenant_code", "name")
);
COMMENT ON TABLE "skill_group" IS '技能组表';
COMMENT ON COLUMN "skill_group"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "skill_group"."name" IS '技能组名称';
COMMENT ON COLUMN "skill_group"."description" IS '说明';
COMMENT ON COLUMN "skill_group"."is_default" IS '是否默认';

CREATE TABLE "agent_status" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "max_concurrency" SMALLINT NOT NULL DEFAULT 5,
  "is_connected" BOOLEAN NOT NULL DEFAULT FALSE,
  "status_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_agent_status_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_status" UNIQUE ("tenant_code", "agent_id")
);
COMMENT ON TABLE "agent_status" IS '坐席状态表';
COMMENT ON COLUMN "agent_status"."agent_id" IS '坐席用户ID';
COMMENT ON COLUMN "agent_status"."status" IS '状态码：1-在线、2-忙碌、3-小休';
COMMENT ON COLUMN "agent_status"."max_concurrency" IS '最多同时接待的会话数';
COMMENT ON COLUMN "agent_status"."is_connected" IS '长连接是否在线（由实时网关维护，路由只分配在线坐席）';
COMMENT ON COLUMN "agent_status"."status_time" IS '状态变更时间（同负载时优先分配给更久没换状态的坐席）';
CREATE INDEX "idx_agent_status_tenant_status" ON "agent_status" ("tenant_code", "status");
CREATE TABLE "channel_test_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "test_result" SMALLINT NOT NULL,
  "latency_ms" INT DEFAULT NULL,
  "summary" VARCHAR(255) DEFAULT NULL,
  "test_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_channel_test_log_test_result CHECK ("test_result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_test_log" IS '渠道联调记录表';
COMMENT ON COLUMN "channel_test_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel_test_log"."channel_id" IS '渠道ID';
COMMENT ON COLUMN "channel_test_log"."test_result" IS '结果码：1-通过、2-失败';
COMMENT ON COLUMN "channel_test_log"."latency_ms" IS '延迟（毫秒）';
COMMENT ON COLUMN "channel_test_log"."summary" IS '检查摘要';
COMMENT ON COLUMN "channel_test_log"."test_time" IS '测试时间';
CREATE INDEX "idx_channel_test_log_tenant_channel_time" ON "channel_test_log" ("tenant_code", "channel_id", "test_time");

CREATE TABLE "session" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "skill_group_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "intent" VARCHAR(64) DEFAULT NULL,
  "emotion" VARCHAR(20) DEFAULT NULL,
  "bot_transfer_reason" VARCHAR(255) DEFAULT NULL,
  "source" VARCHAR(64) DEFAULT NULL,
  "start_time" TIMESTAMP NOT NULL,
  "end_time" TIMESTAMP DEFAULT NULL,
  "csat_score" SMALLINT DEFAULT NULL,
  "last_msg_seq" BIGINT NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_session_no" UNIQUE ("tenant_code", "session_no")
);
COMMENT ON TABLE "session" IS '会话表';
COMMENT ON COLUMN "session"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session"."session_no" IS '会话编号';
COMMENT ON COLUMN "session"."channel_id" IS '来源渠道ID';
COMMENT ON COLUMN "session"."last_msg_seq" IS '会话已分配的最大消息序号';
COMMENT ON COLUMN "session"."customer_id" IS '客户ID';
COMMENT ON COLUMN "session"."status" IS '状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束';
COMMENT ON COLUMN "session"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "session"."agent_id" IS '当前坐席用户ID';
COMMENT ON COLUMN "session"."intent" IS '智能客服识别出的意图（由 AI 客服大脑写入）';
COMMENT ON COLUMN "session"."emotion" IS '智能客服识别出的客户情绪：中性/焦虑/不满/愤怒';
COMMENT ON COLUMN "session"."bot_transfer_reason" IS '机器人转人工的原因（客户情绪激动 / 答不上来 / 命中转人工意图等）';
COMMENT ON COLUMN "session"."source" IS '会话来源（建档时写入渠道名快照，最长 64 字符）';
COMMENT ON COLUMN "session"."start_time" IS '开始时间';
COMMENT ON COLUMN "session"."end_time" IS '结束时间';
COMMENT ON COLUMN "session"."csat_score" IS '满意度评分1-5';
CREATE INDEX "idx_session_tenant_status_time" ON "session" ("tenant_code", "status", "create_time");
-- 质检补扫用：按"已结束"找最近结束的会话
CREATE INDEX "idx_session_tenant_end_time"
    ON "session" ("tenant_code", "end_time" DESC)
    WHERE "status" = 4 AND "is_deleted" = FALSE;
CREATE INDEX "idx_session_tenant_customer" ON "session" ("tenant_code", "customer_id");
-- 排队队列用：按"没人负责且未结束"找待接待会话
CREATE INDEX "idx_session_queue"
    ON "session" ("tenant_code", "start_time")
    WHERE "agent_id" IS NULL AND "is_deleted" = FALSE;

CREATE TABLE "session_message" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "msg_no" VARCHAR(40) NOT NULL,
  "client_msg_no" VARCHAR(255) DEFAULT NULL,
  "seq" BIGINT NOT NULL DEFAULT 0,
  "msg_type" SMALLINT NOT NULL,
  "sender_type" SMALLINT NOT NULL,
  "sender_id" BIGINT DEFAULT NULL,
  "content" TEXT,
  "ref_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "visible_to" SMALLINT NOT NULL DEFAULT 1,
  "send_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_message_msg_type CHECK ("msg_type" IN (1,2,3,4,5)),
  CONSTRAINT ck_session_message_sender_type CHECK ("sender_type" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_visible_to CHECK ("visible_to" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_msg_no" UNIQUE ("tenant_code", "msg_no")
);

-- 幂等：同一个会话里同一个 client_msg_no 只允许一条；增量补拉按 (会话, seq) 走索引
CREATE UNIQUE INDEX "uk_session_client_msg"
    ON "session_message" ("tenant_code", "session_id", "client_msg_no")
    WHERE "client_msg_no" IS NOT NULL;

CREATE INDEX "idx_session_message_seq"
    ON "session_message" ("tenant_code", "session_id", "seq");
COMMENT ON TABLE "session_message" IS '会话消息表';
COMMENT ON COLUMN "session_message"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_message"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_message"."msg_no" IS '消息编号（客户端幂等键）';
COMMENT ON COLUMN "session_message"."client_msg_no" IS '客户端消息号（幂等键，同一条消息重发只落一条）';
COMMENT ON COLUMN "session_message"."seq" IS '会话内序号（从 1 开始，双方按它排序）';
COMMENT ON COLUMN "session_message"."msg_type" IS '类型码：1-文本、2-图片、3-卡片、4-事件、5-系统';
COMMENT ON COLUMN "session_message"."sender_type" IS '发送方码：1-客户、2-坐席、3-机器人、4-系统';
COMMENT ON COLUMN "session_message"."sender_id" IS '发送人ID';
COMMENT ON COLUMN "session_message"."content" IS '消息内容（JSON，含图片/卡片结构）';
COMMENT ON COLUMN "session_message"."ref_id" IS '引用消息ID';
COMMENT ON COLUMN "session_message"."status" IS '状态码：1-已发送、2-已送达、3-已读、4-失败';
COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';
COMMENT ON COLUMN "session_message"."send_time" IS '发送时间';
CREATE INDEX "idx_session_message_tenant_session_time" ON "session_message" ("tenant_code", "session_id", "send_time");

CREATE TABLE "session_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "from_value" VARCHAR(64) DEFAULT NULL,
  "to_value" VARCHAR(64) DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_session_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "session_event" IS '会话事件表';
COMMENT ON COLUMN "session_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_event"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_event"."event_type" IS '事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时、6-机器人转人工';
COMMENT ON COLUMN "session_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "session_event"."from_value" IS '来源值（原坐席/原技能组）';
COMMENT ON COLUMN "session_event"."to_value" IS '目标值';
COMMENT ON COLUMN "session_event"."remark" IS '备注';
COMMENT ON COLUMN "session_event"."event_time" IS '事件时间';
CREATE INDEX "idx_session_event_tenant_session" ON "session_event" ("tenant_code", "session_id", "event_time");

CREATE TABLE "customer" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_no" VARCHAR(32) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "phone" VARCHAR(20) DEFAULT NULL,
  "level" SMALLINT NOT NULL DEFAULT 1,
  "channel" VARCHAR(64) DEFAULT NULL,
  "orders_count" INT NOT NULL DEFAULT 0,
  "total_value" NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  "points" INT NOT NULL DEFAULT 0,
  "csat" NUMERIC(5,2) DEFAULT NULL,
  "sentiment" SMALLINT DEFAULT NULL,
  "last_active" TIMESTAMP DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "ticket_count" INT NOT NULL DEFAULT 0,
  "last_session_at" TIMESTAMP DEFAULT NULL,
  "risk_level" SMALLINT NOT NULL DEFAULT 1,
  "merged_into" VARCHAR(32) DEFAULT NULL,
  "anonymized_at" TIMESTAMP DEFAULT NULL,
  "customer_type" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_level CHECK ("level" IN (1,2,3,4,5)),
  CONSTRAINT ck_customer_sentiment CHECK ("sentiment" IN (1,2,3)),
  CONSTRAINT ck_customer_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_customer_customer_type CHECK ("customer_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_no" UNIQUE ("tenant_code", "customer_no")
);
COMMENT ON TABLE "customer" IS '客户表';
COMMENT ON COLUMN "customer"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "customer"."customer_no" IS '客户编号（匿名访客为 128 位随机串，不可枚举）';
COMMENT ON COLUMN "customer"."name" IS '客户姓名/昵称';
COMMENT ON COLUMN "customer"."phone" IS '手机号（脱敏）';
COMMENT ON COLUMN "customer"."level" IS '会员等级码（价值分层）：1-普通、2-银卡、3-金卡、4-铂金、5-钻石';
COMMENT ON COLUMN "customer"."channel" IS '来源渠道（建档时写入渠道名快照，最长 64 字符）';
COMMENT ON COLUMN "customer"."orders_count" IS '订单数';
COMMENT ON COLUMN "customer"."total_value" IS '累计消费';
COMMENT ON COLUMN "customer"."points" IS '会员积分';
COMMENT ON COLUMN "customer"."csat" IS '满意度均值';
COMMENT ON COLUMN "customer"."sentiment" IS '情绪标签码：1-正面、2-负面、3-中性';
COMMENT ON COLUMN "customer"."last_active" IS '最近活跃时间';
COMMENT ON COLUMN "customer"."remark" IS '客户备注（坐席可见的内部说明，不展示给客户）';
COMMENT ON COLUMN "customer"."session_count" IS '累计会话数（建档后每次新会话 +1，冗余字段，列表排序用）';
COMMENT ON COLUMN "customer"."ticket_count" IS '累计工单数（会话转单 / 坐席建单时 +1）';
COMMENT ON COLUMN "customer"."last_session_at" IS '最近一次会话时间（没有会话时为空）';
COMMENT ON COLUMN "customer"."risk_level" IS '风险等级码：1-正常、2-关注、3-风险';
COMMENT ON COLUMN "customer"."merged_into" IS '重复客户被合并到哪个客户编号（自身软删，便于回溯）';
COMMENT ON COLUMN "customer"."anonymized_at" IS '匿名化时间（姓名 / 手机号 / 备注已抹除，会话与工单统计保留）';
COMMENT ON COLUMN "customer"."customer_type" IS '客户类型码：1-个人客户、2-企业客户（B 端采购，与会员等级无关）';
CREATE INDEX "idx_customer_tenant_level" ON "customer" ("tenant_code", "level");
CREATE INDEX "idx_customer_tenant_last_active" ON "customer" ("tenant_code", "last_active" DESC);

CREATE TABLE "customer_tag" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "tag_id" BIGINT DEFAULT NULL,
  "tag_group" VARCHAR(32) DEFAULT NULL,
  "source" SMALLINT NOT NULL DEFAULT 1,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_tag_source CHECK ("source" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_tag" UNIQUE ("tenant_code", "customer_id", "tag_name")
);
COMMENT ON TABLE "customer_tag" IS '客户标签表';
COMMENT ON COLUMN "customer_tag"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "customer_tag"."customer_id" IS '客户ID';
COMMENT ON COLUMN "customer_tag"."tag_name" IS '标签名';
COMMENT ON COLUMN "customer_tag"."tag_id" IS '标签定义ID（customer_tag_def.id；为空表示历史数据，只有标签名）';
COMMENT ON COLUMN "customer_tag"."tag_group" IS '标签分组（冗余，列表直接读，不用再 join）';
COMMENT ON COLUMN "customer_tag"."source" IS '来源码：1-手工打标、2-规则自动、3-批量导入';
COMMENT ON COLUMN "customer_tag"."operator_id" IS '打标人ID';
COMMENT ON COLUMN "customer_tag"."operator_name" IS '打标人姓名';
CREATE UNIQUE INDEX "uk_tenant_customer_tag_id"
    ON "customer_tag" ("tenant_code", "customer_id", "tag_id")
    WHERE "tag_id" IS NOT NULL AND "is_deleted" = FALSE;
CREATE INDEX "idx_tenant_customer_tag_customer"
    ON "customer_tag" ("tenant_code", "customer_id")
    WHERE "is_deleted" = FALSE;

CREATE TABLE "customer_tag_def" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "tag_code" VARCHAR(32) NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "tag_group" VARCHAR(32) NOT NULL,
  "color" VARCHAR(16) NOT NULL DEFAULT 'blue',
  "tag_type" SMALLINT NOT NULL DEFAULT 1,
  "rule_hint" VARCHAR(255) DEFAULT NULL,
  "rule_metric" VARCHAR(32) DEFAULT NULL,
  "rule_op" VARCHAR(4) DEFAULT NULL,
  "rule_value" NUMERIC(12,2) DEFAULT NULL,
  "rule_window_days" INT NOT NULL DEFAULT 0,
  "description" VARCHAR(255) DEFAULT NULL,
  "sort_no" INT NOT NULL DEFAULT 100,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_tag_def_type CHECK ("tag_type" IN (1,2)),
  CONSTRAINT ck_customer_tag_def_op CHECK ("rule_op" IS NULL OR "rule_op" IN ('GT','GTE','LT','LTE','EQ')),
  CONSTRAINT ck_customer_tag_def_metric CHECK ("rule_metric" IS NULL OR "rule_metric" IN
        ('CSAT','LEVEL','RISK_LEVEL','SESSIONS','TICKETS',
         'REFUND_SESSIONS','COMPLAINT_SESSIONS','NIGHT_SESSIONS','ACTIVE_DAYS')),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_tag_def_code" UNIQUE ("tenant_code", "tag_code"),
  CONSTRAINT "uk_tenant_tag_def_name" UNIQUE ("tenant_code", "tag_name")
);
COMMENT ON TABLE "customer_tag_def" IS '客户标签定义表（标签体系：分组 + 颜色 + 手工/规则 + 排序）';
COMMENT ON COLUMN "customer_tag_def"."tag_code" IS '标签编码（租户内唯一，导入/接口用）';
COMMENT ON COLUMN "customer_tag_def"."tag_group" IS '分组：价值 / 服务 / 风险 / 偏好 / 来源';
COMMENT ON COLUMN "customer_tag_def"."color" IS '展示颜色：blue / green / orange / red / purple / gray';
COMMENT ON COLUMN "customer_tag_def"."tag_type" IS '类型码：1-手工打标、2-规则自动';
COMMENT ON COLUMN "customer_tag_def"."rule_hint" IS '自动标签的命中口径说明（写给人看；rule_metric 那一组是给引擎跑的）';
COMMENT ON COLUMN "customer_tag_def"."rule_metric" IS '规则指标（只允许客服系统自己产生的数据）：CSAT 满意度 / LEVEL 会员等级 / RISK_LEVEL 风险等级 / SESSIONS 会话数 / TICKETS 工单数 / REFUND_SESSIONS 退款类会话 / COMPLAINT_SESSIONS 投诉类会话 / NIGHT_SESSIONS 夜间咨询 / ACTIVE_DAYS 距最近活跃天数';
COMMENT ON COLUMN "customer_tag_def"."rule_op" IS '比较符：GT / GTE / LT / LTE / EQ';
COMMENT ON COLUMN "customer_tag_def"."rule_value" IS '阈值';
COMMENT ON COLUMN "customer_tag_def"."rule_window_days" IS '统计窗口（天）：0 表示全周期；只对会话类指标有意义';
COMMENT ON COLUMN "customer_tag_def"."sort_no" IS '组内排序号（越小越前）';
COMMENT ON COLUMN "customer_tag_def"."is_enabled" IS '是否启用：停用后不能再打标，但已经打上的标签仍然展示';
CREATE INDEX "idx_tenant_tag_def_group" ON "customer_tag_def" ("tenant_code", "tag_group", "sort_no");
CREATE INDEX "idx_tenant_tag_def_rule"
    ON "customer_tag_def" ("tenant_code", "tag_type")
    WHERE "is_deleted" = FALSE AND "is_enabled" = TRUE;

CREATE TABLE "customer_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "event_title" VARCHAR(64) NOT NULL,
  "event_content" VARCHAR(512) DEFAULT NULL,
  "ref_type" SMALLINT DEFAULT NULL,
  "ref_no" VARCHAR(64) DEFAULT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_customer_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "customer_event" IS '客户动态表（建档 / 打标 / 去标 / 等级调整 / 备注 / 风险标记）';
COMMENT ON COLUMN "customer_event"."event_type" IS '事件类型码：1-建档、2-打标、3-去标、4-等级调整、5-备注更新、6-风险标记、7-发起会话、8-会话结束、9-转人工、10-转工单、11-满意度评价、12-敏感信息查阅、13-订单变更、14-客户合并、15-客户匿名化、16-客户删除';
COMMENT ON COLUMN "customer_event"."event_title" IS '动态标题（列表直接展示）';
COMMENT ON COLUMN "customer_event"."event_content" IS '动态详情（例如"银卡 → 金卡"）';
COMMENT ON COLUMN "customer_event"."ref_type" IS '关联对象类型码：1-会话、2-工单、3-订单、4-标签';
COMMENT ON COLUMN "customer_event"."ref_no" IS '关联对象编号（会话号 / 工单号 / 订单号 / 标签编码）';
COMMENT ON COLUMN "customer_event"."operator_id" IS '操作人ID（空表示系统自动）';
COMMENT ON COLUMN "customer_event"."operator_name" IS '操作人姓名';
CREATE INDEX "idx_customer_event_tenant_customer"
    ON "customer_event" ("tenant_code", "customer_id", "event_time" DESC);
CREATE INDEX "idx_customer_event_tenant_type"
    ON "customer_event" ("tenant_code", "event_type", "event_time" DESC);

CREATE TABLE "agent_daily_metric" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "agent_name" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "human_session_count" INT NOT NULL DEFAULT 0,
  "message_count" INT NOT NULL DEFAULT 0,
  "customer_message_count" INT NOT NULL DEFAULT 0,
  "first_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_session_seconds" INT NOT NULL DEFAULT 0,
  "transfer_count" INT NOT NULL DEFAULT 0,
  "csat_count" INT NOT NULL DEFAULT 0,
  "csat_score" NUMERIC(5,2) DEFAULT NULL,
  "good_csat_count" INT NOT NULL DEFAULT 0,
  "close_count" INT NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_date" UNIQUE ("tenant_code", "agent_id", "stat_date")
);
COMMENT ON TABLE "agent_daily_metric" IS '坐席绩效日聚合表（按 坐席 × 天 预聚合，报表与大屏都读它）';
COMMENT ON COLUMN "agent_daily_metric"."agent_id" IS '坐席用户ID（0 表示"未分配/机器人接待"这一行）';
COMMENT ON COLUMN "agent_daily_metric"."stat_date" IS '统计日期（按自然日）';
COMMENT ON COLUMN "agent_daily_metric"."session_count" IS '接待会话数';
COMMENT ON COLUMN "agent_daily_metric"."human_session_count" IS '人工接待过的会话数';
COMMENT ON COLUMN "agent_daily_metric"."message_count" IS '坐席发出的消息数';
COMMENT ON COLUMN "agent_daily_metric"."customer_message_count" IS '客户发出的消息数';
COMMENT ON COLUMN "agent_daily_metric"."first_response_seconds" IS '首次响应时长均值（秒）';
COMMENT ON COLUMN "agent_daily_metric"."avg_response_seconds" IS '平均响应时长（秒）';
COMMENT ON COLUMN "agent_daily_metric"."avg_session_seconds" IS '平均会话时长（秒）';
COMMENT ON COLUMN "agent_daily_metric"."transfer_count" IS '转接次数';
COMMENT ON COLUMN "agent_daily_metric"."csat_count" IS '收到的评价条数';
COMMENT ON COLUMN "agent_daily_metric"."csat_score" IS '满意度均值（1~5）';
COMMENT ON COLUMN "agent_daily_metric"."good_csat_count" IS '好评数（4~5 分）';
COMMENT ON COLUMN "agent_daily_metric"."close_count" IS '结束的会话数';
CREATE INDEX "idx_agent_metric_tenant_date"
    ON "agent_daily_metric" ("tenant_code", "stat_date" DESC);

CREATE TABLE "ticket" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_no" VARCHAR(40) NOT NULL,
  "ticket_type" SMALLINT NOT NULL DEFAULT 1,
  "category" SMALLINT NOT NULL DEFAULT 5,
  "title" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "customer_name" VARCHAR(64) DEFAULT NULL,
  "desc" TEXT,
  "priority" SMALLINT NOT NULL DEFAULT 2,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "source" SMALLINT NOT NULL DEFAULT 3,
  "session_no" VARCHAR(40) DEFAULT NULL,
  "source_session_id" BIGINT DEFAULT NULL,
  "assignee_id" BIGINT DEFAULT NULL,
  "assignee_name" VARCHAR(64) DEFAULT NULL,
  "group_id" BIGINT DEFAULT NULL,
  "sla_first_minutes" INT NOT NULL DEFAULT 0,
  "sla_resolve_minutes" INT NOT NULL DEFAULT 0,
  "first_response_due" TIMESTAMP DEFAULT NULL,
  "first_response_at" TIMESTAMP DEFAULT NULL,
  "resolve_due" TIMESTAMP DEFAULT NULL,
  "resolved_at" TIMESTAMP DEFAULT NULL,
  "sla_deadline" TIMESTAMP DEFAULT NULL,
  "sla_state" SMALLINT DEFAULT 1,
  "sla_alerted" BOOLEAN NOT NULL DEFAULT FALSE,
  "close_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "creator_name" VARCHAR(64) DEFAULT NULL,
  "source_channel" SMALLINT DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_ticket_type CHECK ("ticket_type" IN (1,2)),
  CONSTRAINT ck_ticket_category CHECK ("category" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_status CHECK ("status" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_source CHECK ("source" IN (1,2,3)),
  CONSTRAINT ck_ticket_source_channel CHECK ("source_channel" IS NULL OR "source_channel" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_sla_state CHECK ("sla_state" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_ticket_no" UNIQUE ("tenant_code", "ticket_no")
);
COMMENT ON TABLE "ticket" IS '工单表';
COMMENT ON COLUMN "ticket"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket"."ticket_no" IS '工单号';
COMMENT ON COLUMN "ticket"."ticket_type" IS '类型码：1-企业内部工单、2-平台支持工单';
COMMENT ON COLUMN "ticket"."category" IS '业务分类码：1-订单、2-退款售后、3-物流、4-商品、5-其他';
COMMENT ON COLUMN "ticket"."customer_id" IS '客户ID（快照，分库冗余，便于按客户查工单）';
COMMENT ON COLUMN "ticket"."customer_name" IS '客户姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."title" IS '工单主题';
COMMENT ON COLUMN "ticket"."desc" IS '问题描述（会话转单时带最近聊天记录摘要）';
COMMENT ON COLUMN "ticket"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket"."status" IS '状态码：1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭';
COMMENT ON COLUMN "ticket"."source" IS '来源码：1-会话转单、2-客户自助、3-坐席新建';
COMMENT ON COLUMN "ticket"."session_no" IS '来源会话号（坐席可一键跳回会话看上下文）';
COMMENT ON COLUMN "ticket"."source_session_id" IS '来源会话ID';
COMMENT ON COLUMN "ticket"."assignee_id" IS '处理人ID';
COMMENT ON COLUMN "ticket"."assignee_name" IS '处理人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."group_id" IS '处理技能组ID';
COMMENT ON COLUMN "ticket"."sla_first_minutes" IS '建单时快照的首次响应 SLA（分钟）';
COMMENT ON COLUMN "ticket"."sla_resolve_minutes" IS '建单时快照的解决 SLA（分钟）';
COMMENT ON COLUMN "ticket"."first_response_due" IS '首次响应截止时间';
COMMENT ON COLUMN "ticket"."first_response_at" IS '首次响应时间（第一条对客户可见的回复）';
COMMENT ON COLUMN "ticket"."resolve_due" IS '解决截止时间';
COMMENT ON COLUMN "ticket"."resolved_at" IS '解决时间';
COMMENT ON COLUMN "ticket"."sla_deadline" IS 'SLA 截止时间（旧的单段口径，等于 resolve_due，保留兼容）';
COMMENT ON COLUMN "ticket"."sla_state" IS 'SLA 状态码：1-正常、2-即将超时、3-已超时';
COMMENT ON COLUMN "ticket"."sla_alerted" IS '这一轮预警是否已经推送过（避免重复轰炸）';
COMMENT ON COLUMN "ticket"."close_time" IS '关闭时间';
COMMENT ON COLUMN "ticket"."creator_name" IS '创建人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."source_channel" IS '来源渠道码：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它';
CREATE INDEX "idx_ticket_tenant_status_time" ON "ticket" ("tenant_code", "status", "create_time");
CREATE INDEX "idx_ticket_tenant_assignee" ON "ticket" ("tenant_code", "assignee_id");
CREATE INDEX "idx_ticket_tenant_sla" ON "ticket" ("tenant_code", "sla_state", "resolve_due");
CREATE INDEX "idx_ticket_tenant_session" ON "ticket" ("tenant_code", "session_no");

CREATE TABLE "ticket_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "from_status" SMALLINT DEFAULT NULL,
  "to_status" SMALLINT DEFAULT NULL,
  "visible_to_customer" BOOLEAN NOT NULL DEFAULT FALSE,
  "content" VARCHAR(512) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ticket_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ticket_event" IS '工单流转记录表';
COMMENT ON COLUMN "ticket_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket_event"."ticket_id" IS '工单ID';
COMMENT ON COLUMN "ticket_event"."event_type" IS '事件码：1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开、7-状态变更、8-SLA预警';
COMMENT ON COLUMN "ticket_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "ticket_event"."operator_name" IS '操作人姓名（冗余，时间线直接显示）';
COMMENT ON COLUMN "ticket_event"."from_status" IS '变更前状态（状态流转类事件用）';
COMMENT ON COLUMN "ticket_event"."to_status" IS '变更后状态';
COMMENT ON COLUMN "ticket_event"."visible_to_customer" IS '这条回复是否对客户可见（false=内部备注）';
COMMENT ON COLUMN "ticket_event"."content" IS '事件内容 / 回复正文';
CREATE INDEX "idx_ticket_event_tenant_ticket" ON "ticket_event" ("tenant_code", "ticket_id", "event_time");

CREATE TABLE "ticket_sla_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "priority" SMALLINT NOT NULL,
  "first_response_minutes" INT NOT NULL,
  "resolve_minutes" INT NOT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_sla_rule_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_sla_rule_minutes CHECK ("first_response_minutes" > 0 AND "resolve_minutes" > 0),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ticket_sla_rule" IS '工单 SLA 规则（按优先级配置首次响应 / 解决时长）';
COMMENT ON COLUMN "ticket_sla_rule"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket_sla_rule"."first_response_minutes" IS '首次响应时限（分钟）';
COMMENT ON COLUMN "ticket_sla_rule"."resolve_minutes" IS '解决时限（分钟）';
CREATE UNIQUE INDEX "uk_ticket_sla_rule_tenant_priority"
    ON "ticket_sla_rule" ("tenant_code", "priority")
    WHERE "is_deleted" = FALSE;

CREATE TABLE "notification" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "notify_type" SMALLINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "content" TEXT,
  "link_view" VARCHAR(64) DEFAULT NULL,
  "target_type" SMALLINT DEFAULT 1,
  "target_id" BIGINT DEFAULT NULL,
  "publish_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_notification_notify_type CHECK ("notify_type" IN (1,2,3,4,5,6)),
  CONSTRAINT ck_notification_target_type CHECK ("target_type" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "notification" IS '消息表';
COMMENT ON COLUMN "notification"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "notification"."notify_type" IS '类型码：1-系统、2-工单、3-审核、4-质检、5-公告、6-账单';
COMMENT ON COLUMN "notification"."title" IS '标题';
COMMENT ON COLUMN "notification"."content" IS '内容';
COMMENT ON COLUMN "notification"."link_view" IS '跳转页面标识';
COMMENT ON COLUMN "notification"."target_type" IS '目标码：1-用户、2-企业';
COMMENT ON COLUMN "notification"."target_id" IS '目标ID';
COMMENT ON COLUMN "notification"."publish_time" IS '发布时间';
CREATE INDEX "idx_notification_tenant_type_time" ON "notification" ("tenant_code", "notify_type", "publish_time");

CREATE TABLE "notification_read" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "notify_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "read_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_notify_user" UNIQUE ("tenant_code", "notify_id", "user_id")
);
COMMENT ON TABLE "notification_read" IS '消息已读表';
COMMENT ON COLUMN "notification_read"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "notification_read"."notify_id" IS '消息ID';
COMMENT ON COLUMN "notification_read"."user_id" IS '用户ID';
COMMENT ON COLUMN "notification_read"."read_time" IS '已读时间';

CREATE TABLE "kb_category" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "parent_id" BIGINT NOT NULL DEFAULT 0,
  "name" VARCHAR(64) NOT NULL,
  "sort_no" INT NOT NULL DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_category" IS '知识分类表';
COMMENT ON COLUMN "kb_category"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_category"."parent_id" IS '父分类ID';
COMMENT ON COLUMN "kb_category"."name" IS '分类名称';
COMMENT ON COLUMN "kb_category"."sort_no" IS '排序';
CREATE INDEX "idx_kb_category_tenant_parent" ON "kb_category" ("tenant_code", "parent_id");

CREATE TABLE "kb_document" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_no" VARCHAR(40) NOT NULL,
  "category_id" BIGINT DEFAULT NULL,
  "title" VARCHAR(255) NOT NULL,
  "content" TEXT,
  "summary" VARCHAR(512) DEFAULT NULL,
  "source_type" SMALLINT DEFAULT 1,
  "file_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "hit_count" INT NOT NULL DEFAULT 0,
  "useful_rate" NUMERIC(5,2) DEFAULT NULL,
  "author" VARCHAR(64) DEFAULT NULL,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_kb_document_source_type CHECK ("source_type" IN (1,2,3)),
  CONSTRAINT ck_kb_document_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_doc_no" UNIQUE ("tenant_code", "doc_no")
);
COMMENT ON TABLE "kb_document" IS '知识文档表';
COMMENT ON COLUMN "kb_document"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_document"."doc_no" IS '知识编号';
COMMENT ON COLUMN "kb_document"."category_id" IS '分类ID';
COMMENT ON COLUMN "kb_document"."title" IS '标题';
COMMENT ON COLUMN "kb_document"."content" IS '内容';
COMMENT ON COLUMN "kb_document"."summary" IS '摘要';
COMMENT ON COLUMN "kb_document"."source_type" IS '来源码：1-手工、2-导入、3-AI生成';
COMMENT ON COLUMN "kb_document"."file_id" IS '原文件ID';
COMMENT ON COLUMN "kb_document"."status" IS '状态码：1-草稿、2-审核中、3-已发布、4-已下线';
COMMENT ON COLUMN "kb_document"."hit_count" IS '命中次数';
COMMENT ON COLUMN "kb_document"."useful_rate" IS '采纳率';
COMMENT ON COLUMN "kb_document"."author" IS '维护人';
COMMENT ON COLUMN "kb_document"."publish_time" IS '发布时间';
CREATE INDEX "idx_kb_document_tenant_status" ON "kb_document" ("tenant_code", "status");
CREATE INDEX "idx_kb_document_tenant_category" ON "kb_document" ("tenant_code", "category_id");

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_chunk" IS '知识文档切片表（检索的最小单位）';
CREATE INDEX "idx_kb_chunk_tenant_doc" ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");
CREATE INDEX "idx_kb_chunk_embedding" ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

CREATE TABLE "kb_version" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "version_no" INT NOT NULL,
  "content_snapshot" TEXT,
  "change_log" VARCHAR(255) DEFAULT NULL,
  "publisher" VARCHAR(64) DEFAULT NULL,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_version" IS '知识版本表';
COMMENT ON COLUMN "kb_version"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_version"."doc_id" IS '文档ID';
COMMENT ON COLUMN "kb_version"."version_no" IS '版本号';
COMMENT ON COLUMN "kb_version"."content_snapshot" IS '内容快照';
COMMENT ON COLUMN "kb_version"."change_log" IS '变更说明';
COMMENT ON COLUMN "kb_version"."publisher" IS '发布人';
COMMENT ON COLUMN "kb_version"."publish_time" IS '发布时间';
CREATE INDEX "idx_kb_version_tenant_doc" ON "kb_version" ("tenant_code", "doc_id", "version_no");

CREATE TABLE "kb_audit" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "version_no" INT DEFAULT NULL,
  "auditor_id" BIGINT DEFAULT NULL,
  "result" SMALLINT DEFAULT NULL,
  "reason" VARCHAR(255) DEFAULT NULL,
  "submit_time" TIMESTAMP NOT NULL,
  "audit_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_kb_audit_result CHECK ("result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_audit" IS '知识审核表';
COMMENT ON COLUMN "kb_audit"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_audit"."doc_id" IS '文档ID';
COMMENT ON COLUMN "kb_audit"."version_no" IS '申请版本';
COMMENT ON COLUMN "kb_audit"."auditor_id" IS '审核人ID';
COMMENT ON COLUMN "kb_audit"."result" IS '结果码：1-通过、2-驳回';
COMMENT ON COLUMN "kb_audit"."reason" IS '原因';
COMMENT ON COLUMN "kb_audit"."submit_time" IS '提交时间';
COMMENT ON COLUMN "kb_audit"."audit_time" IS '审核时间';
CREATE INDEX "idx_kb_audit_tenant_doc" ON "kb_audit" ("tenant_code", "doc_id", "submit_time");

CREATE TABLE "qa_task" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_no" VARCHAR(40) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "ai_score" NUMERIC(5,2) DEFAULT NULL,
  "ai_result" VARCHAR(512) DEFAULT NULL,
  "risk_level" SMALLINT DEFAULT 1,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reviewer_id" BIGINT DEFAULT NULL,
  "review_score" NUMERIC(5,2) DEFAULT NULL,
  "review_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_task_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_qa_task_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_task_no" UNIQUE ("tenant_code", "task_no")
);
COMMENT ON TABLE "qa_task" IS '质检任务表';
COMMENT ON COLUMN "qa_task"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_task"."task_no" IS '任务编号';
COMMENT ON COLUMN "qa_task"."session_id" IS '关联会话ID';
COMMENT ON COLUMN "qa_task"."agent_id" IS '被检坐席ID';
COMMENT ON COLUMN "qa_task"."ai_score" IS 'AI 初检评分';
COMMENT ON COLUMN "qa_task"."ai_result" IS 'AI 命中说明（JSON）';
COMMENT ON COLUMN "qa_task"."risk_level" IS '风险码：1-低、2-中、3-高';
COMMENT ON COLUMN "qa_task"."status" IS '状态码：1-待复核、2-已通过、3-已驳回';
COMMENT ON COLUMN "qa_task"."reviewer_id" IS '复核人ID';
COMMENT ON COLUMN "qa_task"."review_score" IS '复核评分';
COMMENT ON COLUMN "qa_task"."review_time" IS '复核时间';
CREATE INDEX "idx_qa_task_tenant_status" ON "qa_task" ("tenant_code", "status");
CREATE INDEX "idx_qa_task_tenant_session" ON "qa_task" ("tenant_code", "session_id");
CREATE UNIQUE INDEX "uk_qa_task_tenant_session"
    ON "qa_task" ("tenant_code", "session_id")
    WHERE "is_deleted" = FALSE AND "session_id" IS NOT NULL;

CREATE TABLE "qa_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "rule_content" TEXT,
  "weight" INT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "is_realtime" BOOLEAN NOT NULL DEFAULT TRUE,
  "hit_keywords" VARCHAR(512) DEFAULT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "timeout_seconds" INTEGER NOT NULL DEFAULT 60,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_rule_rule_type CHECK ("rule_type" IN (1,2,3,4,5)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_rule" IS '质检规则表';
COMMENT ON COLUMN "qa_rule"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_rule"."rule_name" IS '规则名称';
COMMENT ON COLUMN "qa_rule"."rule_type" IS '类型码：1-敏感词、2-承诺规范、3-必答项、4-情绪识别';
COMMENT ON COLUMN "qa_rule"."rule_content" IS '规则内容（关键词/正则/描述）';
COMMENT ON COLUMN "qa_rule"."weight" IS '权重';
COMMENT ON COLUMN "qa_rule"."is_realtime" IS '是否参与实时质检：关掉就只在会话结束后批量质检';
COMMENT ON COLUMN "qa_rule"."hit_keywords" IS '命中词表（逗号分隔）：敏感词类规则命中任意一个就告警';
COMMENT ON COLUMN "qa_rule"."severity" IS '告警级别：1-提示、2-警告、3-严重';
COMMENT ON COLUMN "qa_rule"."timeout_seconds" IS '响应超时秒数（仅"5-响应超时"类规则使用）';
CREATE INDEX "idx_qa_rule_tenant_type" ON "qa_rule" ("tenant_code", "rule_type");

CREATE TABLE "qa_alert" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "message_id" BIGINT DEFAULT NULL,
  "message_seq" BIGINT DEFAULT NULL,
  "rule_id" BIGINT DEFAULT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "hit_keyword" VARCHAR(128) DEFAULT NULL,
  "snippet" VARCHAR(512) DEFAULT NULL,
  "advice" VARCHAR(512) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "handler_id" BIGINT DEFAULT NULL,
  "handle_remark" VARCHAR(512) DEFAULT NULL,
  "handle_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_alert_severity CHECK ("severity" IN (1,2,3)),
  CONSTRAINT ck_qa_alert_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_alert" IS '会话实时质检告警';
COMMENT ON COLUMN "qa_alert"."session_no" IS '会话号（坐席点告警直接跳到这条会话）';
COMMENT ON COLUMN "qa_alert"."agent_id" IS '告警发生时该会话的负责坐席';
COMMENT ON COLUMN "qa_alert"."message_id" IS '触发告警的消息 ID';
COMMENT ON COLUMN "qa_alert"."rule_name" IS '命中的规则名';
COMMENT ON COLUMN "qa_alert"."hit_keyword" IS '命中的词（敏感词类规则才有）';
COMMENT ON COLUMN "qa_alert"."snippet" IS '命中片段（截取消息上下文，便于坐席定位）';
COMMENT ON COLUMN "qa_alert"."advice" IS '处置建议';
COMMENT ON COLUMN "qa_alert"."status" IS '状态码：1-待处理、2-已处理';
CREATE INDEX "idx_qa_alert_tenant_session" ON "qa_alert" ("tenant_code", "session_no", "create_time");
CREATE INDEX "idx_qa_alert_tenant_status" ON "qa_alert" ("tenant_code", "status", "create_time");
CREATE UNIQUE INDEX "uk_qa_alert_message_rule"
    ON "qa_alert" ("tenant_code", "message_id", "rule_id")
    WHERE "is_deleted" = FALSE AND "message_id" IS NOT NULL AND "rule_id" IS NOT NULL;

CREATE TABLE "qa_review" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_id" BIGINT NOT NULL,
  "reviewer_id" BIGINT NOT NULL,
  "action" SMALLINT NOT NULL,
  "comment" VARCHAR(512) DEFAULT NULL,
  "review_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_qa_review_action CHECK ("action" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_review" IS '质检复核记录表';
COMMENT ON COLUMN "qa_review"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_review"."task_id" IS '质检任务ID';
COMMENT ON COLUMN "qa_review"."reviewer_id" IS '复核人ID';
COMMENT ON COLUMN "qa_review"."action" IS '动作码：1-通过、2-驳回、3-重检';
COMMENT ON COLUMN "qa_review"."comment" IS '复核意见';
CREATE INDEX "idx_qa_review_tenant_task" ON "qa_review" ("tenant_code", "task_id", "review_time");

CREATE TABLE "file_meta" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "file_no" VARCHAR(40) NOT NULL,
  "file_name" VARCHAR(255) NOT NULL,
  "object_key" VARCHAR(512) NOT NULL,
  "file_size" BIGINT NOT NULL DEFAULT 0,
  "mime_type" VARCHAR(128) DEFAULT NULL,
  "biz_type" SMALLINT NOT NULL,
  "biz_id" BIGINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_file_meta_biz_type CHECK ("biz_type" IN (1,2,3,4,5)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_file_no" UNIQUE ("tenant_code", "file_no")
);
COMMENT ON TABLE "file_meta" IS '文件元数据表';
COMMENT ON COLUMN "file_meta"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "file_meta"."file_no" IS '文件编号';
COMMENT ON COLUMN "file_meta"."file_name" IS '原始文件名';
COMMENT ON COLUMN "file_meta"."object_key" IS 'OSS 对象键';
COMMENT ON COLUMN "file_meta"."file_size" IS '大小（字节）';
COMMENT ON COLUMN "file_meta"."mime_type" IS 'MIME 类型';
COMMENT ON COLUMN "file_meta"."biz_type" IS '业务类型码：1-头像、2-附件、3-导出、4-发票、5-聊天图片';
COMMENT ON COLUMN "file_meta"."biz_id" IS '业务ID';
CREATE INDEX "idx_file_meta_tenant_biz" ON "file_meta" ("tenant_code", "biz_type", "biz_id");

CREATE TABLE "quick_reply" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "title" VARCHAR(64) NOT NULL,
  "content" VARCHAR(512) NOT NULL,
  "category" VARCHAR(32) DEFAULT NULL,
  "sort_no" INT NOT NULL DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "quick_reply" IS '快捷回复话术表';
COMMENT ON COLUMN "quick_reply"."title" IS '话术标题（如：问候语）';
COMMENT ON COLUMN "quick_reply"."content" IS '话术内容';
COMMENT ON COLUMN "quick_reply"."category" IS '分组（如：售前/售后/致歉）';
CREATE INDEX "idx_quick_reply_tenant_sort" ON "quick_reply" ("tenant_code", "sort_no");

CREATE TABLE "skill_group_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "skill_group_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "is_leader" BOOLEAN NOT NULL DEFAULT FALSE,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_skill_group_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_skill_group_member" UNIQUE ("skill_group_id", "user_id")
);
COMMENT ON TABLE "skill_group_member" IS '技能组坐席绑定表';
COMMENT ON COLUMN "skill_group_member"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "skill_group_member"."user_id" IS '坐席用户ID';
COMMENT ON COLUMN "skill_group_member"."is_leader" IS '是否组长';
COMMENT ON COLUMN "skill_group_member"."status" IS '状态码：1-在组、2-已移出';
CREATE INDEX "idx_skill_group_member_tenant" ON "skill_group_member" ("tenant_code", "skill_group_id");

CREATE TABLE "csat_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "score" SMALLINT NOT NULL,
  "feedback" VARCHAR(512) DEFAULT NULL,
  "evaluate_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_csat_score CHECK ("score" BETWEEN 1 AND 5),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_csat_session" UNIQUE ("tenant_code", "session_id")
);
COMMENT ON TABLE "csat_record" IS '满意度评价明细表';
COMMENT ON COLUMN "csat_record"."session_id" IS '会话ID';
COMMENT ON COLUMN "csat_record"."score" IS '评分 1-5';
COMMENT ON COLUMN "csat_record"."feedback" IS '评价内容';
CREATE INDEX "idx_csat_tenant_time" ON "csat_record" ("tenant_code", "evaluate_time");

CREATE TABLE "customer_order" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "order_no" VARCHAR(40) NOT NULL,
  "product_name" VARCHAR(128) DEFAULT NULL,
  "product_file_id" BIGINT DEFAULT NULL,
  "amount" NUMERIC(12,2) NOT NULL DEFAULT 0,
  "order_status" SMALLINT NOT NULL DEFAULT 1,
  "order_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_order_status CHECK ("order_status" IN (1,2,3,4,5)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_customer_order_no" UNIQUE ("tenant_code", "order_no")
);
COMMENT ON TABLE "customer_order" IS '客户订单表';
COMMENT ON COLUMN "customer_order"."customer_id" IS '客户ID';
COMMENT ON COLUMN "customer_order"."order_no" IS '订单号';
COMMENT ON COLUMN "customer_order"."product_name" IS '商品名称';
COMMENT ON COLUMN "customer_order"."product_file_id" IS '商品图文件ID';
COMMENT ON COLUMN "customer_order"."amount" IS '订单金额';
COMMENT ON COLUMN "customer_order"."order_status" IS '状态码：1-待付款、2-已付款、3-已发货、4-已完成、5-已退款';
COMMENT ON COLUMN "customer_order"."order_time" IS '下单时间';
CREATE INDEX "idx_customer_order_tenant_customer" ON "customer_order" ("tenant_code", "customer_id");

CREATE TABLE "tenant_config" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "config_type" SMALLINT NOT NULL DEFAULT 1,
  "config_json" JSON NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_config_type CHECK ("config_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_config_type" UNIQUE ("tenant_code", "config_type")
);
COMMENT ON TABLE "tenant_config" IS '租户级配置表（系统设置）';
COMMENT ON COLUMN "tenant_config"."config_type" IS '配置类型码：1-通知偏好、2-自动化工作流、3-安全设置';
COMMENT ON COLUMN "tenant_config"."config_json" IS '配置内容（JSON）';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_security.sql
-- ---------------------------------------------------------------------------
-- 访客接入安全加固（可重复执行）：
--   1. 渠道增加访客域名白名单，防止别人拿着公开的 appKey 在自己站上嵌挂件刷会话；
--   2. 匿名客户编号改成不可枚举的随机串（新数据生效，历史数据可自行决定是否重刷）。

ALTER TABLE "channel"
    ADD COLUMN IF NOT EXISTS "allowed_origins" VARCHAR(512) DEFAULT NULL;

COMMENT ON COLUMN "channel"."allowed_origins"
    IS '访客接入域名白名单（逗号分隔，支持 *.example.com；为空表示不限制）';

COMMENT ON COLUMN "customer"."customer_no"
    IS '客户编号（匿名访客为 128 位随机串，不可枚举）';

-- 可选：把历史匿名客户的编号重刷成不可枚举随机串（会让老访客变成新访客，按需执行）
-- UPDATE "customer"
--    SET "customer_no" = 'V' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 26))
--  WHERE "customer_no" ~ '^V[0-9]{17}$';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_collab.sql
-- ---------------------------------------------------------------------------
-- =============================================================================
-- 在线客服工作台（多坐席接待、转接与会话协同）增量脚本
-- 只加一个字段：消息的可见范围。会话事件直接复用已有的 session_event 表。
-- 可重复执行。
-- =============================================================================

ALTER TABLE "session_message"
    ADD COLUMN IF NOT EXISTS "visible_to" SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';

-- 老库补约束：先删后加，保证脚本可重复执行
-- （不用 DO $$ 块：那个写法在按分号切分脚本的执行器里会被切坏）
ALTER TABLE "session_message" DROP CONSTRAINT IF EXISTS "ck_session_message_visible_to";
ALTER TABLE "session_message"
    ADD CONSTRAINT "ck_session_message_visible_to" CHECK ("visible_to" IN (1,2));

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_delivery.sql
-- ---------------------------------------------------------------------------
-- 消息必达（可重复执行）：
--   1. client_msg_no：客户端幂等键，同一条消息重发多少次都只落一条；
--   2. seq：会话内序号，保证双方看到的顺序一致；
--   3. session.last_msg_seq：会话当前最大序号，重连后据此做增量补拉。

ALTER TABLE "session_message"
    ADD COLUMN IF NOT EXISTS "client_msg_no" VARCHAR(64) DEFAULT NULL;

ALTER TABLE "session_message"
    ADD COLUMN IF NOT EXISTS "seq" BIGINT NOT NULL DEFAULT 0;

ALTER TABLE "session"
    ADD COLUMN IF NOT EXISTS "last_msg_seq" BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN "session_message"."client_msg_no" IS '客户端消息号（幂等键，同一条消息重发只落一条）';
COMMENT ON COLUMN "session_message"."seq" IS '会话内序号（从 1 开始，双方按它排序）';
COMMENT ON COLUMN "session"."last_msg_seq" IS '会话已分配的最大消息序号';

-- 幂等：同一个会话里，同一个 client_msg_no 只允许存在一条
CREATE UNIQUE INDEX IF NOT EXISTS "uk_session_client_msg"
    ON "session_message" ("tenant_code", "session_id", "client_msg_no")
    WHERE "client_msg_no" IS NOT NULL;

-- 增量补拉：按 (会话, 序号) 取一页，走索引
CREATE INDEX IF NOT EXISTS "idx_session_message_seq"
    ON "session_message" ("tenant_code", "session_id", "seq");

-- 历史数据回填：按 id 顺序给每个会话重排 seq
WITH ranked AS (
    SELECT "id", row_number() OVER (PARTITION BY "tenant_code", "session_id" ORDER BY "id") AS rn
      FROM "session_message"
     WHERE "is_deleted" = FALSE
)
UPDATE "session_message" m
   SET "seq" = r.rn
  FROM ranked r
 WHERE m."id" = r."id"
   AND m."seq" = 0;

-- 会话的 last_msg_seq 与消息表对齐（可重复执行）
UPDATE "session" s
   SET "last_msg_seq" = COALESCE((
           SELECT MAX(m."seq") FROM "session_message" m WHERE m."session_id" = s."id"
       ), 0),
       "update_time" = CURRENT_TIMESTAMP
 WHERE s."last_msg_seq" <> COALESCE((
           SELECT MAX(m."seq") FROM "session_message" m WHERE m."session_id" = s."id"
       ), 0);

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_qa.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · customer_db 质检中心增量脚本（PostgreSQL 17）
-- 用途：补齐质检规则、质检任务、复核记录三张表。
-- 幂等：可重复执行。

CREATE TABLE IF NOT EXISTS "qa_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "rule_content" TEXT,
  "weight" INT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_rule_rule_type CHECK ("rule_type" IN (1,2,3,4)),
  PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "idx_qa_rule_tenant_type" ON "qa_rule" ("tenant_code", "rule_type");

-- 清理历史并发初始化产生的同名规则，保留最早一条（仅处理未删除数据）
DELETE FROM qa_rule AS dup
 USING qa_rule AS keep
 WHERE dup.tenant_code = keep.tenant_code
   AND dup.rule_name = keep.rule_name
   AND dup.is_deleted = FALSE
   AND keep.is_deleted = FALSE
   AND dup.id > keep.id;

-- 未删除规则同一企业同一名称唯一，阻止再次出现重复
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_rule_tenant_name_active"
    ON "qa_rule" ("tenant_code", "rule_name")
    WHERE "is_deleted" = FALSE;

CREATE TABLE IF NOT EXISTS "qa_task" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_no" VARCHAR(40) NOT NULL,
  "session_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "ai_score" NUMERIC(5,2) DEFAULT NULL,
  "ai_result" VARCHAR(512) DEFAULT NULL,
  "risk_level" SMALLINT DEFAULT 1,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reviewer_id" BIGINT DEFAULT NULL,
  "review_score" NUMERIC(5,2) DEFAULT NULL,
  "review_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_task_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_qa_task_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_task_no" UNIQUE ("tenant_code", "task_no")
);
CREATE INDEX IF NOT EXISTS "idx_qa_task_tenant_status" ON "qa_task" ("tenant_code", "status");
CREATE INDEX IF NOT EXISTS "idx_qa_task_tenant_session" ON "qa_task" ("tenant_code", "session_id");

CREATE TABLE IF NOT EXISTS "qa_review" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_id" BIGINT NOT NULL,
  "reviewer_id" BIGINT NOT NULL,
  "action" SMALLINT NOT NULL,
  "comment" VARCHAR(512) DEFAULT NULL,
  "review_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_qa_review_action CHECK ("action" IN (1,2,3)),
  PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "idx_qa_review_tenant_task" ON "qa_review" ("tenant_code", "task_id", "review_time");

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_routing.sql
-- ---------------------------------------------------------------------------
-- 坐席状态与智能路由（可重复执行）：
--   1. 新增 agent_status：坐席在线/忙碌/小休 + 最多同时接待几单；
--   2. skill_group 补 overflow_after_seconds：排队超过这个秒数就升级（放宽技能组限制）；
--   3. 补两个路由要用的索引。

CREATE TABLE IF NOT EXISTS "agent_status" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "max_concurrency" SMALLINT NOT NULL DEFAULT 5,
  "is_connected" BOOLEAN NOT NULL DEFAULT FALSE,
  "status_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_agent_status_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_status" UNIQUE ("tenant_code", "agent_id")
);

COMMENT ON TABLE "agent_status" IS '坐席状态表';
COMMENT ON COLUMN "agent_status"."agent_id" IS '坐席用户ID';
COMMENT ON COLUMN "agent_status"."status" IS '状态码：1-在线、2-忙碌、3-小休';
COMMENT ON COLUMN "agent_status"."max_concurrency" IS '最多同时接待的会话数';
COMMENT ON COLUMN "agent_status"."is_connected" IS '长连接是否在线（由实时网关维护，路由只分配在线坐席）';
COMMENT ON COLUMN "agent_status"."status_time" IS '状态变更时间（同负载时优先分配给更久没换状态的坐席）';

CREATE INDEX IF NOT EXISTS "idx_agent_status_tenant_status"
    ON "agent_status" ("tenant_code", "status");
ALTER TABLE "agent_status" ADD COLUMN IF NOT EXISTS "is_connected" BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE "skill_group"
    ADD COLUMN IF NOT EXISTS "overflow_after_seconds" INTEGER NOT NULL DEFAULT 60;

COMMENT ON COLUMN "skill_group"."overflow_after_seconds"
    IS '排队超过这个秒数就升级：放宽技能组限制，交给其它在线坐席（0 表示不升级）';

-- 排队扫描：按租户取"没人负责且没结束"的会话，先来先服务
CREATE INDEX IF NOT EXISTS "idx_session_queue"
    ON "session" ("tenant_code", "start_time")
    WHERE "agent_id" IS NULL AND "is_deleted" = FALSE;

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_realtime_qa.sql
-- ---------------------------------------------------------------------------
-- 会话实时质检（边聊边检，可重复执行）：
--   1. qa_rule 补三列：是否参与实时质检、命中词表、告警级别；
--   2. 新增 qa_alert：一句话一条告警，坐席当场就能看到；
--   3. 补两个查询索引（按会话看、按处理状态看）。
--
-- 与"批量质检"的分工：批量质检跑在会话结束之后、可以慢慢调 AI；
-- 实时质检跑在消息落库的同一时刻，只做"能在毫秒级判定"的规则（敏感词 / 绝对化承诺 / 情绪安抚），
-- 所以它不依赖大模型，AI 不可用时也不会漏检。

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "is_realtime" BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "hit_keywords" VARCHAR(512) DEFAULT NULL;

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "severity" SMALLINT NOT NULL DEFAULT 2;

COMMENT ON COLUMN "qa_rule"."is_realtime" IS '是否参与实时质检：关掉就只在会话结束后批量质检';
COMMENT ON COLUMN "qa_rule"."hit_keywords" IS '命中词表（逗号分隔）：敏感词类规则命中任意一个就告警';
COMMENT ON COLUMN "qa_rule"."severity" IS '告警级别：1-提示、2-警告、3-严重';

-- 默认的敏感词规则补一份可用的命中词，装完就能看到实时预警效果
UPDATE "qa_rule"
   SET "hit_keywords" = '妈的,废物,滚,随便你,投诉也没用,爱咋咋地,你懂不懂,自己去查',
       "severity" = 3,
       "update_time" = CURRENT_TIMESTAMP
 WHERE "rule_type" = 1
   AND "is_deleted" = FALSE
   AND ("hit_keywords" IS NULL OR "hit_keywords" = '');

CREATE TABLE IF NOT EXISTS "qa_alert" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "message_id" BIGINT DEFAULT NULL,
  "message_seq" BIGINT DEFAULT NULL,
  "rule_id" BIGINT DEFAULT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "hit_keyword" VARCHAR(128) DEFAULT NULL,
  "snippet" VARCHAR(512) DEFAULT NULL,
  "advice" VARCHAR(512) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "handler_id" BIGINT DEFAULT NULL,
  "handle_remark" VARCHAR(512) DEFAULT NULL,
  "handle_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_alert_severity CHECK ("severity" IN (1,2,3)),
  CONSTRAINT ck_qa_alert_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "qa_alert" IS '会话实时质检告警';
COMMENT ON COLUMN "qa_alert"."session_no" IS '会话号（坐席点告警直接跳到这条会话）';
COMMENT ON COLUMN "qa_alert"."agent_id" IS '告警发生时该会话的负责坐席';
COMMENT ON COLUMN "qa_alert"."message_id" IS '触发告警的消息 ID';
COMMENT ON COLUMN "qa_alert"."rule_name" IS '命中的规则名';
COMMENT ON COLUMN "qa_alert"."hit_keyword" IS '命中的词（敏感词类规则才有）';
COMMENT ON COLUMN "qa_alert"."snippet" IS '命中片段（截取消息上下文，便于坐席定位）';
COMMENT ON COLUMN "qa_alert"."advice" IS '处置建议';
COMMENT ON COLUMN "qa_alert"."status" IS '状态码：1-待处理、2-已处理';

CREATE INDEX IF NOT EXISTS "idx_qa_alert_tenant_session"
    ON "qa_alert" ("tenant_code", "session_no", "create_time");

CREATE INDEX IF NOT EXISTS "idx_qa_alert_tenant_status"
    ON "qa_alert" ("tenant_code", "status", "create_time");

-- 同一条消息 + 同一条规则只告警一次：消息幂等重发时不会重复弹窗
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_alert_message_rule"
    ON "qa_alert" ("tenant_code", "message_id", "rule_id")
    WHERE "is_deleted" = FALSE AND "message_id" IS NOT NULL AND "rule_id" IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_qa_source.sql
-- ---------------------------------------------------------------------------
-- 质检任务接真实会话（可重复执行）：
--   1. qa_task 加"一个会话只建一条质检任务"的唯一索引，避免重复质检同一单；
--   2. 补一个扫描用的索引：按"已结束 + 还没有质检任务"找单子。
--
-- 背景：这一版之前，"发起全量质检"是拿内置样例池随机生成任务的，
-- 真实会话聊了什么根本没进质检中心。这个脚本是把它改成真实数据源的第一步。

-- 同一个租户下，同一个会话只能有一条未删除的质检任务
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_task_tenant_session"
    ON "qa_task" ("tenant_code", "session_id")
    WHERE "is_deleted" = FALSE AND "session_id" IS NOT NULL;

-- 扫描候选：已结束且还没质检过的会话
CREATE INDEX IF NOT EXISTS "idx_session_tenant_end_time"
    ON "session" ("tenant_code", "end_time" DESC)
    WHERE "status" = 4 AND "is_deleted" = FALSE;

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_qa_timeout.sql
-- ---------------------------------------------------------------------------
-- 边聊边检 · 响应超时提醒（可重复执行）：
--   1. qa_rule 加 timeout_seconds：这个租户"客户发完消息多久没回就算超时"；
--   2. 放开规则类型限制，允许 5-响应超时（时效类规则，不看话术内容、看时长）；
--   3. 给已有租户补一条默认的「响应超时」规则（60 秒，警告级）。
--
-- 为什么单独一类规则：敏感词、承诺、情绪都是"看这句话说了什么"，
-- 而响应超时是"看这话晾了多久"，判定时机也不一样——它由定时扫描触发，不是消息触发。

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "timeout_seconds" INTEGER NOT NULL DEFAULT 60;

COMMENT ON COLUMN "qa_rule"."timeout_seconds" IS '响应超时秒数（仅"5-响应超时"类规则使用）：客户发完消息超过这个时长没被回复就告警';

-- 放开类型限制到 5（可重复执行：先删后加）
ALTER TABLE "qa_rule" DROP CONSTRAINT IF EXISTS "ck_qa_rule_rule_type";
ALTER TABLE "qa_rule"
    ADD CONSTRAINT "ck_qa_rule_rule_type" CHECK ("rule_type" IN (1,2,3,4,5));

-- 给还没有"响应超时"规则的租户补一条默认规则（装完就生效）
INSERT INTO "qa_rule" ("id", "tenant_code", "rule_name", "rule_type", "rule_content", "weight",
                       "is_enabled", "is_realtime", "hit_keywords", "severity", "timeout_seconds",
                       "create_time", "update_time", "creator", "is_deleted")
SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::BIGINT + row_number() OVER (),
       t."tenant_code",
       '响应超时',
       5,
       '客户发出消息后长时间没有坐席回复，需要提醒并及时响应',
       25,
       TRUE,
       TRUE,
       NULL,
       2,
       60,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       'SYSTEM',
       FALSE
  FROM (SELECT DISTINCT "tenant_code" FROM "qa_rule" WHERE "is_deleted" = FALSE) t
 WHERE NOT EXISTS (
        SELECT 1 FROM "qa_rule" r
         WHERE r."tenant_code" = t."tenant_code"
           AND r."rule_type" = 5
           AND r."is_deleted" = FALSE
       );

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_kb.sql
-- ---------------------------------------------------------------------------
-- 企业知识库：文档解析、切块与向量索引（可重复执行）
--   1. 打开 pgvector 扩展；
--   2. 新增 kb_chunk：一个文档切出来的每一块，带向量；
--   3. 补两个索引：按文档取切片、按向量做相似度检索（HNSW 余弦）。
--
-- 为什么切片单独一张表：文档（kb_document）是"人看的"，切片（kb_chunk）是"检索用的"。
-- 一份文档切几十上百块很正常，放一张表里查询会互相拖累；分开之后，
-- 文档列表只查文档表，检索只打切片表 + 向量索引。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "kb_chunk" IS '知识文档切片表（检索的最小单位）';
COMMENT ON COLUMN "kb_chunk"."doc_id" IS '所属文档ID';
COMMENT ON COLUMN "kb_chunk"."chunk_no" IS '文档内切片序号（从 1 开始）';
COMMENT ON COLUMN "kb_chunk"."content" IS '切片正文';
COMMENT ON COLUMN "kb_chunk"."char_count" IS '字符数（切块时统计，用于展示）';
COMMENT ON COLUMN "kb_chunk"."token_count" IS '估算 token 数（按 1 汉字≈1 token 粗估）';
COMMENT ON COLUMN "kb_chunk"."embedding" IS '向量（1024 维，千问 text-embedding-v3 默认维度）';
COMMENT ON COLUMN "kb_chunk"."embedding_model" IS '生成这个向量的模型（换模型要重建索引）';

-- 按文档取切片：切片预览、重建索引都要用
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_tenant_doc"
    ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");

-- 向量检索：HNSW + 余弦距离（pgvector 0.5+ 才支持 HNSW）
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_embedding"
    ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

-- 文档表补两列：切了多少块、最后索引进度（前端列表直接展示，不用再 count）
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "chunk_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_status" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_message" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_name" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_size" BIGINT DEFAULT NULL;

COMMENT ON COLUMN "kb_document"."chunk_count" IS '切片数（向量化完成后回填）';
COMMENT ON COLUMN "kb_document"."index_status" IS '索引状态：1-未索引、2-索引中、3-已索引、4-索引失败';
COMMENT ON COLUMN "kb_document"."index_message" IS '索引失败原因 / 最近一次索引说明';
COMMENT ON COLUMN "kb_document"."file_name" IS '原始文件名（导入的文档）';
COMMENT ON COLUMN "kb_document"."file_size" IS '文件大小（字节）';

-- 知识库上传暴露的老问题：file_meta.mime_type 只有 varchar(64)，
-- 而 Office 文档的标准 MIME 比它长（docx 71、xlsx 65、pptx 73），
-- 一上传 Word 就报 "value too long for type character varying(64)"。
ALTER TABLE "file_meta" ALTER COLUMN "mime_type" TYPE VARCHAR(128);
COMMENT ON COLUMN "file_meta"."mime_type" IS '文件 MIME 类型（Office 文档的 MIME 比较长，留到 128）';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_bot_brain.sql
-- ---------------------------------------------------------------------------
-- AI 客服大脑（可重复执行）：
--   1. session 加 bot_transfer_reason：机器人为什么把这单转给人工；
--   2. 放开会话流转类型到 6，新增"6-机器人转人工"。
--
-- 为什么原因要存在 session 上、而不是只写进 session_event：
-- 坐席打开会话时第一眼就要看到"这单为什么转过来"（客户在骂人？机器人答不上来？），
-- 而流转记录是详情页才展开的东西。放在 session 上，列表和会话头部都能直接显示，
-- 前端也不用为了这一行字多打一次接口。

ALTER TABLE "session"
    ADD COLUMN IF NOT EXISTS "bot_transfer_reason" VARCHAR(255);

COMMENT ON COLUMN "session"."bot_transfer_reason" IS '机器人转人工的原因（客户情绪激动 / 答不上来 / 命中转人工意图等）';
COMMENT ON COLUMN "session"."intent" IS '智能客服识别出的意图（由 AI 客服大脑写入）';
COMMENT ON COLUMN "session"."emotion" IS '智能客服识别出的客户情绪：中性/焦虑/不满/愤怒';

-- 放开会话流转类型限制到 6（可重复执行：先删后加）
ALTER TABLE "session_event" DROP CONSTRAINT IF EXISTS "ck_session_event_event_type";
ALTER TABLE "session_event"
    ADD CONSTRAINT "ck_session_event_event_type" CHECK ("event_type" IN (1,2,3,4,5,6));

COMMENT ON COLUMN "session_event"."event_type"
    IS '事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时、6-机器人转人工';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_chat_image.sql
-- ---------------------------------------------------------------------------
-- 聊天图片（可重复执行）：把 file_meta.biz_type 的取值放开到 5。
--
-- 背景：客户在聊天窗口发图片时，图片附件也要登记 file_meta。
-- 原来约束是 CHECK (biz_type IN (1,2,3,4))（1-头像、2-附件、3-导出、4-发票），
-- 聊天图片这个新业务塞不进去，插入直接报：
--   new row for relation "file_meta" violates check constraint "ck_file_meta_biz_type"
--
-- 这类"约束挡住新业务"的问题，光看代码是发现不了的（列都在），
-- 所以除了这个脚本，SchemaGuard 还加了一道"必需约束取值"的自检，启动时能自动发现并补。

ALTER TABLE "file_meta" DROP CONSTRAINT IF EXISTS "ck_file_meta_biz_type";
ALTER TABLE "file_meta"
    ADD CONSTRAINT "ck_file_meta_biz_type" CHECK ("biz_type" IN (1,2,3,4,5));

COMMENT ON COLUMN "file_meta"."biz_type" IS '业务类型码：1-头像、2-附件、3-导出、4-发票、5-聊天图片';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_ticket.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · customer_db 工单中心增量脚本（PostgreSQL 17）
-- 用途：工单从"会话转过来的一张纸"变成"有闭环、有 SLA 的工单"
--   1. ticket 补：分类 / 来源会话号 / 处理人姓名 / 客户姓名 / 双段 SLA（首次响应 + 解决）/ 预警标记；
--   2. ticket 放开状态取值：加上"待客户确认"；
--   3. ticket_event 补：操作人姓名、状态前后、是否对客户可见；放开事件类型（加"状态变更"和"SLA 预警"）；
--   4. 新增 ticket_sla_rule：按优先级配置"首次响应多久、解决多久"。
-- 幂等：可重复执行（CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS + 约束先删后建）。
--
-- 为什么约束要"先删后建"：工单状态原来只允许 1~4，这一篇多了"待客户确认"，
-- 直接插入会被 ck_ticket_status 挡住——和第 23 篇聊天图片的 biz_type=5 是同一种坑，
-- 所以 SchemaGuard 里也把这两个约束登记进了"必需约束取值"自检。

-- ---------- 1. ticket：补齐工单中心要用的列 ----------
CREATE TABLE IF NOT EXISTS "ticket" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_no" VARCHAR(40) NOT NULL,
  "ticket_type" SMALLINT NOT NULL DEFAULT 1,
  "category" SMALLINT NOT NULL DEFAULT 5,
  "title" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "customer_name" VARCHAR(64) DEFAULT NULL,
  "desc" TEXT,
  "priority" SMALLINT NOT NULL DEFAULT 2,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "source" SMALLINT NOT NULL DEFAULT 3,
  "session_no" VARCHAR(40) DEFAULT NULL,
  "source_session_id" BIGINT DEFAULT NULL,
  "assignee_id" BIGINT DEFAULT NULL,
  "assignee_name" VARCHAR(64) DEFAULT NULL,
  "group_id" BIGINT DEFAULT NULL,
  "sla_first_minutes" INT NOT NULL DEFAULT 0,
  "sla_resolve_minutes" INT NOT NULL DEFAULT 0,
  "first_response_due" TIMESTAMP DEFAULT NULL,
  "first_response_at" TIMESTAMP DEFAULT NULL,
  "resolve_due" TIMESTAMP DEFAULT NULL,
  "resolved_at" TIMESTAMP DEFAULT NULL,
  "sla_deadline" TIMESTAMP DEFAULT NULL,
  "sla_state" SMALLINT NOT NULL DEFAULT 1,
  "sla_alerted" BOOLEAN NOT NULL DEFAULT FALSE,
  "close_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "creator_name" VARCHAR(64) DEFAULT NULL,
  "source_channel" SMALLINT DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_ticket_type CHECK ("ticket_type" IN (1,2)),
  CONSTRAINT ck_ticket_category CHECK ("category" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_status CHECK ("status" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_source CHECK ("source" IN (1,2,3)),
  CONSTRAINT ck_ticket_sla_state CHECK ("sla_state" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_ticket_no" UNIQUE ("tenant_code", "ticket_no")
);

ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "category" SMALLINT NOT NULL DEFAULT 5;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "customer_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "source" SMALLINT NOT NULL DEFAULT 3;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "session_no" VARCHAR(40) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "source_session_id" BIGINT DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "assignee_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "sla_first_minutes" INT NOT NULL DEFAULT 0;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "sla_resolve_minutes" INT NOT NULL DEFAULT 0;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "first_response_due" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "first_response_at" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "resolve_due" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "resolved_at" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "sla_alerted" BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "creator_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "source_channel" SMALLINT DEFAULT NULL;

-- 来源渠道（客户从哪来：在线会话 / 电话 / 邮件 / 工单导入 / 其它）：和 source 不是一个维度——
-- source 说的是"这张工单怎么来的"，source_channel 说的是"客户从哪个渠道来的"
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_source_channel";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_source_channel" CHECK ("source_channel" IS NULL OR "source_channel" IN (1,2,3,4,5));

-- 状态多了"待客户确认"（3），约束要跟着放开
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_status";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_status" CHECK ("status" IN (1,2,3,4,5));
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_category";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_category" CHECK ("category" IN (1,2,3,4,5));
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_source";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_source" CHECK ("source" IN (1,2,3));

CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_status_time" ON "ticket" ("tenant_code", "status", "create_time");
CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_assignee" ON "ticket" ("tenant_code", "assignee_id");
-- SLA 扫描就是按这个索引扫的：租户 + 还没超时的那些
CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_sla" ON "ticket" ("tenant_code", "sla_state", "resolve_due");
CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_session" ON "ticket" ("tenant_code", "session_no");

COMMENT ON TABLE "ticket" IS '工单表';
COMMENT ON COLUMN "ticket"."ticket_no" IS '工单号';
COMMENT ON COLUMN "ticket"."ticket_type" IS '类型码：1-企业内部工单、2-平台支持工单';
COMMENT ON COLUMN "ticket"."category" IS '业务分类码：1-订单、2-退款售后、3-物流、4-商品、5-其他';
COMMENT ON COLUMN "ticket"."title" IS '工单主题';
COMMENT ON COLUMN "ticket"."desc" IS '问题描述（会话转单时带最近聊天记录摘要）';
COMMENT ON COLUMN "ticket"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket"."status" IS '状态码：1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭';
COMMENT ON COLUMN "ticket"."source" IS '来源码：1-会话转单、2-客户自助、3-坐席新建';
COMMENT ON COLUMN "ticket"."session_no" IS '来源会话号（坐席可一键跳回会话看上下文）';
COMMENT ON COLUMN "ticket"."source_session_id" IS '来源会话ID';
COMMENT ON COLUMN "ticket"."assignee_id" IS '处理人ID';
COMMENT ON COLUMN "ticket"."assignee_name" IS '处理人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."sla_first_minutes" IS '建单时快照的首次响应 SLA（分钟）';
COMMENT ON COLUMN "ticket"."sla_resolve_minutes" IS '建单时快照的解决 SLA（分钟）';
COMMENT ON COLUMN "ticket"."first_response_due" IS '首次响应截止时间';
COMMENT ON COLUMN "ticket"."first_response_at" IS '首次响应时间（第一条对客户可见的回复）';
COMMENT ON COLUMN "ticket"."resolve_due" IS '解决截止时间';
COMMENT ON COLUMN "ticket"."resolved_at" IS '解决时间';
COMMENT ON COLUMN "ticket"."sla_deadline" IS 'SLA 截止时间（旧的单段口径，等于 resolve_due，保留兼容）';
COMMENT ON COLUMN "ticket"."sla_state" IS 'SLA 状态码：1-正常、2-即将超时、3-已超时';
COMMENT ON COLUMN "ticket"."sla_alerted" IS '这一轮预警是否已经推送过（避免每 30 秒重复轰炸）';
COMMENT ON COLUMN "ticket"."close_time" IS '关闭时间';
COMMENT ON COLUMN "ticket"."creator_name" IS '创建人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."source_channel" IS '来源渠道码：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它';

-- ---------- 2. ticket_event：流转记录 ----------
CREATE TABLE IF NOT EXISTS "ticket_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "from_status" SMALLINT DEFAULT NULL,
  "to_status" SMALLINT DEFAULT NULL,
  "visible_to_customer" BOOLEAN NOT NULL DEFAULT FALSE,
  "content" VARCHAR(512) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ticket_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8)),
  PRIMARY KEY ("id")
);

ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "operator_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "from_status" SMALLINT DEFAULT NULL;
ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "to_status" SMALLINT DEFAULT NULL;
ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "visible_to_customer" BOOLEAN NOT NULL DEFAULT FALSE;

-- 事件类型多了"状态变更"（7）和"SLA 预警"（8）
ALTER TABLE "ticket_event" DROP CONSTRAINT IF EXISTS "ck_ticket_event_event_type";
ALTER TABLE "ticket_event"
    ADD CONSTRAINT "ck_ticket_event_event_type" CHECK ("event_type" IN (1,2,3,4,5,6,7,8));

CREATE INDEX IF NOT EXISTS "idx_ticket_event_tenant_ticket"
    ON "ticket_event" ("tenant_code", "ticket_id", "event_time");

COMMENT ON TABLE "ticket_event" IS '工单流转记录表';
COMMENT ON COLUMN "ticket_event"."event_type" IS '事件码：1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开、7-状态变更、8-SLA预警';
COMMENT ON COLUMN "ticket_event"."operator_name" IS '操作人姓名（冗余，时间线直接显示）';
COMMENT ON COLUMN "ticket_event"."from_status" IS '变更前状态（状态流转类事件用）';
COMMENT ON COLUMN "ticket_event"."to_status" IS '变更后状态';
COMMENT ON COLUMN "ticket_event"."visible_to_customer" IS '这条回复是否对客户可见（false=内部备注）';
COMMENT ON COLUMN "ticket_event"."content" IS '事件内容 / 回复正文';

-- ---------- 3. ticket_sla_rule：按优先级配置 SLA ----------
CREATE TABLE IF NOT EXISTS "ticket_sla_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "priority" SMALLINT NOT NULL,
  "first_response_minutes" INT NOT NULL,
  "resolve_minutes" INT NOT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_sla_rule_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_sla_rule_minutes CHECK ("first_response_minutes" > 0 AND "resolve_minutes" > 0),
  PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "uk_ticket_sla_rule_tenant_priority"
    ON "ticket_sla_rule" ("tenant_code", "priority")
    WHERE "is_deleted" = FALSE;

COMMENT ON TABLE "ticket_sla_rule" IS '工单 SLA 规则（按优先级配置首次响应 / 解决时长）';
COMMENT ON COLUMN "ticket_sla_rule"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket_sla_rule"."first_response_minutes" IS '首次响应时限（分钟）';
COMMENT ON COLUMN "ticket_sla_rule"."resolve_minutes" IS '解决时限（分钟）';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_customer360.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · customer_db 客户 360 视图增量脚本（PostgreSQL 17）
-- 用途：客户从"会话里那一列客户名"变成"坐席接单前就能看懂的人"
--   1. customer 补：备注、累计会话数、累计工单数、最近会话时间、风险等级；
--   2. customer_tag 从"一个名字"升级成"指向标签体系的关联"：
--      补 tag_id / tag_group / source / operator_id / operator_name / update_time / is_deleted；
--   3. 新增 customer_tag_def：标签体系（分组、颜色、类型、排序、启用）；
--   4. 新增 customer_event：客户动态（建档 / 打标 / 去标 / 等级调整 / 备注 / 风险标记）。
--   5. 满意度评价正式开写 csat_record（表在第 4 篇的全量脚本里，这里补齐"老库自愈"）；
--   6. 标签自动化：customer_tag_def 加"指标 + 比较符 + 阈值 + 统计窗口"四件套；
--   7. 客户类型：个人客户 / 企业客户（原来错当成"会员等级"的第 5 档，语义不对）。
-- 幂等：可重复执行（CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS + 约束先删后建 + 索引 IF NOT EXISTS）。
--
-- 为什么 customer_tag 要"加列"而不是"新建一张关联表"：
-- 这张表在第 4 篇的建表脚本里就有，只是没接过代码（一直是空的）。老库上它可能已经被别的分支
-- 塞过数据，直接改名/重建会把数据弄丢；加列 + 补唯一索引，老行照样能用（tag_id 为空时按老口径读）。

-- ---------- 1. customer：补齐客户 360 要用的列 ----------
CREATE TABLE IF NOT EXISTS "customer" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_no" VARCHAR(32) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "phone" VARCHAR(20) DEFAULT NULL,
  "level" SMALLINT NOT NULL DEFAULT 1,
  "channel" VARCHAR(20) DEFAULT NULL,
  "orders_count" INT NOT NULL DEFAULT 0,
  "total_value" NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  "points" INT NOT NULL DEFAULT 0,
  "csat" NUMERIC(5,2) DEFAULT NULL,
  "sentiment" SMALLINT DEFAULT NULL,
  "last_active" TIMESTAMP DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "ticket_count" INT NOT NULL DEFAULT 0,
  "last_session_at" TIMESTAMP DEFAULT NULL,
  "risk_level" SMALLINT NOT NULL DEFAULT 1,
  "merged_into" VARCHAR(32) DEFAULT NULL,
  "anonymized_at" TIMESTAMP DEFAULT NULL,
  "customer_type" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_level CHECK ("level" IN (1,2,3,4,5)),
  CONSTRAINT ck_customer_sentiment CHECK ("sentiment" IN (1,2,3)),
  CONSTRAINT ck_customer_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_customer_customer_type CHECK ("customer_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_no" UNIQUE ("tenant_code", "customer_no")
);

ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "remark" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "session_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "ticket_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "last_session_at" TIMESTAMP DEFAULT NULL;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "risk_level" SMALLINT NOT NULL DEFAULT 1;
-- 合并：源客户软删 + 记下"并到谁了"（不物理删：会话/工单都还指着他）
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "merged_into" VARCHAR(32) DEFAULT NULL;
-- 匿名化：合规要求"删 PII 但不破坏统计"，所以只记一个时间点，姓名/手机/备注被抹掉
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "anonymized_at" TIMESTAMP DEFAULT NULL;
-- 客户类型：1-个人客户、2-企业客户。
-- 为什么要单独一列：客户是"企业自己的客户"，个人还是企业（B 端采购）属于**客户类型**，
-- 跟"会员忠诚度分层"（银卡 / 金卡 / 铂金）不是一回事，混在第 5 档里没人看得懂。
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "customer_type" SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE "customer" DROP CONSTRAINT IF EXISTS "ck_customer_customer_type";
ALTER TABLE "customer"
    ADD CONSTRAINT "ck_customer_customer_type" CHECK ("customer_type" IN (1,2));

-- 风险等级是新约束：老库上直接插 level=2 会被原来没有这条约束的库放过，但新库会挡——
-- 统一"先删后建"，两边口径一致。
ALTER TABLE "customer" DROP CONSTRAINT IF EXISTS "ck_customer_risk_level";
ALTER TABLE "customer"
    ADD CONSTRAINT "ck_customer_risk_level" CHECK ("risk_level" IN (1,2,3));

-- 客户列表默认按"最近活跃"倒序、按等级/风险筛选，这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_customer_tenant_last_active"
    ON "customer" ("tenant_code", "last_active" DESC);

COMMENT ON COLUMN "customer"."remark" IS '客户备注（坐席可见的内部说明，不展示给客户）';
COMMENT ON COLUMN "customer"."session_count" IS '累计会话数（建档后每次新会话 +1，冗余字段，列表排序用）';
COMMENT ON COLUMN "customer"."ticket_count" IS '累计工单数（会话转单 / 坐席建单时 +1）';
COMMENT ON COLUMN "customer"."last_session_at" IS '最近一次会话时间（没有会话时为空）';
COMMENT ON COLUMN "customer"."risk_level" IS '风险等级码：1-正常、2-关注、3-风险';
COMMENT ON COLUMN "customer"."merged_into" IS '重复客户被合并到哪个客户编号（自身软删，便于回溯）';
COMMENT ON COLUMN "customer"."anonymized_at" IS '匿名化时间（姓名 / 手机号 / 备注已抹除，会话与工单统计保留）';
COMMENT ON COLUMN "customer"."customer_type" IS '客户类型码：1-个人客户、2-企业客户（B 端采购，与会员等级无关）';
COMMENT ON COLUMN "customer"."level" IS '会员等级码（价值分层）：1-普通、2-银卡、3-金卡、4-铂金、5-钻石';

-- ---------- 2. customer_tag：从"标签名"升级成"指向标签体系的关联" ----------
CREATE TABLE IF NOT EXISTS "customer_tag" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "tag_id" BIGINT DEFAULT NULL,
  "tag_group" VARCHAR(32) DEFAULT NULL,
  "source" SMALLINT NOT NULL DEFAULT 1,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_tag_source CHECK ("source" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_tag" UNIQUE ("tenant_code", "customer_id", "tag_name")
);

ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "tag_id" BIGINT DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "tag_group" VARCHAR(32) DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "source" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "operator_id" BIGINT DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "operator_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE "customer_tag" DROP CONSTRAINT IF EXISTS "ck_customer_tag_source";
ALTER TABLE "customer_tag"
    ADD CONSTRAINT "ck_customer_tag_source" CHECK ("source" IN (1,2,3));

-- 同一个客户、同一个标签只允许挂一次（软删的不算）：重复打标就是"重复点击"，
-- 靠唯一索引挡住，代码里不用再查一遍有没有
CREATE UNIQUE INDEX IF NOT EXISTS "uk_tenant_customer_tag_id"
    ON "customer_tag" ("tenant_code", "customer_id", "tag_id")
    WHERE "tag_id" IS NOT NULL AND "is_deleted" = FALSE;

CREATE INDEX IF NOT EXISTS "idx_tenant_customer_tag_customer"
    ON "customer_tag" ("tenant_code", "customer_id")
    WHERE "is_deleted" = FALSE;

COMMENT ON COLUMN "customer_tag"."tag_id" IS '标签定义ID（customer_tag_def.id；为空表示历史数据，只有标签名）';
COMMENT ON COLUMN "customer_tag"."tag_group" IS '标签分组（冗余，列表直接读，不用再 join）';
COMMENT ON COLUMN "customer_tag"."source" IS '来源码：1-手工打标、2-规则自动、3-批量导入';
COMMENT ON COLUMN "customer_tag"."operator_id" IS '打标人ID';
COMMENT ON COLUMN "customer_tag"."operator_name" IS '打标人姓名';

-- ---------- 3. customer_tag_def：标签体系 ----------
CREATE TABLE IF NOT EXISTS "customer_tag_def" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "tag_code" VARCHAR(32) NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "tag_group" VARCHAR(32) NOT NULL,
  "color" VARCHAR(16) NOT NULL DEFAULT 'blue',
  "tag_type" SMALLINT NOT NULL DEFAULT 1,
  "rule_hint" VARCHAR(255) DEFAULT NULL,
  "rule_metric" VARCHAR(32) DEFAULT NULL,
  "rule_op" VARCHAR(4) DEFAULT NULL,
  "rule_value" NUMERIC(12,2) DEFAULT NULL,
  "rule_window_days" INT NOT NULL DEFAULT 0,
  "description" VARCHAR(255) DEFAULT NULL,
  "sort_no" INT NOT NULL DEFAULT 100,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_tag_def_type CHECK ("tag_type" IN (1,2)),
  CONSTRAINT ck_customer_tag_def_op CHECK ("rule_op" IS NULL OR "rule_op" IN ('GT','GTE','LT','LTE','EQ')),
  CONSTRAINT ck_customer_tag_def_metric CHECK ("rule_metric" IS NULL OR "rule_metric" IN
        ('CSAT','LEVEL','RISK_LEVEL','SESSIONS','TICKETS',
         'REFUND_SESSIONS','COMPLAINT_SESSIONS','NIGHT_SESSIONS','ACTIVE_DAYS')),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_tag_def_code" UNIQUE ("tenant_code", "tag_code"),
  CONSTRAINT "uk_tenant_tag_def_name" UNIQUE ("tenant_code", "tag_name")
);

-- 规则标签的四件套：指标 / 比较符 / 阈值 / 统计窗口（天，0 = 全周期）
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_metric" VARCHAR(32) DEFAULT NULL;
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_op" VARCHAR(4) DEFAULT NULL;
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_value" NUMERIC(12,2) DEFAULT NULL;
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_window_days" INT NOT NULL DEFAULT 0;

ALTER TABLE "customer_tag_def" DROP CONSTRAINT IF EXISTS "ck_customer_tag_def_op";
ALTER TABLE "customer_tag_def"
    ADD CONSTRAINT "ck_customer_tag_def_op" CHECK ("rule_op" IS NULL OR "rule_op" IN ('GT','GTE','LT','LTE','EQ'));
ALTER TABLE "customer_tag_def" DROP CONSTRAINT IF EXISTS "ck_customer_tag_def_metric";
-- 收窄"可执行指标"白名单：去掉三个**交易类**指标（累计消费 / 订单数 / 积分）。
-- 为什么去掉：这些数据属于电商/交易系统，客服系统既拿不到也维护不了——
-- 配成规则标签的结果是"永远不命中"，比没有更糟（运营会以为规则坏了）。
-- 顺序很重要：先把已经配了这三个指标的标签**降级成手工标签**，再收窄约束，
-- 否则 ADD CONSTRAINT 会因为存量数据不满足而失败。
UPDATE "customer_tag_def"
   SET "tag_type" = 1,
       "rule_metric" = NULL,
       "rule_op" = NULL,
       "rule_value" = NULL
 WHERE "rule_metric" IN ('TOTAL_VALUE', 'ORDERS', 'POINTS');

ALTER TABLE "customer_tag_def"
    ADD CONSTRAINT "ck_customer_tag_def_metric" CHECK ("rule_metric" IS NULL OR "rule_metric" IN
        ('CSAT','LEVEL','RISK_LEVEL','SESSIONS','TICKETS',
         'REFUND_SESSIONS','COMPLAINT_SESSIONS','NIGHT_SESSIONS','ACTIVE_DAYS'));

CREATE INDEX IF NOT EXISTS "idx_tenant_tag_def_group"
    ON "customer_tag_def" ("tenant_code", "tag_group", "sort_no");

-- 规则引擎每 10 分钟扫一遍"有规则的标签"，这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_tenant_tag_def_rule"
    ON "customer_tag_def" ("tenant_code", "tag_type")
    WHERE "is_deleted" = FALSE AND "is_enabled" = TRUE;

COMMENT ON TABLE "customer_tag_def" IS '客户标签定义表（标签体系：分组 + 颜色 + 手工/规则 + 排序）';
COMMENT ON COLUMN "customer_tag_def"."tag_code" IS '标签编码（租户内唯一，导入/接口用）';
COMMENT ON COLUMN "customer_tag_def"."tag_group" IS '分组：价值 / 服务 / 风险 / 偏好 / 来源';
COMMENT ON COLUMN "customer_tag_def"."color" IS '展示颜色：blue / green / orange / red / purple / gray';
COMMENT ON COLUMN "customer_tag_def"."tag_type" IS '类型码：1-手工打标、2-规则自动';
COMMENT ON COLUMN "customer_tag_def"."rule_hint" IS '自动标签的命中口径说明（写给人看；rule_metric 那一组是给引擎跑的）';
COMMENT ON COLUMN "customer_tag_def"."rule_metric" IS '规则指标（只允许客服系统自己产生的数据）：CSAT 满意度 / LEVEL 会员等级 / RISK_LEVEL 风险等级 / SESSIONS 会话数 / TICKETS 工单数 / REFUND_SESSIONS 退款类会话 / COMPLAINT_SESSIONS 投诉类会话 / NIGHT_SESSIONS 夜间咨询 / ACTIVE_DAYS 距最近活跃天数';
COMMENT ON COLUMN "customer_tag_def"."rule_op" IS '比较符：GT / GTE / LT / LTE / EQ';
COMMENT ON COLUMN "customer_tag_def"."rule_value" IS '阈值';
COMMENT ON COLUMN "customer_tag_def"."rule_window_days" IS '统计窗口（天）：0 表示全周期；只对会话类指标有意义';
COMMENT ON COLUMN "customer_tag_def"."sort_no" IS '组内排序号（越小越前）';
COMMENT ON COLUMN "customer_tag_def"."is_enabled" IS '是否启用：停用后不能再打标，但已经打上的标签仍然展示';

-- ---------- 4. customer_event：客户动态（360 里的时间线） ----------
-- ---------- 4. csat_record：满意度评价（第 4 篇设计的表，这一篇正式开写） ----------
-- 表本来就在全量建库脚本里，这里再写一遍是为了"老库缺表能自愈"：
-- 增量脚本 + 启动自检登记，缺了会自动补上（幂等）。
CREATE TABLE IF NOT EXISTS "csat_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "score" SMALLINT NOT NULL,
  "feedback" VARCHAR(512) DEFAULT NULL,
  "evaluate_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_csat_score CHECK ("score" BETWEEN 1 AND 5),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_csat_session" UNIQUE ("tenant_code", "session_id")
);

CREATE INDEX IF NOT EXISTS "idx_csat_tenant_time"
    ON "csat_record" ("tenant_code", "evaluate_time");
-- 客户 360 要按客户算"满意度均值"，这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_csat_tenant_customer"
    ON "csat_record" ("tenant_code", "customer_id");

COMMENT ON TABLE "csat_record" IS '满意度评价明细表（会话结束后由客户评价，坐席也可代录）';
COMMENT ON COLUMN "csat_record"."session_id" IS '会话ID';
COMMENT ON COLUMN "csat_record"."customer_id" IS '客户ID（客户 360 按它算满意度均值）';
COMMENT ON COLUMN "csat_record"."score" IS '评分 1-5';
COMMENT ON COLUMN "csat_record"."feedback" IS '评价内容';

-- ---------- 5. customer_event：客户动态（360 里的时间线） ----------
CREATE TABLE IF NOT EXISTS "customer_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "event_title" VARCHAR(64) NOT NULL,
  "event_content" VARCHAR(512) DEFAULT NULL,
  "ref_type" SMALLINT DEFAULT NULL,
  "ref_no" VARCHAR(64) DEFAULT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_customer_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)),
  PRIMARY KEY ("id")
);

-- 事件类型从 6 类扩到 16 类：会话开始 / 会话结束 / 转人工 / 转工单 / 满意度评价 /
-- 敏感信息查阅 / 订单变更 / 客户合并 / 匿名化 / 客户删除。
-- 老库上这条约束只允许 1~6，直接写 7 会被挡住——所以照例"先删后建"。
ALTER TABLE "customer_event" DROP CONSTRAINT IF EXISTS "ck_customer_event_type";
ALTER TABLE "customer_event"
    ADD CONSTRAINT "ck_customer_event_type" CHECK ("event_type" IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16));

CREATE INDEX IF NOT EXISTS "idx_customer_event_tenant_customer"
    ON "customer_event" ("tenant_code", "customer_id", "event_time" DESC);

-- 按类型查动态（"这个客户最近被谁看过手机号"这类合规查询）
CREATE INDEX IF NOT EXISTS "idx_customer_event_tenant_type"
    ON "customer_event" ("tenant_code", "event_type", "event_time" DESC);

COMMENT ON TABLE "customer_event" IS '客户动态表（建档 / 打标 / 去标 / 等级调整 / 备注 / 风险标记）';
COMMENT ON COLUMN "customer_event"."event_type" IS '事件类型码：1-建档、2-打标、3-去标、4-等级调整、5-备注更新、6-风险标记、7-发起会话、8-会话结束、9-转人工、10-转工单、11-满意度评价、12-敏感信息查阅、13-订单变更、14-客户合并、15-客户匿名化、16-客户删除';
COMMENT ON COLUMN "customer_event"."event_title" IS '动态标题（列表直接展示）';
COMMENT ON COLUMN "customer_event"."event_content" IS '动态详情（例如"银卡 → 金卡"）';
COMMENT ON COLUMN "customer_event"."ref_type" IS '关联对象类型码：1-会话、2-工单、3-订单、4-标签';
COMMENT ON COLUMN "customer_event"."ref_no" IS '关联对象编号（会话号 / 工单号 / 订单号 / 标签编码）';
COMMENT ON COLUMN "customer_event"."operator_id" IS '操作人ID（空表示系统自动）';
COMMENT ON COLUMN "customer_event"."operator_name" IS '操作人姓名';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_metrics.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · customer_db 数据大屏与坐席绩效增量脚本（PostgreSQL 17）
-- 用途：把"报表要用的数字"按 坐席 × 天 预聚合出来，供大屏与绩效报表快速读取。
--   新增 agent_daily_metric：一天的接待量 / 消息量 / 首响时长 / 会话时长 / 满意度 / 转接次数…
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS + 索引 IF NOT EXISTS）。
--
-- 为什么要预聚合而不是每次现算：
--   1. 绩效报表要按坐席筛选、排序、分页、再下钻，现算就得对 session_message 做全表聚合，
--      数据量一大就拖死列表；
--   2. 大屏要 5 秒刷新一次，每次都全表扫是浪费——预聚合之后大屏只读今天的几十行。
--   重算是**幂等**的：同一个坐席同一天重算多少次结果都一样（DELETE + INSERT 或 UPSERT）。

CREATE TABLE IF NOT EXISTS "agent_daily_metric" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "agent_name" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "human_session_count" INT NOT NULL DEFAULT 0,
  "message_count" INT NOT NULL DEFAULT 0,
  "customer_message_count" INT NOT NULL DEFAULT 0,
  "first_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_session_seconds" INT NOT NULL DEFAULT 0,
  "transfer_count" INT NOT NULL DEFAULT 0,
  "csat_count" INT NOT NULL DEFAULT 0,
  "csat_score" NUMERIC(5,2) DEFAULT NULL,
  "good_csat_count" INT NOT NULL DEFAULT 0,
  "close_count" INT NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_date" UNIQUE ("tenant_code", "agent_id", "stat_date")
);

-- 报表默认按"最近 30 天 + 租户"筛，再按坐席聚合；这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_agent_metric_tenant_date"
    ON "agent_daily_metric" ("tenant_code", "stat_date" DESC);

COMMENT ON TABLE "agent_daily_metric" IS '坐席绩效日聚合表（按 坐席 × 天 预聚合，报表与大屏都读它）';
COMMENT ON COLUMN "agent_daily_metric"."agent_id" IS '坐席用户ID（0 表示"未分配/机器人接待"这一行）';
COMMENT ON COLUMN "agent_daily_metric"."stat_date" IS '统计日期（按自然日）';
COMMENT ON COLUMN "agent_daily_metric"."session_count" IS '接待会话数（含机器人阶段后转过来的）';
COMMENT ON COLUMN "agent_daily_metric"."human_session_count" IS '人工接待过的会话数';
COMMENT ON COLUMN "agent_daily_metric"."message_count" IS '这条坐席发的消息数（不含内部备注？含，备注也是坐席产出）';
COMMENT ON COLUMN "agent_daily_metric"."customer_message_count" IS '客户发的消息数（用来算"人均沟通量"）';
COMMENT ON COLUMN "agent_daily_metric"."first_response_seconds" IS '首次响应时长均值（秒）：客户最后一条 → 坐席第一条，按会话平均';
COMMENT ON COLUMN "agent_daily_metric"."avg_response_seconds" IS '平均响应时长（秒）：坐席每次回复距客户上一条消息的平均间隔';
COMMENT ON COLUMN "agent_daily_metric"."avg_session_seconds" IS '平均会话时长（秒）：会话开始到结束';
COMMENT ON COLUMN "agent_daily_metric"."transfer_count" IS '转接次数（session_event 的转接事件）';
COMMENT ON COLUMN "agent_daily_metric"."csat_count" IS '收到的评价条数';
COMMENT ON COLUMN "agent_daily_metric"."csat_score" IS '满意度均值（1~5）';
COMMENT ON COLUMN "agent_daily_metric"."good_csat_count" IS '好评数（4~5 分）';
COMMENT ON COLUMN "agent_daily_metric"."close_count" IS '结束的会话数（状态 4）';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_wechat.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_wechat_subscribe.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_native_channels.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_phone_calls.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_quick_reply.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 快捷话术（工作台 Alt+1~9）
--
-- quick_reply 这张表在基线 customer_db.sql 里就有（表结构一直没人用），
-- 这里只补一列"用过多少次"：话术按使用频率排序，坐席最常发的自然浮到 Alt+1。
-- ---------------------------------------------------------------------------

ALTER TABLE "quick_reply" ADD COLUMN IF NOT EXISTS "use_count" INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN "quick_reply"."use_count" IS '被坐席使用次数（发送时累加，列表按它排序）';

-- 工作台每次打开都要查"这个租户启用中的话术"，索引按这个查询走
CREATE INDEX IF NOT EXISTS "idx_quick_reply_tenant_enabled"
    ON "quick_reply" ("tenant_code", "is_enabled", "sort_no");

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_speech.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 400 热线语音能力（阿里云 NLS ASR）
--
-- 背景：话务侧推 `recording` 事件时只给了录音地址，运营想复盘得自己点开听。
-- 这里给通话记录补上"转写文本"，录音回传后由 customer-service 调 supportflow-ai
-- 做一句话识别（ASR），把结果落库，页面上直接能读。
--
-- 为什么失败也要留痕：语音能力没配（或录音地址拉不到、音频太大）时，
-- 不能只写一条 warn 日志就算了——运营在页面上看到"没转写"时会问为什么，
-- asr_status + asr_hint 就是回答这个问题的（和"识别不了就如实说"一个口径）。
--
-- 可重复执行：列用 IF NOT EXISTS，约束先 drop 再 add。
-- ---------------------------------------------------------------------------

ALTER TABLE "channel_call_record" ADD COLUMN IF NOT EXISTS "asr_text" VARCHAR(2048) DEFAULT NULL;
ALTER TABLE "channel_call_record" ADD COLUMN IF NOT EXISTS "asr_status" SMALLINT DEFAULT NULL;
ALTER TABLE "channel_call_record" ADD COLUMN IF NOT EXISTS "asr_hint" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "channel_call_record" ADD COLUMN IF NOT EXISTS "asr_time" TIMESTAMP DEFAULT NULL;

ALTER TABLE "channel_call_record" DROP CONSTRAINT IF EXISTS "ck_channel_call_asr_status";
ALTER TABLE "channel_call_record"
    ADD CONSTRAINT "ck_channel_call_asr_status" CHECK ("asr_status" IS NULL OR "asr_status" IN (1,2));

COMMENT ON COLUMN "channel_call_record"."asr_text" IS '通话录音的语音识别文本（阿里云 NLS 一句话识别，空=还没转写）';
COMMENT ON COLUMN "channel_call_record"."asr_status" IS '转写状态：1-成功、2-失败/跳过（未配置语音能力 / 录音拉不到 / 音频过大）';
COMMENT ON COLUMN "channel_call_record"."asr_hint" IS '转写失败或跳过时的原因（没配语音能力、录音下载失败…），成功时为 NULL';
COMMENT ON COLUMN "channel_call_record"."asr_time" IS '转写完成时间（用来区分"还没转写"和"转写过了但没识别出内容"）';

-- 转写是在录音回传之后异步做的，按"有没有转写"查列表时用得上
CREATE INDEX IF NOT EXISTS "idx_channel_call_asr_status"
    ON "channel_call_record" ("tenant_code", "asr_status");

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_channel_unique.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 同一企业同一种渠道只能有一个「生效中」
--
-- 业务规则：一个企业账号，每种渠道（网站 / 公众号 / 小程序 / App / 400 / 邮件）
-- 同一时刻只能有一条 status=1（生效中）。允许同一个类型存在多条记录——
-- 历史渠道、待替换的渠道都可以是「已禁用」——但不能两条同时生效：
-- 那样"这个渠道到底走哪条"就说不清了，会话统计、技能组绑定、密钥都会对不上。
--
-- 应用层已经拦了一次（ChannelOnboardingService 在「创建」和「启用」两处校验），
-- 这里再加一道数据库兜底：并发创建时，应用层的"先查再写"挡不住两个请求同时通过。
--
-- 可重复执行。如果历史上已经出现重复的生效渠道，会先报出来（不自动改数据：
-- 停用哪一条是业务决策，脚本不该替人决定）。
-- ---------------------------------------------------------------------------

-- 先做一次友好检查：把"重复的租户 + 渠道类型"用一句能看懂的话报出来，
-- 而不是让人对着 "could not create unique index" 自己找。
-- （库上开了 FORCE RLS 时这条 SELECT 可能被策略过滤掉，那就退化成下面建索引时的原生报错，
--   同样会指出 (tenant_code, channel_type) 重复在哪一行。）
DO $$
DECLARE
  dup RECORD;
BEGIN
  SELECT tenant_code, channel_type, COUNT(*) AS n,
         string_agg(name || '（id=' || id || '，建于 ' || to_char(create_time, 'YYYY-MM-DD HH24:MI') || '）',
                    '、' ORDER BY create_time DESC) AS names
    INTO dup
    FROM "channel"
   WHERE "is_deleted" = FALSE AND "status" = 1
   GROUP BY tenant_code, channel_type
  HAVING COUNT(*) > 1
   ORDER BY COUNT(*) DESC, tenant_code
   LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION '同一企业同一种渠道有多个生效中：tenant_code=%，channel_type=%，共 % 条 —— %',
      dup.tenant_code, dup.channel_type, dup.n, dup.names
      USING HINT = '在控制台「渠道接入」里把不想用的那条「停用」（或 UPDATE channel SET status=2）；'
                   '每种类型只保留一条生效中，再重跑本脚本';
  END IF;
END $$;

-- 真正的闸门：部分唯一索引只约束"生效中且未删除"的行，
-- 已禁用 / 已删除的同类型渠道不受影响（历史记录照常留着）。
CREATE UNIQUE INDEX IF NOT EXISTS "uk_channel_active_type"
    ON "channel" ("tenant_code", "channel_type")
    WHERE "is_deleted" = FALSE AND "status" = 1;

COMMENT ON INDEX "uk_channel_active_type"
    IS '同一企业同一种渠道只能有一个生效中（status=1）；已禁用 / 已删除的不受约束';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_customer_channel.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 客户来源渠道列加宽（VARCHAR(20) → VARCHAR(64)）
--
-- 为什么：访客建档时，customer.channel 写的是**渠道名**（SessionService 里写
-- channel.getName()），而渠道名最长 64 字符。邮件渠道的名字常常直接是收信邮箱地址
-- （service@xinghai-edu.example.com，30 字符），一插就报：
--     ERROR: value too long for type character varying(20)
-- 用户从官网/公众号进来时通常没事（名字短），一旦走邮件或名字起得长就必炸——
-- 属于"平时看不出来、上线才踩"的那种坑，所以这里把列宽对齐到 channel.name 的 64，
-- 并在 SchemaGuard 里加一条列宽自检（缺了自动补，避免老库上继续报这个错）。
--
-- 可重复执行：改到同样的类型是空操作。
-- ---------------------------------------------------------------------------

ALTER TABLE "customer" ALTER COLUMN "channel" TYPE VARCHAR(64);

COMMENT ON COLUMN "customer"."channel"
    IS '来源渠道（建档时写入渠道名快照，长度与 channel.name 对齐，最长 64 字符）';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_session_source.sql
-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 会话来源列加宽（VARCHAR(20) → VARCHAR(64)）
--
-- 和 customer.channel 是**同一个根因**：开会话时 session.source 写的是渠道名
-- （SessionService 里 .source(channel.getName())），而渠道名最长 64 字符——
-- 邮件渠道的名字常常直接是收信邮箱地址（service@xinghai-edu.example.com，31 字符）。
--
--      ERROR: value too long for type character varying(20)
--      ### The error may exist in io/supportflow/customer/mapper/SessionMapper.java
--
-- 上次只修了 customer.channel，漏了这一列，所以"客户能建、会话建不出来"。
-- 这次把两个列都对齐到 channel.name 的 64，并在 SchemaGuard 里各加一条列宽自检。
--
-- 可重复执行：改到同样的类型是空操作。
-- ---------------------------------------------------------------------------

ALTER TABLE "session" ALTER COLUMN "source" TYPE VARCHAR(64);

COMMENT ON COLUMN "session"."source"
    IS '会话来源（建档时写入渠道名快照，长度与 channel.name 对齐，最长 64 字符）';

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_session_message_client_no.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_tenant_upgrade.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 增量：schema/customer_db_rls.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · customer_db 行级安全（RLS）脚本（PostgreSQL 17）
-- 用途：应用层已经强制带 tenant_code 条件（第一道防线），这一层是**数据库兜底**：
--       即使某条 SQL 漏写 WHERE tenant_code = ?，数据库也不会把别的企业的行交出去。
--
-- 幂等：可重复执行（DROP POLICY IF EXISTS + CREATE POLICY，表清单自动扫描）。
--
-- 三个关键约定（读代码前先看这三条）：
--   1. **会话变量 app.tenant_code**：应用每次拿连接时执行
--      `select set_config('app.tenant_code', '<租户>', false)`（见 customer-service 的 TenantRlsConfig）。
--   2. **上下文为空则放行**：迁移脚本、psql 手工排查、平台内部任务不会设置它，
--      策略里对"未设置 / 空 / PLATFORM"放行——否则运维动作会被自己的兜底挡住。
--   3. **FORCE ROW LEVEL SECURITY**：PG 默认对表 owner 不生效（owner 能绕过 RLS），
--      不 FORCE 的话这条兜底形同虚设。生产更彻底的做法是给应用单独建一个
--      **非 owner 角色**并只给它 DML 权限，这里按"用同一个库账号"的现状写成 FORCE。

-- ---------------------------------------------------------------------------
-- 1. 给所有带 tenant_code 的业务表开启 RLS + 兜底策略
--    表清单自动扫描（新表加了 tenant_code 之后，重跑本脚本就会自动覆盖）
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN
        SELECT c.relname AS table_name
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind = 'r'
           AND EXISTS (
                 SELECT 1
                   FROM information_schema.columns col
                  WHERE col.table_schema = 'public'
                    AND col.table_name = c.relname
                    AND col.column_name = 'tenant_code')
         ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.table_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t.table_name);
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I',
                       t.table_name || '_tenant_isolation', t.table_name);
        EXECUTE format(
            'CREATE POLICY %I ON public.%I USING ('
            '    NULLIF(current_setting(''app.tenant_code'', true), '''') IS NULL'      -- 未设置上下文：迁移/运维
            ' OR NULLIF(current_setting(''app.tenant_code'', true), '''') = ''PLATFORM'''  -- 平台账号受控跨租户
            ' OR "tenant_code" = NULLIF(current_setting(''app.tenant_code'', true), '''')' -- 正常业务请求
            ')',
            t.table_name || '_tenant_isolation', t.table_name);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 2. 策略状态视图：平台侧「租户管理」与验证脚本都读它
--    （只暴露"哪些表开了 RLS、有没有策略"，不含业务数据）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW "tenant_rls_status" AS
SELECT c.relname                                AS table_name,
       c.relrowsecurity                         AS rls_enabled,
       c.relforcerowsecurity                    AS rls_forced,
       (SELECT count(1)
          FROM pg_policies p
         WHERE p.schemaname = 'public'
           AND p.tablename = c.relname)         AS policy_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relkind = 'r'
   AND EXISTS (
         SELECT 1
           FROM information_schema.columns col
          WHERE col.table_schema = 'public'
            AND col.table_name = c.relname
            AND col.column_name = 'tenant_code');

COMMENT ON VIEW "tenant_rls_status" IS '行级安全策略状态（哪些表开了 RLS / FORCE / 有几条策略）';

-- ---------------------------------------------------------------------------
-- 3. 自检：跑完顺手看一眼（不想看可以忽略，不影响执行）
--    psql -c "select count(*) filter (where rls_enabled and rls_forced) as protected,
--                    count(*) as total from tenant_rls_status"
-- ---------------------------------------------------------------------------

\connect ops_db

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

\connect tenant_db

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

\connect user_db

-- ===========================================================================
-- 库：user_db   （基线 schema/user_db.sql）
-- ===========================================================================
-- SupportFlow 智能客服平台 · user_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "sys_user" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16)  NOT NULL,
  "user_no" VARCHAR(32)  NOT NULL,
  "name" VARCHAR(64)  NOT NULL,
  "phone" VARCHAR(20)  DEFAULT NULL,
  "email" VARCHAR(128) DEFAULT NULL,
  "password" VARCHAR(128) DEFAULT NULL,
  "avatar" VARCHAR(512) DEFAULT NULL,
  "user_type" SMALLINT  NOT NULL,
  "status" SMALLINT  NOT NULL DEFAULT 1,
  "last_login_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64)  DEFAULT NULL,
  "editor" VARCHAR(64)  DEFAULT NULL,
  "is_deleted" BOOLEAN   NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_user_user_type CHECK ("user_type" IN (1,2)),
  CONSTRAINT ck_sys_user_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_user_no" UNIQUE ("user_no")
);
COMMENT ON TABLE "sys_user" IS '用户表';
COMMENT ON COLUMN "sys_user"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_user"."tenant_code" IS '主归属租户；平台账号为 PLATFORM，新注册企业账号审核开通前挂 PLATFORM，开通后回填正式 T 编码';
COMMENT ON COLUMN "sys_user"."user_no" IS '用户编号（对外）';
COMMENT ON COLUMN "sys_user"."name" IS '姓名';
COMMENT ON COLUMN "sys_user"."phone" IS '手机号（脱敏存储）';
COMMENT ON COLUMN "sys_user"."email" IS '邮箱';
COMMENT ON COLUMN "sys_user"."password" IS '密码哈希（BCrypt），SSO 账号可为空';
COMMENT ON COLUMN "sys_user"."avatar" IS '头像URL';
COMMENT ON COLUMN "sys_user"."user_type" IS '类型码：1-平台账号、2-企业账号';
COMMENT ON COLUMN "sys_user"."status" IS '状态码：1-正常、2-停用、3-锁定';
COMMENT ON COLUMN "sys_user"."last_login_time" IS '最近登录时间';
CREATE INDEX "idx_sys_user_tenant_status" ON "sys_user" ("tenant_code", "status");

CREATE TABLE "tenant_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "join_time" TIMESTAMP DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user" UNIQUE ("tenant_code", "user_id")
);
COMMENT ON TABLE "tenant_member" IS '企业成员关系表';
COMMENT ON COLUMN "tenant_member"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "tenant_member"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "tenant_member"."user_id" IS '用户ID';
COMMENT ON COLUMN "tenant_member"."join_time" IS '加入时间';
COMMENT ON COLUMN "tenant_member"."status" IS '状态码：1-在职、2-已退出';
CREATE INDEX "idx_tenant_member_user_id" ON "tenant_member" ("user_id");

CREATE TABLE "sys_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_code" VARCHAR(64) NOT NULL,
  "role_name" VARCHAR(64) NOT NULL,
  "role_type" SMALLINT NOT NULL,
  "is_fixed" BOOLEAN  NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN  NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_role_role_type CHECK ("role_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_role_code" UNIQUE ("tenant_code", "role_code")
);
COMMENT ON TABLE "sys_role" IS '角色表';
COMMENT ON COLUMN "sys_role"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_role"."tenant_code" IS '租户编码（平台角色为 PLATFORM）';
COMMENT ON COLUMN "sys_role"."role_code" IS '角色编码';
COMMENT ON COLUMN "sys_role"."role_name" IS '角色名称';
COMMENT ON COLUMN "sys_role"."role_type" IS '类型码：1-平台角色、2-企业角色';
COMMENT ON COLUMN "sys_role"."is_fixed" IS '是否内置固定角色';
COMMENT ON COLUMN "sys_role"."is_enabled" IS '是否启用';
COMMENT ON COLUMN "sys_role"."remark" IS '备注';

CREATE TABLE "sys_permission" (
  "id" BIGINT NOT NULL,
  "perm_code" VARCHAR(64)  NOT NULL,
  "perm_name" VARCHAR(64)  NOT NULL,
  "perm_type" SMALLINT  NOT NULL,
  "parent_id" BIGINT DEFAULT 0,
  "sort_no" INT DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_permission_perm_type CHECK ("perm_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_perm_code" UNIQUE ("perm_code")
);
COMMENT ON TABLE "sys_permission" IS '权限点/菜单表';
COMMENT ON COLUMN "sys_permission"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_permission"."perm_code" IS '权限编码（对应菜单/功能）';
COMMENT ON COLUMN "sys_permission"."perm_name" IS '权限名称';
COMMENT ON COLUMN "sys_permission"."perm_type" IS '类型码：1-菜单、2-按钮、3-接口';
COMMENT ON COLUMN "sys_permission"."parent_id" IS '父权限ID';
COMMENT ON COLUMN "sys_permission"."sort_no" IS '排序';

CREATE TABLE "sys_role_permission" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_id" BIGINT NOT NULL,
  "perm_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_role_perm" UNIQUE ("role_id", "perm_id")
);
COMMENT ON TABLE "sys_role_permission" IS '角色权限关联表';
COMMENT ON COLUMN "sys_role_permission"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_role_permission"."tenant_code" IS '租户编码（平台角色为 PLATFORM）';
COMMENT ON COLUMN "sys_role_permission"."role_id" IS '角色ID';
COMMENT ON COLUMN "sys_role_permission"."perm_id" IS '权限ID';
CREATE INDEX "idx_sys_role_permission_perm_id" ON "sys_role_permission" ("perm_id");

CREATE TABLE "sys_user_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user_role" UNIQUE ("tenant_code", "user_id", "role_id")
);
COMMENT ON TABLE "sys_user_role" IS '用户角色关联表';
COMMENT ON COLUMN "sys_user_role"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_user_role"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "sys_user_role"."user_id" IS '用户ID';
COMMENT ON COLUMN "sys_user_role"."role_id" IS '角色ID';
CREATE INDEX "idx_sys_user_role_role_id" ON "sys_user_role" ("role_id");

CREATE TABLE "login_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "login_type" SMALLINT NOT NULL,
  "ip" VARCHAR(64) DEFAULT NULL,
  "device" VARCHAR(128) DEFAULT NULL,
  "browser" VARCHAR(64) DEFAULT NULL,
  "region" VARCHAR(64) DEFAULT NULL,
  "result" SMALLINT NOT NULL,
  "login_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_login_log_login_type CHECK ("login_type" IN (1,2,3)),
  CONSTRAINT ck_login_log_result CHECK ("result" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "login_log" IS '登录记录表';
COMMENT ON COLUMN "login_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "login_log"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "login_log"."user_id" IS '用户ID';
COMMENT ON COLUMN "login_log"."login_type" IS '登录方式码：1-密码、2-企业微信SSO、3-飞书SSO';
COMMENT ON COLUMN "login_log"."ip" IS '登录IP';
COMMENT ON COLUMN "login_log"."device" IS '设备信息';
COMMENT ON COLUMN "login_log"."browser" IS '浏览器';
COMMENT ON COLUMN "login_log"."region" IS '登录地区';
COMMENT ON COLUMN "login_log"."result" IS '结果码：1-成功、2-失败、3-异常';
COMMENT ON COLUMN "login_log"."login_time" IS '登录时间';
CREATE INDEX "idx_login_log_tenant_user_time" ON "login_log" ("tenant_code", "user_id", "login_time");

CREATE TABLE "member_invite" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "invite_code" VARCHAR(32) NOT NULL,
  "inviter_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "invitee_phone" VARCHAR(20) DEFAULT NULL,
  "invitee_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "used_by" BIGINT DEFAULT NULL,
  "used_time" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_member_invite_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_member_invite_code" UNIQUE ("tenant_code", "invite_code")
);
COMMENT ON TABLE "member_invite" IS '成员邀请表';
COMMENT ON COLUMN "member_invite"."invite_code" IS '邀请码';
COMMENT ON COLUMN "member_invite"."inviter_id" IS '邀请人用户ID';
COMMENT ON COLUMN "member_invite"."role_id" IS '受邀默认角色ID';
COMMENT ON COLUMN "member_invite"."status" IS '状态码：1-有效、2-已使用、3-已失效';
COMMENT ON COLUMN "member_invite"."used_by" IS '使用人用户ID';
COMMENT ON COLUMN "member_invite"."used_time" IS '使用时间';
COMMENT ON COLUMN "member_invite"."expire_time" IS '过期时间';
CREATE INDEX "idx_member_invite_tenant_status" ON "member_invite" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/user_db_member_invite.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · user_db 成员邀请增量脚本（PostgreSQL 17）
-- 用途：在已按注册/登录教程建库的本地环境补齐邀请客服成员所需表。
-- 幂等：可重复执行，不会与已存在的表冲突。

CREATE TABLE IF NOT EXISTS "tenant_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "join_time" TIMESTAMP DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user" UNIQUE ("tenant_code", "user_id")
);

CREATE TABLE IF NOT EXISTS "sys_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_code" VARCHAR(64) NOT NULL,
  "role_name" VARCHAR(64) NOT NULL,
  "role_type" SMALLINT NOT NULL,
  "is_fixed" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_role_role_type CHECK ("role_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_role_code" UNIQUE ("tenant_code", "role_code")
);

CREATE TABLE IF NOT EXISTS "sys_user_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user_role" UNIQUE ("tenant_code", "user_id", "role_id")
);

CREATE TABLE IF NOT EXISTS "member_invite" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "invite_code" VARCHAR(32) NOT NULL,
  "inviter_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "invitee_phone" VARCHAR(20) DEFAULT NULL,
  "invitee_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "used_by" BIGINT DEFAULT NULL,
  "used_time" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_member_invite_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_member_invite_code" UNIQUE ("tenant_code", "invite_code")
);

CREATE INDEX IF NOT EXISTS "idx_tenant_member_user_id" ON "tenant_member" ("user_id");
CREATE INDEX IF NOT EXISTS "idx_member_invite_tenant_status" ON "member_invite" ("tenant_code", "status");

-- ---------------------------------------------------------------------------
-- 增量：schema/user_db_platform.sql
-- ---------------------------------------------------------------------------
-- SupportFlow 智能客服平台 · user_db 平台账号中心与角色权限（PostgreSQL 17）
-- 用途：把「平台账号中心（用户管理）」与「平台角色 + 菜单权限」跑起来。
--
-- 说明：sys_role / sys_permission / sys_role_permission / sys_user_role 四张表在最初的
--      user_db.sql 里就建好了，但一直没人用。这里只补**数据**（权限目录 + 平台内置角色 +
--      角色权限绑定 + 给还没有角色的平台账号兜底绑定管理员角色），不改表结构。
--
-- 幂等：可重复执行（统一用 ON CONFLICT DO NOTHING，避免 PK 与唯一键两种冲突）。
--
-- 权限口径：perm_code 直接等于**前端路由 path**（如 /platform/tenants）。
--   这样前端菜单只要拿 route.path 去比对就能决定显不显示，不用再维护一张映射表，
--   也不会出现「权限改了、菜单没跟着改」的错位。perm_type：1-菜单、2-按钮、3-接口
--   （本期只落菜单，按钮/接口级权限预留）。

-- ---------------------------------------------------------------------------
-- 1. 平台菜单权限目录
-- ---------------------------------------------------------------------------
INSERT INTO "sys_permission" ("id", "perm_code", "perm_name", "perm_type", "parent_id",
                              "sort_no", "is_enabled", "creator")
VALUES
  (960000000000000001, '/admin/reviews',     '企业审核',   1, 0, 10, TRUE, 'system'),
  (960000000000000002, '/platform/support',  '支持工单',   1, 0, 20, TRUE, 'system'),
  (960000000000000003, '/platform/tenants',  '租户管理',   1, 0, 30, TRUE, 'system'),
  (960000000000000004, '/platform/billing',  '计费与账单', 1, 0, 40, TRUE, 'system'),
  (960000000000000005, '/platform/payments', '收款流水',   1, 0, 50, TRUE, 'system'),
  (960000000000000006, '/platform/users',    '用户管理',   1, 0, 60, TRUE, 'system'),
  (960000000000000007, '/platform/roles',    '角色权限',   1, 0, 70, TRUE, 'system'),
  (960000000000000008, '/platform/audit',    '审计日志',   1, 0, 80, TRUE, 'system'),
  (960000000000000009, '/platform/settings', '平台设置',   1, 0, 90, TRUE, 'system'),
  (960000000000000010, '/platform/sitecfg',  '官网配置',   1, 0, 100, TRUE, 'system'),
  (960000000000000011, '/platform/analytics','平台数据',   1, 0, 110, TRUE, 'system'),
  (960000000000000012, '/platform/aimon',    'AI 监控',    1, 0, 120, TRUE, 'system'),
  (960000000000000013, '/platform/cs',       '客户成功',   1, 0, 130, TRUE, 'system'),
  (960000000000000014, '/platform/sec',      '安全中心',   1, 0, 140, TRUE, 'system'),
  (960000000000000015, '/platform/observe',  '可观测',     1, 0, 150, TRUE, 'system')
ON CONFLICT DO NOTHING;

-- 名字/排序可能被改过，这里只把「权限名称与排序」对齐（不动启用状态，运营可以自己停用）
UPDATE "sys_permission" p
   SET "perm_name" = v.perm_name,
       "sort_no"   = v.sort_no,
       "is_enabled" = TRUE,
       "update_time" = CURRENT_TIMESTAMP
  FROM (VALUES
        ('/admin/reviews',     '企业审核',   10),
        ('/platform/support',  '支持工单',   20),
        ('/platform/tenants',  '租户管理',   30),
        ('/platform/billing',  '计费与账单', 40),
        ('/platform/payments', '收款流水',   50),
        ('/platform/users',    '用户管理',   60),
        ('/platform/roles',    '角色权限',   70),
        ('/platform/audit',    '审计日志',   80),
        ('/platform/settings', '平台设置',   90),
        ('/platform/sitecfg',  '官网配置',   100),
        ('/platform/analytics','平台数据',   110),
        ('/platform/aimon',    'AI 监控',    120),
        ('/platform/cs',       '客户成功',   130),
        ('/platform/sec',      '安全中心',   140),
        ('/platform/observe',  '可观测',     150)
       ) AS v(perm_code, perm_name, sort_no)
 WHERE p."perm_code" = v.perm_code
   AND p."is_deleted" = FALSE;

-- ---------------------------------------------------------------------------
-- 2. 平台内置角色（tenant_code 固定 PLATFORM，role_type=1 平台角色）
-- ---------------------------------------------------------------------------
INSERT INTO "sys_role" ("id", "tenant_code", "role_code", "role_name", "role_type",
                        "is_fixed", "is_enabled", "remark", "creator")
VALUES
  (970000000000000001, 'PLATFORM', 'PLATFORM_ADMIN',   '平台管理员', 1, TRUE, TRUE,
   '内置：拥有全部平台菜单权限', 'system'),
  (970000000000000002, 'PLATFORM', 'PLATFORM_OPS',     '平台运营',   1, FALSE, TRUE,
   '企业审核 / 支持工单 / 租户管理 / 用户管理', 'system'),
  (970000000000000003, 'PLATFORM', 'PLATFORM_FINANCE', '平台财务',   1, FALSE, TRUE,
   '计费与账单 / 收款流水', 'system')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 角色权限绑定
-- ---------------------------------------------------------------------------
-- 平台管理员 = 全部权限：**先清后插**（不是"插一次就不管"）。
--
-- 为什么必须这么写：绑定行的主键原来是按 row_number 生成的固定 ID，后来加了一条新权限，
-- 排序一变，所有 ID 都错位；重跑脚本时 PK 冲突被 ON CONFLICT DO NOTHING 吞掉，
-- 新权限就永远绑不上去（表现为"内置管理员少了刚加的那个菜单"）。
-- 内置角色的权限本来就不可编辑（PlatformRoleService 里明确拒绝改内置角色权限），
-- 所以这里每次都同步成"全集"是正确语义，也顺带解决了新增权限自动跟进。
DELETE FROM "sys_role_permission"
 WHERE "role_id" IN (SELECT "id" FROM "sys_role"
                      WHERE "tenant_code" = 'PLATFORM' AND "role_code" = 'PLATFORM_ADMIN');

INSERT INTO "sys_role_permission" ("id", "tenant_code", "role_id", "perm_id")
SELECT 980000000000000000 + row_number() OVER (ORDER BY p."sort_no", p."id"), 'PLATFORM', r."id", p."id"
  FROM "sys_role" r
  JOIN "sys_permission" p ON p."is_deleted" = FALSE AND p."is_enabled" = TRUE
 WHERE r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_ADMIN';

-- 平台运营 / 平台财务：**只在"这个角色一条权限都没有"时初始化**。
-- 这两个不是内置角色，运营可以在页面上改权限；脚本不该把人家改过的权限又刷回去，
-- 所以只在首次（还是空的）时给一套默认值，之后完全尊重页面上的配置。
INSERT INTO "sys_role_permission" ("id", "tenant_code", "role_id", "perm_id")
SELECT 981000000000000000 + row_number() OVER (ORDER BY p."perm_code"), 'PLATFORM', r."id", p."id"
  FROM "sys_role" r
  JOIN "sys_permission" p ON p."is_deleted" = FALSE
   AND p."perm_code" IN ('/admin/reviews', '/platform/support', '/platform/tenants',
                         '/platform/users', '/platform/sitecfg', '/platform/analytics',
                         '/platform/aimon', '/platform/cs', '/platform/sec', '/platform/observe')
 WHERE r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_OPS'
   AND NOT EXISTS (SELECT 1 FROM "sys_role_permission" x WHERE x."role_id" = r."id")
ON CONFLICT DO NOTHING;

INSERT INTO "sys_role_permission" ("id", "tenant_code", "role_id", "perm_id")
SELECT 982000000000000000 + row_number() OVER (ORDER BY p."perm_code"), 'PLATFORM', r."id", p."id"
  FROM "sys_role" r
  JOIN "sys_permission" p ON p."is_deleted" = FALSE
   AND p."perm_code" IN ('/platform/billing', '/platform/payments')
 WHERE r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_FINANCE'
   AND NOT EXISTS (SELECT 1 FROM "sys_role_permission" x WHERE x."role_id" = r."id")
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 兜底：还没有任何平台角色的平台账号，绑到「平台管理员」
--    为什么要有这一步：菜单是按权限过滤的，如果历史平台账号没有角色，
--    一升级上来就会「一个菜单都看不到」——把自己锁在门外。
--    这条只对「当前没有任何角色」的账号生效，已经分好角色的不会被改。
-- ---------------------------------------------------------------------------
INSERT INTO "sys_user_role" ("id", "tenant_code", "user_id", "role_id")
SELECT 990000000000000000 + row_number() OVER (ORDER BY u."id"), 'PLATFORM', u."id", r."id"
  FROM "sys_user" u
  JOIN "sys_role" r ON r."tenant_code" = 'PLATFORM' AND r."role_code" = 'PLATFORM_ADMIN'
 WHERE u."user_type" = 1
   AND u."is_deleted" = FALSE
   AND NOT EXISTS (SELECT 1 FROM "sys_user_role" ur
                    WHERE ur."user_id" = u."id" AND ur."tenant_code" = 'PLATFORM')
ON CONFLICT DO NOTHING;
