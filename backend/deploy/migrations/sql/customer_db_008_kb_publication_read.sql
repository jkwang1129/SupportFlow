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
-- The KB identity must filter publication readiness without gaining document
-- content, mutation, whole-table SELECT or any cross-tenant capability.
GRANT SELECT(index_status) ON public.kb_document TO supportflow_kb_tenant;
COMMIT;
