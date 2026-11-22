\connect ai_db
BEGIN;

DO $$ BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='supportflow_agent_runtime') OR EXISTS(
   SELECT FROM pg_roles r WHERE r.rolname='supportflow_agent_runtime' AND
     (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication
      OR EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid)
      OR EXISTS(SELECT FROM pg_class WHERE relowner=r.oid)
      OR EXISTS(SELECT FROM pg_database WHERE datdba=r.oid)
      OR EXISTS(SELECT FROM pg_namespace WHERE nspowner=r.oid))) THEN
   RAISE EXCEPTION 'Agent runtime capability must be non-login, non-owner and non-administrative';
 END IF;
END $$;

CREATE TABLE public.agent_model_quota (
 tenant_code varchar(16) PRIMARY KEY CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 max_active_steps integer NOT NULL CHECK(max_active_steps BETWEEN 0 AND 100)
);
ALTER TABLE public.agent_model_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_model_quota FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_model_quota_tenant ON public.agent_model_quota FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_model_quota_guard ON public.agent_model_quota AS RESTRICTIVE FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT ON public.agent_model_quota TO supportflow_agent_runtime;

CREATE INDEX agent_step_active_model_quota_idx ON public.agent_step(tenant_code)
 WHERE kind='MODEL' AND state='RESERVED';

COMMIT;
