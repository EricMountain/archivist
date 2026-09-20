#!/usr/bin/env python3
"""Finds and trashes duplicate assets that share a filename and an identical
`takenAt` -- the case a fresh `import-from-local` run can create when a file
was already in the library under different bytes (a different export's
compression/resolution, say) that don't hash-match the copy already there, so
`POST /uploads`'s own content-hash dedup never fires and it lands as a second,
independent asset instead of attaching to the first.

Read-only against the crypto: this only ever looks at what design.md's
"Encrypted EXIF" section calls "what stays in the clear" -- `takenAt`,
dimensions, MIME, sizes -- plus each rendition's `path`, which is likewise
unencrypted. No master key, no recovery code, nothing decrypted; just a Cognito
sign-in (see api_client.py) and the ordinary `GET`/`DELETE /photos` routes
`docs/design/api.md` documents, the same routes the app's own UI uses.

Usage:
    .venv/bin/python3 dedupe_by_filename.py --host photos.example.com \\
        --username someone@example.com [--execute] [--include-multi-rendition]

Defaults to a dry run -- reports exactly what it would do and deletes nothing.
Pass --execute to actually trash the losers. See README.md.
"""

from __future__ import annotations

import argparse
import posixpath
import sys
from collections import defaultdict
from dataclasses import dataclass, field

import api_client
import cli_auth
import library_cache
import library_scan

DEFAULT_CONCURRENCY = library_scan.DEFAULT_CONCURRENCY


@dataclass
class AssetInfo:
    photo_id: str
    taken_at: str
    filename: str  # basename of the primary rendition's path
    width: int
    height: int
    max_plain_bytes: int  # largest rendition's original file size
    rendition_count: int
    rendition_roles: list[str] = field(default_factory=list)


def asset_info_from_detail(photo_id: str, detail: dict) -> AssetInfo | None:
    meta = detail["meta"]
    renditions = detail["renditions"]
    primary = next((r for r in renditions if r["renditionId"] == meta.get("primaryRend")), None)
    primary = primary or (renditions[0] if renditions else None)
    if primary is None:
        return None  # shouldn't happen -- every live asset has at least one rendition
    return AssetInfo(
        photo_id=photo_id,
        taken_at=meta["takenAt"],
        filename=posixpath.basename(primary["path"]),
        width=meta.get("width", 0),
        height=meta.get("height", 0),
        max_plain_bytes=max((r.get("plainBytes", 0) for r in renditions), default=0),
        rendition_count=len(renditions),
        rendition_roles=[r.get("role", "?") for r in renditions],
    )


def fetch_all_assets(
    api: api_client.ArchivistApi,
    host: str,
    username: str,
    concurrency: int,
    progress,
    *,
    use_cache: bool = True,
    refresh_listing: bool = False,
    refresh_cache: bool = False,
) -> list[AssetInfo]:
    """Walks the whole live timeline, then fetches each asset's detail to
    recover `path`/`plainBytes`, neither of which the lean timeline listing
    carries (design.md: "path is deliberately not denormalised" onto
    timeline_gsi's projection). See library_cache.py for what "cached" means
    here (default: a pure local read, no live calls at all) and, more
    importantly, what it doesn't cover -- `revalidate_before_delete` below is
    the other half of that story."""
    details, stats = library_cache.get_snapshot(
        api, host, username, progress,
        section="live", use_cache=use_cache, refresh_listing=refresh_listing, refresh_cache=refresh_cache,
        concurrency=concurrency,
    )
    if stats.listing_checked_live:
        progress(
            f"  {stats.from_cache} from cache, {stats.fetched_fresh} fetched fresh, "
            f"{stats.dropped_stale} no longer live."
        )
    else:
        progress(f"  {stats.from_cache} from cache (not checked live -- pass --refresh-listing to).")
    assets = [asset_info_from_detail(pid, detail) for pid, detail in details.items()]
    return [a for a in assets if a is not None]


@dataclass
class DuplicateGroup:
    filename: str
    taken_at: str
    keep: AssetInfo
    delete: list[AssetInfo]
    skipped_multi_rendition: list[AssetInfo]


def find_duplicate_groups(assets: list[AssetInfo], include_multi_rendition: bool) -> list[DuplicateGroup]:
    buckets: dict[tuple[str, str], list[AssetInfo]] = defaultdict(list)
    for a in assets:
        buckets[(a.filename, a.taken_at)].append(a)

    groups = []
    for (filename, taken_at), members in buckets.items():
        if len(members) < 2:
            continue
        # Largest original file first -- "keep the highest resolution photo (the
        # largest original file)", the user's own tie-breaker.
        members.sort(key=lambda a: a.max_plain_bytes, reverse=True)
        keep, *losers = members
        to_delete = []
        skipped = []
        for loser in losers:
            if loser.rendition_count > 1 and not include_multi_rendition:
                # A multi-rendition asset (RAW+JPEG, a Live Photo's motion clip,
                # an edited-copy sibling) losing on primary-rendition size alone
                # isn't a safe auto-delete -- trashing it takes every rendition
                # with it, and one of those might not have a duplicate anywhere
                # else. Surfaced for a human to look at instead.
                skipped.append(loser)
            else:
                to_delete.append(loser)
        if to_delete or skipped:
            groups.append(DuplicateGroup(filename, taken_at, keep, to_delete, skipped))
    return groups


