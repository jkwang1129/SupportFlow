"""Recovery fixtures use isolated RustFS volumes and generated test credentials."""

from contextlib import contextmanager
from io import BytesIO
import os
import secrets
import subprocess
from time import monotonic, sleep
from zipfile import ZipFile

import boto3
from botocore import UNSIGNED
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from attachment_backup import InvalidAttachmentArchive, restore_objects, verify_objects, verify_references


BUCKET = "supportflow"
IMAGE = "rustfs/rustfs@sha256:ee717072290bec9b11abfc0365f8310b00df1cd9b3639943edae47bdef05044a"
VECTOR = "[" + ",".join(["1"] + ["0"] * 1023) + "]"
CHAT_IMAGE_ID = 7060001
AVATAR_ID = 7060002
CHAT_IMAGE_KEY = "chat/RESTORE_A/RESTORE_SESSION_1/截图.png"
AVATAR_KEY = "bot/avatar/RESTORE_A/bot.png"


@contextmanager
def disposable_rustfs():
    name = "supportflow-object-restore-" + secrets.token_hex(6)
    env = {**os.environ, "RUSTFS_ACCESS_KEY": "restore-" + secrets.token_hex(8),
           "RUSTFS_SECRET_KEY": secrets.token_hex(24)}
    subprocess.run([
        "docker", "run", "-d", "--name", name, "-e", "RUSTFS_ACCESS_KEY", "-e", "RUSTFS_SECRET_KEY",
        "-e", "RUSTFS_ADDRESS=:9000", "-e", "RUSTFS_CONSOLE_ENABLE=false",
        "-p", "127.0.0.1::9000", IMAGE, "/data",
    ], check=True, env=env, stdout=subprocess.DEVNULL, timeout=180)
    client = None
    try:
        address = subprocess.check_output(["docker", "port", name, "9000/tcp"], text=True, timeout=10)
        port = int(address.strip().splitlines()[0].rsplit(":", 1)[1])
        client = boto3.client(
            "s3", endpoint_url=f"http://127.0.0.1:{port}", region_name="us-east-1",
            aws_access_key_id=env["RUSTFS_ACCESS_KEY"], aws_secret_access_key=env["RUSTFS_SECRET_KEY"],
            config=Config(s3={"addressing_style": "path"}, proxies={}, connect_timeout=2, read_timeout=10,
                          retries={"total_max_attempts": 1}, request_checksum_calculation="when_required"),
        )
        deadline = monotonic() + 40
        while True:
            try:
                client.create_bucket(Bucket=BUCKET)
                break
            except (BotoCoreError, ClientError):
                if monotonic() >= deadline:
                    raise RuntimeError("Disposable RustFS did not initialize") from None
                sleep(.25)
        yield client, {
            "SUPPORTFLOW_RUSTFS_ENDPOINT": "127.0.0.1", "SUPPORTFLOW_RUSTFS_PORT": str(port),
            "SUPPORTFLOW_RUSTFS_ACCESS_KEY": env["RUSTFS_ACCESS_KEY"],
            "SUPPORTFLOW_RUSTFS_SECRET_KEY": env["RUSTFS_SECRET_KEY"], "SUPPORTFLOW_RUSTFS_BUCKET": BUCKET,
        }
    finally:
        if client is not None:
            client.close()
        subprocess.run(["docker", "rm", "--force", "--volumes", name], check=True,
                       stdout=subprocess.DEVNULL, timeout=30)


