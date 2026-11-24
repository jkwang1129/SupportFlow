\connect ai_db
BEGIN;

CREATE TABLE public.agent_run_event (
 tenant_code varchar(16) NOT NULL,
 run_id uuid NOT NULL,
 seq integer NOT NULL CHECK(seq>0),
 event_type varchar(24) NOT NULL CHECK(event_type IN ('run_started','step_started','step_completed',
   'tool_started','tool_completed','approval_required','run_completed','run_failed','run_cancelled')),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 step_no integer,
 outcome varchar(12) CHECK(outcome IN ('SUCCEEDED','FAILED','UNKNOWN')),
 PRIMARY KEY(tenant_code,run_id,seq),
 FOREIGN KEY(tenant_code,run_id) REFERENCES public.agent_run(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,step_no) REFERENCES public.agent_step(tenant_code,run_id,step_no),
 CHECK((step_no IS NOT NULL)=(event_type IN ('step_started','step_completed','tool_started','tool_completed'))),
 CHECK((outcome IS NOT NULL)=(event_type IN ('step_completed','tool_completed')))
);

ALTER TABLE public.agent_run_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_run_event FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_run_event_tenant ON public.agent_run_event FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_run_event_guard ON public.agent_run_event AS RESTRICTIVE FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT ON public.agent_run_event TO supportflow_agent_runtime;

CREATE FUNCTION public.supportflow_append_run_event() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp SET row_security = off AS $$
DECLARE
 event_kind text;
 event_step integer;
 event_outcome text;
BEGIN
 IF TG_RELID = 'public.agent_run'::regclass AND TG_OP = 'UPDATE' THEN
   IF OLD.state = NEW.state THEN RETURN NULL; END IF;
   event_kind := CASE
     WHEN NEW.state = 'RUNNING' AND OLD.attempt = 0 AND NEW.attempt > 0 THEN 'run_started'
     WHEN NEW.state = 'WAITING_APPROVAL' THEN 'approval_required'
     WHEN NEW.state = 'SUCCEEDED' THEN 'run_completed'
     WHEN NEW.state = 'FAILED' THEN 'run_failed'
     WHEN NEW.state = 'CANCELLED' THEN 'run_cancelled'
   END;
 ELSIF TG_RELID = 'public.agent_step'::regclass THEN
   event_step := NEW.step_no;
   IF TG_OP = 'INSERT' AND NEW.state = 'RESERVED' THEN
     event_kind := CASE WHEN NEW.kind = 'TOOL' THEN 'tool_started' ELSE 'step_started' END;
   ELSIF TG_OP = 'UPDATE' AND OLD.state = 'RESERVED' AND NEW.state <> 'RESERVED' THEN
     event_kind := CASE WHEN NEW.kind = 'TOOL' THEN 'tool_completed' ELSE 'step_completed' END;
     event_outcome := NEW.state;
   END IF;
 ELSE
   RAISE EXCEPTION 'Unexpected run event trigger relation';
 END IF;
 IF event_kind IS NULL THEN RETURN NULL; END IF;

 -- Serialize allocation per Run. The store already locks the Run before mutating steps.
 PERFORM 1 FROM public.agent_run WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id FOR NO KEY UPDATE;
 INSERT INTO public.agent_run_event(tenant_code,run_id,seq,event_type,step_no,outcome)
 SELECT NEW.tenant_code,NEW.run_id,coalesce(max(seq),0)+1,event_kind,event_step,event_outcome
 FROM public.agent_run_event WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id;
 RETURN NULL;
END $$;

REVOKE ALL ON FUNCTION public.supportflow_append_run_event() FROM PUBLIC,supportflow_agent_runtime;
CREATE TRIGGER agent_run_state_event AFTER UPDATE OF state ON public.agent_run
 FOR EACH ROW EXECUTE FUNCTION public.supportflow_append_run_event();
CREATE TRIGGER agent_step_state_event AFTER INSERT OR UPDATE OF state ON public.agent_step
 FOR EACH ROW EXECUTE FUNCTION public.supportflow_append_run_event();

COMMIT;
