#!/usr/bin/env python3
"""Generates the crypto format v1 conformance vectors in testdata/vectors/.

Implements docs/design/crypto-format.md exactly. Every case is self-verified
(decrypted, or confirmed to fail) before being written, so a bug here fails
loudly at generation time rather than producing a silently-wrong fixture.

Run: .venv/bin/python3 generate.py
"""

from __future__ import annotations

import hashlib
import io
import json
import struct
from pathlib import Path

import tink
from tink import cleartext_keyset_handle, streaming_aead
from tink.proto import aes_gcm_hkdf_streaming_pb2, common_pb2, tink_pb2

from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.keywrap import aes_key_wrap
from cryptography.hazmat.primitives import hashes

import argon2.low_level as argon2_low_level

streaming_aead.register()

OUT_DIR = Path(__file__).resolve().parent.parent.parent / "testdata" / "vectors"

SEGMENT = 1048576
HEADER = 40
TAG = 16
C0 = SEGMENT - HEADER - TAG  # 1048520
CN = SEGMENT - TAG  # 1048560

assert C0 == 1048520
assert CN == 1048560

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

PHOTO_ID = "01K5A2Q8ZCV1D9KXM3BQNR7T2F"
OTHER_PHOTO_ID = "01K5A2Q8ZD00000000000000A"
RENDITION_ID = "01K5A2Q8ZCW4MB7XKQNV2HTRF3"


# ---------------------------------------------------------------------------
# Deterministic byte generation, so re-running this script reproduces the
# exact same fixtures.


def derive_bytes(label: str, length: int) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < length:
        out += hashlib.sha256(label.encode("utf-8") + struct.pack(">I", counter)).digest()
        counter += 1
    return bytes(out[:length])


def pattern_bytes(seed: str, length: int) -> bytes:
    """The plaintext pattern documented as `plainPatternSpec` in manifest.json."""
    return derive_bytes(seed, length)


def aad_bytes(s: str) -> bytes:
    return s.encode("utf-8")


# ---------------------------------------------------------------------------
# Mode A -- whole object


def whole_encrypt(dek: bytes, iv: bytes, aad: bytes, plaintext: bytes) -> bytes:
    return AESGCM(dek).encrypt(iv, plaintext, aad)


def whole_decrypt(dek: bytes, iv: bytes, aad: bytes, ciphertext: bytes) -> bytes:
    return AESGCM(dek).decrypt(iv, ciphertext, aad)


# ---------------------------------------------------------------------------
# Mode B -- streaming (Tink AES256_GCM_HKDF_1MB, used directly)


class _NonClosingBytesIO(io.BytesIO):
    def close(self) -> None:  # keep the buffer readable after the `with` block
        pass


def _streaming_handle(dek: bytes) -> tink.KeysetHandle:
    key_proto = aes_gcm_hkdf_streaming_pb2.AesGcmHkdfStreamingKey(
        version=0,
        params=aes_gcm_hkdf_streaming_pb2.AesGcmHkdfStreamingParams(
            ciphertext_segment_size=SEGMENT,
            derived_key_size=32,
            hkdf_hash_type=common_pb2.SHA256,
        ),
        key_value=dek,
    )
    key_data = tink_pb2.KeyData(
        type_url="type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey",
        value=key_proto.SerializeToString(),
        key_material_type=tink_pb2.KeyData.SYMMETRIC,
    )
    key = tink_pb2.Keyset.Key(
        key_data=key_data,
        status=tink_pb2.ENABLED,
        key_id=1,
        output_prefix_type=tink_pb2.RAW,
    )
    keyset = tink_pb2.Keyset(primary_key_id=1, key=[key])
    return cleartext_keyset_handle.from_keyset(keyset)


def streaming_encrypt(dek: bytes, aad: bytes, plaintext: bytes) -> bytes:
    prim = _streaming_handle(dek).primitive(streaming_aead.StreamingAead)
    buf = _NonClosingBytesIO()
    with prim.new_encrypting_stream(buf, aad) as enc:
        enc.write(plaintext)
    return buf.getvalue()


