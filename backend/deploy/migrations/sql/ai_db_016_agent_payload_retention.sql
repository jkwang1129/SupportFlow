\connect ai_db
BEGIN;

DO $$
BEGIN
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='supportflow_payload_maintenance') THEN
   CREATE ROLE supportflow_payload_maintenance NOLOGIN;
 END IF;
 IF EXISTS (SELECT FROM pg_roles r WHERE r.rolname='supportflow_payload_maintenance' AND
    (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication
     OR EXISTS (SELECT FROM pg_auth_members WHERE member=r.oid)
     OR EXISTS (SELECT FROM pg_class WHERE relowner=r.oid)
     OR EXISTS (SELECT FROM pg_database WHERE datdba=r.oid)
     OR EXISTS (SELECT FROM pg_namespace WHERE nspowner=r.oid))) THEN
   RAISE EXCEPTION 'Payload maintenance capability must be non-login, non-owner and non-administrative';
 END IF;
END $$;

CREATE FUNCTION public.supportflow_expired_payload_batch(p_tenant text, p_limit integer, p_apply boolean)
RETURNS bigint LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET row_security = off AS $$
DECLARE
 affected bigint;
 cutoff timestamptz := statement_timestamp();
BEGIN
 IF p_tenant IS NULL OR p_tenant !~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' OR upper(p_tenant)='PLATFORM'
    OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 500 OR p_apply IS NULL THEN
   RAISE EXCEPTION 'Invalid payload maintenance scope' USING ERRCODE='22023';
 END IF;

 IF NOT p_apply THEN
   SELECT count(*) INTO affected FROM (
     SELECT 1 FROM public.agent_payload p JOIN public.agent_run r USING (tenant_code,run_id)
      WHERE p.tenant_code=p_tenant AND p.expires_at<=cutoff
        AND r.state IN ('SUCCEEDED','FAILED','CANCELLED') AND r.finished_at<=cutoff
        AND r.error_code IS DISTINCT FROM 'SIDE_EFFECT_UNKNOWN'
      ORDER BY p.expires_at,p.payload_id LIMIT p_limit
   ) AS preview;
   RETURN affected;
 END IF;

 WITH eligible AS MATERIALIZED (
   SELECT p.tenant_code,p.payload_id FROM public.agent_payload p JOIN public.agent_run r USING (tenant_code,run_id)
    WHERE p.tenant_code=p_tenant AND p.expires_at<=cutoff
      AND r.state IN ('SUCCEEDED','FAILED','CANCELLED') AND r.finished_at<=cutoff
      AND r.error_code IS DISTINCT FROM 'SIDE_EFFECT_UNKNOWN'
    ORDER BY p.expires_at,p.payload_id LIMIT p_limit FOR UPDATE OF r,p SKIP LOCKED
 )
 DELETE FROM public.agent_payload p USING eligible e
  WHERE p.tenant_code=e.tenant_code AND p.payload_id=e.payload_id;
 GET DIAGNOSTICS affected = ROW_COUNT;
 RETURN affected;
END $$;

REVOKE ALL ON FUNCTION public.supportflow_expired_payload_batch(text,integer,boolean) FROM PUBLIC,supportflow_agent_runtime;
GRANT EXECUTE ON FUNCTION public.supportflow_expired_payload_batch(text,integer,boolean) TO supportflow_payload_maintenance;
COMMIT;
