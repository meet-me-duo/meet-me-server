"""Historical #107 catalog oracle supersession; current integration is tested in Node.

The former discovery/count-256 oracle is incompatible with the approved exact V1..V16
contract. Its bytes are retained in .tdd/history/issue-107-integrated-catalog/.
Immutable historical readers, successful-prefix/approval rules, symlink/identity guards
and current publication are independently exercised by issue107-integrated-catalog.test.cjs.
This retained checksum regression never invokes a provider, AWS or a host command.
"""
import importlib.util
from pathlib import Path
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("issue107_checksum", ROOT / "deploy/pipeline/publish_manifest.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class ReleaseCatalogChecksumRegression(unittest.TestCase):
    def test_flyway_v10_checksum_bytes_keep_bom_newline_and_signed_crc_semantics(self):
        raw = b"\xef\xbb\xbf-- synthetic\r\nSELECT 13;\r\n"
        expected_crc = zlib.crc32(b"-- syntheticSELECT 13;")
        expected_signed = expected_crc if expected_crc < 2 ** 31 else expected_crc - 2 ** 32
        self.assertEqual(expected_signed, publisher.flyway_checksum(raw))


if __name__ == "__main__":
    unittest.main()