def format_asset(a: AssetInfo) -> str:
    roles = ",".join(a.rendition_roles)
    return (
        f"{a.photo_id}  {a.width}x{a.height}  {a.max_plain_bytes:>12,} bytes  "
        f"{a.rendition_count} rendition(s) [{roles}]"
    )


def revalidate_before_delete(
    api: api_client.ArchivistApi, group: DuplicateGroup
) -> tuple[list[AssetInfo], list[str]]:
    """The other half of library_cache.py's own honesty about what it can't
    keep fresh: a cached `path` can be stale (a rename elsewhere), so nothing
    gets deleted on cached data alone. Re-fetches `keep` and every `delete`
    candidate live, right before actually issuing any DELETE for this group,
    and only returns candidates whose fresh data still agrees with the cached
    decision that put them here. Returns (safe_to_delete, warnings) --
    `warnings` covers every case where fresh data disagreed, so a run that
    skips something still says why rather than silently doing less."""
    warnings: list[str] = []

    fresh_keep_detail = api.get_photo_detail(group.keep.photo_id)
    if fresh_keep_detail is None:
        warnings.append(
            f"{group.filename} @ {group.taken_at}: the asset to KEEP ({group.keep.photo_id}) "
            "is no longer live (deleted elsewhere?) -- skipping this whole group, re-run to re-evaluate it"
        )
        return [], warnings
    fresh_keep = asset_info_from_detail(group.keep.photo_id, fresh_keep_detail)
    if fresh_keep is None or (fresh_keep.filename, fresh_keep.taken_at) != (group.filename, group.taken_at):
        warnings.append(
            f"{group.filename} @ {group.taken_at}: the asset to KEEP ({group.keep.photo_id}) "
            "no longer matches (renamed?) -- skipping this whole group, re-run to re-evaluate it"
        )
        return [], warnings

    safe: list[AssetInfo] = []
    for candidate in group.delete:
        detail = api.get_photo_detail(candidate.photo_id)
        if detail is None:
            continue  # already gone (a previous run, or trashed some other way) -- nothing to do
        fresh = asset_info_from_detail(candidate.photo_id, detail)
        if fresh is None or (fresh.filename, fresh.taken_at) != (group.filename, group.taken_at):
            warnings.append(
                f"{group.filename} @ {group.taken_at}: {candidate.photo_id} no longer matches "
                "(renamed?) -- not deleting it, re-run to re-evaluate"
            )
            continue
        if fresh.max_plain_bytes >= fresh_keep.max_plain_bytes:
            warnings.append(
                f"{group.filename} @ {group.taken_at}: {candidate.photo_id} is no longer smaller "
                f"than the one being kept ({fresh.max_plain_bytes:,} >= {fresh_keep.max_plain_bytes:,} bytes) "
                "-- not deleting it, re-run to re-evaluate"
            )
            continue
        safe.append(fresh)
    return safe, warnings