def seed_attachments(connect, client):
    files = [
        ("RESTORE_A", CHAT_IMAGE_KEY, 5, "image/png", b"chat-image" * 8000),
        ("RESTORE_A", AVATAR_KEY, 1, "image/png", b"synthetic-avatar"),
        ("RESTORE_B", "kb/document/RESTORE_B/refund.txt", 2, "text/plain", b"synthetic refund reference"),
        ("RESTORE_B", "enterprise/license/RESTORE_B/license.pdf", 2, "application/pdf", b"%PDF-synthetic"),
    ]
    with connect("customer_db") as database:
        for file_id, (tenant, key, kind, mime, payload) in enumerate(files, start=CHAT_IMAGE_ID):
            client.put_object(Bucket=BUCKET, Key=key, Body=payload, ContentType=mime)
            database.execute("""
                INSERT INTO file_meta(id,tenant_code,file_no,file_name,object_key,file_size,mime_type,biz_type)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
            """, (file_id, tenant, str(file_id), key.rsplit("/", 1)[-1], key, len(payload), mime, kind))
        database.execute("""
            INSERT INTO file_meta(id,tenant_code,file_no,file_name,object_key,file_size,biz_type,is_deleted)
            VALUES(7060005,'RESTORE_A','deleted-fixture','deleted.bin','deleted/fixture',1,2,TRUE)
        """)
        database.execute("""
            INSERT INTO kb_document(id,tenant_code,doc_no,title,file_id,source_type,status)
            VALUES(7060010,'RESTORE_B','restore-doc','Synthetic document',7060003,2,3)
        """)
        database.execute("""
            INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content,char_count,token_count,embedding,embedding_model)
            VALUES(7060011,'RESTORE_B',7060010,1,'synthetic refund reference',26,3,%s::vector,'restore-fixture')
        """, (VECTOR,))
    client.put_object(Bucket=BUCKET, Key="retained/unreferenced", Body=b"fixture", ContentType="application/octet-stream")


def live_references(connect):
    with connect("customer_db", readonly=True) as database:
        return database.execute("""
            SELECT object_key,file_size,mime_type FROM file_meta WHERE NOT is_deleted ORDER BY id
        """).fetchall()


def restored_knowledge(connect):
    with connect("customer_db", readonly=True) as database:
        return database.execute("""
            SELECT d.id,f.object_key,c.content,c.embedding_model,c.embedding <=> %s::vector AS distance
              FROM kb_document d
              JOIN file_meta f ON f.tenant_code=d.tenant_code AND f.id=d.file_id AND NOT f.is_deleted
              JOIN kb_chunk c ON c.tenant_code=d.tenant_code AND c.doc_id=d.id
             WHERE d.id=7060010 AND d.tenant_code='RESTORE_B' AND d.status=3
        """, (VECTOR,)).fetchall()


def reject_corrupted_archive(archive, target):
    corrupted = BytesIO()
    with ZipFile(archive) as source, ZipFile(corrupted, "w") as target_zip:
        for member in source.namelist():
            raw = source.read(member)
            if member == "objects/00000000":
                raw = bytes([raw[0] ^ 1]) + raw[1:]
            target_zip.writestr(member, raw)
    try:
        restore_objects(target, BUCKET, corrupted)
    except InvalidAttachmentArchive:
        assert not target.list_objects_v2(Bucket=BUCKET, MaxKeys=1).get("Contents")
    else:
        raise AssertionError("Corrupted archive was accepted")


def verify_target_guards(target, archive, records):
    try:
        restore_objects(target, BUCKET, archive)
    except ValueError as failure:
        assert str(failure) == "Attachment restore requires an empty bucket"
    else:
        raise AssertionError("Nonempty bucket was accepted")
    try:
        target.put_object(Bucket=BUCKET, Key=records[0]["key"], Body=b"fixture overwrite",
                          IfNoneMatch="*")
    except ClientError as failure:
        assert failure.response["ResponseMetadata"]["HTTPStatusCode"] == 412
    else:
        raise AssertionError("RustFS accepted conditional overwrite")
    anonymous = boto3.client(
        "s3", endpoint_url=target.meta.endpoint_url, region_name="us-east-1",
        aws_access_key_id="fixture-unused", aws_secret_access_key="fixture-unused",
        config=Config(signature_version=UNSIGNED, s3={"addressing_style": "path"}, proxies={},
                      connect_timeout=2, read_timeout=5, retries={"total_max_attempts": 1}),
    )
    try:
        try:
            response = anonymous.get_object(Bucket=BUCKET, Key=records[0]["key"])
        except ClientError as failure:
            assert failure.response["ResponseMetadata"]["HTTPStatusCode"] == 403
        else:
            response["Body"].close()
            raise AssertionError("Restored object allows anonymous reads")
    finally:
        anonymous.close()
    verify_objects(target, BUCKET, records)


def verify_missing_reference_guard(references, records):
    try:
        verify_references([*references, ("missing/fixture", 1, "text/plain")], records)
    except ValueError:
        return
    raise AssertionError("Missing active reference was accepted")
