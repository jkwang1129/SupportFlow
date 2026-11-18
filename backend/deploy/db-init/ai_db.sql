-- ai_db 初始化脚本（基线 + 增量），生成时间：2026-10-09 18:23
-- 由 backend/deploy/db-init/generate.sh 从仓库 schema/ 与 scripts/migrate-*.sh 机械拼接，请勿手改。
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
