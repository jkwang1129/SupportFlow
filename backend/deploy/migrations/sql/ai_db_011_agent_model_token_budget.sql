\connect ai_db
BEGIN;

ALTER TABLE public.agent_model_quota
 ADD COLUMN daily_token_limit bigint,
 ADD COLUMN tokens_per_attempt integer,
 ADD CONSTRAINT agent_model_quota_daily_tokens CHECK (
   (daily_token_limit IS NULL AND tokens_per_attempt IS NULL) OR
   (daily_token_limit IS NOT NULL AND daily_token_limit BETWEEN 0 AND 1000000000000 AND
    tokens_per_attempt IS NOT NULL AND tokens_per_attempt BETWEEN 1 AND 1000000)
 );

CREATE TABLE public.agent_model_token_reservation (
 tenant_code varchar(16) NOT NULL,
 run_id uuid NOT NULL,
 step_no integer NOT NULL,
 budget_day date NOT NULL,
 reserved_tokens integer NOT NULL CHECK(reserved_tokens BETWEEN 1 AND 1000000),
 PRIMARY KEY(tenant_code,run_id,step_no),
 FOREIGN KEY(tenant_code,run_id,step_no) REFERENCES public.agent_step(tenant_code,run_id,step_no)
);
CREATE INDEX agent_model_token_reservation_day_idx ON public.agent_model_token_reservation(tenant_code,budget_day);
ALTER TABLE public.agent_model_token_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_model_token_reservation FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_model_token_reservation_tenant ON public.agent_model_token_reservation TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_model_token_reservation_guard ON public.agent_model_token_reservation AS RESTRICTIVE TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM')
 WITH CHECK(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT,INSERT ON public.agent_model_token_reservation TO supportflow_agent_runtime;
REVOKE UPDATE,DELETE,TRUNCATE ON public.agent_model_token_reservation FROM supportflow_agent_runtime;

COMMIT;
