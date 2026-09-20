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

## Cleaning up duplicates the content-hash dedup missed

`contentHash` is an HMAC over the exact plaintext bytes (crypto-format.md), so it
only catches a duplicate whose bytes are identical to what's already there — a
different export of the same photo (different compression, a resized copy, a
different quality tier) hashes differently and lands as a second, independent
asset instead of attaching to the first. `dedupe_by_filename.py` cleans these up
after the fact: it groups live assets by (filename, exact `takenAt`), keeps the
one with the largest original file per group, and trashes the rest via the
ordinary `DELETE /photos/{photoId}` route — the same one the app's own delete
button uses, so a mistake is recoverable via restore, not gone outright.

```sh
.venv/bin/python3 dedupe_by_filename.py \
  --host photos.example.com \
  --username someone@example.com
  # add --execute once the dry-run report looks right
```

Defaults to a dry run — it reports every duplicate group and exactly what it
would keep/delete/skip, and deletes nothing until you pass `--execute`. A losing
asset with more than one rendition (RAW+JPEG, a Live Photo, an edited-copy
sibling) is skipped rather than auto-deleted by default, since trashing it takes
every rendition with it — pass `--include-multi-rendition` to also delete those
once you've checked the report.

Matching is deliberately literal — exact filename, exact `takenAt` to the
millisecond — not a fuzzy "probably the same photo" heuristic. That means a real
duplicate whose two copies resolved to slightly different `takenAt` values (one
via a better-informed rung of the ladder than the other) won't be caught; it
also means this won't over-match two genuinely different photos. No master key
or recovery code needed — only `takenAt`/dimensions/sizes/`path` are read, all
of them unencrypted by design (see `docs/design/design.md`'s "Encrypted EXIF").

**What "standard delete" actually does, if you're relying on cleanup happening
on its own afterwards**: `DELETE /photos/{photoId}` only *trashes* the asset —
recoverable via `POST /photos/{photoId}/restore` for `trashRetentionDays` (owner
setting, default **30 days**), after which a daily sweep purges the S3 objects
and most DynamoDB rows for that asset for real. The one thing that does *not*
go away on that same 30-day schedule is the content-hash pointer itself: purge
converts it to a tombstone rather than deleting it outright, specifically so a
source that keeps re-offering the same bytes keeps getting blocked — that
tombstone's own TTL is `tombstoneRetentionDays` (owner setting, default **365
days**), and every blocked re-upload attempt pushes it out further. It carries
no S3 object and blocks nothing but that exact content hash, so there's nothing
to clean up by clearing it faster, and no reason to.

### Inspecting a photo by filename before (or after) trusting the report

`inspect_photo.py` prints everything the API knows about every asset matching a
filename — full `#META` (dimensions, timestamps and which ladder rung produced
them, status, grouping) and every rendition (role, path, size, content hash) —
for eyeballing a `dedupe_by_filename.py` report before running `--execute`, or
confirming what actually got trashed afterwards. **Searches trashed assets by
default, alongside live ones**, showing `[TRASHED]`/`[live]` on each match
(read straight off `#META.deletedAt` — design.md "Trash and deletion" — not off
which cache section happened to turn a photo up) — pass `--live-only` if you
only want live ones. Trash used to be opt-in here because finding it meant an
uncached, always-live walk; now that both live and trashed detail are cached
the same way (below), there's no reason to hide one by default.

```sh
.venv/bin/python3 inspect_photo.py \
  --host photos.example.com \
  --username someone@example.com \
  --filename IMG_1234.jpg
  # --contains for a substring match; --live-only to skip trashed assets
```

Matches on *any* rendition's filename, not just the primary, so an asset still
turns up by its RAW sibling's own name. Same auth as the other two scripts, same
"nothing here is encrypted, no master key needed" as `dedupe_by_filename.py`.

**If you already know the exact path** (not just the filename) — from a
`dedupe_by_filename.py` report, or from this tool's own previous `stem` output —
`--path` skips the library entirely:

```sh
.venv/bin/python3 inspect_photo.py \
  --host photos.example.com \
  --username someone@example.com \
  --path -1739773001/IMG_1234.jpg
```

This resolves via `GET /photos/by-path` (api.md) — one `GetItem` against the
PATH pointer, live or trashed, no listing and no cache involved at all — instead
of `--filename`'s cached-library search. `--contains`/`--live-only`/
`--refresh-*` don't apply to it: there's no listing to filter or cache to
refresh. Use it whenever the exact path is in hand; fall back to `--filename`
when it isn't.

