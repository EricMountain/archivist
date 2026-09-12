"""Decrypts the repo's own testdata/vectors/ against this tool's crypto_format.py --
the conformance requirement crypto-format.md states directly: "Every client's test
suite decrypts these fixtures. A client that cannot is broken, regardless of what its
own round-trip tests say."

Run: .venv/bin/python3 -m unittest discover -s tests -v   (from tools/import-from-local/)
"""

from __future__ import annotations

import json
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import crypto_format as cf
import recovery_code as rc

VECTORS_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "testdata", "vectors"
)


def _load_manifest():
    with open(os.path.join(VECTORS_DIR, "manifest.json")) as f:
        return json.load(f)


def _plain_bytes(case: dict) -> bytes:
    """Reconstructs a case's plaintext from its pattern seed, per manifest.json's own
    `plainPatternSpec`: plaintext[32*i:32*i+32] = SHA256(seed || be32(i))."""
    length = case["plainLength"]
    seed = case["plainPatternSeed"].encode("utf-8")
    out = bytearray()
    i = 0
    while len(out) < length:
        out += __import__("hashlib").sha256(seed + struct.pack(">I", i)).digest()
        i += 1
    return bytes(out[:length])


def _cipher_bytes(case: dict) -> bytes:
    with open(os.path.join(VECTORS_DIR, case["files"]["cipher"]), "rb") as f:
        return f.read()


class WholeObjectVectors(unittest.TestCase):
    def test_all_whole_object_cases(self):
        manifest = _load_manifest()
        cases = {c["case"]: c for c in manifest["cases"]}
        for n in (1, 2, 3):
            case = cases[n]
            dek = bytes.fromhex(case["dek"])
            iv = bytes.fromhex(case["iv"])
            aad = case["aad"].encode("utf-8")
            expected_plain = _plain_bytes(case)
            cipher = _cipher_bytes(case)
            got = cf.whole_object_decrypt(dek, iv, aad, cipher)
            self.assertEqual(got, expected_plain, f"case {n} plaintext mismatch")
            # Round-trip: re-encrypting the same plaintext under the same iv/aad must
            # reproduce the exact committed ciphertext bytes (AES-GCM is deterministic
            # given a fixed key/iv), not just decrypt successfully.
            self.assertEqual(cf.whole_object_encrypt(dek, iv, aad, expected_plain), cipher)

        for n in (4, 5):
            case = cases[n]
            dek = bytes.fromhex(case["dek"])
            iv = bytes.fromhex(case["iv"])
            aad = case["aad"].encode("utf-8")
            cipher = _cipher_bytes(case)
            with self.assertRaises(Exception, msg=f"case {n} should fail to decrypt"):
                cf.whole_object_decrypt(dek, iv, aad, cipher)

    def test_ciphertext_length_whole_object(self):
        manifest = _load_manifest()
        for case in manifest["cases"]:
            if case.get("mode") != "whole" or "plainLength" not in case:
                continue
            cipher = _cipher_bytes(case)
            self.assertEqual(
                cf.ciphertext_length(case["plainLength"], 0),
                len(cipher),
                f"{case['id']}: bytes = plainBytes + 16",
            )


class StreamingVectors(unittest.TestCase):
    def test_decryptable_cases(self):
        manifest = _load_manifest()
        cases = {c["case"]: c for c in manifest["cases"]}
        for n in (6, 7, 8, 9, 10, 11):
            case = cases[n]
            dek = bytes.fromhex(case["dek"])
            aad = case["aad"].encode("utf-8")
            expected_plain = _plain_bytes(case)
            cipher = _cipher_bytes(case)
            got = cf.streaming_decrypt(dek, aad, cipher)
            self.assertEqual(got, expected_plain, f"case {n} plaintext mismatch")
            self.assertEqual(
                cf.ciphertext_length(case["plainLength"], cf.SEGMENT),
                len(cipher),
                f"case {n} ciphertext length formula",
            )
            # Round-trip through this module's own encryptor too -- same dek/aad,
            # freshly generated salt/nonce prefix, so lengths must match even though
            # the bytes themselves won't (both header fields are random per stream).
            reenc = cf.streaming_encrypt(dek, aad, expected_plain)
            self.assertEqual(len(reenc), len(cipher))
            self.assertEqual(cf.streaming_decrypt(dek, aad, reenc), expected_plain)

    def test_tamper_cases_fail(self):
        manifest = _load_manifest()
        cases = {c["case"]: c for c in manifest["cases"]}
        for n in (12, 13, 14, 15):
            case = cases[n]
            dek = bytes.fromhex(case["dek"])
            aad = case["aad"].encode("utf-8")
            cipher = _cipher_bytes(case)
            with self.assertRaises(Exception, msg=f"case {n} should fail to decrypt"):
                cf.streaming_decrypt(dek, aad, cipher)

    def test_segment_count_boundaries(self):
        # Cases 6-11 are exactly the boundary set crypto-format.md calls out.
        self.assertEqual(cf.segment_count(0), 1)
        self.assertEqual(cf.segment_count(cf.C0), 1)  # case 7: P == C0, one full segment
        self.assertEqual(cf.segment_count(cf.C0 + 1), 2)  # case 8
        self.assertEqual(cf.segment_count(cf.C0 + cf.CN), 2)  # case 9: exactly two full segments
        self.assertEqual(cf.segment_count(cf.C0 + cf.CN + 1), 3)  # case 10


