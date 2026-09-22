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
                the API, not the datastore").

Both target an exact asset via --photo-id or --path, never a search -- see
inspect_photo.py for the same two flags and why a mutation tool doesn't offer
--filename's fuzzy search (you don't want to accidentally correct the wrong
photo because two shared a basename).

`orientation` fixes stored *thumbnails* only, from whatever orientation the
local source file's own EXIF already says -- it doesn't let you override that
value or touch the stored original. If a photo is still wrong even in "view
original" (the source file's own EXIF Orientation tag is itself incorrect),
this tool can't fix that yet; see STATUS.md.

Usage:
    .venv/bin/python3 modify_media.py taken-at --host photos.example.com \\
        --username someone@example.com --photo-id 01K5A2QB3HN7WYP2GKD4RVXM8C \\
        --taken-at-local "2019-06-15 14:00:00" --tz-offset-min 120 --execute

    .venv/bin/python3 modify_media.py orientation --host photos.example.com \\
        --username someone@example.com --path -1739773001/IMG_1234.jpg \\
        --source-file /path/to/local/backup/IMG_1234.jpg --execute
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timedelta, timezone

import cli_auth
import crypto_format as cf
import thumbnails
from api_client import put_bytes
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
    p.set_defaults(run=run_orientation)


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

    try:
        new_thumbs = thumbnails.generate_for_image(args.source_file)
    except Exception as e:
        print(f"error: couldn't read {args.source_file!r} as an image: {e}", file=sys.stderr)
        return 1
    if not new_thumbs:
        print(f"\nerror: {args.source_file!r} produced no thumbnails.", file=sys.stderr)
        return 1

    existing_thumbs = meta.get("thumbs") or {}
    print(f"\n{photo_id}  (stem: {meta.get('stem')})")
    for t in new_thumbs:
        old = existing_thumbs.get(str(t.size))
        old_dims = f"{old.get('width')}x{old.get('height')}" if isinstance(old, dict) and old.get("width") else "?"
        print(f"  {t.size:>4}px  {old_dims}  ->  {t.width}x{t.height}  ({len(t.bytes_):,} bytes)")

    if not args.execute:
        print("\nDry run only -- nothing changed. Re-run with --execute to apply.")
        return 0

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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    add_taken_at_parser(sub)
    add_orientation_parser(sub)
    args = parser.parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
