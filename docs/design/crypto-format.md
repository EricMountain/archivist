# Crypto format

The wire format for everything this system encrypts. `design.md` decides *that* the
client encrypts and *why*; this document says exactly which bytes go where, so that an
Android app, a browser, a Python importer on a home server and whatever exists in 2035
all produce and consume the same files.

**This is a specification, not a library.** The reasoning is in `design.md`
("The crypto format is a published spec, not a shared library"): Kotlin Multiplatform
would cover the Kotlin targets and leave Python and Go exactly where they started, so
the portable artefact is a written format that any language can implement. Most
implementations get it for free — the streaming construction below *is* Tink's
`AES256_GCM_HKDF_1MB`, and Tink ships Java, Python, Go, C++ and Obj-C. Only the browser
hand-rolls it over WebCrypto, because Tink's JavaScript implementation is unmaintained.

**The conformance vectors are the authority, not this prose.** If an implementation
disagrees with `testdata/vectors/`, the implementation is wrong. If this document
disagrees with the vectors, this document is wrong and gets fixed. A format bug that
escapes is not a crash — it is a photo nobody can open, discovered years later.

## Version

This is **crypto format version 1**. Instances advertise it as `cryptoVersion: 1` in the
instance config document (see `android.md`); a client too old for the advertised version
must refuse to connect rather than write data it cannot read back.

The version number is also bound into every AAD string below, so a v2 object cannot be
silently decrypted as v1 even if the algorithms happen to line up.

## Primitives

| Purpose | Algorithm | Notes |
| --- | --- | --- |
| Object encryption, small | AES-256-GCM | 96-bit random IV, 128-bit tag |
| Object encryption, large | AES-GCM-HKDF-STREAMING | Tink `AES256_GCM_HKDF_1MB` |
| Key derivation | HKDF-SHA256 | inside the streaming construction, and for KEKs |
| DEK wrapping | AES-KW (RFC 3394) | 256-bit KEK, 256-bit key, 40-byte output |
| Master key wrapping, Android | RSA-OAEP-256 | SHA-256 digest **and** MGF1-SHA-256 |
| Master key wrapping, web | AES-KW | non-extractable WebCrypto key |
| Recovery KDF | Argon2id v1.3 | m=65536 KiB, t=3, p=1, 32-byte output |
| Dedup hash | HMAC-SHA256 | keyed, see "`contentHash`" |

No other algorithm is permitted in v1. In particular the `wrapAlg` attribute on a `W#`
item is the closed set `AES-KW | RSA-OAEP-256`. There is no ECDH wrapping mode in v1,
so an Android device enrols an RSA Keystore key rather than the cheaper EC one.

All randomness comes from the platform CSPRNG: `SecureRandom` (Android),
`crypto.getRandomValues` (web), `os.urandom` (Python). Never a seeded or userspace PRNG.

### Encoding

Binary values stored as DynamoDB string attributes — `encDek`, `encIv`, `exifEnc`,
`wrappedKey`, `prfSalt`, `kdfSalt`, `thumbs[*].iv` — are **standard base64 with padding**
(RFC 4648 §4, `+/=` alphabet). This is deliberately *not* the base64url used for
pagination cursors; those are URL components, these are not.

`contentHash` is the literal prefix `hmac-sha256:` followed by 64 lowercase hex
characters.

## Key hierarchy

```text
  master key  (256 bit, random, per owner, never leaves a client in plaintext)
      │
      ├── wrapped N times, once per enrolled route ──→  W# items
      │     device (Android)   RSA-OAEP-256 to a Keystore public key
      │     device (web)       AES-KW under a non-extractable IndexedDB key
      │     passkey            AES-KW under a KEK derived from WebAuthn PRF
      │     recovery           AES-KW under a KEK derived from the recovery code
      │
      ├── wraps the owner hash secret  ──────────────→  #SETTINGS (see below)
      │
      └── wraps one DEK per asset  ──────────────────→  encDek on #META
                │
                └── the DEK encrypts every object in that asset:
                      each rendition, each thumbnail, and the EXIF blob
```

