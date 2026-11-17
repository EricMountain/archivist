"""Implements docs/design/crypto-format.md v1: object encryption (whole-object and
streaming), AES-KW key wrapping, and the HMAC-SHA256 contentHash. This module's own
test (tests/test_crypto_format.py) decrypts testdata/vectors/ directly — the spec's
own words: "Every client's test suite decrypts these fixtures. A client that cannot
is broken, regardless of what its own round-trip tests say."

Deliberately independent of tools/gen-vectors/generate.py, which generates the
vectors — this reads crypto-format.md and re-implements it from the prose, the same
way any other language's client would, rather than importing a dev-only reference
script.

**Memory tradeoff, stated rather than hidden**: streaming-mode ciphertext is built
fully in memory (`streaming_encrypt` returns bytes, it doesn't write incrementally to
a socket). Whole-object mode already reads the whole plaintext to compute
`contentHash` before it can encrypt anything, so this only widens an existing
tradeoff for the >32 MiB streaming case: peak memory is roughly 2x the largest file
being imported (plaintext + ciphertext), which the real corpus this tool was written
against tops out at under 1 GB. A home server doing a one-off bulk import has memory
to spare for that; a rewrite to genuinely incremental encrypt-while-uploading would be
needed before pointing this at multi-GB source files or a memory-constrained host.
"""

from __future__ import annotations

import hashlib
import hmac
import io
from dataclasses import dataclass

import tink
from tink import cleartext_keyset_handle, streaming_aead
from tink.proto import aes_gcm_hkdf_streaming_pb2, common_pb2, tink_pb2

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.keywrap import aes_key_unwrap, aes_key_wrap

streaming_aead.register()

CRYPTO_VERSION = 1

# --- Mode B constants (crypto-format.md "Mode B — streaming") -------------------
SEGMENT = 1_048_576
HEADER = 40
TAG = 16
C0 = SEGMENT - HEADER - TAG  # 1_048_520 -- plaintext capacity of segment 0
CN = SEGMENT - TAG  # 1_048_560 -- plaintext capacity of every later segment

# Client policy (crypto-format.md: "the threshold at which a client switches is pure
# client policy"). Matches Android's own default (UploadRepository.kt).
STREAMING_THRESHOLD_BYTES = 32 * 1024 * 1024


def object_ref_rendition(rendition_id: str) -> str:
    return f"r:{rendition_id}"


def object_ref_thumbnail(size: int) -> str:
    return f"t:{size}"


def object_ref_exif() -> str:
    return "x"


def aad(photo_id: str, object_ref: str) -> bytes:
    """`archivist:<version>:<photoId>:<objectRef>` -- crypto-format.md "Associated
    data". UTF-8, no trailing newline."""
    return f"archivist:{CRYPTO_VERSION}:{photo_id}:{object_ref}".encode("utf-8")


def choose_chunk_size(plain_bytes: int) -> int:
    """0 (whole-object) below the threshold, SEGMENT (streaming) at or above it."""
    return SEGMENT if plain_bytes >= STREAMING_THRESHOLD_BYTES else 0


