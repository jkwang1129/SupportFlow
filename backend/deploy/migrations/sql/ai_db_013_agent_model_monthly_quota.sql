\connect ai_db
BEGIN;

ALTER TABLE public.agent_model_quota
 ADD COLUMN monthly_token_limit bigint,
 DROP CONSTRAINT agent_model_quota_daily_tokens,
 ADD CONSTRAINT agent_model_quota_token_limits CHECK (
   (daily_token_limit IS NULL AND monthly_token_limit IS NULL AND tokens_per_attempt IS NULL) OR
   ((daily_token_limit IS NOT NULL OR monthly_token_limit IS NOT NULL) AND
    (daily_token_limit IS NULL OR daily_token_limit BETWEEN 0 AND 1000000000000) AND
    (monthly_token_limit IS NULL OR monthly_token_limit BETWEEN 0 AND 1000000000000) AND
    tokens_per_attempt IS NOT NULL AND tokens_per_attempt BETWEEN 1 AND 1000000)
 );

COMMIT;