class ByteRangeVector(unittest.TestCase):
    def test_case_22(self):
        manifest = _load_manifest()
        case = next(c for c in manifest["cases"] if c["case"] == 22)
        for r in case["ranges"]:
            i_a, off_a = _plain_to_segment(r["plainStart"])
            i_b, _ = _plain_to_segment(r["plainEnd"])
            cipher_start = i_a * cf.SEGMENT
            cipher_end = min((i_b + 1) * cf.SEGMENT, case["cipherLength"]) - 1
            self.assertEqual(cipher_start, r["cipherStart"])
            self.assertEqual(cipher_end, r["cipherEnd"])
            self.assertEqual(off_a, r["trimFront"])


def _plain_to_segment(p: int) -> tuple[int, int]:
    """crypto-format.md "Byte-range mapping"."""
    if p < cf.C0:
        return 0, p
    return 1 + (p - cf.C0) // cf.CN, (p - cf.C0) % cf.CN


class KeyWrapVector(unittest.TestCase):
    def test_case_16(self):
        manifest = _load_manifest()
        case = next(c for c in manifest["cases"] if c["case"] == 16)
        kek = bytes.fromhex(case["kek"])
        plaintext_key = bytes.fromhex(case["plaintextKey"])
        wrapped = cf.wrap_key(kek, plaintext_key)
        self.assertEqual(wrapped.hex(), case["expectedWrapped"])
        self.assertEqual(cf.unwrap_key(kek, wrapped), plaintext_key)


class Argon2Vector(unittest.TestCase):
    def test_case_18(self):
        manifest = _load_manifest()
        case = next(c for c in manifest["cases"] if c["case"] == 18)
        kek = rc.argon2id_kek(
            password=case["password"].encode("ascii"),
            salt=bytes.fromhex(case["salt"]),
            m_kib=case["params"]["m"],
            t=case["params"]["t"],
            p=case["params"]["p"],
            length=case["params"]["hashLen"],
        )
        self.assertEqual(kek.hex(), case["expectedKek"])

    def test_case_19_normalisation_and_kek(self):
        manifest = _load_manifest()
        case = next(c for c in manifest["cases"] if c["case"] == 19)
        normalised = rc.normalise(case["rawInput"])
        self.assertEqual(normalised, case["expectedNormalised"])
        kek = rc.argon2id_kek(
            password=normalised[:25].encode("ascii"),
            salt=bytes.fromhex(case["salt"]),
            m_kib=case["params"]["m"],
            t=case["params"]["t"],
            p=case["params"]["p"],
            length=case["params"]["hashLen"],
        )
        self.assertEqual(kek.hex(), case["expectedKek"])


class ChecksumVector(unittest.TestCase):
    def test_case_20(self):
        manifest = _load_manifest()
        case = next(c for c in manifest["cases"] if c["case"] == 20)
        for sub in case["cases"]:
            normalised = rc.normalise(sub["code"])
            ok = rc.verify_check_symbol(normalised) if len(normalised) == 26 else False
            expect_accept = sub["expect"] == "accept"
            self.assertEqual(ok, expect_accept, sub["label"])


class MemoryParamParsing(unittest.TestCase):
    def test_parses_real_wire_shapes(self):
        # kdfParams.m on the wire is a human string (KdfParamsDto.m: String,
        # android/core/crypto/.../KeyCustody.kt's own parseMemoryKib) -- not the
        # plain KiB integer testdata/vectors/manifest.json happens to use.
        self.assertEqual(rc.parse_memory_kib("64MiB"), 65536)
        self.assertEqual(rc.parse_memory_kib("65536KiB"), 65536)
        self.assertEqual(rc.parse_memory_kib("1GiB"), 1024 * 1024)

    def test_rejects_unrecognised_shape(self):
        with self.assertRaises(ValueError):
            rc.parse_memory_kib("64 MB")


class ContentHashSanity(unittest.TestCase):
    def test_prefix_and_hex_shape(self):
        h = cf.content_hash(b"\x00" * 32, b"hello world")
        self.assertTrue(h.startswith("hmac-sha256:"))
        self.assertEqual(len(h), len("hmac-sha256:") + 64)
        # Deterministic for the same key/message.
        self.assertEqual(h, cf.content_hash(b"\x00" * 32, b"hello world"))
        self.assertNotEqual(h, cf.content_hash(b"\x01" * 32, b"hello world"))


if __name__ == "__main__":
    unittest.main()
