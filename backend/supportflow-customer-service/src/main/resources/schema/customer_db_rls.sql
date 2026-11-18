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
