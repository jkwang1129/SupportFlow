\connect customer_db
BEGIN;
CREATE TABLE IF NOT EXISTS public.agent_event_outbox (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 event_id uuid NOT NULL, message_id bigint NOT NULL REFERENCES public.session_message(id),
 session_id bigint NOT NULL REFERENCES public.session(id), session_no varchar(64) NOT NULL,
 event_seq bigint NOT NULL CHECK(event_seq>0), principal_ref varchar(96) NOT NULL,
 trace_id varchar(64) NOT NULL, runtime_version varchar(32) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SENDING','DELIVERED','HELD')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0), owner varchar(64), lease_until timestamptz,
 ready_at timestamptz NOT NULL DEFAULT clock_timestamp(), run_id uuid, error_code varchar(64),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), delivered_at timestamptz,
 PRIMARY KEY(tenant_code,event_id), UNIQUE(tenant_code,message_id), UNIQUE(tenant_code,session_no,event_seq),
 CHECK((state='SENDING')=(owner IS NOT NULL AND lease_until IS NOT NULL)),
 CHECK((state='DELIVERED')=(run_id IS NOT NULL AND delivered_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS agent_event_outbox_pending ON public.agent_event_outbox(tenant_code,ready_at,event_seq) WHERE state IN ('PENDING','SENDING');
CREATE UNIQUE INDEX IF NOT EXISTS agent_event_outbox_one_sending_session ON public.agent_event_outbox(tenant_code,session_no) WHERE state='SENDING';
ALTER TABLE public.agent_event_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_event_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_event_tenant ON public.agent_event_outbox TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_event_guard ON public.agent_event_outbox AS RESTRICTIVE TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT,UPDATE ON public.agent_event_outbox TO supportflow_customer_tenant;
COMMIT;