Add `--refresh-match` to bypass the cache for just what matched — after finding
candidates (from the cache, by default), it re-fetches live detail for exactly
those photos and patches the cache with the result, with no full listing walk
or full re-fetch of everything else. The right choice for "I want to be sure
about *this* photo specifically" — right after running `dedupe_by_filename.py
--execute`, say, to confirm what happened to it. It can only refresh what the
cache already pointed it at, though: a photo renamed away from the filename
you're searching for won't be found this way (there's nothing cached under
that name to bypass) — `--refresh-listing`/`--refresh-cache` are for that.

### Local caching

Both scripts above spend almost all their time on one thing: neither
`GET /photos` nor `GET /trash` project `path`/`plainBytes` into their lean
listings (design.md: "path is deliberately not denormalised"), so finding
duplicates or a photo by name — including telling whether it's actually been
trashed — means fetching every asset's *full* detail one `GET /photos/{photoId}`
at a time, live and trashed alike — thousands of round trips for a real-sized
library, every single run, even when nothing's changed since the last one.

So both cache that per-asset detail to a local file under `.cache/` (gitignored,
one file per host+username, live and trashed kept as two independent sections
since an asset moving between them — trashed, restored — is exactly the kind of
change worth being able to see) and, **by default, trust it completely**: no
live calls at all, not even the cheap id listing, until you ask for one. Three
levels, cheapest first:

* **Default** — read the cache file as-is. A pure local read; nothing goes
  over the network. Right for "I'm iterating on a report and the library
  hasn't changed since I last ran this." The very first run has no cache to
  read, so it's necessarily a full live scan regardless of flags.
* **`--refresh-listing`** — additionally does the *cheap* live id listing
  (tens of calls, not thousands), so a photo added or trashed since the cache
  was built is picked up: a new id gets fetched fresh, an id no longer live
  gets dropped. Detail for everything else is still reused from the cache.
* **`--refresh-cache`** — like `--refresh-listing`, but re-fetches *every*
  currently-live photo's own detail too, not just the new ones.
* **`inspect_photo.py --refresh-match`** — a fourth, targeted option that
  doesn't fit the cheapest-to-thorough ladder above because it isn't about the
  whole library at all: bypass the cache for exactly the handful of photos a
  search just matched, and patch only those back into the cache file. See
  "Inspecting a photo" above.

What none of the above catch, even `--refresh-cache`: a rename on a photo that
stays live the whole time, since that touches neither the listing nor which
ids exist (`--refresh-match` catches this for a photo it can already find some
other way, but not one renamed away from the very name you're searching for —
there's nothing cached under that name to bypass). That's a real, known gap,
not a hidden one — and it's why `dedupe_by_filename.py --execute` never trusts
the cache for the deletion itself, at any of these levels: it re-fetches and
re-verifies each duplicate group live, right before deleting anything from it,
so a stale cache can cost a wasted API call but never a wrong deletion.
`inspect_photo.py` has no mutation to protect, so for it this is purely about
how sure you need the report to be right now.

`--no-cache` bypasses the cache entirely — reads none, writes none, always a
full live scan — for "just don't touch disk at all."

We considered a DynamoDB index instead (a filename-keyed GSI, to make this kind
of query cheap server-side) and decided against it: `design.md` is explicit and
repeated that `path` is deliberately not denormalised anywhere, specifically to
keep renames O(1) — adding one would mean a permanent extra write on every
rendition, a backfill, and a schema change (plus updating `design.md`/
`sample-data.md` to match, per this repo's own sync rules) to speed up a script
that runs occasionally, for a query the app itself never needs. A local cache
gets the same speedup with none of that cost, and fits this tool's own framing
(design.md: "third parties use the API, not the datastore") better than adding
a bespoke index would.

That's a different thing from `--path` above, which needed none of this: the
PATH pointer it reads (`GET /photos/by-path`) already existed — `POST /uploads`
and rename have relied on it for years to enforce path uniqueness — so exposing
it cost one read-only route, no new index, no backfill, no extra write. The
GSI we rejected here would have been for *fuzzy* filename search across the
whole corpus (what `--filename`/`--contains` still do, via the cache); `--path`
only ever answers "what's at this exact path," which the existing pointer
already knows for free.

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
