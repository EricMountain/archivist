# import-from-local

Imports a local directory of photos/videos into a real archivist instance — the
reference importer docs/design/design.md's "Third parties use the API, not the
datastore" describes: a script that authenticates, encrypts client-side, and PUTs
to presigned URLs, the same path the Android app takes and with no privileged
position of its own. It never touches DynamoDB or S3 directly.

Written for, and validated against, a real-world export: ~5,600 files merged from
a Google Photos Takeout and a couple of old phone backups, all with one shared
filesystem mtime (the day they were extracted) and zero `OffsetTimeOriginal` EXIF
tags between them. See "What this handles" below for exactly what that shaped.

## Setup

```sh
cd tools/import-from-local
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## Before running it

**The owner must already be enrolled on another client (in practice: the Android
app) first.** This tool consumes the account's existing recovery code to recover
the master key; it deliberately does not mint the very first master key/recovery
code pair itself — see `importer.py`'s `enroll()` docstring for why. If `GET
/keys` comes back empty, sign in with Android first.

You'll be asked for (or can set as an environment variable, so they don't linger
in shell history):

| What | Env var | Notes |
| --- | --- | --- |
| Cognito password | `ARCHIVIST_PASSWORD` | The account's normal sign-in password |
| A temporary password's replacement | `ARCHIVIST_NEW_PASSWORD` | Only if the account still has an admin-set temporary password (`docs/ops/create-user.md`) |
| Recovery code | `ARCHIVIST_RECOVERY_CODE` | `XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`, whatever punctuation/case — it's normalised the same way crypto-format.md specifies |

## Running it

```sh
.venv/bin/python3 main.py \
  --host photos.example.com \
  --username someone@example.com \
  --source /path/to/your/photos \
  --dry-run          # remove this once the plan looks right
