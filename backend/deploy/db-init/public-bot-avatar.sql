-- Public brand assets only. Private chat media never uses this projection.
\connect customer_db
BEGIN;
CREATE OR REPLACE FUNCTION public.supportflow_public_bot_avatar(p_id bigint)
RETURNS TABLE(file_name varchar, mime_type varchar, object_key varchar)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT f.file_name, f.mime_type, f.object_key
    FROM public.file_meta f
    WHERE f.id = p_id AND f.biz_type = 1 AND NOT f.is_deleted
      AND starts_with(f.object_key, 'bot/avatar/' || f.tenant_code || '/')
$$;
REVOKE ALL ON FUNCTION public.supportflow_public_bot_avatar(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.supportflow_public_bot_avatar(bigint) TO supportflow_customer_tenant;
COMMIT;
