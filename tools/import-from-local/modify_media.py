#!/usr/bin/env python3
"""Manually corrects one photo's metadata after the fact -- one entry point for
the "something about this imported photo is wrong and only a human can fix it"
family of corrections. Subcommands:

  taken-at      Corrects takenAt/tzOffsetMin (`PATCH /photos/{photoId}`,
                design.md "Manually correcting takenAt"). Cognito sign-in only
                -- touches no encrypted content.
  orientation   Regenerates a photo's thumbnails from a local source file with
                EXIF orientation correctly baked in (`POST
                /photos/{photoId}/thumbs`) -- for photos imported before
                thumbnails.py's `exif_transpose` fix (this tool's own thumbnail
                generator never applied it, so a sideways-shot photo's
                thumbnails came out sideways even though the original file's
                own EXIF was fine and "view original" -- which reads that EXIF
                live -- displayed it correctly). Needs the account's recovery
                code to unwrap the asset's DEK: thumbnails are encrypted
                client-side like everything else (design.md "Third parties use
                the API, not the datastore"). Add --rotate to also replace the
                stored *original* (`POST .../renditions/{id}/replace`) with an
                extra clockwise rotation on top of whatever EXIF already
                implies -- for when the orientation baked into the original
                itself is wrong, not just its thumbnails.

  location      Re-geocodes a photo's place (`PUT /photos/{photoId}/location`,
                design.md "Location" / "Manual update",
                docs/plans/geolocation.md "Manual updates"), for one photo or,
                with --all, the whole library. Coordinates come from the
                decrypted exifEnc, else from the primary rendition's original
                (a verified local copy under --source-dir -- at the same
                relative path, else any file with the same name -- else
                downloaded and decrypted, only the first ciphertext segment
                for a chunked JPEG; --local-only never downloads), read with
                the importer's own media_probe/mp4box code.
                --allow-content-mismatch also accepts a same-named local copy
                whose bytes differ, when its capture time matches takenAt.
                When they came from the original, the PUT also carries the
                exifEnc re-encrypted with gpsLat/gpsLon/gpsAltM added, so the
                original is never needed again. No coordinates anywhere ->
                place: null. Skips assets whose place.src is already the
                gazetteer's (--force to redo them) and remembers no-GPS assets
                in library_cache.py's cache so a re-run doesn't re-download
                them. Refuses when the owner has stripLocationOnUpload on,
                unless --ignore-strip-setting. Needs the recovery code, like
                orientation. Errors don't abort --all; the summary lists them.

All target an exact asset via --photo-id or --path, never a search -- see
inspect_photo.py for the same two flags and why a mutation tool doesn't offer
--filename's fuzzy search (you don't want to accidentally correct the wrong
photo because two shared a basename). `location` alone also takes --all.

Usage:
    .venv/bin/python3 modify_media.py taken-at --host photos.example.com \\
        --username someone@example.com --photo-id 01K5A2QB3HN7WYP2GKD4RVXM8C \\
        --taken-at-local "2019-06-15 14:00:00" --tz-offset-min 120 --execute

    .venv/bin/python3 modify_media.py orientation --host photos.example.com \\
        --username someone@example.com --path -1739773001/IMG_1234.jpg \\
        --source-file /path/to/local/backup/IMG_1234.jpg --execute

    .venv/bin/python3 modify_media.py orientation --host photos.example.com \\
        --username someone@example.com --path -1739773001/IMG_1234.jpg \\
        --source-file /path/to/local/backup/IMG_1234.jpg --rotate 90 --execute

    .venv/bin/python3 modify_media.py location --host photos.example.com \\
        --username someone@example.com --all --source-dir /path/to/local/backup \\
        --local-only --missing-report missing.csv --execute
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import sys
import tempfile
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from PIL import Image, ImageOps

import cli_auth
import crypto_format as cf
import gazetteer as gz
import library_cache
import library_scan
import media_probe
import mp4box
import takenat_ladder
import thumbnails
from api_client import ApiError, download_media, put_bytes
from importer import EnrollmentError, b64, b64d, enroll, to_iso_utc


def add_common_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--host", required=True)
    parser.add_argument("--username", required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--photo-id", help="a ULID already in hand -- straight to GET /photos/{photoId}")
    target.add_argument(
        "--path", help="exact server path, e.g. -1739773001/IMG_1234.jpg -- resolved via GET /photos/by-path"
    )
    parser.add_argument("--execute", action="store_true", help="apply the correction (default: dry run)")


def resolve_target(api, args) -> str | None:
    """--photo-id or --path down to a bare photoId, or None (already reported) if
    --path doesn't resolve to anything."""
    if args.path:
        ptr = api.get_photo_by_path(args.path)
        if ptr is None:
            print(f"\nNo asset filed under path {args.path!r}.", file=sys.stderr)
            return None
        return ptr["photoId"]
    return args.photo_id


# --- taken-at --------------------------------------------------------------


def parse_taken_at_local(value: str, tz_offset_min: int) -> str:
    """A naive local wall-clock string, given the offset it was recorded in, to a
    UTC ISO-8601 instant. `tz_offset_min` minutes *east* of UTC, so the UTC
    instant is the local value minus that offset."""
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M"):
        try:
            naive = datetime.strptime(value, fmt)
            break
        except ValueError:
            continue
    else:
        raise ValueError(f"couldn't parse {value!r} as a local date/time (try 'YYYY-MM-DD HH:MM:SS')")
    aware = naive.replace(tzinfo=timezone(timedelta(minutes=tz_offset_min)))
    return to_iso_utc(aware)


def parse_taken_at_utc(value: str) -> str:
    """An already-UTC ISO-8601 instant, normalised to the server's exact
    fixed-width shape -- naive input (no offset/Z) is treated as UTC directly,
    since that's what --taken-at is documented to mean."""
    text = value.strip()
    normalised = text[:-1] + "+00:00" if text.endswith("Z") else text
    try:
        parsed = datetime.fromisoformat(normalised)
    except ValueError as e:
        raise ValueError(f"couldn't parse {value!r} as an ISO-8601 instant: {e}") from None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return to_iso_utc(parsed)


def add_taken_at_parser(sub: argparse._SubParsersAction) -> None:
    p = sub.add_parser("taken-at", help="correct takenAt/tzOffsetMin")
    add_common_args(p)
    when = p.add_mutually_exclusive_group(required=True)
    when.add_argument("--taken-at", help="new takenAt as a UTC instant, e.g. 2019-06-15T14:00:00.000Z")
    when.add_argument(
        "--taken-at-local",
        help="new takenAt as local wall-clock time, e.g. '2019-06-15 14:00:00' -- requires --tz-offset-min",
    )
    p.add_argument(
        "--tz-offset-min",
        type=int,
        help="UTC offset in minutes, positive = east of UTC (e.g. 120 for UTC+2). Required with "
        "--taken-at-local; with --taken-at, defaults to the asset's existing tzOffsetMin",
    )
    p.set_defaults(run=run_taken_at)


