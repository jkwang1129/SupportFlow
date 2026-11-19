-- Administrator-only, repeatable batch ledger/outbox migration. No production data is removed.
\set ON_ERROR_STOP on
\connect billing_db
BEGIN;
CREATE TABLE IF NOT EXISTS public.billing_usage_batch (
    tenant_code varchar(16) NOT NULL,
    batch_id uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    stat_date date NOT NULL,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_code, batch_id)
);
REVOKE ALL ON public.billing_usage_batch FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS (SELECT FROM pg_roles WHERE rolname='supportflow_billing') THEN
   GRANT SELECT, INSERT ON public.billing_usage_batch TO supportflow_billing;
   REVOKE UPDATE, DELETE, TRUNCATE ON public.billing_usage_batch FROM supportflow_billing;
 END IF;
END $$;
COMMIT;
\connect customer_db
BEGIN;
CREATE TABLE IF NOT EXISTS public.billing_usage_outbox (
    batch_id uuid NOT NULL PRIMARY KEY,
    tenant_code varchar(16) NOT NULL UNIQUE,
    stat_date date NOT NULL,
    sessions bigint NOT NULL CHECK (sessions >= 0),
    messages bigint NOT NULL CHECK (messages >= 0),
    ai_calls bigint NOT NULL CHECK (ai_calls >= 0),
    seats integer NOT NULL CHECK (seats >= 0),
    storage_gb numeric(12,2) NOT NULL CHECK (storage_gb >= 0),
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
REVOKE ALL ON public.billing_usage_outbox FROM PUBLIC;
ALTER TABLE public.billing_usage_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.billing_usage_outbox FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS billing_usage_outbox_tenant_isolation ON public.billing_usage_outbox;
DROP POLICY IF EXISTS supportflow_tenant_scope ON public.billing_usage_outbox;
CREATE POLICY billing_usage_outbox_tenant_isolation ON public.billing_usage_outbox TO supportflow_customer_tenant
    USING (tenant_code = nullif(current_setting('app.tenant_code',true),'') AND tenant_code <> 'PLATFORM'
           AND tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$')
    WITH CHECK (tenant_code = nullif(current_setting('app.tenant_code',true),'') AND tenant_code <> 'PLATFORM'
           AND tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$');
CREATE POLICY supportflow_tenant_scope ON public.billing_usage_outbox AS RESTRICTIVE TO supportflow_customer_tenant
    USING (tenant_code = nullif(current_setting('app.tenant_code',true),'') AND tenant_code <> 'PLATFORM'
           AND tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$')
    WITH CHECK (tenant_code = nullif(current_setting('app.tenant_code',true),'') AND tenant_code <> 'PLATFORM'
           AND tenant_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$');
GRANT SELECT, INSERT, DELETE ON public.billing_usage_outbox TO supportflow_customer_tenant;
COMMIT;