```

`--dry-run` resolves and logs every file's plan (timestamp, offset, grouping)
without calling the API at all — always run this first over a new source
directory. `--limit N` caps it to the first N files, useful for a quick sanity
check before a multi-hour run over thousands of files.

A batch-wide timezone hint is available for "this camera's clock was never set
to the right zone" (`--tz-offset-min -300 --force-tz`, beats real EXIF/GPS
evidence) versus "these old files have no zone info at all, assume this one"
(`--tz-offset-min -300` alone, only fills genuine gaps — see design.md's
"Upload-supplied offset" for why the two modes exist and aren't interchangeable).

Re-running the same source directory is safe: every file is matched against the
server's own content-hash/stem dedup, so anything already uploaded comes back as
`duplicate`/`resumed` at negligible cost rather than being re-uploaded. There's no
local progress file — resumability is entirely the server's, on purpose, so
nothing here can drift from what's actually landed.

## What this handles

* **Every rendition-grouping mechanism the design already has**, applied to what
  a Google Takeout export actually contains:
  * Same-stem RAW+JPEG / Live Photo pairs — the server's own stem matching, no
    special handling needed here.
  * **Google Photos' `-edited`/`-EFFECTS`/`-SMILE`/`-PANO`/`-COLLAGE`/`-MIX`
    "edited copy" files** (measured: 655 in this tool's own corpus, 653 with a
    same-directory base sibling) — these don't share a stem with their base, so
    they'd otherwise land as disconnected assets. Detected and attached via
    `groupWith` instead, landing as a second rendition of the same photo. An
    orphaned suffix file (no base found — 2 in this tool's corpus) falls back to
    becoming its own asset, which is the only sane thing left to do.
* **The `takenAt` ladder, including the new `filename` rung** (see this branch's
  own `src/core/items.ts`/`design.md` change): EXIF `DateTimeOriginal` first,
  then a timestamp parsed out of the filename (`IMG_20180713_140225.jpg`,
  `20150408_123456.jpg`, and several other conventions — see
  `filename_time.py`), then the file's own mtime as a last resort. Validated
  against 4,864 real files that had both a filename timestamp and real EXIF: 93.5%
  agreed within 2 seconds, 98.2% within 2 minutes.
* **The full `tzOffsetMin` offset ladder** — EXIF `OffsetTimeOriginal`, GPS delta
  (generalised to work from the filename rung too, when EXIF has GPS tags but no
  `DateTimeOriginal` — measured: 49 such files), a device's stored default
  (`GET /devices`), the owner's `homeTz`, or UTC as the last resort.
* **`stripLocationOnUpload`**, read live from `GET /settings` and applied exactly
  as design.md specifies: the GPS EXIF IFD dropped from a temporary copy of each
  image (`quality="keep"` so this doesn't also silently recompress the JPEG), and
  for video, the `moov/udta/loci` / `moov/udta/©xyz` / `moov/meta` `keys`+`ilst`
  boxes zeroed and retyped `free` in place — byte-identical everywhere else,
  regardless of whether `moov` sits before or after `mdat` in the source file
  (both orderings are real in this tool's own corpus).
* **Video creation time and dimensions** via a small ISO-BMFF (`mp4box.py`) box
  walker — no `ffprobe`/`ffmpeg` dependency for this part. 92/94 real videos in
  this tool's corpus resolved a usable creation time this way.
* **The crypto format itself**: whole-object AES-256-GCM below the 32 MiB
  threshold, Tink's `AES256_GCM_HKDF_1MB` streaming construction above it, AES-KW
  key wrapping, the HMAC-SHA256 `contentHash`. `tests/test_crypto_format.py`
  decrypts this repo's own `testdata/vectors/` — the conformance requirement
  crypto-format.md states directly, not just a round-trip test of this tool's own
  code.

## What it doesn't (known, deliberate gaps)

* **Video thumbnails need `ffmpeg` on `PATH`.** Without it (the case on this
  tool's own development machine), a video uploads with no thumbnails at all —
  a real, visible gap (a blank timeline tile), not a fabricated placeholder.
  `main.py`'s summary reports how many files this affected on your machine.
* **No true streaming upload.** Ciphertext is built fully in memory before the
  PUT, for both modes — simpler and lower-risk than driving Tink's encrypting
  stream directly into a socket, at the cost of roughly 2x peak memory for the
  largest file being imported. Fine for a source corpus topping out under 1 GB
  per file (this tool's own: 758 MB, largest); would need reworking before
  pointing this at much larger files or a memory-constrained host. See
  `crypto_format.py`'s own module docstring.
* **No concurrency.** Files are processed one at a time, partly for simplicity
  and partly because the `-edited` grouping pass genuinely needs each file's base
  uploaded (or at least attempted) before it, in order.
* **Google Photos Takeout's `.supplemental-metadata.json` sidecars are not read.**
  Measured against this tool's own corpus: only ~7 of 5,592 files had no
  resolvable date from EXIF, filename, or (for video) the container's own
  creation time — sidecars would have rescued 3 of those. Not worth the
  complexity for this corpus; if yours is different (little to no EXIF, few
  filename timestamps), this would be the first thing worth adding.
* **RAW formats are not decoded at all** — Pillow has no CR2/CR3/NEF/ARW/DNG/
  RAF/ORF/RW2 decoder, matching the Android client's own documented limitation
  ("the phone will not handle RAW"). A RAW file still uploads correctly as its
  own rendition (role `raw`, grouped with a same-stem JPEG the ordinary way),
  just with no EXIF read, no thumbnails, and — the one gap worth calling out
  specifically — **no location stripping**: if `stripLocationOnUpload` is on,
  this tool has no way to find or remove a RAW file's own embedded GPS tags,
  unlike JPEG/video. The run summary reports how many RAW files this affected;
  not present in this tool's own corpus, but real for anyone whose export
  includes RAW originals with this setting on.
* **Album membership isn't imported.** `POST /uploads` has no facet/album input,
  and nothing server-side currently writes `F#ALBUM` items from anything a
  client could supply at upload time.
