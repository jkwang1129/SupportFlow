-- psql-only provisioning. Password values come from the process environment, not CLI arguments.
\set ON_ERROR_STOP on
\connect postgres
\getenv user_pw SUPPORTFLOW_USER_DB_PASSWORD
\getenv tenant_pw SUPPORTFLOW_TENANT_DB_PASSWORD
\getenv customer_pw SUPPORTFLOW_CUSTOMER_DB_PASSWORD
\getenv billing_pw SUPPORTFLOW_BILLING_DB_PASSWORD
\getenv ops_pw SUPPORTFLOW_OPS_DB_PASSWORD
\getenv ai_pw SUPPORTFLOW_AI_DB_PASSWORD
\getenv kb_pw SUPPORTFLOW_KB_DB_PASSWORD
\getenv platform_pw SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION', r)
FROM unnest(ARRAY['supportflow_user','supportflow_tenant','supportflow_customer','supportflow_billing',
                 'supportflow_ops','supportflow_ai','supportflow_kb','supportflow_customer_platform']) r
WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname=r) \gexec
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname=ANY(ARRAY['supportflow_user','supportflow_tenant','supportflow_customer',
    'supportflow_billing','supportflow_ops','supportflow_ai','supportflow_kb','supportflow_customer_platform'])
    AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication
         OR EXISTS(SELECT FROM pg_class WHERE relowner=pg_roles.oid)
         OR EXISTS(SELECT FROM pg_database WHERE datdba=pg_roles.oid)
         OR EXISTS(SELECT FROM pg_namespace WHERE nspowner=pg_roles.oid)
         OR EXISTS(SELECT FROM pg_auth_members m JOIN pg_roles g ON g.oid=m.roleid WHERE m.member=pg_roles.oid
            AND NOT ((pg_roles.rolname='supportflow_customer' AND g.rolname='supportflow_customer_tenant')
                 OR (pg_roles.rolname='supportflow_kb' AND g.rolname='supportflow_kb_tenant')
                 OR (pg_roles.rolname='supportflow_customer_platform' AND g.rolname='supportflow_platform_access'))))) THEN
   RAISE EXCEPTION 'Runtime identities must not own objects or have administrative flags';
 END IF;
END $$;
-- Refuse unset/weak credentials rather than silently leaving a login unusable.
SELECT 1/(CASE WHEN least(length(:'user_pw'),length(:'tenant_pw'),length(:'customer_pw'),length(:'billing_pw'),
 length(:'ops_pw'),length(:'ai_pw'),length(:'kb_pw'),length(:'platform_pw'))>=24 AND (SELECT count(DISTINCT v) FROM unnest(ARRAY[:'user_pw',:'tenant_pw',:'customer_pw',:'billing_pw',:'ops_pw',:'ai_pw',:'kb_pw',:'platform_pw']) v)=8 THEN 1 ELSE 0 END);
SELECT format('ALTER ROLE %I PASSWORD %L',r,p) FROM (VALUES
 ('supportflow_user', :'user_pw'),('supportflow_tenant', :'tenant_pw'),
 ('supportflow_customer', :'customer_pw'),('supportflow_billing', :'billing_pw'),
 ('supportflow_ops', :'ops_pw'),('supportflow_ai', :'ai_pw'),('supportflow_kb', :'kb_pw'),
 ('supportflow_customer_platform', :'platform_pw')) v(r,p)
WHERE length(p)>=24 \gexec
REVOKE CONNECT ON DATABASE user_db,tenant_db,customer_db,billing_db,ops_db,ai_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE user_db,tenant_db,customer_db,billing_db,ops_db,ai_db FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
GRANT CONNECT ON DATABASE user_db TO supportflow_user;
GRANT CONNECT ON DATABASE tenant_db TO supportflow_tenant;
GRANT CONNECT ON DATABASE customer_db TO supportflow_customer,supportflow_kb,supportflow_customer_platform;
GRANT CONNECT ON DATABASE billing_db TO supportflow_billing;
GRANT CONNECT ON DATABASE ops_db TO supportflow_ops;
GRANT CONNECT ON DATABASE ai_db TO supportflow_ai;
GRANT supportflow_customer_tenant TO supportflow_customer;
GRANT supportflow_kb_tenant TO supportflow_kb;
GRANT supportflow_platform_access TO supportflow_customer_platform;
\connect user_db
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO supportflow_user;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO supportflow_user;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO supportflow_user;
\connect tenant_db
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO supportflow_tenant;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO supportflow_tenant;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO supportflow_tenant;
\connect billing_db
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO supportflow_billing;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO supportflow_billing;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO supportflow_billing;
DO $$ BEGIN
 IF to_regclass('public.billing_usage_batch') IS NOT NULL THEN
   REVOKE UPDATE,DELETE,TRUNCATE ON public.billing_usage_batch FROM supportflow_billing;
 END IF;
END $$;
\connect ops_db
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO supportflow_ops;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO supportflow_ops;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO supportflow_ops;
\connect ai_db
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO supportflow_ai;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO supportflow_ai;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO supportflow_ai;
\connect customer_db
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;
REVOKE ALL ON SCHEMA public FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform;

\connect customer_db
DO $$ BEGIN
 IF to_regclass('public.supportflow_schema_history') IS NOT NULL THEN
   REVOKE ALL ON public.supportflow_schema_history FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform,supportflow_customer_tenant,supportflow_kb_tenant,supportflow_platform_access;
   GRANT SELECT ON public.supportflow_schema_history TO supportflow_customer;
 END IF;
END $$;

\connect billing_db
DO $$ BEGIN
 IF to_regclass('public.supportflow_schema_history') IS NOT NULL THEN
   REVOKE ALL ON public.supportflow_schema_history FROM supportflow_user,supportflow_tenant,supportflow_customer,supportflow_billing,supportflow_ops,supportflow_ai,supportflow_kb,supportflow_customer_platform,supportflow_customer_tenant,supportflow_kb_tenant,supportflow_platform_access;
   GRANT SELECT ON public.supportflow_schema_history TO supportflow_billing;
 END IF;
END $$;

-- Preserve the historical caller database after provisioning.
\connect customer_db