def streaming_decrypt(dek: bytes, aad: bytes, ciphertext: bytes) -> bytes:
    prim = _streaming_handle(dek).primitive(streaming_aead.StreamingAead)
    with prim.new_decrypting_stream(io.BytesIO(ciphertext), aad) as dec:
        return dec.read()


def n_segments(plain_len: int) -> int:
    if plain_len <= C0:
        return 1
    import math

    return 1 + math.ceil((plain_len - C0) / CN)


def expected_ciphertext_len(plain_len: int) -> int:
    return HEADER + plain_len + TAG * n_segments(plain_len)


# ---------------------------------------------------------------------------
# Key wrapping primitives


def aes_kw(kek: bytes, key: bytes) -> bytes:
    return aes_key_wrap(kek, key)


def rsa_oaep256_encrypt(pub, plaintext: bytes) -> bytes:
    return pub.encrypt(
        plaintext,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


def rsa_oaep256_decrypt(priv, ciphertext: bytes) -> bytes:
    return priv.decrypt(
        ciphertext,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


def argon2id_kek(password: bytes, salt: bytes, m_kib: int, t: int, p: int, length: int) -> bytes:
    return argon2_low_level.hash_secret_raw(
        secret=password,
        salt=salt,
        time_cost=t,
        memory_cost=m_kib,
        parallelism=p,
        hash_len=length,
        type=argon2_low_level.Type.ID,
        version=19,  # 0x13
    )


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt or None, info=info).derive(ikm)


# ---------------------------------------------------------------------------
# Recovery code


def check_symbol(entropy25: str) -> str:
    total = sum((2 * i + 1) * ALPHABET.index(c) for i, c in enumerate(entropy25))
    return ALPHABET[total % 32]


def make_code(entropy25: str) -> str:
    return entropy25 + check_symbol(entropy25)


def normalise(code: str) -> str:
    out = []
    for ch in code.upper():
        if ch in ALPHABET:
            out.append(ch)
        elif ch in ("I", "L"):
            out.append("1")
        elif ch == "O":
            out.append("0")
        # anything else (hyphens, whitespace, U, ...) is dropped
    return "".join(out)


def verify_code(code: str) -> bool:
    n = normalise(code)
    if len(n) != 26:
        return False
    return check_symbol(n[:25]) == n[25]


# ---------------------------------------------------------------------------


class VectorSet:
    def __init__(self, out_dir: Path):
        self.out_dir = out_dir
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.entries: list[dict] = []

    def write(self, name: str, data: bytes) -> str:
        (self.out_dir / name).write_bytes(data)
        return name

    def add(self, entry: dict) -> None:
        self.entries.append(entry)

    def save_manifest(self) -> None:
        manifest = {
            "cryptoVersion": 1,
            "plainPatternSpec": (
                "For cases that omit a .plain file: plaintext[32*i : 32*i+32] = "
                "SHA256(UTF8(plainPatternSeed) || be32(i)) for i = 0, 1, 2, ...; "
                "the final block is truncated to the remaining length. "
                "See tools/gen-vectors/generate.py:derive_bytes."
            ),
            "cases": self.entries,
        }
        (self.out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main() -> None:
    vs = VectorSet(OUT_DIR)

    # -- Whole-object cases (1-5) -------------------------------------------------

    # One DEK plays the role of one asset's DEK; each object (case) below gets
    # its own freshly-generated IV, exactly as design.md requires.
    dek_whole = derive_bytes("dek:whole", 32)
    aad_whole = f"archivist:1:{PHOTO_ID}:r:{RENDITION_ID}"

    def whole_case(case_no: int, cid: str, plain_len: int, seed: str):
        iv = derive_bytes(f"iv:{cid}", 12)
        plaintext = pattern_bytes(seed, plain_len)
        ct = whole_encrypt(dek_whole, iv, aad_bytes(aad_whole), plaintext)
        assert len(ct) == plain_len + TAG
        assert whole_decrypt(dek_whole, iv, aad_bytes(aad_whole), ct) == plaintext
        vs.write(f"{cid}.cipher", ct)
        vs.add({
            "case": case_no,
            "id": cid,
            "mode": "whole",
            "dek": dek_whole.hex(),
            "iv": iv.hex(),
            "aad": aad_whole,
            "plainLength": plain_len,
            "plainPatternSeed": seed,
            "files": {"cipher": f"{cid}.cipher"},
            "expect": "decrypt",
        })
        return ct, plaintext, iv

    whole_case(1, "01-whole-empty", 0, "whole-empty")
    whole_case(2, "02-whole-1byte", 1, "whole-1byte")
    ct3, pt3, iv3 = whole_case(3, "03-whole-typical", 200_000, "whole-typical-jpeg")

    # 4: case 3's ciphertext with one tag bit flipped.
    ct4 = bytearray(ct3)
    ct4[-1] ^= 0x01
    ct4 = bytes(ct4)
    try:
        whole_decrypt(dek_whole, iv3, aad_bytes(aad_whole), ct4)
        raise AssertionError("case 4 was expected to fail to decrypt")
    except Exception:
        pass
    vs.write("04-whole-tag-bit-flipped.cipher", ct4)
    vs.add({
        "case": 4,
        "id": "04-whole-tag-bit-flipped",
        "mode": "whole",
        "dek": dek_whole.hex(),
        "iv": iv3.hex(),
        "aad": aad_whole,
        "files": {"cipher": "04-whole-tag-bit-flipped.cipher"},
        "expect": "fail",
    })

    # 5: case 3's ciphertext, unmodified, decrypted against a different photoId.
    altered_aad = f"archivist:1:{OTHER_PHOTO_ID}:r:{RENDITION_ID}"
    try:
        whole_decrypt(dek_whole, iv3, aad_bytes(altered_aad), ct3)
        raise AssertionError("case 5 was expected to fail to decrypt")
    except Exception:
        pass
    vs.write("05-whole-aad-altered.cipher", ct3)
    vs.add({
        "case": 5,
        "id": "05-whole-aad-altered",
        "mode": "whole",
        "dek": dek_whole.hex(),
        "iv": iv3.hex(),
        "aad": altered_aad,
        "files": {"cipher": "05-whole-aad-altered.cipher"},
        "expect": "fail",
        "note": "cipher is case 3's, unmodified; aad here is deliberately wrong",
    })

    # -- Streaming cases (6-15) ---------------------------------------------------

    dek_stream = derive_bytes("dek:stream", 32)
    aad_stream = f"archivist:1:{PHOTO_ID}:x"

    def stream_case(case_no: int, cid: str, plain_len: int, seed: str) -> bytes:
        plaintext = pattern_bytes(seed, plain_len)
        ct = streaming_encrypt(dek_stream, aad_bytes(aad_stream), plaintext)
        assert len(ct) == expected_ciphertext_len(plain_len), (cid, len(ct), expected_ciphertext_len(plain_len))
        assert streaming_decrypt(dek_stream, aad_bytes(aad_stream), ct) == plaintext
        vs.write(f"{cid}.cipher", ct)
        vs.add({
            "case": case_no,
            "id": cid,
            "mode": "streaming",
            "dek": dek_stream.hex(),
            "aad": aad_stream,
            "plainLength": plain_len,
            "plainPatternSeed": seed,
            "files": {"cipher": f"{cid}.cipher"},
            "expect": "decrypt",
        })
        return ct

    stream_case(6, "06-stream-empty", 0, "stream-empty")
    stream_case(7, "07-stream-c0", C0, "stream-c0")
    stream_case(8, "08-stream-c0-plus-1", C0 + 1, "stream-c0-plus-1")
    stream_case(9, "09-stream-c0-plus-cn", C0 + CN, "stream-c0-plus-cn")
    ct10 = stream_case(10, "10-stream-c0-plus-cn-plus-1", C0 + CN + 1, "stream-c0-plus-cn-plus-1")
    assert n_segments(C0 + CN + 1) == 3

    plain_len_11 = 3_500_000
    ct11 = stream_case(11, "11-stream-multi-mb", plain_len_11, "stream-multi-mb")
    assert n_segments(plain_len_11) == 4

    # 12: case 10 (3 segments) with the final segment removed.
    ct12 = ct10[: 2 * SEGMENT]
    try:
        streaming_decrypt(dek_stream, aad_bytes(aad_stream), ct12)
        raise AssertionError("case 12 was expected to fail to decrypt")
    except Exception:
        pass
    vs.write("12-stream-final-segment-dropped.cipher", ct12)
    vs.add({
        "case": 12,
        "id": "12-stream-final-segment-dropped",
        "mode": "streaming",
        "dek": dek_stream.hex(),
        "aad": aad_stream,
        "files": {"cipher": "12-stream-final-segment-dropped.cipher"},
        "expect": "fail",
        "note": "case 10's ciphertext with the final (3rd) segment removed",
    })

    # 13: case 11 truncated mid final-segment.
    ct13 = ct11[:-500]
    try:
        streaming_decrypt(dek_stream, aad_bytes(aad_stream), ct13)
        raise AssertionError("case 13 was expected to fail to decrypt")
    except Exception:
        pass
    vs.write("13-stream-truncated-mid-segment.cipher", ct13)
    vs.add({
        "case": 13,
        "id": "13-stream-truncated-mid-segment",
        "mode": "streaming",
        "dek": dek_stream.hex(),
        "aad": aad_stream,
        "files": {"cipher": "13-stream-truncated-mid-segment.cipher"},
        "expect": "fail",
        "note": "case 11's ciphertext with the last 500 bytes dropped mid final-segment",
    })

    # 14: case 10's segments 1 and 2 (0-indexed) exchanged.
    seg0 = ct10[0:SEGMENT]
    seg1 = ct10[SEGMENT : 2 * SEGMENT]
    seg2 = ct10[2 * SEGMENT :]
    ct14 = seg0 + seg2 + seg1
    try:
        streaming_decrypt(dek_stream, aad_bytes(aad_stream), ct14)
        raise AssertionError("case 14 was expected to fail to decrypt")
    except Exception:
        pass
    vs.write("14-stream-segments-swapped.cipher", ct14)
    vs.add({
        "case": 14,
        "id": "14-stream-segments-swapped",
        "mode": "streaming",
        "dek": dek_stream.hex(),
        "aad": aad_stream,
        "files": {"cipher": "14-stream-segments-swapped.cipher"},
        "expect": "fail",
        "note": "case 10's ciphertext with segment index 1 and segment index 2 exchanged",
    })

    # 15: case 11 with the header salt (bytes 1..33) altered.
    ct15 = bytearray(ct11)
    ct15[1] ^= 0x01
    ct15 = bytes(ct15)
    try:
        streaming_decrypt(dek_stream, aad_bytes(aad_stream), ct15)
        raise AssertionError("case 15 was expected to fail to decrypt")
    except Exception:
        pass
    vs.write("15-stream-header-salt-altered.cipher", ct15)
    vs.add({
        "case": 15,
        "id": "15-stream-header-salt-altered",
        "mode": "streaming",
        "dek": dek_stream.hex(),
        "aad": aad_stream,
        "files": {"cipher": "15-stream-header-salt-altered.cipher"},
        "expect": "fail",
        "note": "case 11's ciphertext with one bit flipped in the header salt",
    })

    # -- Key wrapping (16-21) ------------------------------------------------------

    kek16 = derive_bytes("kek:16", 32)
    dek16 = derive_bytes("dek:16", 32)
    wrapped16 = aes_kw(kek16, dek16)
    assert len(wrapped16) == 40
    from cryptography.hazmat.primitives.keywrap import aes_key_unwrap

    assert aes_key_unwrap(kek16, wrapped16) == dek16
    vs.add({
        "case": 16,
        "id": "16-aes-kw-dek",
        "mode": "aes-kw",
        "kek": kek16.hex(),
        "plaintextKey": dek16.hex(),
        "expectedWrapped": wrapped16.hex(),
        "expect": "exact",
    })

    rsa_priv = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    rsa_pub = rsa_priv.public_key()
    master_key17 = derive_bytes("masterkey:17", 32)
    ct17 = rsa_oaep256_encrypt(rsa_pub, master_key17)
    assert rsa_oaep256_decrypt(rsa_priv, ct17) == master_key17
    pub_numbers = rsa_pub.public_numbers()
    priv_numbers = rsa_priv.private_numbers()
    vs.write("17-rsa-oaep-unwrap.cipher", ct17)
    vs.add({
        "case": 17,
        "id": "17-rsa-oaep-unwrap",
        "mode": "rsa-oaep",
        "rsaModulus": format(pub_numbers.n, "x"),
        "rsaPublicExponent": pub_numbers.e,
        "rsaPrivateExponent": format(priv_numbers.d, "x"),
        "expectedPlaintext": master_key17.hex(),
        "files": {"cipher": "17-rsa-oaep-unwrap.cipher"},
        "expect": "exact",
        "note": "RSA-OAEP-256, MGF1-SHA256 explicit -- see crypto-format.md's Android warning",
    })

    entropy18 = "".join(ALPHABET[b % 32] for b in derive_bytes("entropy:18", 25))
    salt18 = derive_bytes("salt:18", 16)
    kek18 = argon2id_kek(entropy18.encode("ascii"), salt18, 65536, 3, 1, 32)
    vs.add({
        "case": 18,
        "id": "18-argon2id-kek",
        "mode": "argon2id",
        "password": entropy18,
        "salt": salt18.hex(),
        "params": {"m": 65536, "t": 3, "p": 1, "hashLen": 32, "version": "0x13", "type": "id"},
        "expectedKek": kek18.hex(),
        "expect": "exact",
    })

    # 19: a "dirty" 26-char code that normalises to the same 25-char entropy as 18.
    code18 = make_code(entropy18)
    dirty_chars = []
    for i, ch in enumerate(code18):
        if ch == "1":
            dirty_chars.append("i" if i % 2 == 0 else "l")
        elif ch == "0":
            dirty_chars.append("o")
        else:
            dirty_chars.append(ch.lower())
    dirty = f" {dirty_chars[0]}{dirty_chars[1]}{dirty_chars[2]}{dirty_chars[3]}{dirty_chars[4]}-"
    dirty += f"{''.join(dirty_chars[5:10])}-{''.join(dirty_chars[10:15])}-"
    dirty += f"{''.join(dirty_chars[15:20])}-{''.join(dirty_chars[20:26])}\t\n"
    assert normalise(dirty) == code18, (dirty, normalise(dirty), code18)
    kek19 = argon2id_kek(normalise(dirty)[:25].encode("ascii"), salt18, 65536, 3, 1, 32)
    assert kek19 == kek18
    vs.add({
        "case": 19,
        "id": "19-recovery-normalisation",
        "mode": "recovery-normalisation",
        "rawInput": dirty,
        "expectedNormalised": code18,
        "salt": salt18.hex(),
        "params": {"m": 65536, "t": 3, "p": 1, "hashLen": 32, "version": "0x13", "type": "id"},
        "expectedKek": kek18.hex(),
        "expect": "exact",
        "note": "normalise(rawInput) must equal expectedNormalised, and Argon2id over its first 25 chars must equal expectedKek (case 18's)",
    })

    # 20: checksum acceptance / rejection.
    valid_entropy = "".join(ALPHABET[b % 32] for b in derive_bytes("entropy:20", 25))
    valid_code = make_code(valid_entropy)
    assert verify_code(valid_code)

    altered = list(valid_code)
    orig_val = ALPHABET.index(altered[0])
    altered[0] = ALPHABET[(orig_val + 1) % 32]
    altered_code = "".join(altered)
    assert not verify_code(altered_code)

    # Find an adjacent transposition (i, i+1) within the entropy whose values
    # differ by something other than 16 (the checksum's documented blind spot).
    swap_code = None
    for i in range(24):
        a, b = valid_entropy[i], valid_entropy[i + 1]
        if a == b:
            continue
        if abs(ALPHABET.index(a) - ALPHABET.index(b)) == 16:
            continue
        swapped_entropy = valid_entropy[:i] + b + a + valid_entropy[i + 2 :]
        swap_code = swapped_entropy + valid_code[25]
        break
    assert swap_code is not None
    assert not verify_code(swap_code)

    len25_code = valid_entropy  # no check symbol at all
    assert not verify_code(len25_code)

    len27_code = valid_code + "5"
    assert not verify_code(len27_code)

    u_in_middle = valid_code[:12] + "U" + valid_code[13:]
    assert not verify_code(u_in_middle)

    vs.add({
        "case": 20,
        "id": "20-recovery-checksum",
        "mode": "recovery-checksum",
        "cases": [
            {"label": "valid", "code": valid_code, "expect": "accept"},
            {"label": "one-char-altered", "code": altered_code, "expect": "reject"},
            {"label": "adjacent-transposition", "code": swap_code, "expect": "reject"},
            {"label": "25-chars", "code": len25_code, "expect": "reject"},
            {"label": "27-chars", "code": len27_code, "expect": "reject"},
            {"label": "u-in-middle", "code": u_in_middle, "expect": "reject"},
        ],
    })

    prf_output21 = derive_bytes("prf:21", 32)
    info21 = "archivist:1:passkey-kek"
    kek21 = hkdf_sha256(prf_output21, b"", info21.encode("ascii"), 32)
    vs.add({
        "case": 21,
        "id": "21-hkdf-passkey-kek",
        "mode": "hkdf-passkey",
        "prfOutput": prf_output21.hex(),
        "info": info21,
        "expectedKek": kek21.hex(),
        "expect": "exact",
    })

    # -- 22: byte-range table, against case 11 ------------------------------------

    def plaintext_to_segment(p: int) -> tuple[int, int]:
        if p < C0:
            return 0, p
        i = 1 + (p - C0) // CN
        off = (p - C0) % CN
        return i, off

    def cipher_range_for(a: int, b: int, total_len: int) -> tuple[int, int, int]:
        i_a, off_a = plaintext_to_segment(a)
        i_b, _ = plaintext_to_segment(b)
        start = i_a * SEGMENT
        end = min((i_b + 1) * SEGMENT, total_len) - 1
        return start, end, off_a

    total_len_11 = len(ct11)
    ranges = [
        (0, 99),
        (C0 - 1, C0 + 10),
        (C0 + CN - 5, C0 + CN + 5),
        (plain_len_11 - 10, plain_len_11 - 1),
        (0, plain_len_11 - 1),
    ]
    table = []
    for a, b in ranges:
        start, end, off_a = cipher_range_for(a, b, total_len_11)
        table.append({
            "plainStart": a,
            "plainEnd": b,
            "cipherStart": start,
            "cipherEnd": end,
            "trimFront": off_a,
        })
    vs.add({
        "case": 22,
        "id": "22-byte-range-table",
        "mode": "byte-range",
        "sourceCase": "11-stream-multi-mb",
        "plainLength": plain_len_11,
        "cipherLength": total_len_11,
        "ranges": table,
        "expect": "exact",
    })

    vs.save_manifest()
    print(f"wrote {len(vs.entries)} cases to {vs.out_dir}")


if __name__ == "__main__":
    main()
