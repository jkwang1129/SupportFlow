\connect customer_db
BEGIN;
ALTER TABLE public.agent_source_snapshot ADD CONSTRAINT agent_source_result_ref UNIQUE(tenant_code,event_id,run_id,snapshot_id);
ALTER TABLE public.session_message ADD CONSTRAINT session_message_tenant_result_ref UNIQUE(tenant_code,id);
CREATE TABLE public.agent_result_receipt (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 run_id uuid NOT NULL, event_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 arguments_sha256 char(64) NOT NULL CHECK(arguments_sha256 ~ '^[0-9a-f]{64}$'),
 operation varchar(32) NOT NULL CHECK(operation='session.reply'),
 schema_version varchar(16) NOT NULL CHECK(schema_version='reply-v1'),
 step_key varchar(64) NOT NULL CHECK(step_key ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'),
 step_no bigint NOT NULL CHECK(step_no BETWEEN 1 AND 100), fence bigint NOT NULL CHECK(fence>0),
 message_id bigint NOT NULL, message_no varchar(64) NOT NULL, message_seq bigint NOT NULL CHECK(message_seq>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id), UNIQUE(tenant_code,event_id), UNIQUE(tenant_code,message_id),
 FOREIGN KEY(tenant_code,event_id,run_id,snapshot_id) REFERENCES public.agent_source_snapshot(tenant_code,event_id,run_id,snapshot_id),
 FOREIGN KEY(tenant_code,message_id) REFERENCES public.session_message(tenant_code,id)
);
ALTER TABLE public.agent_result_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_result_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_result_tenant ON public.agent_result_receipt TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_result_guard ON public.agent_result_receipt AS RESTRICTIVE TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_result_receipt TO supportflow_customer_tenant;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_result_receipt FROM supportflow_customer_tenant;
COMMIT;
