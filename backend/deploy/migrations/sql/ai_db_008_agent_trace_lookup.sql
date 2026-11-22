\connect ai_db
BEGIN;

CREATE INDEX agent_run_trace_lookup_idx
    ON public.agent_run(tenant_code, trace_id, created_at DESC, run_id DESC);

COMMIT;