def segment_count(plain_len: int) -> int:
    """n(P) from crypto-format.md's "Segment count" -- always >= 1, even for P=0."""
    if plain_len <= C0:
        return 1
    remainder = plain_len - C0
    return 1 + -(-remainder // CN)  # ceil division without importing math


def ciphertext_length(plain_bytes: int, chunk_size: int) -> int:
    """`bytes` on the R# item -- crypto-format.md "Ciphertext length" (mode B) and
    the mode-A "bytes = plainBytes + 16" note."""
    if chunk_size == 0:
        return plain_bytes + TAG
    return HEADER + plain_bytes + TAG * segment_count(plain_bytes)


# --- Mode A -- whole-object AES-256-GCM ------------------------------------------


def whole_object_encrypt(dek: bytes, iv: bytes, associated_data: bytes, plaintext: bytes) -> bytes:
    """ciphertext || 16-byte tag, per crypto-format.md "Mode A — whole-object"."""
    return AESGCM(dek).encrypt(iv, plaintext, associated_data)


def whole_object_decrypt(dek: bytes, iv: bytes, associated_data: bytes, ciphertext: bytes) -> bytes:
    return AESGCM(dek).decrypt(iv, ciphertext, associated_data)


# --- Mode B -- streaming (Tink AES256_GCM_HKDF_1MB, used directly) --------------
#
# Byte-for-byte Tink's construction, per crypto-format.md's own insistence that a
# spec saying "see Tink" isn't a spec -- but implemented *via* Tink's library here
# (as most real clients will: "Most implementations get it for free... Tink ships
# Java, Python, Go, C++ and Obj-C"), not hand-rolled the way the browser has to.


class _NonClosingBytesIO(io.BytesIO):
    """Tink's encrypting-stream context manager closes its output on __exit__;
    override close() to a no-op so the buffer is still readable afterwards, mirroring
    tools/gen-vectors/generate.py's own helper of the same name."""

    def close(self) -> None:
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


def streaming_encrypt(dek: bytes, associated_data: bytes, plaintext: bytes) -> bytes:
    prim = _streaming_handle(dek).primitive(streaming_aead.StreamingAead)
    buf = _NonClosingBytesIO()
    with prim.new_encrypting_stream(buf, associated_data) as enc:
        enc.write(plaintext)
    return buf.getvalue()


def streaming_decrypt(dek: bytes, associated_data: bytes, ciphertext: bytes) -> bytes:
    prim = _streaming_handle(dek).primitive(streaming_aead.StreamingAead)
    with prim.new_decrypting_stream(io.BytesIO(ciphertext), associated_data) as dec:
        return dec.read()


# --- Key wrapping (crypto-format.md "Key wrapping") -----------------------------


def wrap_key(kek: bytes, key: bytes) -> bytes:
    """AES-KW (RFC 3394): 40 bytes for a 32-byte key. Used for encDek and, in this
    tool, for unwrapping the master key from a recovery-code KEK."""
    return aes_key_wrap(kek, key)


def unwrap_key(kek: bytes, wrapped: bytes) -> bytes:
    return aes_key_unwrap(kek, wrapped)


# --- contentHash (design.md "contentHash is HMAC'd") ----------------------------


def content_hash(hash_secret: bytes, plaintext: bytes) -> str:
    """`hmac-sha256:` + hex(HMAC-SHA256(hashSecret, plaintext)) over the whole
    plaintext rendition. Callers with a file on disk should prefer
    `content_hash_of_file`, which never buffers the whole file just for this."""
    return "hmac-sha256:" + hmac.new(hash_secret, plaintext, hashlib.sha256).hexdigest()


def content_hash_of_file(hash_secret: bytes, path: str, chunk_size: int = 1024 * 1024) -> str:
    mac = hmac.new(hash_secret, digestmod=hashlib.sha256)
    with open(path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            mac.update(chunk)
    return "hmac-sha256:" + mac.hexdigest()


@dataclass(frozen=True)
class EncryptedObject:
    ciphertext: bytes
    iv: bytes | None  # None in streaming mode -- the salt/nonce prefix travel in the header instead
    chunk_size: int


def encrypt_object(dek: bytes, associated_data: bytes, plaintext: bytes, chunk_size: int) -> EncryptedObject:
    """Whole-object or streaming, chosen by the caller via `chunk_size`
    (0 or SEGMENT -- see `choose_chunk_size`). Thumbnails and the EXIF blob are
    always whole-object (crypto-format.md: "always well under any plausible
    threshold"); pass chunk_size=0 for those regardless of this function's own
    default reasoning."""
    if chunk_size == 0:
        iv = _random_iv()
        return EncryptedObject(whole_object_encrypt(dek, iv, associated_data, plaintext), iv, 0)
    return EncryptedObject(streaming_encrypt(dek, associated_data, plaintext), None, SEGMENT)


def _random_iv() -> bytes:
    import os

    return os.urandom(12)


# --- Mode B, partial and file-backed reads --------------------------------------


def decrypt_first_segment(dek: bytes, associated_data: bytes, ciphertext_prefix: bytes) -> bytes:
    """The plaintext of segment 0 alone (the first `C0` bytes), from the first
    `SEGMENT` bytes of a streaming-mode object that has **more than one segment** --
    crypto-format.md "Byte-range mapping", for a caller that only needs the start of
    a file (EXIF sits in a JPEG's first 64 KiB). Authenticated like any other
    segment: decrypted as segment 0 with the last-segment flag clear, so it fails
    when the object actually has only one segment (decrypt that whole instead) and
    on any wrong key, AAD or byte.

    Done by hand rather than through Tink, whose decrypting stream needs the
    following segment to know segment 0 isn't the last. Same construction as
    crypto-format.md "Mode B": streamKey = HKDF-SHA256(DEK, salt, info=aad),
    nonce = prefix || uint32be(0) || 0x00, empty GCM associated data."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF

    if len(ciphertext_prefix) < SEGMENT:
        raise ValueError(f"need the first {SEGMENT} ciphertext bytes, got {len(ciphertext_prefix)}")
    if ciphertext_prefix[0] != HEADER:
        raise ValueError(f"bad streaming header length byte {ciphertext_prefix[0]}")
    salt = ciphertext_prefix[1:33]
    nonce_prefix = ciphertext_prefix[33:HEADER]
    stream_key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=associated_data).derive(dek)
    nonce = nonce_prefix + (0).to_bytes(4, "big") + b"\x00"
    return AESGCM(stream_key).decrypt(nonce, ciphertext_prefix[HEADER:SEGMENT], b"")


def streaming_decrypt_file(dek: bytes, associated_data: bytes, src_path: str, dst_path: str) -> None:
    """`streaming_decrypt`, file to file, one read buffer at a time -- for a
    downloaded video original that needn't sit in memory twice."""
    prim = _streaming_handle(dek).primitive(streaming_aead.StreamingAead)
    with open(src_path, "rb") as src, open(dst_path, "wb") as dst:
        with prim.new_decrypting_stream(src, associated_data) as dec:
            while True:
                block = dec.read(SEGMENT)
                if not block:
                    break
                dst.write(block)
