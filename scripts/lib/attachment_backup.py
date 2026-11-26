"""Logical S3 attachment backup for the isolated recovery drill."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import hashlib
import json
import re
from zipfile import BadZipFile, ZipFile


MANIFEST = "manifest.json"
VERSION = "supportflow-attachments-v1"
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
CHUNK_BYTES = 64 * 1024


class InvalidAttachmentArchive(ValueError):
    pass


def _member(index):
    return f"objects/{index:08}"


def _digest(body, output=None):
    size = 0
    digest = hashlib.sha256()
    while chunk := body.read(CHUNK_BYTES):
        size += len(chunk)
        digest.update(chunk)
        if output is not None:
            output.write(chunk)
    return size, digest.hexdigest()


def list_objects(client, bucket, *, page_size=1000):
    paginator = client.get_paginator("list_objects_v2")
    return sorted(
        ((item["Key"], item["Size"]) for page in paginator.paginate(
            Bucket=bucket, PaginationConfig={"PageSize": page_size}
        ) for item in page.get("Contents", [])),
        key=lambda item: item[0],
    )


def backup_objects(client, bucket, archive, *, page_size=1000):
    inventory = list_objects(client, bucket, page_size=page_size)
    records = []
    with ZipFile(archive, "w", allowZip64=True) as writer:
        for index, (key, expected_size) in enumerate(inventory):
            response = client.get_object(Bucket=bucket, Key=key)
            with response["Body"] as body, writer.open(_member(index), "w", force_zip64=True) as output:
                size, digest = _digest(body, output)
            if size != expected_size or size != response["ContentLength"]:
                raise RuntimeError("An attachment changed during backup")
            records.append(dict(key=key, size=size, sha256=digest, content_type=response["ContentType"]))
        manifest = dict(schema_version=VERSION, bucket=bucket, objects=records)
        raw = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(raw) > MAX_MANIFEST_BYTES:
            raise InvalidAttachmentArchive("Attachment manifest is too large")
        writer.writestr(MANIFEST, raw)
    if list_objects(client, bucket, page_size=page_size) != inventory:
        raise RuntimeError("Source attachment inventory changed during backup")
    return records


def _validated_records(reader, bucket):
    if reader.getinfo(MANIFEST).file_size > MAX_MANIFEST_BYTES:
        raise InvalidAttachmentArchive("Attachment manifest is too large")
    manifest = json.loads(reader.read(MANIFEST))
    if (type(manifest) is not dict or manifest.keys() != {"schema_version", "bucket", "objects"}
            or manifest["schema_version"] != VERSION or manifest["bucket"] != bucket
            or type(manifest["objects"]) is not list):
        raise InvalidAttachmentArchive("Invalid attachment manifest")
    records = manifest["objects"]
    names = reader.namelist()
    expected = {MANIFEST} | {_member(index) for index in range(len(records))}
    if len(names) != len(expected) or set(names) != expected:
        raise InvalidAttachmentArchive("Unexpected attachment archive members")
    keys = set()
    for index, entry in enumerate(records):
        if type(entry) is not dict or entry.keys() != {"key", "size", "sha256", "content_type"}:
            raise InvalidAttachmentArchive("Invalid attachment record")
        key, size, digest, content_type = (entry[field] for field in ("key", "size", "sha256", "content_type"))
        if (type(key) is not str or not 0 < len(key.encode("utf-8")) <= 1024 or "\x00" in key or key in keys
                or type(size) is not int or size < 0 or size != reader.getinfo(_member(index)).file_size
                or type(digest) is not str or not re.fullmatch("[0-9a-f]{64}", digest)
                or type(content_type) is not str or not re.fullmatch("[ -~]{1,128}", content_type)):
            raise InvalidAttachmentArchive("Invalid attachment object metadata")
        keys.add(key)
        with reader.open(_member(index)) as body:
            if _digest(body) != (size, digest):
                raise InvalidAttachmentArchive("Attachment checksum mismatch")
    return records


def restore_objects(client, bucket, archive):
    """Validate the complete archive before writing to a pre-created private bucket."""
    if client.list_objects_v2(Bucket=bucket, MaxKeys=1).get("Contents"):
        raise ValueError("Attachment restore requires an empty bucket")
    archive.seek(0)
    try:
        reader = ZipFile(archive)
    except BadZipFile:
        raise InvalidAttachmentArchive("Invalid attachment archive") from None
    with reader:
        try:
            records = _validated_records(reader, bucket)
        except (BadZipFile, KeyError, UnicodeError, json.JSONDecodeError, RecursionError):
            raise InvalidAttachmentArchive("Malformed attachment archive") from None
        for index, entry in enumerate(records):
            with reader.open(_member(index)) as body:
                client.put_object(Bucket=bucket, Key=entry["key"], ContentType=entry["content_type"],
                                  ContentLength=entry["size"], Body=body, IfNoneMatch="*")
    return records


def verify_objects(client, bucket, records):
    expected = sorted((entry["key"], entry["size"]) for entry in records)
    if list_objects(client, bucket) != expected:
        raise ValueError("Restored attachment inventory differs")
    for entry in records:
        response = client.get_object(Bucket=bucket, Key=entry["key"])
        with response["Body"] as body:
            if (_digest(body) != (entry["size"], entry["sha256"])
                    or response["ContentLength"] != entry["size"]
                    or response["ContentType"] != entry["content_type"]):
                raise ValueError("Restored attachment bytes or MIME type differ")


def verify_references(references, records):
    """Match active file metadata against the recovered bucket inventory."""
    by_key = {entry["key"]: entry for entry in records}
    for key, size, content_type in references:
        entry = by_key.get(key)
        if entry is None or entry["size"] != size or (content_type and entry["content_type"] != content_type):
            raise ValueError("Live file reference differs from attachment inventory")
    return len(references)
