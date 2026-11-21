\connect ai_db
BEGIN;

CREATE INDEX agent_approval_due_idx ON public.agent_approval(tenant_code,expires_at,run_id)
 WHERE status IN ('PENDING','APPROVED');

-- As in AI002, return only bounded tenant codes to the worker dispatcher.
CREATE OR REPLACE FUNCTION public.supportflow_agent_due_tenants(p_limit integer)
RETURNS TABLE(tenant_code varchar) LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 WITH due AS (
   SELECT r.tenant_code,r.created_at FROM public.agent_run r
   WHERE ((r.state IN ('PENDING','RETRY_WAIT') AND r.ready_at<=clock_timestamp())
      OR (r.state='RUNNING' AND (r.lease_until<=clock_timestamp() OR r.deadline_at<=clock_timestamp())))
   AND NOT EXISTS(SELECT 1 FROM public.agent_run earlier WHERE earlier.tenant_code=r.tenant_code AND earlier.session_no=r.session_no
      AND earlier.run_id<>r.run_id AND (earlier.state='RUNNING' OR
         (earlier.event_seq<r.event_seq AND (earlier.state NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR earlier.error_code='SIDE_EFFECT_UNKNOWN'))))
   UNION ALL
   SELECT r.tenant_code,r.created_at FROM public.agent_approval a
   JOIN public.agent_run r ON r.tenant_code=a.tenant_code AND r.run_id=a.run_id
   WHERE a.status IN ('PENDING','APPROVED') AND a.expires_at<=clock_timestamp()
     AND r.state IN ('WAITING_APPROVAL','PENDING','RETRY_WAIT','RUNNING')
     AND NOT EXISTS(SELECT 1 FROM public.agent_step s WHERE s.tenant_code=r.tenant_code AND s.run_id=r.run_id
       AND s.effect='WRITE' AND s.state IN ('RESERVED','UNKNOWN'))
 )
 SELECT due.tenant_code FROM due GROUP BY due.tenant_code
 ORDER BY min(due.created_at),due.tenant_code
 LIMIT greatest(1,least(coalesce(p_limit,32),100))
$$;
REVOKE ALL ON FUNCTION public.supportflow_agent_due_tenants(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.supportflow_agent_due_tenants(integer) TO supportflow_agent_runtime;
COMMIT;
