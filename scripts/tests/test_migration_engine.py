import hashlib
import os
from pathlib import Path
import sys
import unittest
import tempfile
import json

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import psycopg
from migration_engine import Migration,MigrationError,database_section,load_plan,run_plan


class MigrationParserTest(unittest.TestCase):
    def test_non_sql_configuration_files_cannot_be_executed_as_migrations(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest=Path(directory)/"manifest.json"
            manifest.write_text(json.dumps({"format":1,"migrations":[{"database":"customer_db","version":"001",
                                "name":"invalid","source":".env"}]}),encoding="utf-8")
            with self.assertRaisesRegex(MigrationError,"backend SQL file"):
                load_plan(manifest)

    def test_manifest_has_explicit_ordered_database_plans(self):
        plans=load_plan()
        self.assertEqual(["001","002","003","004","005","006","007"],[m.version for m in plans["customer_db"]])
        self.assertEqual(["001"],[m.version for m in plans["billing_db"]])
        for database in ("ai_db", "user_db", "tenant_db", "ops_db"):
            self.assertEqual(["001","002","003","004"] if database=='ai_db' else ["001"], [m.version for m in plans[database]])
            self.assertEqual("baseline", plans[database][0].kind)
        for plan in plans.values():
            for migration in plan:
                self.assertNotIn("\\connect",migration.content)
                self.assertEqual(64,len(migration.checksum))

    def test_database_sections_cannot_cross_connections(self):
        source="\\connect billing_db\nBEGIN;\nSELECT 1;\nCOMMIT;\n\\connect customer_db\nBEGIN;\nSELECT 2;\nCOMMIT;\n"
        self.assertEqual("SELECT 2;\n",database_section(source,"customer_db"))

    def test_includes_unknown_database_and_nested_transactions_are_rejected(self):
        for source in ("\\i other.sql", "\\connect untrusted_db\nSELECT 1;", "BEGIN;\nCOMMIT;\nSELECT 1;"):
            with self.assertRaises(MigrationError): database_section(source,"customer_db")

    def test_line_endings_are_normalized_but_sql_changes_affect_checksum(self):
        a=Migration("customer_db","001","test","test.sql","SELECT 1;\n")
        b=Migration("customer_db","001","test","test.sql","SELECT 1;\r\n")
        c=Migration("customer_db","001","test","test.sql","SELECT 2;\n")
        self.assertEqual(a.checksum,b.checksum)
        self.assertNotEqual(a.checksum,c.checksum)

    def test_baseline_contracts_are_versioned_and_contain_no_executable_statements(self):
        for database, plan in load_plan().items():
            for migration in plan:
                if migration.kind == "baseline":
                    contract = json.loads(migration.content)
                    self.assertEqual(database, contract["database"])
                    self.assertEqual(1, contract["format"])
                    self.assertTrue(contract["tables"])
                    self.assertEqual(64, len(contract["source"]["sha256"]))

    def test_unknown_migration_kind_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest=Path(directory)/"manifest.json"
            manifest.write_text(json.dumps({"format":1,"migrations":[{"database":"ai_db","version":"001",
                                "kind":"shell","name":"invalid","source":"backend/deploy/db-init/db-init.sql"}]}),encoding="utf-8")
            with self.assertRaisesRegex(MigrationError,"Unsupported migration kind"):
                load_plan(manifest)


@unittest.skipUnless(os.getenv("SUPPORTFLOW_MIGRATIONS_TEST_URL"),"requires disposable migration database")
class MigrationPostgresTest(unittest.TestCase):
    def setUp(self):
        self.connection=psycopg.connect(os.environ["SUPPORTFLOW_MIGRATIONS_TEST_URL"],autocommit=True)
        self.base=load_plan()["customer_db"]
        self.next_version=f"{int(self.base[-1].version)+1:03d}"
        run_plan(self.connection,self.base,verify=True)

    def tearDown(self):
        # Only the isolated fixture owns these synthetic objects; business tables are never cleared.
        self.connection.execute("DELETE FROM supportflow_schema_history WHERE version>%s",(self.base[-1].version,))
        self.connection.execute("DROP TABLE IF EXISTS supportflow_migration_test_effect")
        self.connection.close()

    def added(self,content):
        return [*self.base,Migration("customer_db",self.next_version,"synthetic transaction fault","fixture.sql",content)]

    def test_applied_migrations_are_noops_and_data_is_preserved(self):
        self.assertEqual([],run_plan(self.connection,self.base)["applied"])
        self.assertEqual(("L2r","migration-fixture/object"),self.connection.execute(
            "SELECT tenant_code,object_key FROM file_meta WHERE id=770001").fetchone())

    def test_failed_sql_and_its_receipt_roll_back_then_retry_succeeds(self):
        failed=self.added("CREATE TABLE supportflow_migration_test_effect(value int); INSERT INTO supportflow_migration_test_effect VALUES(1); SELECT 1/0;")
        with self.assertRaises(MigrationError): run_plan(self.connection,failed)
        self.assertEqual((None,),self.connection.execute("SELECT to_regclass('supportflow_migration_test_effect')").fetchone())
        self.assertEqual((0,),self.connection.execute("SELECT count(*) FROM supportflow_schema_history WHERE version=%s",(self.next_version,)).fetchone())
        corrected=self.added("CREATE TABLE supportflow_migration_test_effect(value int); INSERT INTO supportflow_migration_test_effect VALUES(1);")
        self.assertEqual([self.next_version],run_plan(self.connection,corrected)["applied"])
        self.assertEqual([],run_plan(self.connection,corrected)["applied"])
        self.assertEqual([(1,)],self.connection.execute("SELECT value FROM supportflow_migration_test_effect").fetchall())

    def test_applied_checksum_drift_is_rejected(self):
        original=self.added("CREATE TABLE supportflow_migration_test_effect(value int);")
        run_plan(self.connection,original)
        with self.assertRaisesRegex(MigrationError,"Checksum mismatch"):
            run_plan(self.connection,self.added(original[-1].content+"\nSELECT 2;"))

    def test_another_connection_cannot_migrate_under_an_active_lock(self):
        key=int.from_bytes(hashlib.sha256(b"supportflow:migrations:customer_db").digest()[:8],"big",signed=True)
        with psycopg.connect(os.environ["SUPPORTFLOW_MIGRATIONS_TEST_URL"],autocommit=True) as owner:
            owner.execute("SELECT pg_advisory_lock(%s)",(key,))
            try:
                with self.assertRaisesRegex(MigrationError,"owns this database lock"):
                    run_plan(self.connection,self.base)
            finally: owner.execute("SELECT pg_advisory_unlock(%s)",(key,))
        run_plan(self.connection,self.base,verify=True)

    def test_verify_can_run_in_a_read_only_transaction(self):
        with psycopg.connect(os.environ["SUPPORTFLOW_MIGRATIONS_TEST_URL"],autocommit=True,options="-c default_transaction_read_only=on") as reader:
            self.assertEqual([],run_plan(reader,self.base,verify=True)["applied"])

    def test_runtime_role_cannot_apply_migrations_or_create_history(self):
        with psycopg.connect(os.environ["SUPPORTFLOW_MIGRATIONS_RUNTIME_TEST_URL"],autocommit=True) as runtime:
            with self.assertRaisesRegex(MigrationError,"administrator"):
                run_plan(runtime,self.base)

    def test_read_only_verify_does_not_create_missing_history(self):
        info=psycopg.conninfo.conninfo_to_dict(os.environ["SUPPORTFLOW_MIGRATIONS_TEST_URL"])
        info["dbname"]="ai_db"
        with psycopg.connect(**info,autocommit=True) as other:
            other.execute("ALTER TABLE supportflow_schema_history RENAME TO supportflow_history_test_backup")
            try:
                with self.assertRaisesRegex(MigrationError,"history is absent"):
                    run_plan(other,[],verify=True)
                self.assertEqual((None,),other.execute("SELECT to_regclass('supportflow_schema_history')").fetchone())
            finally:
                other.execute("ALTER TABLE supportflow_history_test_backup RENAME TO supportflow_schema_history")

    def test_an_existing_transaction_cannot_turn_migration_commits_into_savepoints(self):
        self.connection.execute("BEGIN")
        try:
            with self.assertRaisesRegex(MigrationError,"owns transactions"):
                run_plan(self.connection,self.base)
        finally:
            self.connection.execute("ROLLBACK")

    def test_a_gap_in_history_is_rejected_before_applying_any_pending_sql(self):
        plan=self.added("CREATE TABLE supportflow_migration_test_effect(value int);")
        later=Migration("customer_db",f"{int(self.next_version)+1:03d}","synthetic out of order","fixture.sql","SELECT 1;")
        self.connection.execute("INSERT INTO supportflow_schema_history(version,name,checksum,source) VALUES(%s,%s,%s,%s)",
                                (later.version,later.name,later.checksum,later.source))
        with self.assertRaisesRegex(MigrationError,"contiguous"):
            run_plan(self.connection,[*plan,later])
        self.assertEqual((None,),self.connection.execute("SELECT to_regclass('supportflow_migration_test_effect')").fetchone())

    def test_migration_keeps_only_read_access_for_its_own_runtime_history(self):
        if not self.connection.execute("SELECT 1 FROM pg_roles WHERE rolname='supportflow_customer'").fetchone():
            self.connection.execute("CREATE ROLE supportflow_customer NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB")
        run_plan(self.connection,self.base)
        self.assertEqual((True,False),self.connection.execute("""SELECT
            has_table_privilege('supportflow_customer','supportflow_schema_history','SELECT'),
            has_table_privilege('supportflow_customer','supportflow_schema_history','INSERT,UPDATE,DELETE,TRUNCATE')""").fetchone())


if __name__=="__main__": unittest.main()
