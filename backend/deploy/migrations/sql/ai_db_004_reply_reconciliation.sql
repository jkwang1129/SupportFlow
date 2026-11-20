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
CREATE TABLE public.agent_reply_reconciliation (
 tenant_code varchar(16) NOT NULL CHECK (tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 run_id uuid NOT NULL,
 step_no integer NOT NULL CHECK(step_no>0),
 observed_fence bigint NOT NULL CHECK(observed_fence>0),
 source_event_id uuid NOT NULL,
 snapshot_id uuid NOT NULL,
 arguments_sha256 char(64) NOT NULL CHECK(arguments_sha256 ~ '^[0-9a-f]{64}$'),
 intent_hash char(64) NOT NULL CHECK(intent_hash ~ '^[0-9a-f]{64}$'),
 message_id bigint NOT NULL CHECK(message_id>0),
 message_no varchar(64) NOT NULL CHECK(message_no ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'),
 message_seq bigint NOT NULL CHECK(message_seq>0),
 operator_ref varchar(64) NOT NULL CHECK(operator_ref ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'),
 original_error_code varchar(64) NOT NULL CHECK(original_error_code='SIDE_EFFECT_UNKNOWN'),
 original_step_result jsonb NOT NULL CHECK(jsonb_typeof(original_step_result)='object' AND octet_length(original_step_result::text)<=4096),
 confirmed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,step_no) REFERENCES public.agent_step(tenant_code,run_id,step_no)
);
ALTER TABLE public.agent_reply_reconciliation ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_reply_reconciliation FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_reply_reconciliation_tenant ON public.agent_reply_reconciliation TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_reply_reconciliation_guard ON public.agent_reply_reconciliation AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_reply_reconciliation TO supportflow_agent_runtime;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_reply_reconciliation FROM supportflow_agent_runtime;
COMMIT;
