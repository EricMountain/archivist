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

Imports are geocoded on this machine from the repo's bundled gazetteer, plus the
instance's region packs if it publishes any; see "Location" under "What this
handles".

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
confirming what actually got trashed afterwards. **Live assets only by
default** — pass `--include-trashed` to also search trashed ones, which then
show as `[TRASHED]`/`[live]` per match (read straight off `#META.deletedAt` —
design.md "Trash and deletion" — not off which cache section happened to turn
a photo up). Trash search was briefly default-on here once both sections were
cached the same way (below), but building or refreshing the trashed section's
cache means a `GET /trash` listing walk, which has been observed timing out
server-side at real trash sizes (a bare `HTTP 500`) even with `getTrash`'s
bounded-concurrency fix — so it's opt-in again, and a warning is printed
whenever it's left off, so an absent trashed match is never silently mistaken
for "not trashed".

```sh
.venv/bin/python3 inspect_photo.py \
  --host photos.example.com \
  --username someone@example.com \
  --filename IMG_1234.jpg
  # --contains for a substring match; --include-trashed to also search trashed assets
```

The output also shows the asset's `thumbs` (still ladder sizes) and `preview` (the video preview clip's size and key, or `none`) — the quickest way to tell whether a video's preview ever reached the server. A `.../<generation>/preview` key means it came from a repair.

Matches on *any* rendition's filename, not just the primary, so an asset still
turns up by its RAW sibling's own name. Same auth as the other two scripts, same
"nothing here is encrypted, no master key needed" as `dedupe_by_filename.py`.

**If you already have a `photoId`** — `dedupe_by_filename.py`'s own report prints
one for every candidate — `--photo-id` skips straight to `GET /photos/{photoId}`,
no pointer read, no listing, no cache:

```sh
.venv/bin/python3 inspect_photo.py \
  --host photos.example.com \
  --username someone@example.com \
  --photo-id 01K5A2QB3HN7WYP2GKD4RVXM8C
```

**If you know the exact server path but not the id** — this tool's own previous
`stem` output, say — `--path` resolves it via `GET /photos/by-path` (api.md): one
`GetItem` against the PATH pointer, live or trashed, then the same detail read
`--photo-id` does:

```sh
.venv/bin/python3 inspect_photo.py \
  --host photos.example.com \
  --username someone@example.com \
  --path -1739773001/IMG_1234.jpg
```

Both skip the library and the cache entirely — instead of `--filename`'s
cached-library search, they're one or two direct reads. `--contains`/
`--include-trashed`/`--refresh-*` don't apply to either: there's no listing to
filter or cache to refresh. Reach for `--photo-id` first when you have one,
`--path` when you don't, and `--filename` only when you have neither.

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

## Fixing something after the fact

`modify_media.py` is the "something about this imported photo is wrong and
only a human can fix it" tool — one entry point, subcommands for each kind of
correction. All target an exact asset via `--photo-id`/`--path` (same as
`inspect_photo.py` above — never a fuzzy `--filename` search, since
accidentally correcting the wrong photo because two shared a basename would
be worse than not correcting it at all; `location` also takes `--all`), and
are a dry run by default;
`--execute` applies.

**`taken-at`** corrects `takenAt`/`tzOffsetMin` — for when the whole ladder in
design.md's "Establishing takenAt" got it wrong: a camera with no clock set, a
filename-parsed date that used the wrong convention, an import that fell all
the way back to file mtime.

```sh
.venv/bin/python3 modify_media.py taken-at \
  --host photos.example.com \
  --username someone@example.com \
  --photo-id 01K5A2QB3HN7WYP2GKD4RVXM8C \
  --taken-at-local "2019-06-15 14:00:00" --tz-offset-min 120 \
  --execute
```

`--taken-at-local` takes the local wall-clock time the photographer actually
experienced plus the offset it was in (positive = east of UTC); `--taken-at`
takes a UTC instant directly if you already have one. Calls `PATCH
/photos/{photoId}` (api.md, design.md "Manually correcting takenAt"), which
sets `takenAtSrc`/`tzSrc` to `manual` server-side — the one rung that
permanently outranks every automatic source, so a later-attached rendition's
own EXIF can never silently overwrite the correction on the next upload. Works
on a trashed asset too. Cognito sign-in only, no master key: this touches no
encrypted content.