Two properties hold this together, and both are load-bearing:

* **The master key never encrypts bulk data.** It only ever wraps 32-byte keys, so
  rotating it is a re-wrap of small values and never touches S3. See "Key rotation is
  cheap" in `design.md`.
* **One DEK per asset, a distinct IV per object.** Renditions, thumbnails and the EXIF
  blob of one asset share a key. Nonce reuse under one GCM key is catastrophic, so IVs
  are freshly generated per object and stored per object, never derived from anything
  that repeats.

An asset carries on the order of ten objects (up to three renditions, three thumbnails,
one EXIF blob), so the birthday bound on 96-bit random IVs under one DEK is not
remotely a concern. What *is* a rule: **a DEK is never reused across assets**, and never
used to encrypt more than 2^32 objects.

## Object encryption

Three kinds of object are encrypted, all with the asset's DEK:

| Object | Where the ciphertext lives | Mode |
| --- | --- | --- |
| Rendition | S3, `s3Bucket` / `s3Key` on the `R#` item | either, per `encChunkSize` |
| Thumbnail | S3, `thumbs[size].bucket` / `.key` on `#META` | always whole-object |
| EXIF blob | `exifEnc` on `#META`, inline | always whole-object |

### Choosing a mode

`encChunkSize` on the `R#` item records which mode was used:

| `encChunkSize` | Mode |
| --- | --- |
| `0` | whole-object AES-256-GCM |
| `1048576` | streaming, 1 MiB ciphertext segments |

No other value is valid in v1. The **threshold** at which a client switches is pure
client policy — default 32 MiB (33 554 432 bytes) of plaintext — and because the value
actually used is recorded per object, the threshold can be retuned at any time without
invalidating a single existing object. Nothing server-side is aware it exists.

Thumbnails and EXIF blobs are always well under any plausible threshold and are always
whole-object, which is why `thumbs[*]` carries an `iv` and no chunk size.

### Associated data

Every encryption, in both modes, binds an **object context string**:

```text
aad = "archivist:" <version> ":" <photoId> ":" <objectRef>

objectRef =  "r:" <renditionId>     a rendition
          |  "t:" <size>            a thumbnail, size as decimal longest edge
          |  "x"                    the EXIF blob
```

UTF-8, no trailing newline. For v1 the version field is the literal `1`. Examples:

```text
archivist:1:01K5A2Q8ZCV1D9KXM3BQNR7T2F:r:01K5A2Q8ZCW4MB7XKQNV2HTRF3
archivist:1:01K5A2Q8ZCV1D9KXM3BQNR7T2F:t:1024
archivist:1:01K5A2Q8ZCV1D9KXM3BQNR7T2F:x
```

ULIDs are Crockford base32 and thumbnail sizes are digits, so no field can contain the
`:` separator and the string is unambiguous.

**The AAD is never stored.** It is reconstructed from the DynamoDB item at decrypt time.
That is the point: an object moved to another asset, a thumbnail relabelled as a
different size, or a v2 object fed to a v1 decrypter all fail authentication rather than
producing plausible bytes. Reconstructing it wrongly is a hard failure, which is the
failure mode you want.

### Mode A — whole-object

```text
key    = the asset DEK, used directly as the AES-256-GCM key
iv     = 12 random bytes, stored base64 in encIv (renditions),
         thumbs[size].iv (thumbnails), or exifIv (the EXIF blob)
aad    = the object context string above
output = AES-256-GCM(key, iv, plaintext, aad)  =  ciphertext ‖ 16-byte tag
```

The IV is stored beside the object, never inside it. So:

```text
bytes = plainBytes + 16
```

`design.md` and `sample-data.md` originally showed a 12-byte delta here — the IV
length, mistaken for the tag length. The IV is not in the object; the delta is 16, and
both documents now say so.

### Mode B — streaming

Byte-for-byte Tink's `AES256_GCM_HKDF_1MB`. Restated here because the browser has to
implement it by hand, and because a spec that says "see Tink" is not a spec.

Constants:

```text
SEGMENT      1048576   ciphertext segment size, including its tag
HEADER            40   = 1 + 32 + 7
TAG               16
SALT_LEN          32   = the derived key size
PREFIX_LEN         7   nonce prefix
```

**Header**, emitted once at the start of the ciphertext:

```text
offset 0    1 byte    header length, the constant 40
offset 1   32 bytes   salt, random per stream
offset 33   7 bytes   nonce prefix, random per stream
```

**Stream key**, derived once, not per segment:

```text
streamKey = HKDF-SHA256(ikm = DEK, salt = header salt, info = aad, L = 32)
```

Note where the associated data goes: into the HKDF `info`, not into each segment's GCM
call. Every segment is then encrypted with **empty** GCM associated data. This is Tink's
design and it must be followed exactly — folding the AAD in at derivation time means a
wrong AAD yields a wrong key and every segment fails, which is strictly stronger than
binding it per segment.

**Segment nonces** are where the ordering guarantees live:

```text
nonce(i, last) = noncePrefix ‖ uint32be(i) ‖ (last ? 0x01 : 0x00)      12 bytes
```

The segment index is in the nonce, so segments cannot be reordered or duplicated. The
final segment is flagged in the nonce, so **truncation is detected**: dropping trailing
segments leaves a stream whose last segment authenticates as non-final, and decryption
fails. This is the classic way hand-rolled streaming AEAD goes wrong, and it is the main
reason for adopting a construction designed by cryptographers rather than inventing one.

**Segment capacities.** The header is charged against the first segment, so every
ciphertext segment except the last is exactly `SEGMENT` bytes:

```text
C0 = SEGMENT - HEADER - TAG = 1048520     plaintext in segment 0
Cn = SEGMENT - TAG          = 1048560     plaintext in every later segment
```

**Segment count** for a plaintext of `P` bytes:

```text
n(P) = 1                                   if P <= C0
     = 1 + ceil((P - C0) / Cn)             otherwise
```

A zero-byte plaintext still produces one segment: the header plus a bare 16-byte tag.
There is never an empty trailing segment — a plaintext that ends exactly on a boundary
produces a *full-size* final segment carrying the last-segment flag, so a decrypter must
handle "full segment followed by EOF" as a legitimate ending. Vectors 3 and 5 exist
precisely to pin this down.

**Ciphertext length:**

```text
bytes = HEADER + plainBytes + TAG * n(plainBytes)
```

Worked, for the 480 MB video in `sample-data.md` (asset A8):

```text
plainBytes  503316480
P - C0      502267960
ceil(/Cn)   480                    → n = 481 segments
bytes       40 + 503316480 + 7696 = 503324216
final segment  503324216 - 480*1048576 = 7736 bytes (7720 plaintext + tag)
```

### Byte-range mapping

This is what makes video seeking work, and it is simpler than it looks, because the
header is absorbed into segment 0:

```text
ciphertext segment i starts at exactly  i * SEGMENT
```

To read plaintext byte `p`:

```text
i   = 0                        and off = p                  if p < C0
i   = 1 + (p - C0) / Cn        and off = (p - C0) mod Cn     otherwise   (integer div)
```

To serve a plaintext range `[a, b]`, compute `i_a` and `i_b`, then issue

```text
Range: bytes=<i_a * SEGMENT>-<min((i_b + 1) * SEGMENT, bytes) - 1>
```

against S3 or CloudFront, decrypt those segments, and trim `off_a` bytes from the front.
A player asking for `bytes=0-` gets segment 0 onwards and the first-segment offset never
enters the arithmetic again.

The tempting formula, `index × (chunkSize + 16)`, is wrong twice over: the segment size
*includes* its tag rather than adding to it, and it ignores the header. The multiplier
is a flat `1048576`, and segment 0's shorter payload is handled by `C0` in the offset
calculation rather than by shifting every later segment.

**Multipart alignment.** S3 multipart parts must be ≥5 MB, and misaligned part
boundaries make the arithmetic above painful for no gain, so uploads use **8 MiB parts —
exactly 8 ciphertext segments**. Every part boundary is a segment boundary except the
last, which is short by construction.

