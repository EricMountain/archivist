#!/usr/bin/env python3
"""Prints everything the API can tell you about every asset matching a given
filename -- meant for eyeballing a `dedupe_by_filename.py` report before
trusting it, or checking what actually landed after `--execute`: full `#META`
(dimensions, timestamps, status, grouping) and every `R#` rendition (role,
path, size, content hash) for each match, human-readable.

**Searches trashed assets by default, alongside live ones** -- "did this
actually get trashed?" is exactly the question a dedupe report gets asked
right after `--execute`, and design.md's `deletedAt`/`deletedBy` on `#META`
already answer it directly once an asset's detail is in hand; the only reason
this was ever opt-in was that fetching the trash listing used to mean a full,
uncached live walk. Now that both live and trashed detail are cached the same
way (library_cache.py), there's no reason to hide one by default -- pass
`--live-only` to search only live assets.

Doesn't touch DynamoDB directly -- same as dedupe_by_filename.py, this walks
the ordinary `GET /photos`/`GET /photos/{photoId}`/`GET /trash` routes
docs/design/api.md documents, with the same Cognito sign-in and no master key
or recovery code (nothing here is encrypted).

**`--photo-id`/`--path` resolve directly** -- `GET /photos/{photoId}` or
`GET /photos/by-path` (one DynamoDB read either way, live or trashed) -- instead of
loading or paging through the library at all. Use `--photo-id` when you already have
one (straight from a `dedupe_by_filename.py` report, which prints it for every
candidate), `--path` when you have the exact server path but not the id (this tool's
own `stem` output). `--filename`/`--contains` are for when you have neither, and pay
for a full cached-library search instead.

Usage:
    .venv/bin/python3 inspect_photo.py --host photos.example.com \\
        --username someone@example.com --filename IMG_1234.jpg

    .venv/bin/python3 inspect_photo.py --host photos.example.com \\
        --username someone@example.com --path -1739773001/IMG_1234.jpg

    .venv/bin/python3 inspect_photo.py --host photos.example.com \\
        --username someone@example.com --photo-id 01K5A2QB3HN7WYP2GKD4RVXM8C
"""

from __future__ import annotations

import argparse
import posixpath
import sys

import cli_auth
import library_cache
import library_scan


def matches(filename: str, candidate: str, contains: bool) -> bool:
    if contains:
        return filename.lower() in candidate.lower()
    return filename == candidate


def find_matches(
    details: dict[str, dict], filename: str, contains: bool
) -> list[tuple[str, dict, list[dict]]]:
    """(photoId, detail, matching renditions) for every asset with at least one
    rendition whose path's basename matches -- not just the primary, so a photo
    is still found by its RAW sibling's own filename, say."""
    out = []
    for photo_id, detail in details.items():
        hits = [r for r in detail["renditions"] if matches(filename, posixpath.basename(r["path"]), contains)]
        if hits:
            out.append((photo_id, detail, hits))
    return out


def status_tag(meta: dict) -> str:
    """Read directly off `deletedAt` (design.md "Trash and deletion"), not off
    which cache section a photoId happened to be discovered through -- the two
    can disagree the instant something's trashed or restored elsewhere, and
    this is the one that's actually true."""
    return "TRASHED" if meta.get("deletedAt") else "live"


def format_meta(meta: dict) -> str:
    lines = [
        f"  status        {meta.get('status')}",
        f"  stem          {meta.get('stem')}",
        f"  takenAt       {meta.get('takenAt')}  (src={meta.get('takenAtSrc')})",
        f"  tzOffsetMin   {meta.get('tzOffsetMin')}  (src={meta.get('tzSrc')})",
        f"  dimensions    {meta.get('width')}x{meta.get('height')}",
        f"  mime          {meta.get('mime')}",
        f"  primaryRend   {meta.get('primaryRend')}",
        f"  renditions    {meta.get('renditions')}",
        f"  groupSrc      {meta.get('groupSrc')}",
    ]
    if meta.get("deviceKey"):
        lines.append(f"  deviceKey     {meta['deviceKey']}")
    if meta.get("deletedAt"):
        lines.append(f"  deletedAt     {meta['deletedAt']}")
        lines.append(f"  deletedBy     {meta.get('deletedBy')}")
    return "\n".join(lines)


def format_rendition(r: dict, is_match: bool) -> str:
    marker = "→" if is_match else " "
    return (
        f"  {marker} {r.get('renditionId')}  role={r.get('role')}  "
        f"path={r.get('path')!r}\n"
        f"      {r.get('width')}x{r.get('height')}  "
        f"plainBytes={r.get('plainBytes'):,}  bytes={r.get('bytes'):,}  "
        f"encChunkSize={r.get('encChunkSize')}\n"
        f"      contentHash={r.get('contentHash')}\n"
        f"      addedAt={r.get('addedAt')}"
    )