**`orientation`** regenerates a photo's thumbnails from a local file with EXIF
orientation correctly applied — for a photo imported before this tool's
`thumbnails.py` gained an `exif_transpose` call: its thumbnails came out
sideways even though the source file's own EXIF was fine (and "view original"
displayed it correctly, since Android reads that EXIF live off the original's
own bytes rather than trusting anything stored).

```sh
.venv/bin/python3 modify_media.py orientation \
  --host photos.example.com \
  --username someone@example.com \
  --path -1739773001/IMG_1234.jpg \
  --source-file /path/to/local/backup/IMG_1234.jpg \
  --execute
```

`--source-file` is the local original — the same file (or an identical copy)
`main.py` imported from, EXIF intact. Needs the account's recovery code
(`ARCHIVIST_RECOVERY_CODE`, same prompt as `main.py`) to unwrap the asset's DEK,
since thumbnails are encrypted client-side like everything else. Calls `POST
/photos/{photoId}/thumbs` (api.md), the same repair route the Android app's own
"Repair thumbnails" menu action uses.

**Add `--rotate 90/180/270`** when the orientation baked into the *original*
itself is wrong — not just a thumbnail that never read a correct EXIF tag, but
one where even "view original" shows it sideways:

```sh
.venv/bin/python3 modify_media.py orientation \
  --host photos.example.com \
  --username someone@example.com \
  --path -1739773001/IMG_1234.jpg \
  --source-file /path/to/local/backup/IMG_1234.jpg \
  --rotate 90 \
  --execute
```

