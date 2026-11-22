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

CREATE TABLE public.agent_model_usage (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 run_id uuid NOT NULL,
 step_no integer NOT NULL CHECK(step_no BETWEEN 1 AND 100),
 fence bigint NOT NULL CHECK(fence>0),
 intent_hash char(64) NOT NULL CHECK(intent_hash ~ '^[0-9a-f]{64}$'),
 payload_id uuid NOT NULL,
 payload_hash char(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 input_tokens bigint NOT NULL CHECK(input_tokens>=0),
 chat_output_tokens bigint CHECK(chat_output_tokens>=0),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id,step_no),
 FOREIGN KEY(tenant_code,run_id,step_no) REFERENCES public.agent_step(tenant_code,run_id,step_no)
);
ALTER TABLE public.agent_model_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_model_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_model_usage_tenant ON public.agent_model_usage TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_model_usage_guard ON public.agent_model_usage AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_model_usage TO supportflow_agent_runtime;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_model_usage FROM supportflow_agent_runtime;

COMMIT;