def format_facets(facets: list[dict]) -> str | None:
    if not facets:
        return None
    parts = [f"{f.get('facetType')}#{f.get('facetValue')}" for f in facets]
    return "  facets        " + ", ".join(parts)


def print_asset(photo_id: str, detail: dict, matched_rendition_ids: set[str]) -> None:
    meta = detail["meta"]
    print(f"\n=== {photo_id}  [{status_tag(meta)}] ===")
    print(format_meta(meta))
    print("  renditions:")
    for r in detail["renditions"]:
        print(format_rendition(r, r["renditionId"] in matched_rendition_ids))
    facets_line = format_facets(detail.get("facets", []))
    if facets_line:
        print(facets_line)


def refresh_matches(
    api,
    matched_ids: set[str],
    host: str,
    username: str,
    use_cache: bool,
    progress,
    concurrency: int = library_scan.DEFAULT_CONCURRENCY,
) -> tuple[dict[str, dict], int]:
    """Re-fetches live detail for exactly `matched_ids` (a handful, not the
    library) and, if `use_cache`, patches just those entries into the on-disk
    cache -- `--refresh-match`'s whole point: bypass staleness for this one
    query without a full listing walk or full re-fetch.

    `GET /photos/{photoId}` answers identically regardless of live/trashed
    status (`getAssetPartition` server-side has no such filter), so a refresh
    also catches a status change since the cache was built -- the fresh detail
    is written into whichever of the two cache sections it now actually
    belongs to, and removed from the other one if it had been cached there
    under a now-stale status. Returns (fresh_details, removed_count);
    `removed_count` counts a matched id no longer reachable at all (purged),
    which is dropped from both sections rather than left stale in either."""
    fetched = library_scan.fetch_details(api, list(matched_ids), progress, concurrency)
    removed = matched_ids - set(fetched.keys())
    if use_cache:
        live_updates: dict[str, dict | None] = {}
        trashed_updates: dict[str, dict | None] = {}
        for pid in matched_ids:
            detail = fetched.get(pid)
            if detail is None:
                live_updates[pid] = None
                trashed_updates[pid] = None
            elif detail["meta"].get("deletedAt"):
                live_updates[pid] = None
                trashed_updates[pid] = detail
            else:
                live_updates[pid] = detail
                trashed_updates[pid] = None
        library_cache.merge_updates(host, username, live_updates, section="live")
        library_cache.merge_updates(host, username, trashed_updates, section="trashed")
    return fetched, len(removed)


