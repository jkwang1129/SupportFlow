-- Run with the schema/migration administrator on customer_db.
-- This function exposes only the tenant of one valid channel key.
\connect customer_db
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'customer_db' THEN
        RAISE EXCEPTION 'Apply channel lookup to customer_db only';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'supportflow_customer_tenant') THEN
        CREATE ROLE supportflow_customer_tenant NOLOGIN;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION public.supportflow_channel_tenant(p_app_key text)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT (SELECT min(k.tenant_code)::text
    FROM public.channel_key AS k
    JOIN public.channel AS c ON c.id = k.channel_id AND c.tenant_code = k.tenant_code
    WHERE length(p_app_key) BETWEEN 1 AND 64
      AND k.app_key = p_app_key AND k.status = 1 AND NOT k.is_deleted
      AND (k.expire_time IS NULL OR k.expire_time > CURRENT_TIMESTAMP)
      AND c.status = 1 AND NOT c.is_deleted
      AND k.tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$'
      AND upper(k.tenant_code) <> 'PLATFORM'
    HAVING count(*) = 1)
$$;

REVOKE ALL ON FUNCTION public.supportflow_channel_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.supportflow_channel_tenant(text) TO supportflow_customer_tenant;

CREATE OR REPLACE FUNCTION public.supportflow_wechat_tenant(p_app_id text)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT (SELECT min(w.tenant_code)::text
    FROM public.channel_wechat_config AS w
    JOIN public.channel AS c ON c.id = w.channel_id AND c.tenant_code = w.tenant_code
    WHERE length(p_app_id) BETWEEN 1 AND 64 AND w.app_id = p_app_id
      AND w.status = 1 AND NOT w.is_deleted AND c.status = 1 AND NOT c.is_deleted
      AND c.channel_type IN (2, 3)
      AND w.tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$'
      AND upper(w.tenant_code) <> 'PLATFORM'
    HAVING count(*) = 1)
$$;
REVOKE ALL ON FUNCTION public.supportflow_wechat_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.supportflow_wechat_tenant(text) TO supportflow_customer_tenant;
COMMIT;
