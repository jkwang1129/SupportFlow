\connect ai_db
BEGIN;

SET LOCAL row_security = off;

-- Keep the backfill and trigger installation at the same write boundary.
LOCK TABLE public.agent_model_token_reservation, public.agent_model_usage IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE public.agent_model_token_day (
 tenant_code varchar(16) NOT NULL,
 budget_day date NOT NULL,
 charged_tokens numeric NOT NULL CHECK(charged_tokens >= 0 AND charged_tokens < 'Infinity'::numeric),
 PRIMARY KEY(tenant_code,budget_day)
);

INSERT INTO public.agent_model_token_day(tenant_code,budget_day,charged_tokens)
SELECT r.tenant_code,r.budget_day,
       sum(coalesce(u.input_tokens::numeric + coalesce(u.chat_output_tokens,0),r.reserved_tokens))
FROM public.agent_model_token_reservation r
LEFT JOIN public.agent_model_usage u USING(tenant_code,run_id,step_no)
GROUP BY r.tenant_code,r.budget_day;

ALTER TABLE public.agent_model_token_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_model_token_day FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_model_token_day_tenant ON public.agent_model_token_day FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
CREATE POLICY agent_model_token_day_guard ON public.agent_model_token_day AS RESTRICTIVE FOR SELECT TO supportflow_agent_runtime
 USING(tenant_code=nullif(current_setting('app.tenant_code',true),'') AND upper(tenant_code)<>'PLATFORM');
GRANT SELECT ON public.agent_model_token_day TO supportflow_agent_runtime;

CREATE FUNCTION public.supportflow_model_token_day_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp SET row_security = off AS $$
DECLARE
 source_day date;
 expected_tokens integer;
 observed_tokens numeric;
BEGIN
 IF TG_RELID = 'public.agent_model_token_reservation'::regclass THEN
   SELECT input_tokens::numeric + coalesce(chat_output_tokens,0) INTO observed_tokens
   FROM public.agent_model_usage
   WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id AND step_no=NEW.step_no;
   INSERT INTO public.agent_model_token_day AS d(tenant_code,budget_day,charged_tokens)
   VALUES(NEW.tenant_code,NEW.budget_day,coalesce(observed_tokens,NEW.reserved_tokens))
   ON CONFLICT(tenant_code,budget_day) DO UPDATE
   SET charged_tokens=d.charged_tokens+EXCLUDED.charged_tokens;
 ELSIF TG_RELID = 'public.agent_model_usage'::regclass THEN
   SELECT budget_day,reserved_tokens INTO source_day,expected_tokens
   FROM public.agent_model_token_reservation
   WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id AND step_no=NEW.step_no;
   IF FOUND THEN
     UPDATE public.agent_model_token_day
     SET charged_tokens=charged_tokens-expected_tokens+NEW.input_tokens::numeric+coalesce(NEW.chat_output_tokens,0)
     WHERE tenant_code=NEW.tenant_code AND budget_day=source_day;
     IF NOT FOUND THEN
       RAISE EXCEPTION 'Missing model Token day for reservation';
     END IF;
   END IF;
 ELSE
   RAISE EXCEPTION 'Unexpected model Token day trigger relation';
 END IF;
 RETURN NULL;
END $$;

REVOKE ALL ON FUNCTION public.supportflow_model_token_day_insert() FROM PUBLIC,supportflow_agent_runtime;
CREATE TRIGGER agent_model_token_reservation_day_insert AFTER INSERT ON public.agent_model_token_reservation
 FOR EACH ROW EXECUTE FUNCTION public.supportflow_model_token_day_insert();
CREATE TRIGGER agent_model_usage_day_insert AFTER INSERT ON public.agent_model_usage
 FOR EACH ROW EXECUTE FUNCTION public.supportflow_model_token_day_insert();

COMMIT;
