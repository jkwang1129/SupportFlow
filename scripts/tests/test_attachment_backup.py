"""Validate archive and destination checks before the first S3 write."""

import hashlib
from io import BytesIO
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from attachment_backup import InvalidAttachmentArchive, VERSION, restore_objects, verify_references


BUCKET = "fixture-bucket"
PAYLOAD = b"object data"


def record(key):
    return {"key": key, "size": len(PAYLOAD), "sha256": hashlib.sha256(PAYLOAD).hexdigest(),
            "content_type": "text/plain"}


def archive(records, *, bucket=BUCKET, last_payload=PAYLOAD, extra_member=False):
    stream = BytesIO()
    with ZipFile(stream, "w") as writer:
        writer.writestr("manifest.json", json.dumps({"schema_version": VERSION, "bucket": bucket,
                                                    "objects": records}))
        for index in range(len(records)):
            writer.writestr(f"objects/{index:08}", last_payload if index == len(records) - 1 else PAYLOAD)
        if extra_member:
            writer.writestr("unlisted", b"fixture")
    return stream


class AttachmentBackupTest(unittest.TestCase):
    def setUp(self):
        self.target = Mock()
        self.target.list_objects_v2.return_value = {}

    def test_corrupted_later_member_is_rejected_before_any_put(self):
        damaged = PAYLOAD[:-1] + b"x"
        with self.assertRaisesRegex(InvalidAttachmentArchive, "checksum"):
            restore_objects(self.target, BUCKET, archive([record("first"), record("last")], last_payload=damaged))
        self.target.put_object.assert_not_called()

    def test_duplicate_keys_are_rejected_before_any_put(self):
        with self.assertRaises(InvalidAttachmentArchive):
            restore_objects(self.target, BUCKET, archive([record("same"), record("same")]))
        self.target.put_object.assert_not_called()

    def test_bucket_name_and_archive_inventory_are_validated(self):
        for invalid in (archive([record("one")], bucket="other-bucket"),
                        archive([record("one")], extra_member=True)):
            with self.subTest(archive=invalid):
                with self.assertRaises(InvalidAttachmentArchive):
                    restore_objects(self.target, BUCKET, invalid)
        self.target.put_object.assert_not_called()

    def test_invalid_zip_is_rejected(self):
        with self.assertRaises(InvalidAttachmentArchive):
            restore_objects(self.target, BUCKET, BytesIO(b"invalid fixture"))
        self.target.put_object.assert_not_called()

    def test_nonempty_target_is_rejected_without_a_put(self):
        self.target.list_objects_v2.return_value = {"Contents": [{"Key": "existing"}]}
        with self.assertRaisesRegex(ValueError, "empty bucket"):
            restore_objects(self.target, BUCKET, archive([record("one")]))
        self.target.put_object.assert_not_called()

    def test_restore_uses_conditional_writes_and_preserves_metadata(self):
        entries = [record("tenant/path/示例.txt")]
        observed = []
        self.target.put_object.side_effect = lambda **kwargs: observed.append(kwargs["Body"].read())
        self.assertEqual(restore_objects(self.target, BUCKET, archive(entries)), entries)
        request = self.target.put_object.call_args.kwargs
        self.assertEqual((request["Key"], request["ContentType"], request["ContentLength"], request["IfNoneMatch"]),
                         (entries[0]["key"], "text/plain", len(PAYLOAD), "*"))
        self.assertEqual(observed, [PAYLOAD])

    def test_reference_checks_reject_missing_file_size_or_mime_mismatch(self):
        entries = [record("one")]
        self.assertEqual(verify_references([("one", len(PAYLOAD), None)], entries), 1)
        for reference in (("missing", len(PAYLOAD), "text/plain"), ("one", 1, "text/plain"),
                          ("one", len(PAYLOAD), "image/png")):
            with self.subTest(reference=reference):
                with self.assertRaises(ValueError):
                    verify_references([reference], entries)


if __name__ == "__main__":
    unittest.main()
