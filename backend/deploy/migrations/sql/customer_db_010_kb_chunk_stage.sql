\connect customer_db
BEGIN;

CREATE TABLE public.kb_chunk_stage (
    LIKE public.kb_chunk INCLUDING DEFAULTS,
    attempt_id uuid NOT NULL,
    PRIMARY KEY (tenant_code, doc_id, attempt_id, chunk_no)
);

CREATE INDEX idx_kb_chunk_stage_expiry ON public.kb_chunk_stage (tenant_code, create_time);

ALTER TABLE public.kb_chunk_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.kb_chunk_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY kb_chunk_stage_tenant ON public.kb_chunk_stage
    TO supportflow_customer_tenant, supportflow_kb_tenant
    USING (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM')
    WITH CHECK (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM');
CREATE POLICY kb_chunk_stage_tenant_guard ON public.kb_chunk_stage AS RESTRICTIVE
    TO supportflow_customer_tenant, supportflow_kb_tenant
    USING (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM')
    WITH CHECK (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM');

GRANT SELECT, DELETE ON public.kb_chunk_stage TO supportflow_customer_tenant;
GRANT SELECT, INSERT, DELETE ON public.kb_chunk_stage TO supportflow_kb_tenant;

COMMIT;
