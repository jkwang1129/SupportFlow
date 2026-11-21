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

ALTER TABLE public.agent_run DROP CONSTRAINT agent_run_state_check;
ALTER TABLE public.agent_run ADD CONSTRAINT agent_run_state_check CHECK(
 state IN ('PENDING','RUNNING','WAITING_APPROVAL','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')
);
ALTER TABLE public.agent_run ADD CONSTRAINT agent_run_approval_has_no_lease CHECK(
 state<>'WAITING_APPROVAL' OR (owner IS NULL AND lease_until IS NULL)
);

CREATE TABLE public.agent_approval (
 tenant_code varchar(16) NOT NULL CHECK(tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 run_id uuid NOT NULL,
 approval_id uuid NOT NULL,
 principal_ref varchar(96) NOT NULL CHECK(principal_ref ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}$'),
 snapshot_id uuid NOT NULL,
 tool_name varchar(48) NOT NULL CHECK(tool_name='create_ticket'),
 operation varchar(32) NOT NULL CHECK(operation='ticket.create'),
 schema_version varchar(32) NOT NULL CHECK(schema_version='ticket-create-v1'),
 purpose varchar(32) NOT NULL CHECK(purpose='customer-support-ticket'),
 arguments_sha256 char(64) NOT NULL CHECK(arguments_sha256 ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','DENIED','REVOKED','EXPIRED','CANCELLED','CONSUMED')),
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL CHECK(expires_at>requested_at AND expires_at<=requested_at+interval '1 hour'),
 decided_by varchar(64) CHECK(decided_by ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'),
 decided_at timestamptz CHECK(decided_at>=requested_at AND decided_at<expires_at),
 closed_by varchar(64) CHECK(closed_by ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$'),
 closed_at timestamptz CHECK(closed_at>=coalesce(decided_at,requested_at)),
 consumed_step_no integer CHECK(consumed_step_no BETWEEN 1 AND 100),
 consumed_at timestamptz CHECK(consumed_at>=decided_at AND consumed_at<expires_at),
 PRIMARY KEY(tenant_code,run_id),
 UNIQUE(tenant_code,approval_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES public.agent_run(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,consumed_step_no) REFERENCES public.agent_step(tenant_code,run_id,step_no),
 CHECK((decided_by IS NULL)=(decided_at IS NULL)),
 CHECK((closed_by IS NULL)=(closed_at IS NULL)),
 CHECK((consumed_step_no IS NULL)=(consumed_at IS NULL)),
 CHECK((status='CONSUMED')=(consumed_at IS NOT NULL)),
 CHECK((status IN ('REVOKED','EXPIRED','CANCELLED'))=(closed_at IS NOT NULL)),
 CHECK(status NOT IN ('APPROVED','DENIED','REVOKED','CONSUMED') OR decided_at IS NOT NULL),
 CHECK(status<>'PENDING' OR decided_at IS NULL)
);

ALTER TABLE public.agent_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_approval_tenant ON public.agent_approval TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_approval_guard ON public.agent_approval AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_approval TO supportflow_agent_runtime;
GRANT UPDATE(status,decided_by,decided_at,closed_by,closed_at,consumed_step_no,consumed_at)
 ON public.agent_approval TO supportflow_agent_runtime;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_approval FROM supportflow_agent_runtime;
COMMIT;
