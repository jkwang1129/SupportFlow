\connect ai_db
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='supportflow_agent_runtime') THEN
   CREATE ROLE supportflow_agent_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
 END IF;
END $$;
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles r WHERE r.rolname='supportflow_agent_runtime' AND
     (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication
      OR EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid)
      OR EXISTS(SELECT FROM pg_class WHERE relowner=r.oid)
      OR EXISTS(SELECT FROM pg_database WHERE datdba=r.oid)
      OR EXISTS(SELECT FROM pg_namespace WHERE nspowner=r.oid))) THEN
   RAISE EXCEPTION 'Agent runtime capability must be non-login, non-owner and non-administrative';
 END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.agent_run (
 tenant_code varchar(16) NOT NULL CHECK (tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$' AND upper(tenant_code)<>'PLATFORM'),
 run_id uuid NOT NULL,
 source_event_id uuid NOT NULL,
 session_no varchar(64) NOT NULL,
 event_seq bigint NOT NULL CHECK(event_seq>0),
 message_id bigint NOT NULL CHECK(message_id>0),
 principal_ref varchar(96) NOT NULL,
 trace_id varchar(64) NOT NULL,
 runtime_version varchar(32) NOT NULL,
 input_hash char(64) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
 max_steps integer NOT NULL CHECK(max_steps BETWEEN 1 AND 100),
 max_model_calls integer NOT NULL CHECK(max_model_calls BETWEEN 0 AND 100),
 max_tool_calls integer NOT NULL CHECK(max_tool_calls BETWEEN 0 AND 100),
 max_retries integer NOT NULL CHECK(max_retries BETWEEN 0 AND 10),
 step_count integer NOT NULL DEFAULT 0 CHECK(step_count BETWEEN 0 AND max_steps),
 model_calls integer NOT NULL DEFAULT 0 CHECK(model_calls BETWEEN 0 AND max_model_calls),
 tool_calls integer NOT NULL DEFAULT 0 CHECK(tool_calls BETWEEN 0 AND max_tool_calls),
 retry_count integer NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND max_retries),
 attempt integer NOT NULL DEFAULT 0 CHECK(attempt>=0),
 fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
 owner varchar(64), lease_until timestamptz,
 ready_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 deadline_at timestamptz NOT NULL,
 cancel_requested boolean NOT NULL DEFAULT false,
 error_code varchar(64), result_ref jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
 PRIMARY KEY(tenant_code,run_id),
 UNIQUE(tenant_code,source_event_id), UNIQUE(tenant_code,session_no,event_seq),
 CHECK ((state='RUNNING')=(owner IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS agent_run_one_running_session ON public.agent_run(tenant_code,session_no) WHERE state='RUNNING';
CREATE INDEX IF NOT EXISTS agent_run_ready_queue ON public.agent_run(tenant_code,ready_at,event_seq) WHERE state IN ('PENDING','RETRY_WAIT');
CREATE INDEX IF NOT EXISTS agent_run_expired_lease ON public.agent_run(lease_until) WHERE state='RUNNING';

CREATE TABLE IF NOT EXISTS public.agent_step (
 tenant_code varchar(16) NOT NULL, run_id uuid NOT NULL,
 step_no integer NOT NULL CHECK(step_no>0), step_key varchar(64) NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN ('MODEL','TOOL','INTERNAL')),
 effect varchar(8) NOT NULL CHECK(effect IN ('NONE','READ','WRITE')),
 intent_hash char(64) NOT NULL, intent_ref jsonb NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','SUCCEEDED','FAILED','UNKNOWN')),
 fence bigint NOT NULL, result_ref jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), completed_at timestamptz,
 PRIMARY KEY(tenant_code,run_id,step_no), UNIQUE(tenant_code,run_id,step_key),
 FOREIGN KEY(tenant_code,run_id) REFERENCES public.agent_run(tenant_code,run_id),
 CHECK ((kind='TOOL' AND effect IN ('READ','WRITE')) OR (kind<>'TOOL' AND effect='NONE'))
);

ALTER TABLE public.agent_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_run FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_step FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS agent_runtime_tenant ON public.agent_run;
CREATE POLICY agent_runtime_tenant ON public.agent_run TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
DROP POLICY IF EXISTS agent_runtime_guard ON public.agent_run;
CREATE POLICY agent_runtime_guard ON public.agent_run AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
DROP POLICY IF EXISTS agent_runtime_tenant ON public.agent_step;
CREATE POLICY agent_runtime_tenant ON public.agent_step TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
DROP POLICY IF EXISTS agent_runtime_guard ON public.agent_step;
CREATE POLICY agent_runtime_guard ON public.agent_step AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT USAGE ON SCHEMA public TO supportflow_agent_runtime;
GRANT SELECT,INSERT,UPDATE ON public.agent_run,public.agent_step TO supportflow_agent_runtime;

-- Queue discovery exposes only bounded tenant metadata, never task inputs/results.
CREATE OR REPLACE FUNCTION public.supportflow_agent_due_tenants(p_limit integer)
RETURNS TABLE(tenant_code varchar) LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT r.tenant_code FROM public.agent_run r
 WHERE ((r.state IN ('PENDING','RETRY_WAIT') AND r.ready_at<=clock_timestamp())
    OR (r.state='RUNNING' AND (r.lease_until<=clock_timestamp() OR r.deadline_at<=clock_timestamp())))
 AND NOT EXISTS(SELECT 1 FROM public.agent_run earlier WHERE earlier.tenant_code=r.tenant_code AND earlier.session_no=r.session_no
    AND earlier.run_id<>r.run_id AND (earlier.state='RUNNING' OR
       (earlier.event_seq<r.event_seq AND (earlier.state NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR earlier.error_code='SIDE_EFFECT_UNKNOWN'))))
 GROUP BY r.tenant_code ORDER BY min(r.created_at),r.tenant_code
 LIMIT greatest(1,least(coalesce(p_limit,32),100))
$$;
REVOKE ALL ON FUNCTION public.supportflow_agent_due_tenants(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.supportflow_agent_due_tenants(integer) TO supportflow_agent_runtime;
COMMIT;
