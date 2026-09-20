#!/usr/bin/env python3
"""Import a local directory of photos/videos into a real archivist instance --
docs/design/design.md's "Third parties use the API, not the datastore": this is
the reference importer that section describes, a script that authenticates,
encrypts locally, and PUTs to presigned URLs the same way the Android app does.

Usage (from tools/import-from-local/, with its own venv active):

    .venv/bin/python3 main.py \\
        --host photos.example.com \\
        --username someone@example.com \\
        --source /path/to/your/photos \\
        [--dry-run] [--limit 20]

Credentials are never accepted as bare CLI flags (they'd sit in shell history
and `ps` output) -- pass them via the environment or let the tool prompt:

    ARCHIVIST_PASSWORD / ARCHIVIST_RECOVERY_CODE / ARCHIVIST_NEW_PASSWORD

See README.md for the full account of what this does and doesn't handle.
"""

from __future__ import annotations

import argparse
import os
import sys

import cli_auth
import takenat_ladder as ladder
from importer import EnrollmentError, Importer, enroll


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", required=True, help="the instance's domain, e.g. photos.example.com")
    parser.add_argument("--username", required=True, help="Cognito username (usually an email address)")
    parser.add_argument("--source", required=True, help="local directory to import, walked recursively")
    parser.add_argument("--dry-run", action="store_true", help="resolve and log everything, upload nothing")
    parser.add_argument("--limit", type=int, default=None, help="only process the first N files (testing)")
    parser.add_argument(
        "--restore-trashed",
        action="store_true",
        help="if a file matches a previously-trashed photo by content, restore it "
        "instead of leaving it trashed (off by default: an intentional deletion "
        "should stay deleted unless you explicitly ask for this)",
    )
    parser.add_argument(
        "--tz-offset-min",
        type=int,
        default=None,
        help="a batch-wide timezone offset hint in minutes, e.g. -300 for US Eastern",
    )
    parser.add_argument(
        "--force-tz",
        action="store_true",
        help="with --tz-offset-min: override EXIF/GPS evidence instead of only filling "
        "gaps ('my camera's clock was set to the wrong zone for this whole trip', "
        "design.md's offsetMode=force -- default is offsetMode=fallback, which never "
        "clobbers a real per-file reading)",
    )
    args = parser.parse_args()

    if not os.path.isdir(args.source):
        print(f"error: --source {args.source!r} is not a directory", file=sys.stderr)
        return 2

    try:
        session = cli_auth.authenticate(args.host, args.username)
    except cli_auth.AuthFailed as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    api = session.api

    print("Enrolling this run as a device via the account's recovery code...", file=sys.stderr)
    recovery = cli_auth.read_secret(
        "ARCHIVIST_RECOVERY_CODE", "Recovery code (XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX): "
    )
    try:
        enrollment = enroll(api, recovery)
    except EnrollmentError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"error unwrapping the master key -- likely a mistyped recovery code: {e}", file=sys.stderr)
        return 1

    print(
        f"  master key version {enrollment.master_key_ver}, homeTz={enrollment.home_tz!r}, "
        f"stripLocationOnUpload={enrollment.strip_location}",
        file=sys.stderr,
    )

    upload_offset = None
    if args.tz_offset_min is not None:
        upload_offset = ladder.UploadOffsetHint(tz_offset_min=args.tz_offset_min, force=args.force_tz)

    importer = Importer(
        enrollment,
        args.source,
        dry_run=args.dry_run,
        restore_trashed=args.restore_trashed,
        upload_offset=upload_offset,
    )
    stats = importer.run(limit=args.limit)

    print("\n--- Summary ---", file=sys.stderr)
    print(f"created:              {stats.created}", file=sys.stderr)
    print(f"attached (grouped):   {stats.attached}", file=sys.stderr)
    print(f"already uploaded:     {stats.duplicate}", file=sys.stderr)
    print(f"resumed:              {stats.resumed}", file=sys.stderr)
    print(f"restored from trash:  {stats.restored}", file=sys.stderr)
    print(f"blocked (trashed):    {stats.trashed_blocked}", file=sys.stderr)
    print(f"skipped (purged):     {stats.tombstone_skipped}", file=sys.stderr)
    print(f"errors:               {len(stats.errors)}", file=sys.stderr)
    print(file=sys.stderr)
    print(f"no real timestamp evidence (used 'now'): {stats.no_timestamp_evidence}", file=sys.stderr)
    print(f"offset resolved via GPS delta:            {stats.gps_rung_used}", file=sys.stderr)
    print(f"offset resolved via homeTz fallback:      {stats.owner_default_rung_used}", file=sys.stderr)
    print(f"location metadata stripped:               {stats.location_stripped}", file=sys.stderr)
    print(f"videos uploaded with no thumbnail:        {stats.video_without_thumbnail}", file=sys.stderr)
    if stats.raw_uploaded_without_location_strip:
        print(
            f"WARNING: {stats.raw_uploaded_without_location_strip} RAW file(s) uploaded "
            "unstripped despite stripLocationOnUpload being on -- this tool can't read or "
            "strip a RAW file's embedded GPS tags (see README.md)",
            file=sys.stderr,
        )

    if stats.errors:
        print("\nFiles that failed:", file=sys.stderr)
        for path, err in stats.errors:
            print(f"  {path}: {err}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