def run_taken_at(args) -> int:
    if args.taken_at_local and args.tz_offset_min is None:
        print("error: --taken-at-local requires --tz-offset-min", file=sys.stderr)
        return 2

    def progress(msg: str) -> None:
        print(msg, file=sys.stderr)

    try:
        api = cli_auth.authenticate(args.host, args.username, progress).api
    except cli_auth.AuthFailed as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    photo_id = resolve_target(api, args)
    if photo_id is None:
        return 1

    detail = api.get_photo_detail(photo_id)
    if detail is None:
        print(f"\nNo asset with photoId {photo_id!r}.", file=sys.stderr)
        return 1
    meta = detail["meta"]

    try:
        if args.taken_at_local:
            new_taken_at = parse_taken_at_local(args.taken_at_local, args.tz_offset_min)
        else:
            new_taken_at = parse_taken_at_utc(args.taken_at)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    new_tz_offset_min = args.tz_offset_min if args.tz_offset_min is not None else meta.get("tzOffsetMin", 0)

    trashed = " [TRASHED]" if meta.get("deletedAt") else ""
    print(f"\n{photo_id}{trashed}  (stem: {meta.get('stem')})")
    print(f"  takenAt      {meta.get('takenAt')}  ->  {new_taken_at}")
    print(f"  tzOffsetMin  {meta.get('tzOffsetMin')}  ->  {new_tz_offset_min}")
    print(f"  takenAtSrc   {meta.get('takenAtSrc')}  ->  manual")
    print(f"  tzSrc        {meta.get('tzSrc')}  ->  manual")

    if not args.execute:
        print("\nDry run only -- nothing changed. Re-run with --execute to apply.")
        return 0

    api.patch_taken_at(photo_id, new_taken_at, new_tz_offset_min)
    print("\nApplied.")
    return 0


# --- orientation -------------------------------------------------------------


def add_orientation_parser(sub: argparse._SubParsersAction) -> None:
    p = sub.add_parser("orientation", help="regenerate thumbnails with EXIF orientation applied")
    add_common_args(p)
    p.add_argument(
        "--source-file", required=True, help="local path to the original file, EXIF orientation intact"
    )
    p.add_argument(
        "--rotate",
        type=int,
        choices=(90, 180, 270),
        help="also replace the stored ORIGINAL (POST .../renditions/{id}/replace, api.md), rotated "
        "this many degrees clockwise *beyond* whatever the source file's own EXIF orientation "
        "already implies -- for when the orientation baked into the original itself is wrong, not "
        "just the thumbnails (which get regenerated from these same corrected pixels either way). "
        "Targets the asset's primary rendition. Without this flag only thumbnails are touched.",
    )
    p.set_defaults(run=run_orientation)


def _encode_image(im: Image.Image, pil_format: str) -> bytes:
    """Re-encodes a corrected `Image` back to bytes in its source format -- quality
    is a lossy-format-only kwarg (PNG et al reject it outright), so this falls back
    to a plain save rather than assuming every format takes it."""
    buf = io.BytesIO()
    try:
        im.save(buf, format=pil_format, quality=ORIGINAL_REENCODE_QUALITY)
    except TypeError:
        buf = io.BytesIO()
        im.save(buf, format=pil_format)
    return buf.getvalue()


ORIGINAL_REENCODE_QUALITY = 95


def run_orientation(args) -> int:
    def progress(msg: str) -> None:
        print(msg, file=sys.stderr)

    try:
        api = cli_auth.authenticate(args.host, args.username, progress).api
    except cli_auth.AuthFailed as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    print("Enrolling this run as a device via the account's recovery code...", file=sys.stderr)
    recovery = cli_auth.read_secret("ARCHIVIST_RECOVERY_CODE", "Recovery code (XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX): ")
    try:
        enrollment = enroll(api, recovery)
    except EnrollmentError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"error unwrapping the master key -- likely a mistyped recovery code: {e}", file=sys.stderr)
        return 1

    photo_id = resolve_target(api, args)
    if photo_id is None:
        return 1

    detail = api.get_photo_detail(photo_id)
    if detail is None:
        print(f"\nNo asset with photoId {photo_id!r}.", file=sys.stderr)
        return 1
    meta = detail["meta"]
    dek = cf.unwrap_key(enrollment.master_key, b64d(meta["encDek"]))

    target_rendition = None
    if args.rotate:
        target_rendition = next(
            (r for r in detail["renditions"] if r["renditionId"] == meta.get("primaryRend")), None
        )
        if target_rendition is None:
            print("\nerror: this asset has no primary rendition to replace.", file=sys.stderr)
            return 1

    try:
        with Image.open(args.source_file) as im:
            im.load()
            source_format = im.format or "JPEG"
            corrected = ImageOps.exif_transpose(im)
            if args.rotate:
                # rotate()'s angle is counter-clockwise; --rotate is documented as
                # clockwise, hence the sign flip. expand=True so a 90/270 swaps the
                # canvas dimensions rather than cropping to the original box.
                corrected = corrected.rotate(-args.rotate, expand=True)
            new_thumbs = thumbnails.ladder_from_image(corrected)
    except Exception as e:
        print(f"error: couldn't read {args.source_file!r} as an image: {e}", file=sys.stderr)
        return 1
    if not new_thumbs:
        print(f"\nerror: {args.source_file!r} produced no thumbnails.", file=sys.stderr)
        return 1

    print(f"\n{photo_id}  (stem: {meta.get('stem')})")
    if target_rendition is not None:
        print(
            f"  original      {target_rendition['width']}x{target_rendition['height']}  ->  "
            f"{corrected.width}x{corrected.height}  (rotate {args.rotate}° clockwise)"
        )
    existing_thumbs = meta.get("thumbs") or {}
    for t in new_thumbs:
        old = existing_thumbs.get(str(t.size))
        old_dims = f"{old.get('width')}x{old.get('height')}" if isinstance(old, dict) and old.get("width") else "?"
        print(f"  {t.size:>4}px  {old_dims}  ->  {t.width}x{t.height}  ({len(t.bytes_):,} bytes)")

    if not args.execute:
        print("\nDry run only -- nothing changed. Re-run with --execute to apply.")
        return 0

    if target_rendition is not None:
        rendition_id = target_rendition["renditionId"]
        plain_bytes = _encode_image(corrected, source_format)
        content_hash = cf.content_hash(enrollment.hash_secret, plain_bytes)
        chunk_size = cf.choose_chunk_size(len(plain_bytes))
        enc = cf.encrypt_object(dek, cf.aad(photo_id, cf.object_ref_rendition(rendition_id)), plain_bytes, chunk_size)
        body = {
            "contentHash": content_hash,
            "plainBytes": len(plain_bytes),
            "bytes": len(enc.ciphertext),
            "mime": target_rendition["mime"],
            "width": corrected.width,
            "height": corrected.height,
            "encChunkSize": enc.chunk_size,
        }
        if enc.iv is not None:
            body["encIv"] = b64(enc.iv)
        resp = api.post_rendition_replace(photo_id, rendition_id, body)
        put_bytes(resp["uploadUrl"], target_rendition["mime"], enc.ciphertext)
        print(f"Replaced the original ({rendition_id}).")

    encrypted = {
        t.size: (t, cf.encrypt_object(dek, cf.aad(photo_id, cf.object_ref_thumbnail(t.size)), t.bytes_, 0))
        for t in new_thumbs
    }
    descriptors = {str(size): {"bytes": len(enc.ciphertext), "iv": b64(enc.iv)} for size, (_t, enc) in encrypted.items()}
    resp = api.post_photo_thumbs(photo_id, descriptors)
    uploads = resp.get("thumbUploads") or {}
    for size, (_t, enc) in encrypted.items():
        url = uploads.get(str(size))
        if not url:
            print(f"warning: no presigned URL came back for size {size}, skipping it", file=sys.stderr)
            continue
        put_bytes(url, "image/webp", enc.ciphertext)

    print("\nApplied.")
    return 0