## Key wrapping

### DEKs

```text
encDek = AES-KW(kek = master key, key = DEK)          40 bytes → 56 base64 chars
```

AES-KW is deterministic and needs no IV, which is why `#META` carries `encDek` with no
companion IV attribute. `encKeyId` records which master key version did the wrapping, so
a rotation sweep can find the photos it has not reached yet and resume.

### The master key

The master key is 32 random bytes, generated once per owner on the first client, and
wrapped once per enrolled route into a `W#` item. All four routes below produce a
256-bit KEK and then use AES-KW, except Android, which wraps directly with RSA.

**`kind: device`, Android.** `wrapAlg: RSA-OAEP-256`. A 3072-bit RSA keypair in the
Android Keystore, non-exportable by construction, optionally biometric-gated. The master
key is wrapped to the public half.

> The Android provider's `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` defaults its **MGF1**
> digest to SHA-1 despite the name, and other platforms default it to SHA-256. Pass an
> explicit `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
> PSource.PSpecified.DEFAULT)`. Getting this wrong produces a wrapping that only the
> device that made it can read — which looks fine until the day it matters.

**`kind: device`, web.** `wrapAlg: AES-KW`. A 256-bit AES-KW `CryptoKey` generated with
`extractable: false` and stored in IndexedDB. Raw key bytes are never a readable
JavaScript value; `unwrapKey` produces the master key as another non-extractable
`CryptoKey`. Eviction of this key is expected, not exceptional — see `design.md`.

**`kind: passkey`.** `wrapAlg: AES-KW`. `prfSalt` is 32 random bytes, stored on the item
and passed as the WebAuthn PRF `eval.first` input. The 32-byte PRF output becomes a KEK:

```text
kek = HKDF-SHA256(ikm = prfOutput, salt = <empty>, info = "archivist:1:passkey-kek", L = 32)
```

**`kind: recovery`.** `wrapAlg: AES-KW`. `kdfSalt` is 16 random bytes; `kdfParams` is
`{ alg: argon2id, m: 64MiB, t: 3, p: 1 }`, stored per item so the cost can be raised for
new enrolments without invalidating an existing code.

```text
kek = Argon2id(pwd = entropy, salt = kdfSalt, m = 65536 KiB, t = 3, p = 1, L = 32)
```

Argon2 **version 1.3 (0x13)**, type **id**. The `m` value in `kdfParams` is written for
humans; implementations use KiB.

#### The code

**26 characters: 25 of entropy, then one check symbol.** The alphabet is Crockford
base32 — `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, which omits `I`, `L`, `O` and `U`.

```text
ALPHABET  0123456789ABCDEFGHJKMNPQRSTVWXYZ
value(c)  the index of c in ALPHABET

entropy   25 characters drawn uniformly from ALPHABET   → 32^25 = 2^125
check     ALPHABET[ (sum over i in 0..24 of (2i + 1) * value(entropy[i])) mod 32 ]

