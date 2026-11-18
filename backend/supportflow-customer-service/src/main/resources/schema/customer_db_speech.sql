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
