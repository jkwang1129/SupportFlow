\connect customer_db
BEGIN;

DO $$ BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='supportflow_kb_tenant') OR EXISTS(
   SELECT FROM pg_roles r WHERE r.rolname='supportflow_kb_tenant' AND
     (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication
      OR EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid)
      OR EXISTS(SELECT FROM pg_class WHERE relowner=r.oid)
      OR EXISTS(SELECT FROM pg_namespace WHERE nspowner=r.oid)
      OR EXISTS(SELECT FROM pg_database WHERE datdba=r.oid))) THEN
   RAISE EXCEPTION 'Knowledge capability must be non-login, non-owner and non-administrative';
 END IF;
END $$;

ALTER TABLE public.kb_document
    ADD COLUMN IF NOT EXISTS source_authority varchar(16) NOT NULL DEFAULT 'official';

DO $$ BEGIN
 IF NOT EXISTS (
   SELECT 1 FROM pg_constraint
    WHERE conrelid = 'public.kb_document'::regclass
      AND conname = 'chk_kb_document_source_authority'
 ) THEN
   ALTER TABLE public.kb_document
     ADD CONSTRAINT chk_kb_document_source_authority
     CHECK (source_authority IN ('official', 'reference', 'community'));
 END IF;
END $$;

-- Allow KB retrieval to enforce source authority governance without granting
-- document body SELECT, mutation, or cross-tenant capability.
GRANT SELECT(source_authority) ON public.kb_document TO supportflow_kb_tenant;

COMMIT;
