"""Mints a client-side candidate `photoId` -- design.md: "the client gets to
propose a photoId" so it can pick the AAD for pre-encrypted thumbnails/EXIF before
the upload call returns. Format must match src/core/ids.ts's `isUlid`
(`^[0-7][0-9A-HJKMNP-TV-Z]{25}$`): a 48-bit millisecond timestamp + 80 bits of
randomness, Crockford base32, 26 characters, first character restricted to 0-7 so
the encoded value never exceeds 128 bits.
"""

from __future__ import annotations

import os
import time

_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def new_ulid() -> str:
    timestamp_ms = int(time.time() * 1000) & ((1 << 48) - 1)
    randomness = int.from_bytes(os.urandom(10), "big")
    value = (timestamp_ms << 80) | randomness  # 128 bits total
    chars = []
    for i in range(25, -1, -1):
        chars.append(_ALPHABET[(value >> (i * 5)) & 0x1F])
    return "".join(chars)
