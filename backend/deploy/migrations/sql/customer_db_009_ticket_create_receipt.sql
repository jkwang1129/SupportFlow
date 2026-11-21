\connect customer_db
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='supportflow_customer_tenant') OR EXISTS(
   SELECT FROM pg_roles r WHERE r.rolname='supportflow_customer_tenant' AND
     (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication
      OR EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid)
      OR EXISTS(SELECT FROM pg_class WHERE relowner=r.oid)
      OR EXISTS(SELECT FROM pg_namespace WHERE nspowner=r.oid)
      OR EXISTS(SELECT FROM pg_database WHERE datdba=r.oid))) THEN
   RAISE EXCEPTION 'Customer capability must be non-login, non-owner and non-administrative';
 END IF;
END $$;
ALTER TABLE public.ticket ADD CONSTRAINT ticket_tenant_agent_result_ref UNIQUE(tenant_code,id);
CREATE TABLE public.agent_ticket_receipt (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 run_id uuid NOT NULL, event_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 arguments_sha256 char(64) NOT NULL CHECK(arguments_sha256 ~ '^[0-9a-f]{64}$'),
 operation varchar(32) NOT NULL CHECK(operation='ticket.create'),
 schema_version varchar(16) NOT NULL CHECK(schema_version='ticket-create-v1'),
 step_key varchar(64) NOT NULL CHECK(step_key ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'),
 step_no bigint NOT NULL CHECK(step_no BETWEEN 1 AND 100), fence bigint NOT NULL CHECK(fence>0),
 ticket_id bigint NOT NULL CHECK(ticket_id>0),
 ticket_no varchar(64) NOT NULL CHECK(ticket_no ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id), UNIQUE(tenant_code,event_id), UNIQUE(tenant_code,ticket_id),
 FOREIGN KEY(tenant_code,event_id,run_id,snapshot_id) REFERENCES public.agent_source_snapshot(tenant_code,event_id,run_id,snapshot_id),
 FOREIGN KEY(tenant_code,ticket_id) REFERENCES public.ticket(tenant_code,id)
);
ALTER TABLE public.agent_ticket_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_ticket_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_ticket_tenant ON public.agent_ticket_receipt TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_ticket_guard ON public.agent_ticket_receipt AS RESTRICTIVE TO supportflow_customer_tenant
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_ticket_receipt TO supportflow_customer_tenant;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_ticket_receipt FROM supportflow_customer_tenant;
COMMIT;
