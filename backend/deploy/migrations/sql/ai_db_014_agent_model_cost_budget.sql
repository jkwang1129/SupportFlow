\connect ai_db
BEGIN;

ALTER TABLE public.agent_model_quota
 ADD COLUMN daily_cost_limit_picos bigint CHECK(daily_cost_limit_picos >= 0),
 ADD COLUMN monthly_cost_limit_picos bigint CHECK(monthly_cost_limit_picos >= 0),
 ADD COLUMN price_book text CHECK(octet_length(price_book) BETWEEN 1 AND 32768),
 DROP CONSTRAINT agent_model_quota_token_limits,
 ADD CONSTRAINT agent_model_quota_budget_limits CHECK (
   (tokens_per_attempt IS NOT NULL) = (daily_token_limit IS NOT NULL OR monthly_token_limit IS NOT NULL OR
      daily_cost_limit_picos IS NOT NULL OR monthly_cost_limit_picos IS NOT NULL) AND
   (tokens_per_attempt IS NULL OR tokens_per_attempt BETWEEN 1 AND 1000000) AND
   (daily_token_limit IS NULL OR daily_token_limit BETWEEN 0 AND 1000000000000) AND
   (monthly_token_limit IS NULL OR monthly_token_limit BETWEEN 0 AND 1000000000000) AND
   (price_book IS NOT NULL) = (daily_cost_limit_picos IS NOT NULL OR monthly_cost_limit_picos IS NOT NULL)
 );

ALTER TABLE public.agent_model_token_reservation
 ADD COLUMN price_book_version varchar(32),
 ADD COLUMN price_book_sha256 char(64),
 ADD COLUMN currency varchar(3),
 ADD COLUMN input_rate bigint,
 ADD COLUMN output_rate bigint,
 ADD COLUMN reserved_cost_picos bigint,
 ADD CONSTRAINT agent_model_reservation_price CHECK (
   (price_book_version IS NULL AND price_book_sha256 IS NULL AND currency IS NULL AND input_rate IS NULL AND
    output_rate IS NULL AND reserved_cost_picos IS NULL) OR
   (price_book_version IS NOT NULL AND price_book_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,31}$' AND
    price_book_sha256 IS NOT NULL AND price_book_sha256 ~ '^[0-9a-f]{64}$' AND
    currency IS NOT NULL AND currency ~ '^[A-Z]{3}$' AND
    input_rate IS NOT NULL AND input_rate BETWEEN 0 AND 1000000000000 AND
    (output_rate IS NULL OR output_rate BETWEEN 0 AND 1000000000000) AND
    reserved_cost_picos IS NOT NULL AND reserved_cost_picos=reserved_tokens*greatest(input_rate,output_rate))
 );

CREATE TABLE public.agent_model_cost_day (
 tenant_code varchar(16) NOT NULL,
 budget_day date NOT NULL,
 currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 charged_picos numeric NOT NULL CHECK(charged_picos >= 0 AND charged_picos < 'Infinity'::numeric),
 PRIMARY KEY(tenant_code,budget_day,currency)
);
ALTER TABLE public.agent_model_cost_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_model_cost_day FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_model_cost_day_tenant ON public.agent_model_cost_day FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_model_cost_day_guard ON public.agent_model_cost_day AS RESTRICTIVE FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT ON public.agent_model_cost_day TO supportflow_agent_runtime;

CREATE OR REPLACE FUNCTION public.supportflow_model_token_day_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp SET row_security = off AS $$
DECLARE
 reservation public.agent_model_token_reservation%ROWTYPE;
 input_count bigint;
 output_count bigint;
BEGIN
 IF TG_RELID = 'public.agent_model_token_reservation'::regclass THEN
   SELECT input_tokens,chat_output_tokens INTO input_count,output_count
   FROM public.agent_model_usage
   WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id AND step_no=NEW.step_no;
   INSERT INTO public.agent_model_token_day AS d(tenant_code,budget_day,charged_tokens)
   VALUES(NEW.tenant_code,NEW.budget_day,coalesce(input_count::numeric + coalesce(output_count,0),NEW.reserved_tokens))
   ON CONFLICT(tenant_code,budget_day) DO UPDATE
   SET charged_tokens=d.charged_tokens+EXCLUDED.charged_tokens;
   IF NEW.currency IS NOT NULL THEN
     INSERT INTO public.agent_model_cost_day AS d(tenant_code,budget_day,currency,charged_picos)
     VALUES(NEW.tenant_code,NEW.budget_day,NEW.currency,
       coalesce(input_count::numeric * NEW.input_rate + coalesce(output_count,0)::numeric * coalesce(NEW.output_rate,0),
         NEW.reserved_cost_picos))
     ON CONFLICT(tenant_code,budget_day,currency) DO UPDATE
     SET charged_picos=d.charged_picos+EXCLUDED.charged_picos;
   END IF;
 ELSIF TG_RELID = 'public.agent_model_usage'::regclass THEN
   SELECT * INTO reservation FROM public.agent_model_token_reservation
   WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id AND step_no=NEW.step_no;
   IF FOUND THEN
     UPDATE public.agent_model_token_day
     SET charged_tokens=charged_tokens-reservation.reserved_tokens+NEW.input_tokens::numeric+coalesce(NEW.chat_output_tokens,0)
     WHERE tenant_code=NEW.tenant_code AND budget_day=reservation.budget_day;
     IF NOT FOUND THEN
       RAISE EXCEPTION 'Missing model Token day for reservation';
     END IF;
     IF reservation.currency IS NOT NULL THEN
       UPDATE public.agent_model_cost_day
       SET charged_picos=charged_picos-reservation.reserved_cost_picos+NEW.input_tokens::numeric * reservation.input_rate
          +coalesce(NEW.chat_output_tokens,0)::numeric * coalesce(reservation.output_rate,0)
       WHERE tenant_code=NEW.tenant_code AND budget_day=reservation.budget_day AND currency=reservation.currency;
       IF NOT FOUND THEN
         RAISE EXCEPTION 'Missing model cost day for reservation';
       END IF;
     END IF;
   END IF;
 ELSE
   RAISE EXCEPTION 'Unexpected model Token day trigger relation';
 END IF;
 RETURN NULL;
END $$;

COMMIT;
