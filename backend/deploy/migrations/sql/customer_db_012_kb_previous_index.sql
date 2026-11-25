\connect customer_db
BEGIN;

CREATE TABLE public.kb_index_previous (
    tenant_code varchar(16) NOT NULL,
    doc_id bigint NOT NULL,
    version_id uuid NOT NULL,
    content_snapshot text,
    chunk_count integer NOT NULL CHECK (chunk_count > 0),
    PRIMARY KEY (tenant_code, doc_id)
);

CREATE TABLE public.kb_index_previous_chunk (
    LIKE public.kb_chunk INCLUDING DEFAULTS,
    PRIMARY KEY (tenant_code, doc_id, id),
    FOREIGN KEY (tenant_code, doc_id) REFERENCES public.kb_index_previous (tenant_code, doc_id) ON DELETE CASCADE
);

DO $$
DECLARE relation_name text;
BEGIN
 FOR relation_name IN SELECT unnest(ARRAY['kb_index_previous', 'kb_index_previous_chunk']) LOOP
   EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', relation_name);
   EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', relation_name);
   EXECUTE format($sql$CREATE POLICY kb_previous_tenant ON public.%I TO supportflow_customer_tenant
     USING (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM')
     WITH CHECK (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM')$sql$, relation_name);
   EXECUTE format($sql$CREATE POLICY kb_previous_tenant_guard ON public.%I AS RESTRICTIVE TO supportflow_customer_tenant
     USING (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM')
     WITH CHECK (tenant_code = NULLIF(current_setting('app.tenant_code', true), '') AND upper(tenant_code) <> 'PLATFORM')$sql$, relation_name);
 END LOOP;
END $$;

GRANT SELECT, INSERT, DELETE ON public.kb_index_previous, public.kb_index_previous_chunk TO supportflow_customer_tenant;

COMMIT;
