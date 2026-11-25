\connect customer_db
BEGIN;

DO $$
BEGIN
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='supportflow_kb_stage_maintenance') THEN
   CREATE ROLE supportflow_kb_stage_maintenance NOLOGIN;
 END IF;
 IF EXISTS (SELECT FROM pg_roles r WHERE r.rolname='supportflow_kb_stage_maintenance' AND
    (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication
     OR EXISTS (SELECT FROM pg_auth_members WHERE member=r.oid)
     OR EXISTS (SELECT FROM pg_class WHERE relowner=r.oid)
     OR EXISTS (SELECT FROM pg_database WHERE datdba=r.oid)
     OR EXISTS (SELECT FROM pg_namespace WHERE nspowner=r.oid))) THEN
   RAISE EXCEPTION 'KB stage maintenance capability must be non-login, non-owner and non-administrative';
 END IF;
END $$;

-- Existing staging timestamps used the customer database's session timezone.
ALTER TABLE public.kb_chunk_stage ALTER COLUMN create_time TYPE timestamptz
    USING create_time AT TIME ZONE current_setting('TimeZone');

CREATE FUNCTION public.supportflow_expired_kb_stage_batch(p_tenant text, p_limit integer, p_apply boolean)
RETURNS bigint LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET row_security = off AS $$
DECLARE
 affected bigint;
 cutoff timestamptz := statement_timestamp() - interval '24 hours';
BEGIN
 IF p_tenant IS NULL OR p_tenant !~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' OR upper(p_tenant)='PLATFORM'
    OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 500 OR p_apply IS NULL THEN
   RAISE EXCEPTION 'Invalid KB stage maintenance scope' USING ERRCODE='22023';
 END IF;

 IF NOT p_apply THEN
   SELECT count(*) INTO affected FROM (
     SELECT 1 FROM public.kb_chunk_stage s
      WHERE s.tenant_code=p_tenant AND s.create_time<cutoff
      ORDER BY s.create_time,s.doc_id,s.attempt_id,s.chunk_no LIMIT p_limit
   ) AS preview;
   RETURN affected;
 END IF;

 WITH eligible AS MATERIALIZED (
   SELECT s.doc_id,s.attempt_id,s.chunk_no FROM public.kb_chunk_stage s
    WHERE s.tenant_code=p_tenant AND s.create_time<cutoff
    ORDER BY s.create_time,s.doc_id,s.attempt_id,s.chunk_no LIMIT p_limit FOR UPDATE OF s SKIP LOCKED
 )
 DELETE FROM public.kb_chunk_stage s USING eligible e
  WHERE s.tenant_code=p_tenant AND (s.doc_id,s.attempt_id,s.chunk_no)=(e.doc_id,e.attempt_id,e.chunk_no);
 GET DIAGNOSTICS affected = ROW_COUNT;
 RETURN affected;
END $$;

REVOKE ALL ON FUNCTION public.supportflow_expired_kb_stage_batch(text,integer,boolean)
    FROM PUBLIC,supportflow_customer_tenant,supportflow_kb_tenant;
GRANT EXECUTE ON FUNCTION public.supportflow_expired_kb_stage_batch(text,integer,boolean)
    TO supportflow_kb_stage_maintenance;
COMMIT;