def execute_deletes(
    api: api_client.ArchivistApi, groups_with_deletes: list[DuplicateGroup], progress
) -> tuple[int, list[tuple[str, str]], list[str]]:
    """The actual `--execute` work: re-verify then delete, one group at a time.
    Returns (deleted_count, errors, warnings). A group whose own re-verification
    fails outright (api_client.py's own retries exhausted -- a sustained outage,
    something unexpected) is recorded as an error and skipped, not allowed to
    take the rest of a run spanning thousands of groups down with it; the next
    run picks it back up from the cache exactly as if this one had never
    reached it."""
    errors: list[tuple[str, str]] = []
    warnings: list[str] = []
    deleted = 0
    for i, g in enumerate(groups_with_deletes, start=1):
        if i % 50 == 0 or i == len(groups_with_deletes):
            progress(f"  re-verified {i}/{len(groups_with_deletes)} group(s), {deleted} trashed so far...")
        try:
            safe, group_warnings = revalidate_before_delete(api, g)
        except Exception as e:  # noqa: BLE001 -- see this function's own docstring
            errors.append((f"{g.filename} @ {g.taken_at}", str(e)))
            progress(f"  ERROR re-verifying {g.filename} @ {g.taken_at}: {e}")
            continue
        warnings.extend(group_warnings)
        for a in safe:
            try:
                api.delete_photo(a.photo_id, deleted_by="dedupe_by_filename.py")
                deleted += 1
            except Exception as e:  # noqa: BLE001 -- one failure must not abort the run
                errors.append((a.photo_id, str(e)))
                progress(f"  ERROR trashing {a.photo_id}: {e}")
    return deleted, errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--execute", action="store_true", help="actually trash the losers (default: dry run)")
    parser.add_argument(
        "--include-multi-rendition",
        action="store_true",
        help="also delete a losing asset that has more than one rendition (RAW+JPEG, "
        "Live Photo, an edited-copy sibling) -- off by default, since trashing it "
        "takes every rendition with it",
    )
    parser.add_argument("--concurrency", type=int, default=DEFAULT_CONCURRENCY)
    parser.add_argument(
        "--no-cache", action="store_true", help="always fetch fresh, ignoring and not writing any local cache"
    )
    parser.add_argument(
        "--refresh-listing",
        action="store_true",
        help="check live for photos added/trashed since the cache was built (cheap -- tens of calls, "
        "not thousands) and fetch detail for any new ones; default is to use the cache exactly as "
        "it is, with no live calls at all, for the report -- the actual delete step always "
        "re-verifies live regardless (see library_cache.py), so this only affects the report",
    )
    parser.add_argument(
        "--refresh-cache",
        action="store_true",
        help="re-fetch every photo's detail even if cached, for the report (implies --refresh-listing) "
        "-- again, only affects the report, never delete safety",
    )
    args = parser.parse_args()

    def progress(msg: str) -> None:
        print(msg, file=sys.stderr)

    try:
        api = cli_auth.authenticate(args.host, args.username, progress).api
    except cli_auth.AuthFailed as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    progress("Loading the live library (cache by default -- see --refresh-listing/--refresh-cache)...")
    assets = fetch_all_assets(
        api, args.host, args.username, args.concurrency, progress,
        use_cache=not args.no_cache, refresh_listing=args.refresh_listing, refresh_cache=args.refresh_cache,
    )
    progress(f"Found {len(assets)} live assets.")

    groups = find_duplicate_groups(assets, args.include_multi_rendition)
    if not groups:
        print("No duplicate (filename, takenAt) groups found.")
        return 0

    to_delete_total = 0
    skipped_total = 0
    for g in sorted(groups, key=lambda g: (g.filename, g.taken_at)):
        print(f"\n{g.filename}  @ {g.taken_at}")
        print(f"  KEEP    {format_asset(g.keep)}")
        for a in g.delete:
            print(f"  DELETE  {format_asset(a)}")
            to_delete_total += 1
        for a in g.skipped_multi_rendition:
            print(f"  SKIP    {format_asset(a)}  (multi-rendition -- pass --include-multi-rendition to also delete)")
            skipped_total += 1

    print(f"\n--- {len(groups)} duplicate group(s), {to_delete_total} asset(s) to delete, {skipped_total} skipped ---")

    if not args.execute:
        print("\nDry run only -- nothing deleted. Re-run with --execute to actually trash the losers.")
        print("Trashed assets are recoverable for trashRetentionDays (owner setting, default 30) via")
        print("POST /photos/{photoId}/restore before the daily purge sweep removes them for real.")
        if not args.no_cache and not args.refresh_cache:
            if not args.refresh_listing:
                print("This report used the local cache as-is, with no live calls at all -- it won't see a")
                print("photo added or trashed since the cache was built. --refresh-listing checks live for")
                print("those (cheap); --refresh-cache also re-fetches every photo's own detail (catches a")
                print("rename too, but costs one call per photo). --execute always re-verifies live")
                print("regardless, so none of this affects what actually gets deleted, only this report.")
            else:
                print("This report's photo list was checked live, but per-photo detail may still be")
                print("cached from before that (a rename elsewhere wouldn't show up) -- --refresh-cache")
                print("re-fetches every photo's own detail too. --execute always re-verifies live")
                print("regardless, so this only affects the report, never what actually gets deleted.")
        return 0

    print(f"\nRe-verifying each group live before deleting anything from it (the report above may be")
    print("cached -- see library_cache.py; nothing is ever deleted on cached data alone)...")
    deleted, errors, all_warnings = execute_deletes(api, [g for g in groups if g.delete], progress)

    if all_warnings:
        print(f"\n{len(all_warnings)} group(s)/asset(s) skipped on live re-verification:")
        for w in all_warnings:
            print(f"  {w}")

    print(f"\nDone. {deleted}/{to_delete_total} trashed, {len(errors)} error(s), "
          f"{to_delete_total - deleted - len(errors)} skipped on re-verification.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
