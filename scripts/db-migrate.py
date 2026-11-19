"""Initialize absent databases, migrate existing schemas or verify history and actual structure."""
import argparse
import json
import os
import psycopg
from local_env import load
from migration_engine import MigrationError,load_plan,migrate_databases


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=("init","migrate","verify"))
    parser.add_argument("--database",choices=sorted(load_plan()))
    args=parser.parse_args()
    if args.command=="init" and args.database is not None:
        parser.error("init initializes all six absent databases; --database is only for migrate/verify")
    load()
    try:
        if args.command=="init":
            from database_init import initialize_databases
            result=initialize_databases(os.environ)
        else:
            result=migrate_databases(os.environ,verify=args.command=="verify",database=args.database)
    except MigrationError as error:
        parser.exit(1,str(error)+"\n")
    except psycopg.Error as error:
        parser.exit(1,f"Migration database unavailable; errorType={type(error).__name__} SQLSTATE={error.sqlstate}\n")
    print(json.dumps(result,ensure_ascii=False))


if __name__=="__main__": main()
