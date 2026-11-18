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