# --- location ----------------------------------------------------------------
#
# docs/plans/geolocation.md "Manual updates" and step 10; design.md "Location" /
# "Manual update". Per asset: decrypt exifEnc; take its coordinates, else read the
# primary rendition's original (--source-dir first, then a download); geocode; PUT
# /photos/{photoId}/location, with a re-encrypted exifEnc when the coordinates had to
# come from the original. No coordinates anywhere -> place: null.

COORD_EXIF_BLOB = "exif-blob"
COORD_SOURCE_DIR = "source-dir"
COORD_SOURCE_BY_NAME = "source-dir by filename"
COORD_SOURCE_UNVERIFIED = "source-dir UNVERIFIED (name + capture time)"
COORD_DOWNLOAD = "download"
COORD_NONE = "none"

UPDATED = "updated"
UNCHANGED = "unchanged"
SKIPPED = "skipped"
NO_GPS = "no-gps"
# Needed its original, had no verified local copy, and --local-only forbade the
# download: left exactly as it was (no PUT, no no-GPS memory), not an error.
MISSING_LOCALLY = "missing-locally"
# The download was refused because the stored object sits in an S3 archive tier that
# can't be read without a restore. Left untouched, like MISSING_LOCALLY.
ARCHIVED = "archived"
ERROR = "error"
OUTCOME_KINDS = (UPDATED, UNCHANGED, SKIPPED, NO_GPS, MISSING_LOCALLY, ARCHIVED, ERROR)

# Where an original that had to be read came from (LocationOutcome.original_via).
VIA_EXACT = "exact-path"
VIA_BY_NAME = "by-filename"
VIA_UNVERIFIED = "by-filename-unverified"  # --allow-content-mismatch
VIA_DOWNLOAD = "download"
VIA_MISSING = "missing-locally"
VIA_ARCHIVED = "archived"

# Why no local copy was usable (LocalMatch.reason).
NO_FILE_BY_NAME = "no-file-by-name"
CONTENT_DIFFERS = "name-found-content-differs"
# --allow-content-mismatch: why no unverified candidate was used either.
UNVERIFIED_TIME_MISMATCH = "content-differs-time-mismatch"
UNVERIFIED_NO_CAPTURE_TIME = "content-differs-no-capture-time"
UNVERIFIED_AMBIGUOUS = "content-differs-ambiguous"
UNVERIFIED_NO_GPS = "content-differs-no-gps"  # the copy may have lost GPS the original kept
UNVERIFIED_UNREADABLE = "content-differs-unreadable"
LOCAL_REASONS = (
    NO_FILE_BY_NAME, CONTENT_DIFFERS, UNVERIFIED_TIME_MISMATCH, UNVERIFIED_NO_CAPTURE_TIME, UNVERIFIED_AMBIGUOUS,
    UNVERIFIED_NO_GPS, UNVERIFIED_UNREADABLE,
)

# An unverified copy's own capture time must be within this of the asset's takenAt.
# EXIF DateTimeOriginal and an MP4 mvhd creation time are whole seconds while takenAt
# carries milliseconds (SubSecTimeOriginal, or a client's ms clock), so the right file
# can be up to 1 s off; 2 s leaves one more second for a tool that rounds rather than
# truncates. A same-named different photo (another camera, a counter reset) shot within
# the same 2 s is not a realistic collision; a burst from one camera has distinct names.
CAPTURE_TIME_TOLERANCE_S = 2

# Containers whose metadata sits at the very start, so a chunked original needs only
# its first ciphertext segment. JPEG's APP1 (EXIF) segment is at most 64 KiB and comes
# before the image data. HEIC's EXIF item has no such size bound (its `iloc` offset
# can point anywhere), and MP4/MOV can keep `moov` at the end, so both download whole.
FIRST_SEGMENT_EXTS = {"jpg", "jpeg"}


def add_location_parser(sub: argparse._SubParsersAction) -> None:
    p = sub.add_parser(
        "location", help="re-geocode one photo or the whole library (PUT /photos/{photoId}/location)"
    )
    p.add_argument("--host", required=True)
    p.add_argument("--username", required=True)
    target = p.add_mutually_exclusive_group(required=True)
    target.add_argument("--photo-id", help="a ULID already in hand -- straight to GET /photos/{photoId}")
    target.add_argument(
        "--path", help="exact server path, e.g. -1739773001/IMG_1234.jpg -- resolved via GET /photos/by-path"
    )
    target.add_argument("--all", action="store_true", help="every live asset (GET /photos)")
    p.add_argument("--include-trash", action="store_true", help="with --all, trashed assets too (GET /trash)")
    p.add_argument("--execute", action="store_true", help="apply the correction (default: dry run)")
    p.add_argument(
        "--source-dir",
        help="local copies of the originals, in any layout. An original is looked for at "
        "<source-dir>/<rendition path> first, then as any file anywhere under it with the same "
        "basename (case-insensitive). A file is used only when its size and contentHash match the "
        "stored original, so a same-named different photo is never read",
    )
    p.add_argument(
        "--local-only",
        action="store_true",
        help="with --source-dir: never download an original. An asset that needs one and has no "
        "verified local copy is reported as 'missing locally' and left untouched",
    )
    p.add_argument(
        "--missing-report",
        metavar="PATH",
        help="with --source-dir: write every asset that needed its original and had no verified "
        "local copy to this CSV file, including those --allow-content-mismatch used an unverified "
        "copy for (written in dry runs too)",
    )
    p.add_argument(
        "--allow-content-mismatch",
        action="store_true",
        help="with --source-dir: when no same-named local file matches the stored original, use one "
        "whose size and contentHash differ (re-saved, metadata edited...). Skips both checks -- a "
        "different size always means a different hash, so they can't be separated -- and instead "
        "requires the file's own capture time (EXIF DateTimeOriginal, or the video's creation time) to "
        f"be within {CAPTURE_TIME_TOLERANCE_S} s of the asset's takenAt, with exactly one such file. "
        "A verified copy is always preferred. Every copy used this way is listed in --missing-report",
    )
    p.add_argument("--force", action="store_true", help="ignore the skip rule and the no-GPS memory")
    p.add_argument("--concurrency", type=int, default=library_scan.DEFAULT_CONCURRENCY)
    p.add_argument(
        "--ignore-strip-setting",
        action="store_true",
        help="run even though the owner has stripLocationOnUpload on (design.md 'Strip-on wins')",
    )
    p.add_argument(
        "--gazetteer",
        help="the reverse-geocoding gazetteer.bin (default: $ARCHIVIST_GAZETTEER, else the copy the "
        "Android app bundles, android/app/src/main/assets/gazetteer.bin)",
    )
    p.set_defaults(run=run_location)


