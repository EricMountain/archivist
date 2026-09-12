"""The recovery-code KEK path from docs/design/crypto-format.md's "kind: recovery"
and "The code"/"Verification" sections. This is the *only* master-key route this
tool implements — a Python importer isn't a phone or a browser, so it has no
Keystore, no IndexedDB, no WebAuthn authenticator to enrol as; the recovery code is
the one wrapping every owner already has (design.md: "Invariant: at least two
wrappings exist at all times, and one is the recovery code" — mandatory at
enrolment), and it's the intended answer for exactly this case: "The independent
photo copy is the real recovery story... it's what makes changing the thumbnail
ladder possible at all", i.e. this design already expects a home-side script to
show up needing the master key with no phone or browser involved.
"""

from __future__ import annotations

import re

import argon2.low_level as argon2_low_level

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # Crockford base32, no I/L/O/U


def normalise(code: str) -> str:
    """Uppercase; drop anything outside the alphabet (hyphens, whitespace); map
    I/L -> 1, O -> 0. Order matters: this must run before the length check, so a
    code with stray whitespace or lowercase l's still verifies."""
    out = []
    for ch in code.upper():
        if ch in ALPHABET:
            out.append(ch)
        elif ch in ("I", "L"):
            out.append("1")
        elif ch == "O":
            out.append("0")
        # anything else -- U, punctuation other than the grouping hyphens -- is
        # simply dropped, same as a hyphen would be
    return "".join(out)


def check_symbol(entropy25: str) -> str:
    total = sum((2 * i + 1) * ALPHABET.index(c) for i, c in enumerate(entropy25))
    return ALPHABET[total % 32]


def verify_check_symbol(normalised_code: str) -> bool:
    """`normalised_code` must already be exactly 26 characters -- callers check the
    length first (crypto-format.md step 1), since this function only checks step 2."""
    if len(normalised_code) != 26:
        return False
    return check_symbol(normalised_code[:25]) == normalised_code[25]


def entropy_of(normalised_code: str) -> str:
    """The Argon2id password: the first 25 characters, as ASCII bytes -- the check
    symbol is discarded once verified, never fed into the KDF (crypto-format.md
    step 3: "the checksum is a validation shell around the secret, never an input
    to it")."""
    return normalised_code[:25]


_MEMORY_RE = re.compile(r"^(\d+)(KiB|MiB|GiB)$")
_MEMORY_MULTIPLIERS = {"KiB": 1, "MiB": 1024, "GiB": 1024 * 1024}


def parse_memory_kib(m: str) -> int:
    """`kdfParams.m` on the wire is e.g. `"64MiB"` -- written for humans per
    crypto-format.md ("The m value in kdfParams is written for humans;
    implementations use KiB"), confirmed against the real wire shape
    (`KdfParamsDto.m: String` server/Android-side, not a plain integer) --
    mirrors android/core/crypto/.../KeyCustody.kt's `parseMemoryKib`."""
    match = _MEMORY_RE.match(m.strip())
    if not match:
        raise ValueError(f"unrecognised kdfParams.m value: {m!r}")
    value, unit = match.groups()
    return int(value) * _MEMORY_MULTIPLIERS[unit]


def argon2id_kek(password: bytes, salt: bytes, m_kib: int, t: int, p: int, length: int) -> bytes:
    """Argon2id v1.3 (0x13) -- crypto-format.md "kind: recovery". `m_kib` must
    already be in KiB -- callers reading a real `kdfParams.m` off the wire (a
    string like `"64MiB"`) must run it through `parse_memory_kib` first."""
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


class InvalidRecoveryCode(ValueError):
    pass


def verify_and_derive_entropy(raw_code: str) -> str:
    """Normalises, checks length and checksum, and returns the 25-character
    entropy field ready for `argon2id_kek`. Raises InvalidRecoveryCode with a
    message meant for a human typing the code at a terminal -- crypto-format.md's
    whole point for the check symbol is exactly this: "you typed it wrong" is a
    different, immediate message from "this is the wrong code"."""
    normalised = normalise(raw_code)
    if len(normalised) != 26:
        raise InvalidRecoveryCode(
            f"recovery code must be 26 characters after removing hyphens/spaces "
            f"(got {len(normalised)}) -- check for a dropped or doubled character"
        )
    if not verify_check_symbol(normalised):
        raise InvalidRecoveryCode(
            "recovery code check symbol does not match -- check for a mistyped character"
        )
    return entropy_of(normalised)
