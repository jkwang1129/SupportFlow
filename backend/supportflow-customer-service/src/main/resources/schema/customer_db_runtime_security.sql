-- Runtime security migration v31. Execute as schema administrator, never a business login.
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'customer_db' THEN
        RAISE EXCEPTION 'Apply the customer tenant policy to customer_db only';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'supportflow_customer_tenant') THEN
        CREATE ROLE supportflow_customer_tenant NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'supportflow_kb_tenant') THEN
        CREATE ROLE supportflow_kb_tenant NOLOGIN;
    END IF;
    IF EXISTS (
        SELECT FROM pg_roles AS r
            WHERE r.rolname IN ('supportflow_customer_tenant', 'supportflow_kb_tenant')
              AND (r.rolcanlogin OR r.rolsuper OR r.rolbypassrls OR r.rolcreaterole
                   OR r.rolcreatedb OR r.rolreplication
                   OR EXISTS (SELECT FROM pg_auth_members AS m WHERE m.member = r.oid)
                   OR EXISTS (SELECT FROM pg_class AS c WHERE c.relowner = r.oid)
                   OR EXISTS (SELECT FROM pg_namespace AS n WHERE n.nspowner = r.oid)
                   OR EXISTS (SELECT FROM pg_database AS d WHERE d.datdba = r.oid))
    ) THEN
        RAISE EXCEPTION 'Tenant roles must be unprivileged groups with no ownership';
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='supportflow_platform_access') THEN
        CREATE ROLE supportflow_platform_access NOLOGIN;
    END IF;
    IF EXISTS (SELECT FROM pg_roles r WHERE rolname='supportflow_platform_access'
               AND (rolcanlogin OR rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication
                 OR EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid)
                 OR EXISTS(SELECT FROM pg_class WHERE relowner=r.oid)
                 OR EXISTS(SELECT FROM pg_namespace WHERE nspowner=r.oid)
                 OR EXISTS(SELECT FROM pg_database WHERE datdba=r.oid))) THEN
        RAISE EXCEPTION 'Platform access group must be unprivileged';
    END IF;
END $$;
-- Converge historical direct grants as well as row policies.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM supportflow_customer_tenant,supportflow_kb_tenant,supportflow_platform_access;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM supportflow_customer_tenant,supportflow_kb_tenant,supportflow_platform_access;
GRANT USAGE ON SCHEMA public TO supportflow_platform_access;
CREATE TABLE IF NOT EXISTS public.platform_access_audit (
    id bigserial PRIMARY KEY, actor_id bigint NOT NULL CHECK(actor_id>0),
    operation text NOT NULL, phase text NOT NULL, http_status integer NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp
);
REVOKE ALL ON public.platform_access_audit FROM PUBLIC;
GRANT INSERT ON public.platform_access_audit TO supportflow_platform_access;
GRANT USAGE ON SEQUENCE public.platform_access_audit_id_seq TO supportflow_platform_access;

-- A private namespace survives enterprise approval and never shares PLATFORM rows.
CREATE OR REPLACE FUNCTION public.supportflow_license_scope(actor bigint) RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
DECLARE n bigint := actor; result text := ''; digits constant text := '0123456789abcdefghijklmnopqrstuvwxyz';
BEGIN
    IF n<=0 THEN RAISE EXCEPTION 'Positive applicant ID required'; END IF;
    WHILE n>0 LOOP result := substr(digits,(n%36)::int+1,1)||result; n:=n/36; END LOOP;
    RETURN 'L'||result;
END $$;
REVOKE ALL ON FUNCTION public.supportflow_license_scope(bigint) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT FROM public.file_meta WHERE biz_type=2 AND is_deleted=false
               AND (creator IS NULL OR creator !~ '^[1-9][0-9]{0,18}$'
                    OR CASE WHEN creator ~ '^[1-9][0-9]{0,18}$' THEN creator::numeric>9223372036854775807 ELSE false END)) THEN
        RAISE EXCEPTION 'Active license has no valid uploader; repair creator before migration';
    END IF;
END $$;
UPDATE public.file_meta SET tenant_code=public.supportflow_license_scope(creator::bigint)
WHERE biz_type=2 AND CASE WHEN creator ~ '^[1-9][0-9]{0,18}$' THEN creator::numeric<=9223372036854775807 ELSE false END;