@dataclass
class LocationOutcome:
    photo_id: str
    kind: str  # one of OUTCOME_KINDS
    old_place: dict | None = None
    new_place: dict | None = None
    coord_source: str = COORD_NONE
    exif_rewritten: bool = False
    from_no_gps_memory: bool = False
    bytes_downloaded: int = 0
    message: str = ""
    # What to do to the no-GPS memory once the outcome is final (main thread only):
    # an entry to remember, or forget this photoId (coordinates found after all).
    remember_no_gps: dict | None = None
    forget_no_gps: bool = False
    # Set only when the asset needed its primary original (no coordinates in exifEnc):
    # one of the VIA_* values, plus what --missing-report needs.
    original_via: str | None = None
    rendition_path: str = ""
    plain_bytes: int = 0
    taken_at: str = ""
    local_path: str | None = None
    local_reason: str | None = None  # one of LOCAL_REASONS when no verified local copy
    local_detail: str = ""
    local_candidates: list = field(default_factory=list)
    bytes_hashed: int = 0


def _name_key(name: str) -> str:
    """Basename as an index key: NFC (macOS hands back decomposed names, the server
    path is whatever the uploader sent) and casefolded (IMG_1234.JPG from the camera,
    img_1234.jpg after some copy tool; both names mean the same file to a person, and
    the contentHash check makes a false name match cost one hash, never a wrong read)."""
    return unicodedata.normalize("NFC", name).casefold()


@dataclass
class LocalMatch:
    path: str | None  # the verified local copy, or None
    via: str | None = None  # VIA_EXACT | VIA_BY_NAME when path is set
    reason: str | None = None  # NO_FILE_BY_NAME | CONTENT_DIFFERS when it isn't
    detail: str = ""
    candidates: list = field(default_factory=list)  # same-named files that didn't match
    bytes_hashed: int = 0


class LocalOriginals:
    """Every file under --source-dir, indexed once by `_name_key(basename)` with its
    size, plus an in-run memo of contentHashes so a file is hashed at most once per
    run. Only candidates whose size already matches are ever hashed.

    No persistent hash cache: each asset normally has exactly one same-named,
    same-sized candidate, so a run reads each needed local original once (far cheaper
    than the download it replaces), and once --execute has written an asset's
    coordinates into exifEnc it is skipped and never hashed again. The one repeat is
    dry run then --execute, and a dry run writes no cache, so a cache couldn't save
    that pass anyway."""

    def __init__(self, root: str, hash_secret: bytes):
        self.root = root
        self.hash_secret = hash_secret
        self.by_name: dict[str, list[tuple[str, int]]] = {}
        self.files_indexed = 0
        self.walk_errors = 0
        self._hashes: dict[str, str] = {}

    @classmethod
    def build(cls, root: str, hash_secret: bytes, progress=lambda msg: None) -> "LocalOriginals":
        index = cls(root, hash_secret)
        stack = [root]
        while stack:
            directory = stack.pop()
            try:
                entries = list(os.scandir(directory))
            except OSError:
                index.walk_errors += 1
                continue
            for entry in entries:
                try:
                    if entry.is_dir(follow_symlinks=False):
                        stack.append(entry.path)
                    elif entry.is_file():
                        size = entry.stat().st_size
                        index.by_name.setdefault(_name_key(entry.name), []).append((entry.path, size))
                        index.files_indexed += 1
                        if index.files_indexed % 20000 == 0:
                            progress(f"  indexed {index.files_indexed} local file(s) so far...")
                except OSError:
                    index.walk_errors += 1
        for candidates in index.by_name.values():
            candidates.sort()
        return index

    def _content_hash(self, path: str) -> tuple[str, int]:
        """(contentHash, bytes read now) -- 0 bytes when memoised."""
        cached = self._hashes.get(path)
        if cached is not None:
            return cached, 0
        value = cf.content_hash_of_file(self.hash_secret, path)
        self._hashes[path] = value
        return value, os.path.getsize(path)

    def find(self, rendition: dict) -> LocalMatch:
        rel = rendition["path"]
        size, want = rendition["plainBytes"], rendition["contentHash"]
        name = rel.rsplit("/", 1)[-1]
        hashed = 0
        wrong_size: list[str] = []
        wrong_hash: list[str] = []

        # 1. The exact relative path main.py uploaded it under.
        exact = os.path.join(self.root, *rel.split("/"))
        exact_exists = os.path.isfile(exact)
        if exact_exists:
            if os.path.getsize(exact) != size:
                wrong_size.append(exact)
            else:
                got, n = self._content_hash(exact)
                hashed += n
                if got == want:
                    return LocalMatch(exact, VIA_EXACT, bytes_hashed=hashed)
                wrong_hash.append(exact)

        # 2. Any file with the same name, anywhere under the root.
        for path, cand_size in self.by_name.get(_name_key(name), []):
            if exact_exists and _same_file(path, exact):
                continue
            if cand_size != size:
                wrong_size.append(path)
                continue
            got, n = self._content_hash(path)
            hashed += n
            if got == want:
                return LocalMatch(path, VIA_BY_NAME, bytes_hashed=hashed)
            wrong_hash.append(path)

        if not wrong_size and not wrong_hash:
            return LocalMatch(None, reason=NO_FILE_BY_NAME, detail=f"no local file named {name}", bytes_hashed=hashed)
        parts = []
        if wrong_size:
            parts.append(f"{len(wrong_size)} with a different size")
        if wrong_hash:
            parts.append(f"{len(wrong_hash)} same size but a different contentHash")
        return LocalMatch(
            None,
            reason=CONTENT_DIFFERS,
            detail=f"{len(wrong_size) + len(wrong_hash)} local file(s) named {name}, none matches: " + ", ".join(parts),
            candidates=wrong_hash + wrong_size,
            bytes_hashed=hashed,
        )

    def pick_unverified(self, candidates: list[str], ext: str, taken_at: str | None,
                        tz_offset_min: int | None) -> "UnverifiedPick":
        """--allow-content-mismatch: among same-named files whose content isn't the
        stored original's, the one whose own capture time is within
        CAPTURE_TIME_TOLERANCE_S of the asset's takenAt -- or none, with the reason.
        Several matching files are only accepted when they're byte-identical."""
        want = _parse_utc(taken_at)
        if want is None:
            return UnverifiedPick(None, UNVERIFIED_NO_CAPTURE_TIME, "the asset has no takenAt to compare with")
        matching: list[tuple[str, datetime]] = []
        mismatched: list[tuple[float, str, datetime]] = []
        timeless = 0
        for path in sorted(candidates):
            try:
                instants = capture_instants(path, ext, tz_offset_min)
            except Exception:  # noqa: BLE001 -- an unparseable copy just has no usable time
                instants = []
            if not instants:
                timeless += 1
                continue
            best = min(instants, key=lambda t: abs((t - want).total_seconds()))
            delta = abs((best - want).total_seconds())
            if delta <= CAPTURE_TIME_TOLERANCE_S:
                matching.append((path, best))
            else:
                mismatched.append((delta, path, best))

        if not matching:
            if mismatched:
                delta, path, got = min(mismatched)
                return UnverifiedPick(
                    None, UNVERIFIED_TIME_MISMATCH,
                    f"{len(mismatched)} candidate(s) with another capture time (closest: {path} at "
                    f"{_iso(got)}, {delta:.0f} s from takenAt {taken_at})"
                    + (f"; {timeless} without a readable capture time" if timeless else ""),
                )
            return UnverifiedPick(
                None, UNVERIFIED_NO_CAPTURE_TIME, f"none of {timeless} candidate(s) has a readable capture time"
            )
        hashed = 0
        if len(matching) > 1:
            hashes = set()
            for path, _ in matching:
                h, n = self._content_hash(path)
                hashed += n
                hashes.add(h)
            if len(hashes) > 1:
                return UnverifiedPick(
                    None, UNVERIFIED_AMBIGUOUS,
                    f"{len(matching)} different files match name and capture time: "
                    + ", ".join(p for p, _ in matching),
                    bytes_hashed=hashed,
                )
        path, got = matching[0]
        others = len(candidates) - 1
        return UnverifiedPick(
            path, None,
            f"used UNVERIFIED {path}: capture time {_iso(got)} within {CAPTURE_TIME_TOLERANCE_S} s of takenAt "
            f"{taken_at}" + (f" ({len(matching)} identical copies)" if len(matching) > 1 else "")
            + (f"; {others} other candidate(s) not used" if others > len(matching) - 1 else ""),
            bytes_hashed=hashed,
        )