def _load_section(api, host, username, section, progress, *, use_cache, refresh_listing, refresh_cache, concurrency):
    details, stats = library_cache.get_snapshot(
        api, host, username, progress,
        section=section, use_cache=use_cache, refresh_listing=refresh_listing,
        refresh_cache=refresh_cache, concurrency=concurrency,
    )
    if stats.listing_checked_live:
        progress(
            f"  {section}: {stats.from_cache} from cache, {stats.fetched_fresh} fetched fresh, "
            f"{stats.dropped_stale} no longer {section}."
        )
    else:
        progress(f"  {section}: {stats.from_cache} from cache (not checked live -- pass --refresh-listing to).")
    return details


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", required=True)
    parser.add_argument("--username", required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--filename", help="exact basename to match, e.g. IMG_1234.jpg")
    target.add_argument(
        "--path",
        help="exact full path as the server stores it, e.g. -1739773001/IMG_1234.jpg -- the same "
        "string this tool's own 'stem' field is built from. Resolves it directly via "
        "GET /photos/by-path (one DynamoDB read, live or trashed) instead of loading or paging "
        "through the library at all -- use this over --filename whenever you already have the "
        "exact path (e.g. from a dedupe_by_filename.py report). --contains/--live-only/"
        "--refresh-* don't apply here: there's no listing to filter or cache to refresh.",
    )
    target.add_argument(
        "--photo-id",
        help="a ULID already in hand -- straight to GET /photos/{photoId}, no pointer read, no "
        "listing, no cache. The natural choice right after dedupe_by_filename.py's own report, "
        "which prints each candidate's photo_id directly. --contains/--live-only/--refresh-* "
        "don't apply here either.",
    )
    parser.add_argument(
        "--contains",
        action="store_true",
        help="with --filename: substring match (case-insensitive) instead of an exact basename match",
    )
    parser.add_argument(
        "--live-only",
        action="store_true",
        help="don't search trashed assets -- by default both are searched (both are cached the "
        "same way, so trash costs nothing extra to include)",
    )
    parser.add_argument("--concurrency", type=int, default=library_scan.DEFAULT_CONCURRENCY)
    parser.add_argument(
        "--no-cache", action="store_true", help="always fetch fresh, ignoring and not writing any local cache"
    )
    parser.add_argument(
        "--refresh-listing",
        action="store_true",
        help="check live for photos added/trashed/restored since the cache was built (cheap -- tens "
        "of calls, not thousands) and fetch detail for any new ones; default is to use the cache "
        "exactly as it is, with no live calls at all",
    )
    parser.add_argument(
        "--refresh-cache",
        action="store_true",
        help="re-fetch every photo's detail even if cached (a rename elsewhere wouldn't otherwise "
        "be picked up -- see library_cache.py); implies --refresh-listing",
    )
    parser.add_argument(
        "--refresh-match",
        action="store_true",
        help="after finding matches (from the cache, by default), re-fetch live detail for "
        "exactly those photos and patch the cache with the result -- bypasses staleness for "
        "this one query without a full listing walk or full re-fetch. Won't find a photo the "
        "cache has nothing matching at all (e.g. renamed away from this filename); "
        "--refresh-listing/--refresh-cache are for that",
    )
    args = parser.parse_args()

    def progress(msg: str) -> None:
        print(msg, file=sys.stderr)

    try:
        api = cli_auth.authenticate(args.host, args.username, progress).api
    except cli_auth.AuthFailed as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    if args.photo_id:
        progress(f"Fetching {args.photo_id} directly (GET /photos/{{photoId}} -- one read, no listing)...")
        detail = api.get_photo_detail(args.photo_id)
        if detail is None:
            print(f"\nNo asset with photoId {args.photo_id!r} (wrong id, or purged already).")
            return 0
        print(f"\nAsset {args.photo_id}:")
        print_asset(args.photo_id, detail, set())
        return 0

    if args.path:
        progress(f"Resolving {args.path!r} directly (GET /photos/by-path -- one read, no listing)...")
        ptr = api.get_photo_by_path(args.path)
        if ptr is None:
            print(f"\nNo asset filed under path {args.path!r} (never uploaded, wrong path, or purged already).")
            return 0
        detail = api.get_photo_detail(ptr["photoId"])
        if detail is None:
            print(
                f"\n{args.path!r} resolves to {ptr['photoId']} but that asset is gone -- purged since "
                "the PATH pointer was written (pointers aren't cleaned up until overwritten)."
            )
            return 0
        print(f"\n1 asset matching path {args.path!r}:")
        print_asset(ptr["photoId"], detail, {ptr["renditionId"]})
        return 0

    progress("Loading the library (cache by default -- see --refresh-listing/--refresh-cache)...")
    common = dict(use_cache=not args.no_cache, refresh_listing=args.refresh_listing,
                  refresh_cache=args.refresh_cache, concurrency=args.concurrency)
    all_details = _load_section(api, args.host, args.username, "live", progress, **common)
    if not args.live_only:
        try:
            all_details.update(_load_section(api, args.host, args.username, "trashed", progress, **common))
        except Exception as e:  # noqa: BLE001 -- trash inclusion is a default convenience, not a
            # requirement: a live server error, a network blip api_client.py's own retries didn't
            # absorb, or anything else here must not take down a search that live results alone can
            # still usefully answer. Reported plainly rather than silently swallowed either way.
            print(f"warning: couldn't load trashed assets ({e}) -- showing live results only", file=sys.stderr)

    matches_found = find_matches(all_details, args.filename, args.contains)

    if not matches_found:
        scope = "live assets" if args.live_only else "live or trashed assets"
        print(f"\nNo asset found among {scope} with a rendition matching {args.filename!r}.")
        if args.live_only:
            print("(--live-only was set -- a trashed match, if any, wouldn't show up here.)")
        return 0

    if args.refresh_match and not args.no_cache:
        matched_ids = {pid for pid, _, _ in matches_found}
        progress(f"Bypassing the cache for {len(matched_ids)} matched photo(s)...")
        fresh, removed_count = refresh_matches(
            api, matched_ids, args.host, args.username, True, progress, args.concurrency
        )
        for pid in matched_ids:
            if pid in fresh:
                all_details[pid] = fresh[pid]
            else:
                all_details.pop(pid, None)
        if removed_count:
            progress(f"  {removed_count} matched photo(s) no longer reachable at all -- dropped from the cache.")
        matches_found = find_matches(all_details, args.filename, args.contains)
        if not matches_found:
            print(f"\nNo asset matching {args.filename!r} is still reachable after refreshing.")
            return 0

    print(f"\n{len(matches_found)} asset(s) matching {args.filename!r}:")
    for photo_id, detail, hit_renditions in sorted(matches_found, key=lambda t: t[1]["meta"].get("takenAt", "")):
        matched_ids = {r["renditionId"] for r in hit_renditions}
        print_asset(photo_id, detail, matched_ids)

    return 0


if __name__ == "__main__":
    sys.exit(main())
