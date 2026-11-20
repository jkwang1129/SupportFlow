\connect customer_db
BEGIN;
ALTER TABLE public.agent_event_outbox ADD CONSTRAINT agent_event_run_receipt UNIQUE(tenant_code,event_id,run_id);
CREATE TABLE public.agent_source_snapshot (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 event_id uuid NOT NULL, run_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 schema_version varchar(16) NOT NULL CHECK(schema_version='source-v1'),
 payload_json text NOT NULL CHECK(octet_length(payload_json)<=65536 AND jsonb_typeof(payload_json::jsonb)='object'),
 payload_hash char(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '1 hour',
 PRIMARY KEY(tenant_code,event_id), UNIQUE(tenant_code,snapshot_id), CHECK(expires_at>created_at),
 FOREIGN KEY(tenant_code,event_id,run_id) REFERENCES public.agent_event_outbox(tenant_code,event_id,run_id)
);
CREATE INDEX agent_source_snapshot_expiry ON public.agent_source_snapshot(expires_at,tenant_code);
ALTER TABLE public.agent_source_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_source_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_source_tenant ON public.agent_source_snapshot TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_source_guard ON public.agent_source_snapshot AS RESTRICTIVE TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_source_snapshot TO supportflow_customer_tenant;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_source_snapshot FROM supportflow_customer_tenant;
COMMIT;