printed   XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX     five groups; the last carries six
```

Generation is a uniform draw per character, not a bit-packed encoding of a random
integer. That matters: **every 25-character string over the alphabet is a valid
entropy field**, so there is no invalid-tail class for implementations to disagree
about. 125 bits rather than a round 128 is a consequence of that, and is not a security
question — see "Recovery code" in `design.md`.

#### Verification

`normalise(code)` is, in order: uppercase; drop every character outside the alphabet
(which removes the grouping hyphens and any pasted whitespace); map `I`, `L` → `1` and
`O` → `0`. Then:

1. The normalised string **must** be exactly 26 characters. Anything else is rejected.
2. Recompute the check symbol over the first 25 and compare with the 26th. A mismatch
   is rejected.
3. **The Argon2id password is the first 25 characters** — the entropy field alone, as
   ASCII bytes. The check symbol is discarded once verified, and the entropy is not
   decoded to an integer first; decoding is where two implementations would drift.

Step 3 is deliberate layering: the checksum is a validation shell around the secret,
never an input to it. Bake the check symbol into the KDF and the checksum algorithm is
frozen into every derived key forever, so it could never be changed or dropped.

What the check symbol buys, verified rather than assumed:

| Error | Caught |
| --- | --- |
| Any single wrong character | **always** — odd weights are invertible mod 32 |
| A dropped or doubled character | **always** — by the length check in step 1 |
| A `U`, or any other off-alphabet character | **always** — dropped, so the length fails |
| Two adjacent characters swapped | ~97% — missed only when their values differ by 16 |
| A wholly wrong code | 31/32 |

The gap is transpositions whose values differ by exactly 16, which is unavoidable with
a power-of-two modulus and not worth Crockford's 37-symbol check alphabet to close: it
would add five symbols (`*~$=U`) outside the data alphabet, and `U` is excluded from
that alphabet precisely to avoid confusion.

None of this is a security boundary — an uncaught error just falls through to Argon2id
and fails there, as it does today. What the check symbol buys is a **local, instant
answer** in a system that has no support path: "you typed it wrong" arrives before the
KDF runs, and is a different message from "this is the wrong code, or your library is
gone." It also gives the enrolment confirmation step something to validate against
short of a full unwrap.

Normalisation is the difference between "typed the code with a lowercase l" and "locked
out of your library forever". It and the check symbol get their own conformance
vectors.

### Rotation and revocation

Rotating the master key mints a new version, re-wraps every asset's `encDek`, writes
fresh `W#` items for the surviving routes and deletes the revoked one. No object bytes
are touched. `encKeyId` is the resume cursor.

**A rotation is client work.** Re-wrapping `encDek` requires unwrapping it under the old
master key and re-wrapping under the new, and no server in this design has ever held
either. An implementation must hold both keys in memory for the duration and write each
photo back through the API.

Version strings are `mk-<n>`, `n` an integer from 1, **allocated by the server** with an
atomic increment of `masterKeyVerSeq` on `#SETTINGS`. A client must never mint one
itself: two clients rotating concurrently would label two different master keys `mk-4`,
and every `encKeyId` in the table becomes ambiguous. See "Key rotation is cheap" in
`design.md`.

## `contentHash`

Dedup needs a stable hash of the *plaintext*, but a bare SHA-256 in DynamoDB would let
anyone with table access confirm whether the library holds a specific known image. So:

```text
contentHash = "hmac-sha256:" hex(HMAC-SHA256(key = hashSecret, message = plaintext))
```

over the **whole plaintext rendition**, before any encryption, streamed rather than
buffered.

The `hashSecret` is a third key, and its lifetime constraint is what makes it
interesting: **it must survive master key rotation unchanged.** Deriving it from the
master key would be the obvious move and would be a bug — every `HASH#` pointer in the
table would become unreachable the moment the master key rotated, silently breaking
dedup and orphaning the purge tombstones that `design.md` relies on.

So it is its own 32-byte random key, generated once at enrolment and wrapped by the
master key exactly as a DEK is:

```text
encHashSecret = AES-KW(kek = master key, key = hashSecret)
```

Rotation re-wraps it alongside the DEKs; the value never changes. It lives on the
`#SETTINGS` item as `encHashSecret`, with `hashSecretKeyId` recording the wrapping
version. The first client writes it at enrolment, so it is absent on a freshly
bootstrapped owner and the server never unwraps it.

## What must fail

An implementation is not conformant because its own round-trip passes. It is conformant
when all of these are rejected:

| Attack | Detected by |
| --- | --- |
| Trailing segments dropped | last-segment flag in the nonce |
| Stream truncated mid-segment | GCM tag |
| Segments reordered or duplicated | segment index in the nonce |
| A rendition swapped between two assets | `photoId` in the AAD |
| A 256px thumbnail served as the 1024px one | size in the AAD |
| Any single bit flipped | GCM tag |
| A v2 object decrypted by a v1 client | version in the AAD |
| Header salt or nonce prefix altered | derived key changes; every segment fails |

Note what is *not* protected: an attacker with table access can still delete an object,
or roll a `#META` item back to an earlier state. Authenticated encryption gives
integrity per object, not freshness across the table. That is a known and accepted
limit — the independent photo copy in `design.md` is the answer to it, not the crypto.

