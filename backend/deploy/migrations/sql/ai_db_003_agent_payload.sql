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
CREATE TABLE public.agent_payload (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 payload_id uuid NOT NULL,run_id uuid NOT NULL,step_no integer NOT NULL,fence bigint NOT NULL CHECK(fence>0),
 payload_schema varchar(32) NOT NULL CHECK(payload_schema IN ('model-output-v1','tool-output-v1','working-state-v1')),
 payload_json text NOT NULL CHECK(octet_length(payload_json)<=65536 AND jsonb_typeof(payload_json::jsonb)='object'),
 payload_hash char(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '7 days',
 PRIMARY KEY(tenant_code,payload_id),UNIQUE(tenant_code,run_id,step_no),CHECK(expires_at>created_at),
 FOREIGN KEY(tenant_code,run_id,step_no) REFERENCES public.agent_step(tenant_code,run_id,step_no)
);
CREATE INDEX agent_payload_expiry ON public.agent_payload(expires_at,tenant_code);
ALTER TABLE public.agent_payload ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_payload FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_payload_tenant ON public.agent_payload TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_payload_guard ON public.agent_payload AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_payload TO supportflow_agent_runtime;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_payload FROM supportflow_agent_runtime;
COMMIT;
