"""Create a named platform administrator in an initialized user_db."""

import argparse
from getpass import getpass
import os
import secrets

import bcrypt
import psycopg
from local_env import load


def main() -> None:
    load()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", required=True)
    parser.add_argument("--name", default="SupportFlow Admin")
    args = parser.parse_args()
    email = args.email.strip()
    name = args.name.strip()
    if email.count("@") != 1 or email.startswith("@") or email.endswith("@") or len(email) > 128 or any(char.isspace() for char in email):
        parser.error("Enter a valid email address of up to 128 characters.")
    if not 1 <= len(name) <= 64:
        parser.error("The name must contain 1 to 64 characters.")

    database_password = os.environ.get("SUPPORTFLOW_DB_PASSWORD")
    if not database_password:
        parser.error("Set SUPPORTFLOW_DB_PASSWORD before running this script.")
    password = getpass("New admin password: ")
    if not 10 <= len(password) <= 64 or len(password.encode()) > 72:
        parser.error("Use 10 to 64 characters and at most 72 UTF-8 bytes.")
    if password != getpass("Confirm password: "):
        parser.error("The passwords did not match.")

    password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt(prefix=b"2a")).decode()
    user_no = "SF" + secrets.token_hex(8)
    with psycopg.connect(
        host=os.environ.get("SUPPORTFLOW_DB_HOST", "127.0.0.1"),
        port=os.environ.get("SUPPORTFLOW_DB_PORT", "5432"),
        dbname=os.environ.get("SUPPORTFLOW_USER_DB", "user_db"),
        user=os.environ.get("SUPPORTFLOW_DB_USER", "supportflow"),
        password=database_password,
    ) as connection:
        with connection.cursor() as cursor:
            cursor.execute("LOCK TABLE sys_user IN SHARE ROW EXCLUSIVE MODE")
            cursor.execute("SELECT 1 FROM sys_user WHERE lower(email) = lower(%s) AND NOT is_deleted", (email,))
            if cursor.fetchone():
                raise SystemExit("That email already belongs to an account.")
            cursor.execute("""
                SELECT id FROM sys_role
                WHERE tenant_code = 'PLATFORM' AND role_code = 'PLATFORM_ADMIN' AND NOT is_deleted
                """)
            role = cursor.fetchone()
            if role is None:
                raise SystemExit("The PLATFORM_ADMIN role is missing. Initialize user_db first.")
            user_id = secrets.randbits(62) + 1
            cursor.execute("""
                INSERT INTO sys_user (id, tenant_code, user_no, name, email, password, user_type)
                VALUES (%s, 'PLATFORM', %s, %s, %s, %s, 1)
                """, (user_id, user_no, name, email, password_hash))
            cursor.execute("""
                INSERT INTO sys_user_role (id, tenant_code, user_id, role_id)
                VALUES (%s, 'PLATFORM', %s, %s)
                """, (secrets.randbits(62) + 1, user_id, role[0]))
    print(f"Platform administrator created. Sign in with {email} or {user_no}.")


if __name__ == "__main__":
    main()