## Conformance vectors

**Every client's test suite decrypts these fixtures. A client that cannot is broken,
regardless of what its own round-trip tests say.** This is the single mechanism that
keeps four independent implementations honest, and it is not optional.

Generated with Tink (Python is easiest) by `tools/gen-vectors/`, committed under
`testdata/vectors/` **with their key material** — they are test fixtures and protect
nothing.

```text
testdata/vectors/
  manifest.json          one entry per case: id, mode, keys (hex), aad, files, expect
  <id>.plain             plaintext, or absent where the manifest gives a length + pattern
  <id>.cipher            the ciphertext under test
```

Required cases:

| # | Case | Expect |
| --- | --- | --- |
| 1 | Whole-object, 0 bytes | decrypt |
| 2 | Whole-object, 1 byte | decrypt |
| 3 | Whole-object, typical JPEG | decrypt |
| 4 | Whole-object, one tag bit flipped | **fail** |
| 5 | Whole-object, AAD `photoId` altered | **fail** |
| 6 | Streaming, 0 bytes — header + bare tag | decrypt |
| 7 | Streaming, `P = C0` — one full segment, marked last | decrypt |
| 8 | Streaming, `P = C0 + 1` — two segments, 1-byte tail | decrypt |
| 9 | Streaming, `P = C0 + Cn` — exactly two full segments | decrypt |
| 10 | Streaming, `P = C0 + Cn + 1` — three segments | decrypt |
| 11 | Streaming, multi-MB with a partial final segment | decrypt |
| 12 | Case 10 with the final segment removed | **fail** |
| 13 | Case 11 truncated mid-segment | **fail** |
| 14 | Case 10 with segments 1 and 2 exchanged | **fail** |
| 15 | Case 11 with the header salt altered | **fail** |
| 16 | AES-KW of a known 32-byte DEK under a known KEK | exact bytes |
| 17 | RSA-OAEP-256 unwrap of a known wrapping (fixed keypair) | exact bytes |
| 18 | Argon2id KEK from a known code + salt | exact bytes |
| 19 | Recovery-code normalisation: lowercase, `l`/`I`/`O`, stray spaces | same KEK as 18 |
| 20 | Recovery-code check symbol: valid code; one character altered; two adjacent swapped; 25 chars; 27 chars; a `U` in the middle | accept, then **reject** ×5 |
| 21 | HKDF passkey KEK from a known PRF output | exact bytes |
| 22 | Byte-range: for case 11, a table of plaintext ranges → ciphertext ranges | exact |

Cases 7 and 9 are the boundary cases that decide the "no empty trailing segment"
question. Case 20 must use a transposition whose character values differ by something
other than 16, since that class is the checksum's documented blind spot. Case 22 is what
stops a seeking bug from being found by a user scrubbing a video.

## Open items

`design.md` and `sample-data.md` now agree with this document throughout: the 16-byte
whole-object tag, the streaming ciphertext sizes for A2 and A8, `encIv` being absent in
streaming mode, `encHashSecret` / `hashSecretKeyId` on `#SETTINGS`, the rewritten
"Chunked encryption" section, the 26-character recovery code, and `mk-<n>` master key
versions. Nothing is outstanding between the three.

What remains is genuinely undecided rather than contradictory, and is tracked in
`design.md`'s "Open questions" so that decisions live in one place:

* **No ECDH wrapping mode** — v1 is `AES-KW | RSA-OAEP-256`, so Android enrols RSA.
  Adding ECDH-ES + AES-KW is a new `wrapAlg` value, not a break, but it needs an `epk`
  attribute on the `W#` item since ECDH is key agreement rather than key transport.
  Plan step 2.4a measures whether StrongBox accepts RSA-3072 on real hardware, which is
  what decides it.

One item lives only here, because nothing outside this document depends on it:

* **`prfSalt` reuse across credentials.** Each `kind: passkey` item generates its own
  salt today. Nothing requires that; it is simply the conservative choice.