-- Dedicated platform login has no session/KB/channel access. These row restrictions
-- also survive a historical permissive policy being replayed by an administrator.
GRANT SELECT ON public.file_meta TO supportflow_platform_access;
GRANT SELECT, UPDATE ON public.ticket TO supportflow_platform_access;
GRANT SELECT, INSERT ON public.ticket_event TO supportflow_platform_access;
GRANT INSERT ON public.notification TO supportflow_platform_access;
GRANT SELECT (create_time,is_deleted,ai_score,risk_level,status) ON public.qa_task TO supportflow_platform_access;
GRANT SELECT (create_time,is_deleted,severity,status,tenant_code,rule_name,rule_type) ON public.qa_alert TO supportflow_platform_access;
DO $$
DECLARE item record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('file_meta','biz_type=2 AND is_deleted=false'),
        ('ticket','ticket_type=2 AND is_deleted=false'),
        ('ticket_event','EXISTS (SELECT 1 FROM public.ticket t WHERE t.id=ticket_id AND t.tenant_code=ticket_event.tenant_code AND t.ticket_type=2 AND t.is_deleted=false)'),
        ('notification','tenant_code<>''PLATFORM'' AND target_type=1'),
        ('qa_task','true'), ('qa_alert','true')
    ) AS rules(tbl,predicate) LOOP
        EXECUTE format('DROP POLICY IF EXISTS platform_operation ON public.%I',item.tbl);
        EXECUTE format('DROP POLICY IF EXISTS platform_operation_guard ON public.%I',item.tbl);
        EXECUTE format('CREATE POLICY platform_operation ON public.%I TO supportflow_platform_access USING (%s) WITH CHECK (%s)', item.tbl,item.predicate,item.predicate);
        EXECUTE format('CREATE POLICY platform_operation_guard ON public.%I AS RESTRICTIVE TO supportflow_platform_access USING (%s) WITH CHECK (%s)', item.tbl,item.predicate,item.predicate);
    END LOOP;
END $$;
GRANT USAGE ON SCHEMA public TO supportflow_customer_tenant, supportflow_kb_tenant;

-- Explicit grant list: subsequent tables need an explicit grant in their migration.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    public.channel, public.channel_key, public.skill_group, public.agent_status, public.channel_test_log,
    public.session, public.session_message, public.session_event,
    public.customer, public.customer_tag, public.customer_tag_def, public.customer_event, public.agent_daily_metric,
    public.ticket, public.ticket_event, public.ticket_sla_rule,
    public.notification, public.notification_read,
    public.kb_category, public.kb_document, public.kb_chunk, public.kb_version, public.kb_audit,
    public.qa_task, public.qa_rule, public.qa_alert, public.qa_review,
    public.file_meta, public.quick_reply, public.skill_group_member, public.csat_record,
    public.customer_order, public.tenant_config,
    public.channel_wechat_config, public.wechat_customer_bind, public.wechat_inbound_log, public.subscribe_message_log,
    public.channel_native_config, public.channel_identity, public.channel_inbound_log, public.channel_call_record
TO supportflow_customer_tenant;

GRANT SELECT, INSERT, DELETE ON public.kb_chunk TO supportflow_kb_tenant;
GRANT SELECT (id, tenant_code, title, status, is_deleted) ON public.kb_document TO supportflow_kb_tenant;
GRANT SELECT ON public.tenant_rls_status TO supportflow_customer_tenant;
GRANT SELECT, INSERT ON public.schema_upgrade_log TO supportflow_customer_tenant;

DO $$
DECLARE
    table_name name;
    predicate constant text :=
        'tenant_code = nullif(pg_catalog.current_setting(''app.tenant_code'', true), '''')'
        ' AND tenant_code <> ''PLATFORM'' AND tenant_code ~ ''^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$''';
BEGIN
    FOR table_name IN
        SELECT c.relname
        FROM pg_class AS c
        JOIN pg_namespace AS n ON n.oid = c.relnamespace
        JOIN pg_attribute AS a ON a.attrelid = c.oid
        WHERE n.nspname = 'public' AND c.relkind = 'r'
          AND a.attname = 'tenant_code' AND a.attnum > 0 AND NOT a.attisdropped
        ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', table_name || '_tenant_isolation', table_name);
        EXECUTE format('DROP POLICY IF EXISTS supportflow_tenant_scope ON public.%I', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON public.%I TO supportflow_customer_tenant, supportflow_kb_tenant USING (%s) WITH CHECK (%s)',
            table_name || '_tenant_isolation', table_name, predicate, predicate
        );
        -- A separate restrictive policy prevents a later permissive policy from widening these two roles.
        EXECUTE format(
            'CREATE POLICY supportflow_tenant_scope ON public.%I AS RESTRICTIVE TO supportflow_customer_tenant, '
            'supportflow_kb_tenant USING (%s) WITH CHECK (%s)',
            table_name, predicate, predicate
        );
    END LOOP;
END $$;


COMMIT;
