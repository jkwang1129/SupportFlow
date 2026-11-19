-- SupportFlow 智能客服平台 · customer_db 行级安全（RLS）脚本（PostgreSQL 17）
-- 用途：应用层已经强制带 tenant_code 条件（第一道防线），这一层是**数据库兜底**：
--       即使某条 SQL 漏写 WHERE tenant_code = ?，数据库也不会把别的企业的行交出去。
--
-- 幂等：可重复执行（DROP POLICY IF EXISTS + CREATE POLICY，表清单自动扫描）。
--
-- Empty/invalid/PLATFORM scopes never authorize runtime access. Platform operations use a separate role.
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
            '    "tenant_code" = NULLIF(current_setting(''app.tenant_code'', true), '''')'
            ' AND "tenant_code" <> ''PLATFORM'''
            ' AND "tenant_code" ~ ''^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$'''
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