The rotation is applied *on top of* whatever `exif_transpose` already does with
the source file's own EXIF, and clockwise — `--rotate 90` on a photo that
already displays upright turns it 90° clockwise from there. It targets the
asset's primary rendition, re-encodes it (same format, quality 95) and calls
the new `POST .../renditions/{renditionId}/replace` (api.md, design.md
"Replacing a rendition's bytes") to swap the stored original's bytes in place,
then regenerates and uploads thumbnails from those same corrected pixels — one
`--execute` run, two API calls, always in that order (original, then
thumbnails, matching `POST /uploads`' own sequencing for a first-time upload).

Neither `taken-at` nor `orientation` does more than one photo per run,
deliberately: each correction needs its own actual value, not a uniform shift
or fix, so they don't try to guess a bulk mode — loop the script yourself if
you have a list. `location` (below) is the exception, because its correction
*is* uniform: re-derive the place from the photo's own coordinates.

**`location`** re-geocodes a photo's place, or every photo's with `--all`
(docs/plans/geolocation.md "Manual updates", design.md "Location" → "Manual
update"). For assets uploaded before place names existed, or geocoded with an
older gazetteer or pack set:

```sh
.venv/bin/python3 modify_media.py location \
  --host photos.example.com \
  --username someone@example.com \
  --all --source-dir /path/to/local/backup
# check the per-asset "old -> new" lines, then:
.venv/bin/python3 modify_media.py location ... --all --source-dir ... --execute
```

Per asset it decrypts `exifEnc` and uses its `gpsLat`/`gpsLon` if present
(`[coords: exif-blob]`). Otherwise it reads the primary rendition's original,
from a local copy if there is one, else downloaded and decrypted from
`/media/*` (`[coords: download]`). A chunked (≥ 32 MiB) JPEG fetches only its first 1 MiB
ciphertext segment, since EXIF sits at the very start; videos are fetched
whole, since `moov` can be at the end. Coordinates are read with the
importer's own `media_probe.py`/`mp4box.py` code, (0, 0) counting as none. It
then geocodes exactly as the importer does (the gazetteer, `--gazetteer`, else
`$ARCHIVIST_GAZETTEER`, else the Android app's bundled copy, plus the instance's
region packs if it publishes any) and calls `PUT
/photos/{photoId}/location`. When the coordinates came from the original, the
PUT also carries `exifEnc` re-encrypted (same DEK, same `…:x` AAD, fresh IV)
with `gpsLat`/`gpsLon`/`gpsAltM` added and every other field kept, so the
next run never needs the original. No coordinates anywhere leaves the stored place as
it is ("keeps the stored place"): it can't be re-derived, and it may have been set by
hand (`locate_missing.py`, the app's "Update location"), so a re-geocode after a new
pack set must not erase it. The photo goes in the no-GPS memory, so later runs don't
download its original again unless the original or its `exifEnc` changes.
`--clear-without-gps` restores the old behaviour, sending `place: null` instead.

**Local copies (`--source-dir`).** The directory can have any layout; it
doesn't have to mirror the server paths. It is walked once at the start and
every file indexed by name. For each original needed, in order:

1. `<source-dir>/<rendition path>`, the path `main.py` uploaded it under
   (`[coords: source-dir]`);
2. any file anywhere under `<source-dir>` with the same basename as the
   server path (`-1739773001/IMG_1234.jpg` → `IMG_1234.jpg`), compared
   case-insensitively and Unicode-normalised, since cameras, phones and copy
   tools disagree on `IMG_1234.JPG` vs `img_1234.jpg` and macOS decomposes
   accented names (`[coords: source-dir by filename]`, with the local path
   after `<-`).

A file counts only if its size and `contentHash` (the HMAC of the plaintext,
keyed by the account's hash secret, so only this tool can compute it) are the
stored original's. A same-named different photo, say `IMG_0001.jpg` from two
cameras, is never read; when several files share the name, the one whose hash
matches is used. Only files of the right size are hashed, each at most once
per run. There is no persistent hash cache: normally exactly one file per asset
is hashed, once `--execute` has stored the coordinates the asset is skipped
for good, and a dry run writes no cache, so a cache wouldn't save the dry
run → `--execute` re-read.

**`--local-only`** (needs `--source-dir`) never downloads an original. An asset
that needs one and has no verified local copy is reported as **missing
locally** and left exactly as it is: no `place: null`, no entry in the no-GPS
memory, not an error. A later run with more local copies, or without
`--local-only`, picks it up. **`--missing-report PATH`** writes every asset that
needed its original and had no verified local copy to a CSV (`photoId`, `path`,
`filename`, `plainBytes`, `takenAt`, `reason`, `outcome`, `detail`,
`candidates`, `usedLocalCopy`). `reason` is `no-file-by-name` or
`name-found-content-differs` (with the rejected local paths in `candidates`),
or one of the `content-differs-*` reasons below with `--allow-content-mismatch`;
`outcome` is `left untouched`, `downloaded` (without `--local-only`),
`download refused: archived` or `used unverified local copy`. The
report is written in a dry run too: it's the dry run's output, not state.
The first 20 missing assets are also printed after the summary.

**`--allow-content-mismatch`** (needs `--source-dir`) is for local copies that
are the same photo but not the same bytes as what was uploaded (re-saved,
metadata edited by some tool). It skips both the size and the `contentHash`
check — a different size always means a different hash, so they can't be
separated — and puts a capture-time check in their place:

* A verified copy (size + `contentHash` match) always wins; an unverified one
  is only considered when no verified one exists.
* A same-named candidate counts only if its own capture time is within 2 s of
  the asset's `takenAt`, compared in UTC: a still's EXIF `DateTimeOriginal` is
  read at the asset's `tzOffsetMin` (the offset `takenAt` was derived with)
  and, if the file has one, at its own `OffsetTimeOriginal`; a video's
  `mvhd` creation time is UTC already. 2 s because both are whole seconds
  while `takenAt` has milliseconds, plus one second for a tool that rounds;
  a same-named different photo (another camera, a counter reset) is not
  going to have been shot in the same 2 s.
* Exactly one candidate may pass (several byte-identical copies count as
  one). None with a capture time → `content-differs-no-capture-time`; only
  other times → `content-differs-time-mismatch`; several different files →
  `content-differs-ambiguous`. None is used in those cases.
* An accepted copy's coordinates go through the normal path (geocode,
  `exifEnc` re-encrypted with them, PUT), shown as `[coords: source-dir
  UNVERIFIED (name + capture time)]` with the local path after `<-`. An
  accepted copy **without GPS** (`content-differs-no-gps`) or that can't be
  read (`content-differs-unreadable`) proves nothing, since a re-saved copy
  may have lost the GPS its original kept: it is treated as missing (the
  original is downloaded in the same run, or left untouched with
  `--local-only`), never cleared to `place: null` (even with `--clear-without-gps`) and never put in
  the no-GPS memory.
* Audit trail: every copy used this way is a row in `--missing-report`, with
  `outcome` `used unverified local copy`, the file in `usedLocalCopy` and the
  capture-time comparison in `detail`; the summary counts them separately
  (`local copy, UNVERIFIED`) and breaks the rejections down by reason.

The risk, in one sentence: the content of such a copy is not verified, so if a
different photo has the same name *and* a capture time within 2 s of the
original's, its GPS is what gets stored.

A plain dry run (no `--local-only`) still **downloads** every original it
needs and has no local copy of, so that it can say what `--execute` would do.
On a library uploaded before geolocation that is nearly every original. Add
`--local-only` to make it download nothing. For a whole library whose
originals you also have locally:

```sh
# 1. Dry run: reads local files only, downloads nothing, writes nothing but the report.
.venv/bin/python3 modify_media.py location \
  --host photos.example.com --username someone@example.com \
  --all --source-dir /path/to/local/photos --local-only \
  --missing-report /tmp/location-missing.csv
# Check the coverage line and the report. If coverage is good enough:
# 2. Apply. Missing assets stay untouched and can be handled later.
.venv/bin/python3 modify_media.py location \
  --host photos.example.com --username someone@example.com \
  --all --source-dir /path/to/local/photos --local-only \
  --missing-report /tmp/location-missing.csv --execute
```

**Archive tiers.** Originals are stored in S3 Intelligent-Tiering (design.md
"Tiering") with no opt-in archive tiers, so every one is readable by a plain
GET, including objects that have sunk to the Archive Instant Access tier.
Reading one carries no retrieval fee, but it moves the object back to the
Frequent Access tier, billed at that rate until it goes 30 days unread
again. That is the cost `--local-only` avoids. If an operator has enabled
Intelligent-Tiering's Archive Access / Deep Archive Access tiers, or moved
objects into a Glacier Flexible Retrieval / Deep Archive class, S3 refuses the
GET with `403 InvalidObjectState`. Such an asset is reported as **archived**,
its own category, not an error, and left untouched. Nothing here restores it.

* **Skip rule:** an asset whose `place.src` is already current (the gazetteer's,
  or `osm@<set>;<gazetteer>` once the instance publishes a pack set, so the first
  run after publishing one redoes every located asset) is
  skipped straight off the `GET /photos` listing, with no per-asset fetch, so
  `--all` is resumable and an immediate re-run does nothing. Assets whose
  original had no GPS are remembered in the local cache (`.cache/`, see
  "Local caching" above; keyed by photoId plus the primary rendition's id and
  `contentHash`), so a re-run doesn't download them again. `--force` ignores
  both.
* **`--include-trash`** adds trashed assets to `--all` (the PUT works on them,
  moving no place counts).
* **Strip-on:** if the owner has `stripLocationOnUpload` on, it refuses before
  asking for the recovery code, unless `--ignore-strip-setting`.
* **Errors don't abort `--all`.** The summary counts updated, unchanged,
  skipped (current `src`), no GPS, missing locally (with `--local-only`),
  archived (when any) and errors, lists each error with its photoId, and gives
  wall time, bytes downloaded and bytes read locally. A second block covers the
  assets that needed their original: how many came from a local copy (exact
  path / by filename / unverified with `--allow-content-mismatch`), were
  downloaded, had no verified local copy (no file by that name / name found
  but size or `contentHash` differs, broken down by `content-differs-*` reason
  with `--allow-content-mismatch`) or were archived, and a coverage line,
  `local copies found for N of M originals needed (P%)`.
  Only errors make the exit status non-zero. A `409` (the photo changed
  between read and write) is retried once from a fresh read.
* **HEIC/HEIF originals** are read through `pillow-heif` (downloaded whole: their
  EXIF isn't guaranteed to sit in the first segment the way a JPEG's does).
* **Can't read:** RAW originals (Pillow has no decoder for them), so an asset
  whose coordinates aren't already in `exifEnc` and whose primary is RAW is
  reported as an error rather than cleared.
* `--concurrency N` (default 8) assets at a time. Needs the recovery code
  (`ARCHIVIST_RECOVERY_CODE`), like `orientation`.

Afterwards, `make backfill-places-dev ALL=1` (a dry run of
`tools/backfill-places.mjs`) should report `differences: 0`.

## Placing photos that have no GPS

`locate_missing.py` works out a coarse place for every file under a local
directory that has no GPS of its own, and writes it to a JSON Lines file
(`missing-locations.jsonl`, gitignored) for a later `PUT
/photos/{photoId}/location`. It never signs in. Its network access is a one-time
download of GeoNames `cities500.zip`, `admin1CodesASCII.txt` and `countryInfo.txt`
into `.cache/locate/geonames/`, and, with `--host`, the instance's public region-pack
manifest and packs (cached in `.cache/geo/`).

```sh
.venv/bin/python3 locate_missing.py --source /path/to/your/photos --host photos.example.com
.venv/bin/python3 locate_missing.py --source ... --no-prompt   # infer + report only
```

Pass the `--host` the output is for: its region packs decide what a `place` looks
like there (design.md "Detailed places"), as they do for uploads. Without it, places
come from the gazetteer alone, and `modify_media.py location` would later redo them
on an instance that publishes packs. `--manifest PATH` uses a pack set on disk
instead (`make geo-build`'s output).

It's built to ask as little as possible:

* **Inferred without asking:** an edited copy whose original (same directory)
  has GPS, a photo bracketed by two GPS photos within `--bracket-hours` (4) that
  are within `--agree-km` (15) of each other, or within `--near-minutes` (45) of
  one GPS photo. Never for a file whose only time is its mtime.
* **One question per cluster:** photos are grouped wherever consecutive ones are
  more than `--gap-hours` (6) apart, shown as an inline contact sheet (iTerm2,
  through tmux if `allow-passthrough` is on; otherwise opened in Preview), with a
  suggestion taken from the nearest known place in time (within
  `--suggest-days`, 90). Enter accepts it; `s` splits a mixed cluster at its
  biggest time gap.
* **Review sheets:** once a place is known, the next clusters (up to `--sheet`
  photos, 24) are shown on one numbered sheet with the question "which are NOT
  <place>?". Enter confirms them all; `3 7-9` sends those back as questions, each
  run of consecutive numbers as one. Years of photos at home cost one keypress
  per sheet.
* **Names:** type part of a name in any language GeoNames has (`lyo`, `munchen`,
  `st etienne`), optionally narrowed (`paris, tx`, `paris, france`), or a region or
  country (English names) for something coarser; also `lat,lon` or `#<geonameid>`.
  With region packs, names in the packs are searched too: communes, villages,
  hamlets, protected areas and natural features. Communes are listed with their
  level-6 area (a French département, a US county) since names repeat ("Le Monteil,
  Cantal" vs "Le Monteil, Haute-Loire"), and that name narrows a search too
  (`le monteil, cantal`); it is only in the label, not the stored place. A pick means no more than it says:
  a commune is a locality; a village or hamlet is added to its commune (a hamlet
  with the village it's near); a park is just the park, since it can span
  communes; a peak or lake is added to its commune. A GeoNames town in a covered
  area becomes the commune its centre is in. Coordinates typed as `lat,lon` get
  every layer. Candidates are ranked by match, population (or, for pack names, a
  stand-in by kind) and distance to the suggestion. You
  confirm a name once; it is remembered in `.cache/locate/aliases.json` across
  runs and sources (`!name` to choose again).

Answers are saved after each question (`.cache/locate/<source hash>/state.json`):
`q` or Ctrl-C, then the same command, resumes. `u` undoes the last answer, `-`
skips a cluster (`--retry-skipped` asks again). Changing the thresholds and
re-running only changes what is inferred, never what you answered. The file scan
is cached by path, size and mtime.

Each output line carries `path` (relative to `--source`), `size`, `takenLocal`,
`timeSrc`, `method` (`prompt`, `neighbour-gps`, `sibling-gps`, plus `basis` for
the inferred ones), `place` in exactly the shape the API takes (with the
geocoder's `src`), a human `label`, and `approx`: the chosen place's centre or
the coordinates typed. An inferred place comes from a nearby GPS photo but is kept
at town or commune level, never that photo's village or hamlet. `approx` is not a measurement and shouldn't go into
`exifEnc` as `gpsLat`/`gpsLon`.

Nothing consumes the file yet: `modify_media.py location` would need a mode that
reads it, matches each `path` to an asset the way `--source-dir` already does,
and PUTs `place` alone.

## Looking up coordinates

`geocode.py` prints the cities500 entry for a pair of coordinates, using the same
bundled `gazetteer.bin` lookup as the importer and the app, so the `place` shown
is exactly what an upload at those coordinates would get on an instance with no
region packs. `--host HOST` adds what HOST's region packs give (every layer, as an
upload there gets it), and `--manifest PATH` the same for a pack set on disk, to
try one before publishing it:

```sh
.venv/bin/python3 geocode.py 45.7640,4.8357 "-33.8688, 151.2093"
echo "35.0116,135.7681" | .venv/bin/python3 geocode.py --json
.venv/bin/python3 geocode.py --details --nearest 5 46.2044 6.1432
```

`--details` adds the matched locality's full cities500 row; `--nearest K` lists the
K nearest entries ignoring the 25/100 km caps, for when the answer surprises you
(a border town, a city district such as "Lyon 01" winning over the city). Both
read the GeoNames files `locate_missing.py` caches in `.cache/locate/geonames/`.

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
* **Location: every import is geocoded on this machine**, the same way the Android
  app does it (design.md "Location"). Coordinates come from EXIF for stills
  (`GPSLatitude`/`GPSLongitude` and their refs, plus `GPSAltitude`/`GPSAltitudeRef`)
  and from the container for video (the `moov/meta` keys+ilst ISO 6709 entry, then
  `©xyz`, then `loci`). They go into the encrypted EXIF blob as `gpsLat`/`gpsLon`/
  `gpsAltM`, the same field names as the app's `ExifBlob`. `gazetteer.py` turns them
  into the plaintext `place` sent with `POST /uploads`: nearest GeoNames place within
  25 km gives country, region and town; within 100 km, country and region; further
  out, no `place` at all. Nothing leaves the machine for this; there is no geocoding
  service. A file with no coordinates, an all-zero GPS block, or any file while
  `stripLocationOnUpload` is on gets neither coordinates nor a `place`.
  `main.py`'s summary reports how many files had coordinates and how many got a
  place. `--dry-run` prints the place it would send.

  The gazetteer is the `gazetteer.bin` the app bundles, read from
  `android/app/src/main/assets/gazetteer.bin` in this checkout by default. Point at
  another copy with `--gazetteer PATH` or the `ARCHIVIST_GAZETTEER` environment
  variable (the flag wins). It must be the same file the app ships, or the two
  clients disagree about where a photo was (`tools/gazetteer/README.md`).
  `tests/test_gazetteer.py` checks this reader against the shared
  `tools/gazetteer/vectors.json`. Place names come from
  [GeoNames](https://www.geonames.org/), CC BY 4.0.

  The lookup is sent as-is. A place within 25 km whose GeoNames record has no region
  (about 460 places, e.g. Stanley, Falkland Islands) goes up as `{cc, locality}`, which
  `POST /uploads` accepts; the app does the same.

  **Region packs.** If the instance publishes them (its public
  `https://<host>/geo/manifest.json`; design.md "Detailed places: OpenStreetMap
  region packs"), `places.py` applies them on top of the gazetteer, the way the app
  does: the commune, village, hamlet, protected areas and natural feature around a
  photo, and `src` `osm@<set>;<gazetteer src>`. Packs are downloaded when first
  needed, checked against the manifest's size and sha256, and cached in
  `.cache/geo/`. A pack that can't be had gives that photo the gazetteer's place and
  `src`, so a later `modify_media.py location --all` redoes it.
  `tests/test_geopack.py` checks `geopack.py` against `tools/geopack/vectors.json`.
  Map data © OpenStreetMap contributors, ODbL 1.0.
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
* **HEIC/HEIF is supported** via `pillow-heif` (BSD-licensed, bundling libheif),
  registered with Pillow in `media_probe.py`: EXIF (takenAt, camera, GPS and
  place) and the thumbnail ladder, upright — pillow-heif applies the container's
  own rotation on decode. With `stripLocationOnUpload` on, a HEIC that has GPS is
  re-encoded without it (quality 90; unlike JPEG there is no lossless re-save,
  and auxiliary images such as depth maps or an HDR gain map are dropped).
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