@dataclass
class UnverifiedPick:
    path: str | None  # the accepted unverified copy, or None
    reason: str | None  # one of the UNVERIFIED_* values when path is None
    detail: str
    bytes_hashed: int = 0


def _parse_utc(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def capture_instants(path: str, ext: str, tz_offset_min: int | None) -> list[datetime]:
    """The UTC instants a local file's own capture time can mean ([] when it has none).
    Video: the mvhd creation time, UTC by definition. Still: EXIF DateTimeOriginal, a
    naive local time, read at the asset's tzOffsetMin (the offset its takenAt was
    derived with) and, if the file says, at its own OffsetTimeOriginal -- both, since
    an upload can have forced an offset that differs from the file's."""
    if media_probe.is_video(ext):
        t = media_probe.probe_video(path).creation_time_utc
        return [t] if t is not None else []
    exif = media_probe.extract_exif(path)
    naive = takenat_ladder.parse_exif_naive_local(exif.date_time_original)
    if naive is None:
        return []
    offsets = [tz_offset_min, takenat_ladder.parse_exif_offset_minutes(exif.offset_time_original)]
    return [
        (naive - timedelta(minutes=off)).replace(tzinfo=timezone.utc)
        for off in dict.fromkeys(o for o in offsets if isinstance(o, int))
    ]


def _same_file(a: str, b: str) -> bool:
    try:
        return os.path.samefile(a, b)
    except OSError:
        return False


class ArchivedOriginal(Exception):
    """The stored object is in an S3 archive tier a plain GET can't read."""


def _is_archive_refusal(e: ApiError) -> bool:
    # S3 answers a GET on an object in Glacier Flexible Retrieval / Deep Archive, or in
    # Intelligent-Tiering's opt-in Archive Access / Deep Archive Access tiers, with 403
    # InvalidObjectState until it's restored; CloudFront's /media/* passes that through.
    return e.status == 403 and "InvalidObjectState" in (e.body or "")


@dataclass
class LocationContext:
    api: object
    host: str
    master_key: bytes
    hash_secret: bytes
    gazetteer: object
    execute: bool
    force: bool = False
    source_dir: str | None = None
    local: LocalOriginals | None = None  # the --source-dir index; built by location_main
    local_only: bool = False
    allow_content_mismatch: bool = False
    no_gps: dict = field(default_factory=dict)  # photoId -> {renditionId, contentHash}
    # (s3_key, dest_path, byte_range) -> bytes written; swapped for a fake in tests.
    download: object = None


def describe_place(place: dict | None) -> str:
    if not place:
        return "(none)"
    parts = [p["name"] for p in (place.get("locality"), place.get("region")) if p]
    return ", ".join(parts + [place["cc"]]) + f"  [{place.get('src')}]"


def _current_src(place: dict | None, gazetteer) -> bool:
    return bool(place) and place.get("src") == gazetteer.src


def _no_gps_remembered(ctx: LocationContext, photo_id: str, place: dict | None, primary: dict | None) -> bool:
    """The memory only counts while the asset still has no place and, once its
    detail is known, the same primary rendition that was read."""
    entry = ctx.no_gps.get(photo_id)
    if ctx.force or entry is None or place:
        return False
    if primary is None:
        return True
    return entry.get("renditionId") == primary["renditionId"] and entry.get("contentHash") == primary["contentHash"]


def decrypt_exif_blob(dek: bytes, photo_id: str, meta: dict) -> dict:
    """The asset's ExifBlob as a dict ({} when it has no exifEnc)."""
    if not meta.get("exifEnc"):
        return {}
    plain = cf.whole_object_decrypt(
        dek, b64d(meta["exifIv"]), cf.aad(photo_id, cf.object_ref_exif()), b64d(meta["exifEnc"])
    )
    return json.loads(plain.decode("utf-8"))


def encrypt_exif_blob_with_coords(dek: bytes, photo_id: str, blob: dict, coords) -> tuple[str, str]:
    """The blob with gpsLat/gpsLon/gpsAltM set from `coords` and every other field kept,
    re-encrypted under the same DEK and the same AAD (`...:x`) as the original exifEnc,
    with a fresh IV. Same JSON encoding as importer.py's own blob. Returns (exifEnc,
    exifIv), base64."""
    out = dict(blob)
    out["gpsLat"], out["gpsLon"] = coords[0], coords[1]
    if coords[2] is not None:
        out["gpsAltM"] = coords[2]
    else:
        out.pop("gpsAltM", None)
    plaintext = json.dumps(out, separators=(",", ":")).encode("utf-8")
    enc = cf.encrypt_object(dek, cf.aad(photo_id, cf.object_ref_exif()), plaintext, 0)
    return b64(enc.ciphertext), b64(enc.iv)


def _coords_from_blob(blob: dict):
    lat, lon = blob.get("gpsLat"), blob.get("gpsLon")
    if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
        return None
    if not mp4box.plausible_coordinates(float(lat), float(lon)):
        return None
    alt = blob.get("gpsAltM")
    return float(lat), float(lon), float(alt) if isinstance(alt, (int, float)) else None


def _download_original(ctx: LocationContext, photo_id: str, dek: bytes, rendition: dict, ext: str, workdir: str):
    """Downloads and decrypts the primary original into `workdir`; returns (path, bytes
    downloaded). A chunked JPEG fetches only its first ciphertext segment. Raises
    ArchivedOriginal when S3 refuses the GET because of the object's archive tier."""
    aad = cf.aad(photo_id, cf.object_ref_rendition(rendition["renditionId"]))
    ct_path = os.path.join(workdir, "original.enc")
    plain_path = os.path.join(workdir, f"original.{ext}")
    chunk = rendition.get("encChunkSize") or 0
    first_segment_only = bool(chunk) and ext in FIRST_SEGMENT_EXTS and rendition["bytes"] > cf.SEGMENT
    try:
        n = ctx.download(rendition["s3Key"], ct_path, (0, cf.SEGMENT - 1) if first_segment_only else None)
    except ApiError as e:
        if _is_archive_refusal(e):
            raise ArchivedOriginal(f"HTTP {e.status} InvalidObjectState") from None
        raise
    if first_segment_only:
        with open(ct_path, "rb") as f:
            prefix = f.read()
        with open(plain_path, "wb") as f:
            f.write(cf.decrypt_first_segment(dek, aad, prefix))
        return plain_path, n
    if chunk:
        cf.streaming_decrypt_file(dek, aad, ct_path, plain_path)
    else:
        with open(ct_path, "rb") as f:
            ciphertext = f.read()
        with open(plain_path, "wb") as f:
            f.write(cf.whole_object_decrypt(dek, b64d(rendition["encIv"]), aad, ciphertext))
    return plain_path, n


def _try_unverified(ctx: LocationContext, out: LocationOutcome, match: LocalMatch, ext: str, meta: dict):
    """--allow-content-mismatch, once no verified copy exists. Returns (used, coords).
    Used only when the copy passes the capture-time check *and* yields coordinates:
    a re-saved copy can have lost the GPS its original kept, so "unverified copy, no
    GPS" (or unreadable) is no evidence of anything -- it falls back like a missing
    copy (download, or left untouched under --local-only), never place: null and never
    the no-GPS memory."""
    pick = ctx.local.pick_unverified(match.candidates, ext, meta.get("takenAt"), meta.get("tzOffsetMin"))
    out.bytes_hashed += pick.bytes_hashed
    out.local_detail = f"{match.detail}; {pick.detail}"
    if pick.path is None:
        out.local_reason = pick.reason
        return False, None
    try:
        coords = media_probe.read_coordinates(pick.path, ext)
    except Exception as e:  # noqa: BLE001
        out.local_reason = UNVERIFIED_UNREADABLE
        out.local_detail += f"; but it can't be read ({type(e).__name__}: {e})"
        return False, None
    if coords is None:
        out.local_reason = UNVERIFIED_NO_GPS
        out.local_detail += "; but it has no GPS, which proves nothing about the original"
        return False, None
    out.local_reason = CONTENT_DIFFERS
    out.original_via, out.local_path = VIA_UNVERIFIED, pick.path
    out.coord_source = COORD_SOURCE_UNVERIFIED
    return True, coords


def locate_one(ctx: LocationContext, photo_id: str, listing: dict | None = None) -> LocationOutcome:
    """One asset, start to finish; never raises. A 409 (the place or trash state moved
    between our read and our write) is retried once from a fresh read."""
    try:
        try:
            return _locate_one(ctx, photo_id, listing)
        except ApiError as e:
            if e.status != 409:
                raise
            return _locate_one(ctx, photo_id, None)
    except Exception as e:  # noqa: BLE001 -- one bad asset must not abort --all
        return LocationOutcome(photo_id, ERROR, message=f"{type(e).__name__}: {e}")


def _locate_one(ctx: LocationContext, photo_id: str, listing: dict | None) -> LocationOutcome:
    gaz = ctx.gazetteer
    # Cheap checks off the listing first, so a re-run of --all costs no detail fetch
    # for anything already done.
    if listing is not None and not ctx.force:
        listed_place = listing.get("place")
        if _current_src(listed_place, gaz):
            return LocationOutcome(photo_id, SKIPPED, old_place=listed_place, new_place=listed_place)
        if _no_gps_remembered(ctx, photo_id, listed_place, None):
            return LocationOutcome(photo_id, NO_GPS, from_no_gps_memory=True)

    detail = ctx.api.get_photo_detail(photo_id)
    if detail is None:
        return LocationOutcome(photo_id, ERROR, message="no such photo (purged?)")
    meta = detail["meta"]
    old_place = meta.get("place")
    if not ctx.force and _current_src(old_place, gaz):
        return LocationOutcome(photo_id, SKIPPED, old_place=old_place, new_place=old_place)
    primary = next((r for r in detail.get("renditions", []) if r["renditionId"] == meta.get("primaryRend")), None)
    if _no_gps_remembered(ctx, photo_id, old_place, primary):
        return LocationOutcome(photo_id, NO_GPS, old_place=old_place, from_no_gps_memory=True)

    dek = cf.unwrap_key(ctx.master_key, b64d(meta["encDek"]))
    blob = decrypt_exif_blob(dek, photo_id, meta)
    out = LocationOutcome(photo_id, UNCHANGED, old_place=old_place)

    coords = _coords_from_blob(blob)
    if coords is not None:
        out.coord_source = COORD_EXIF_BLOB
    else:
        if primary is None:
            return LocationOutcome(photo_id, ERROR, old_place=old_place, message="no primary rendition")
        ext = (primary.get("ext") or primary["path"].rpartition(".")[2]).lower()
        if not media_probe.coordinates_readable(ext):
            # RAW: not "no GPS" -- clearing a place we simply can't check would be wrong.
            return LocationOutcome(
                photo_id, ERROR, old_place=old_place,
                message=f"can't read coordinates from a .{ext} original (RAW: no decoder here)",
            )
        out.rendition_path = primary["path"]
        out.plain_bytes = primary.get("plainBytes") or 0
        out.taken_at = meta.get("takenAt") or ""
        match = ctx.local.find(primary) if ctx.local is not None else None
        if match is not None:
            out.bytes_hashed = match.bytes_hashed
            out.local_reason, out.local_detail, out.local_candidates = match.reason, match.detail, match.candidates
        unverified = False
        if match is not None and match.path is None and ctx.allow_content_mismatch and match.candidates:
            unverified, coords = _try_unverified(ctx, out, match, ext, meta)
        if match is not None and match.path is not None:
            out.original_via, out.local_path = match.via, match.path
            out.coord_source = COORD_SOURCE_DIR if match.via == VIA_EXACT else COORD_SOURCE_BY_NAME
            coords = media_probe.read_coordinates(match.path, ext)
        elif unverified:
            pass  # coordinates read from the accepted unverified copy
        elif ctx.local_only:
            # Nothing is known about this asset's coordinates: leave it exactly as it is
            # (no PUT, no no-GPS memory), so a later run with more local copies, or
            # without --local-only, still does it.
            out.kind = MISSING_LOCALLY
            out.original_via = VIA_MISSING
            out.coord_source = COORD_NONE
            out.message = out.local_detail if match is not None else "no --source-dir"
            return out
        else:
            out.coord_source = COORD_DOWNLOAD
            with tempfile.TemporaryDirectory(prefix="archivist-location-") as workdir:
                try:
                    path, out.bytes_downloaded = _download_original(ctx, photo_id, dek, primary, ext, workdir)
                except ArchivedOriginal as e:
                    out.kind = ARCHIVED
                    out.original_via = VIA_ARCHIVED
                    out.message = f"original is in an S3 archive tier and needs a restore first ({e})"
                    return out
                out.original_via = VIA_DOWNLOAD
                coords = media_probe.read_coordinates(path, ext)
        if coords is None:
            out.coord_source = f"{COORD_NONE} (read via {out.coord_source})"

    if coords is None:
        out.kind = NO_GPS
        out.new_place = None
        if old_place is not None and ctx.execute:
            ctx.api.put_photo_location(photo_id, None)
        out.message = "clears the stored place" if old_place is not None else ""
        if primary is not None:
            out.remember_no_gps = {"renditionId": primary["renditionId"], "contentHash": primary["contentHash"]}
        return out

    out.forget_no_gps = True
    out.new_place = gaz.lookup(coords[0], coords[1])
    exif_enc = exif_iv = None
    if out.coord_source != COORD_EXIF_BLOB:
        exif_enc, exif_iv = encrypt_exif_blob_with_coords(dek, photo_id, blob, coords)
        out.exif_rewritten = True
    if out.new_place == old_place and not out.exif_rewritten:
        out.kind = UNCHANGED
        return out
    out.kind = UPDATED
    if ctx.execute:
        resp = ctx.api.put_photo_location(photo_id, out.new_place, exif_enc, exif_iv)
        if resp is None:
            raise RuntimeError("PUT /photos/{photoId}/location: photo not found")
    return out


@dataclass
class LocationSummary:
    counts: dict = field(default_factory=lambda: {k: 0 for k in OUTCOME_KINDS})
    no_gps_from_memory: int = 0
    exif_rewritten: int = 0
    bytes_downloaded: int = 0
    bytes_hashed: int = 0
    # Assets that needed their primary original, by VIA_*, and the missing ones by reason.
    via: dict = field(default_factory=lambda: {
        k: 0 for k in (VIA_EXACT, VIA_BY_NAME, VIA_UNVERIFIED, VIA_DOWNLOAD, VIA_MISSING, VIA_ARCHIVED)
    })
    # Every asset with no verified local copy, by reason (used unverified copies count
    # under CONTENT_DIFFERS).
    no_local_copy: dict = field(default_factory=lambda: {k: 0 for k in LOCAL_REASONS})
    errors: list = field(default_factory=list)  # (photoId, message)
    archived: list = field(default_factory=list)  # LocationOutcome
    without_local_copy: list = field(default_factory=list)  # LocationOutcome, for --missing-report

    def add(self, o: LocationOutcome) -> None:
        self.counts[o.kind] += 1
        self.no_gps_from_memory += o.from_no_gps_memory
        self.exif_rewritten += o.exif_rewritten
        self.bytes_downloaded += o.bytes_downloaded
        self.bytes_hashed += o.bytes_hashed
        if o.original_via is not None:
            self.via[o.original_via] += 1
            if o.local_reason is not None and (o.local_path is None or o.original_via == VIA_UNVERIFIED):
                self.no_local_copy[o.local_reason] += 1
                self.without_local_copy.append(o)
        if o.kind == ERROR:
            self.errors.append((o.photo_id, o.message))
        if o.kind == ARCHIVED:
            self.archived.append(o)

    @property
    def originals_needed(self) -> int:
        return sum(self.via.values())

    @property
    def local_found(self) -> int:
        return self.via[VIA_EXACT] + self.via[VIA_BY_NAME] + self.via[VIA_UNVERIFIED]


def _filename(o: LocationOutcome) -> str:
    return o.rendition_path.rsplit("/", 1)[-1]


def _print_outcome(o: LocationOutcome) -> None:
    if o.kind == SKIPPED:
        print(f"{o.photo_id}  skipped: place already from {o.old_place.get('src')}")
        return
    if o.from_no_gps_memory:
        print(f"{o.photo_id}  no GPS (remembered from an earlier run; --force to re-read)")
        return
    if o.kind == ERROR:
        print(f"{o.photo_id}  ERROR: {o.message}")
        return
    if o.kind == MISSING_LOCALLY:
        print(f"{o.photo_id}  missing locally, left untouched: {o.rendition_path}  ({o.message})")
        return
    if o.kind == ARCHIVED:
        print(f"{o.photo_id}  ARCHIVED, left untouched: {o.rendition_path}  ({o.message})")
        return
    extra = "  +exifEnc with coordinates" if o.exif_rewritten else ""
    note = f"  ({o.message})" if o.message else ""
    local = f"  <- {o.local_path}" if o.original_via in (VIA_BY_NAME, VIA_UNVERIFIED) else ""
    print(
        f"{o.photo_id}  {describe_place(o.old_place)}  ->  {describe_place(o.new_place)}"
        f"  [coords: {o.coord_source}]  {o.kind}{extra}{note}{local}"
    )


MISSING_REPORT_COLUMNS = (
    "photoId", "path", "filename", "plainBytes", "takenAt", "reason", "outcome", "detail", "candidates",
    "usedLocalCopy",
)


def write_missing_report(path: str, outcomes: list[LocationOutcome]) -> None:
    """CSV of every asset that needed its original and had no verified local copy.
    `outcome` says what the run did instead: left untouched (--local-only), or
    downloaded, or refused as archived, or (--allow-content-mismatch) used an
    unverified local copy, named in `usedLocalCopy` so it can be audited."""
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(MISSING_REPORT_COLUMNS)
        for o in sorted(outcomes, key=lambda o: o.rendition_path):
            outcome = {
                VIA_MISSING: "left untouched", VIA_ARCHIVED: "download refused: archived",
                VIA_UNVERIFIED: "used unverified local copy",
            }.get(o.original_via, "downloaded")
            w.writerow([
                o.photo_id, o.rendition_path, _filename(o), o.plain_bytes, o.taken_at, o.local_reason, outcome,
                o.local_detail, " | ".join(o.local_candidates),
                o.local_path if o.original_via == VIA_UNVERIFIED else "",
            ])


def run_location(args) -> int:
    def progress(msg: str) -> None:
        print(msg, file=sys.stderr)

    try:
        api = cli_auth.authenticate(args.host, args.username, progress).api
    except cli_auth.AuthFailed as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return location_main(api, args, progress=progress)


MISSING_LIST_INLINE_LIMIT = 20


def location_main(api, args, *, progress=lambda msg: print(msg, file=sys.stderr), enroll_fn=None, gazetteer=None,
                  download=None, read_recovery=None) -> int:
    """Everything after sign-in, with the collaborators injectable for tests."""
    if args.include_trash and not args.all:
        print("error: --include-trash only applies with --all", file=sys.stderr)
        return 2
    if (args.local_only or args.missing_report or args.allow_content_mismatch) and not args.source_dir:
        print("error: --local-only, --missing-report and --allow-content-mismatch need --source-dir",
              file=sys.stderr)
        return 2
    if args.source_dir and not os.path.isdir(args.source_dir):
        if args.local_only:
            print(f"error: --source-dir {args.source_dir!r} is not a directory", file=sys.stderr)
            return 2
        print(f"warning: --source-dir {args.source_dir!r} is not a directory; every original will be "
              "downloaded", file=sys.stderr)

    # Before asking for the recovery code: a library whose owner asked for no
    # location is exactly what this must not touch by accident.
    settings = api.get_settings() or {}
    if settings.get("stripLocationOnUpload"):
        if not args.ignore_strip_setting:
            print(
                "error: this library has stripLocationOnUpload on, so it is meant to hold no location "
                "(design.md 'Strip-on wins'). Refusing; pass --ignore-strip-setting to geocode anyway.",
                file=sys.stderr,
            )
            return 1
        print("warning: stripLocationOnUpload is on; continuing because of --ignore-strip-setting", file=sys.stderr)

    try:
        gaz = gazetteer if gazetteer is not None else gz.load(args.gazetteer)
    except (FileNotFoundError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    print("Enrolling this run as a device via the account's recovery code...", file=sys.stderr)
    read_recovery = read_recovery or (lambda: cli_auth.read_secret(
        "ARCHIVIST_RECOVERY_CODE", "Recovery code (XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX): "
    ))
    try:
        enrollment = (enroll_fn or enroll)(api, read_recovery())
    except EnrollmentError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"error unwrapping the master key -- likely a mistyped recovery code: {e}", file=sys.stderr)
        return 1

    local = None
    if args.source_dir:
        progress(f"Indexing local files under {args.source_dir}...")
        local = LocalOriginals.build(args.source_dir, enrollment.hash_secret, progress)
        progress(f"  {local.files_indexed} local file(s), {len(local.by_name)} distinct name(s)"
                 + (f", {local.walk_errors} unreadable entr(ies) skipped" if local.walk_errors else ""))

    ctx = LocationContext(
        api=api,
        host=args.host,
        master_key=enrollment.master_key,
        hash_secret=enrollment.hash_secret,
        gazetteer=gaz,
        execute=args.execute,
        force=args.force,
        source_dir=args.source_dir,
        local=local,
        local_only=args.local_only,
        allow_content_mismatch=args.allow_content_mismatch,
        no_gps=library_cache.load_no_gps(args.host, args.username),
        download=download or (lambda key, dest, rng: download_media(args.host, key, dest, rng)),
    )

    targets: list[tuple[str, dict | None]]
    if args.all:
        items = library_scan.list_live_items(api, progress)
        if args.include_trash:
            items += library_scan.list_trashed_items(api, progress)
        targets = [(item["photoId"], item) for item in items]
    else:
        photo_id = resolve_target(api, args)
        if photo_id is None:
            return 1
        targets = [(photo_id, None)]

    originals = "local copies only, never downloaded" if args.local_only else (
        "local copies first, else downloaded" if args.source_dir else "downloaded"
    )
    if args.allow_content_mismatch:
        originals += (f"; unverified same-named copies accepted on capture time (+/-{CAPTURE_TIME_TOLERANCE_S} s)"
                      " when no verified one exists")
    print(f"\nGazetteer {gaz.src}; {len(targets)} asset(s); {'EXECUTE' if args.execute else 'dry run'}; "
          f"originals: {originals}.\n")
    if not args.execute and not args.local_only:
        print("note: a dry run still downloads every original it needs and has no local copy of, so it can "
              "report what --execute would do. Add --source-dir ... --local-only to download nothing.\n")
    started = time.monotonic()
    summary = LocationSummary()
    no_gps_changed = False
    with ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = [pool.submit(locate_one, ctx, pid, listing) for pid, listing in targets]
        for future in as_completed(futures):
            o = future.result()
            summary.add(o)
            _print_outcome(o)
            # Only remembered once the clearing PUT (if any) has been made: a dry run
            # remembering would make the --execute run skip the very asset it previewed.
            if args.execute and o.remember_no_gps is not None:
                ctx.no_gps[o.photo_id] = o.remember_no_gps
                no_gps_changed = True
            elif o.forget_no_gps and o.photo_id in ctx.no_gps:
                ctx.no_gps.pop(o.photo_id)
                no_gps_changed = True
    if no_gps_changed and args.execute:
        library_cache.save_no_gps(args.host, args.username, ctx.no_gps)
    if args.missing_report:
        # Written in a dry run too: it's the output the dry run exists to produce, not state.
        write_missing_report(args.missing_report, summary.without_local_copy)

    c = summary.counts
    print(f"\nSummary ({time.monotonic() - started:.1f} s, {summary.bytes_downloaded:,} bytes downloaded, "
          f"{summary.bytes_hashed:,} bytes read locally):")
    print(f"  updated                {c[UPDATED]}  ({summary.exif_rewritten} with coordinates added to exifEnc)")
    print(f"  unchanged              {c[UNCHANGED]}")
    print(f"  skipped (current src)  {c[SKIPPED]}")
    print(f"  no GPS                 {c[NO_GPS]}  ({summary.no_gps_from_memory} remembered, not re-read)")
    if args.local_only:
        print(f"  missing locally        {c[MISSING_LOCALLY]}  (left untouched)")
    if c[ARCHIVED]:
        print(f"  archived               {c[ARCHIVED]}  (S3 archive tier, needs a restore; left untouched)")
    print(f"  errors                 {c[ERROR]}")
    for photo_id, message in sorted(summary.errors):
        print(f"    {photo_id}  {message}")

    v = summary.via
    print(f"\nOriginals needed (no coordinates in exifEnc): {summary.originals_needed}")
    if args.source_dir:
        print(f"  local copy, exact path    {v[VIA_EXACT]}")
        print(f"  local copy, by filename   {v[VIA_BY_NAME]}")
        if args.allow_content_mismatch:
            print(f"  local copy, UNVERIFIED    {v[VIA_UNVERIFIED]}  (name + capture time; size/contentHash differ"
                  " -- listed in --missing-report)")
    print(f"  downloaded                {v[VIA_DOWNLOAD]}  ({summary.bytes_downloaded:,} bytes)")
    if args.source_dir:
        n = summary.no_local_copy
        differs = sum(n.values()) - n[NO_FILE_BY_NAME]
        print(f"  no verified local copy    {sum(n.values())}  "
              f"(no file by that name: {n[NO_FILE_BY_NAME]}; name found but size/contentHash differs: "
              f"{differs})" + ("  -- left untouched" if args.local_only else ""))
        if args.allow_content_mismatch:
            print(f"    of the {differs}: used unverified {v[VIA_UNVERIFIED]}; not used: capture time differs "
                  f"{n[UNVERIFIED_TIME_MISMATCH]}, no capture time {n[UNVERIFIED_NO_CAPTURE_TIME]}, ambiguous "
                  f"{n[UNVERIFIED_AMBIGUOUS]}, copy has no GPS {n[UNVERIFIED_NO_GPS]}, copy unreadable "
                  f"{n[UNVERIFIED_UNREADABLE]}")
    if v[VIA_ARCHIVED]:
        print(f"  archived, not readable    {v[VIA_ARCHIVED]}")
    print(f"  bytes read locally        {summary.bytes_hashed:,}  (hashed to verify candidates)")
    if args.source_dir:
        needed = summary.originals_needed
        pct = f"{100 * summary.local_found / needed:.1f}%" if needed else "n/a"
        print(f"  local copies found for {summary.local_found} of {needed} originals needed ({pct})"
              + (f", {v[VIA_UNVERIFIED]} of them unverified" if args.allow_content_mismatch else ""))

    missing = [o for o in summary.without_local_copy if o.original_via == VIA_MISSING]
    if missing:
        print(f"\nMissing locally ({len(missing)}; photoId, server path, reason):")
        for o in sorted(missing, key=lambda o: o.rendition_path)[:MISSING_LIST_INLINE_LIMIT]:
            print(f"    {o.photo_id}  {o.rendition_path}  {o.local_reason}: {o.local_detail}")
        if len(missing) > MISSING_LIST_INLINE_LIMIT:
            rest = len(missing) - MISSING_LIST_INLINE_LIMIT
            where = f"see {args.missing_report}" if args.missing_report else "pass --missing-report PATH for all"
            print(f"    ... and {rest} more ({where})")
    if summary.archived:
        print(f"\nArchived originals ({len(summary.archived)}; a GET is refused until restored -- not done here):")
        for o in sorted(summary.archived, key=lambda o: o.rendition_path):
            print(f"    {o.photo_id}  {o.rendition_path}")
    if args.missing_report:
        print(f"\nWrote {len(summary.without_local_copy)} row(s) to {args.missing_report}.")
    if not args.execute:
        print("\nDry run only -- nothing changed. Re-run with --execute to apply.")
    return 1 if summary.errors else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    add_taken_at_parser(sub)
    add_orientation_parser(sub)
    add_location_parser(sub)
    args = parser.parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
