"""A local, on-disk cache of `library_scan.fetch_details`'s expensive half --
the per-asset `GET /photos/{photoId}` detail, which is what a full library scan
actually costs (thousands of round trips; the id listing itself is tens, and
by default this doesn't even pay that: a cache hit is a pure local read, no
live calls at all).

Covers **both** partitions the same way, live and trashed, since both cost the
same thing for the same reason: neither `GET /photos` nor `GET /trash` project
`path`/`plainBytes` into their lean listings (design.md: "path is deliberately
not denormalised"), so telling anything about a photo by name -- including
whether it's actually been trashed -- needs its own `GET /photos/{photoId}`
either way. One cache file per host+username holds both sections; refreshing
one never touches the other.

**Three ways to ask for either section, from cheapest to most thorough:**

1. Default (no flags) -- if a cache file exists, use it exactly as it is: no
   live id listing, no detail fetches, nothing but a local file read. Fast,
   and the right default for "I'm iterating on a report and the library
   hasn't changed since five minutes ago." First run (no cache file yet) has
   no choice but to do a full live scan regardless -- there's nothing to read.
2. `refresh_listing=True` -- do the cheap live id listing (tens of calls), so
   a photo added, trashed or restored since the cache was built is picked up:
   a newly-seen id gets fetched fresh, an id no longer in this section is
   dropped. Cached detail for everything else is reused as-is.
3. `refresh_cache=True` -- like (2), but re-fetches detail for *every*
   currently-live (or currently-trashed) id regardless of what's cached, not
   just the new ones.

**What none of these three catch**, even (3): a field changing on a photo
that stays in the same section the whole time -- a rename being the one that
matters here, since `path` is exactly what these scripts key their matching
on (design.md: `PATCH .../renditions/{id}` never touches `timeline_gsi`, so
there's no cheap signal for it the way create/trash/restore have one via
`histogramVersion`). That's a real gap, not a hidden one: `dedupe_by_filename.py`
re-fetches and re-verifies a duplicate group *live*, right before actually
deleting anything from it, specifically so this cache -- at any of the three
levels above -- can only ever cost a wasted API call, never cause a wrong
deletion. `inspect_photo.py` has no mutation to protect, so for it this is
purely a "how sure do I need to be right now" choice, and its own
`--refresh-match` is a fourth, targeted way to bypass either section's cache
for just the photos a search already matched -- see `merge_updates`.

File format is intentionally simple (one JSON object) since this cache is
regenerated wholesale on a miss, never patched byte-by-byte -- there's nothing
here that benefits from a database's own concurrency control.
"""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass

import api_client
import library_scan

CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")
# Bumped from 1: the cache file now holds a "trashed" section alongside "live".
# An old v1 file has no such section and is simply treated as absent (same
# fallback as a corrupt file) -- costs one full re-scan, not worth a migration.
CACHE_VERSION = 2

Section = str  # "live" | "trashed" -- not an enum, since it only ever threads through as a dict key


def _cache_path(host: str, username: str) -> str:
    # Hashed rather than a literal "<host>__<username>.json" so an email
    # address never sits in a filename on disk unnecessarily -- this
    # directory is gitignored either way, this is just tidiness.
    key = hashlib.sha256(f"{host}|{username}".encode("utf-8")).hexdigest()[:16]
    return os.path.join(CACHE_DIR, f"{key}.json")


def _load_all(host: str, username: str) -> dict[Section, dict[str, dict]] | None:
    """None when there's no cache file at all, or it's corrupt, or the wrong
    version/host -- three different reasons a caller should all treat as
    "start over", so they're deliberately not distinguished.

    **A section key is present in the returned dict only if that section has
    actually been cached before** -- a section never fetched at all is simply
    absent, not defaulted to `{}`. This is load-bearing, not cosmetic: two
    real bugs shipped from getting it wrong, both the same shape at two
    different levels. First, returning `{}` instead of `None` for a missing
    *file* made the very first `get_snapshot` call for a brand-new
    host+username silently skip both the live listing and the save, because
    an empty-but-present section read as "already have this" instead of
    "nothing here yet". Fixing that at the file level then exposed the same
    bug one level down: once *either* section had ever been cached, the file
    existed, so the *other* section defaulting to `{}` via `.get(section, {})`
    again meant "never fetched" and "fetched and legitimately empty" (a
    library with zero trashed photos, say) were indistinguishable. Both are
    closed the same way: absence, not an empty value, is what "never cached"
    has to look like at every level, or `is None` checks upstream silently
    stop meaning what they say."""
    path = _cache_path(host, username)
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError):
        return None  # corrupt/partial cache file -- treat exactly like no cache
    if data.get("version") != CACHE_VERSION or data.get("host") != host:
        return None
    return {section: data[section] for section in ("live", "trashed") if section in data}


def _save_all(host: str, username: str, sections: dict[Section, dict[str, dict]]) -> None:
    os.makedirs(CACHE_DIR, exist_ok=True)
    path = _cache_path(host, username)
    tmp_path = path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump({"version": CACHE_VERSION, "host": host, **sections}, f)
    os.replace(tmp_path, path)  # atomic on POSIX -- never leaves a half-written cache


def _save_section(host: str, username: str, section: Section, data: dict[str, dict]) -> None:
    """Read-modify-write on the *other* section too, so saving "live" can
    never clobber whatever "trashed" already held on disk (or vice versa) --
    the two are refreshed independently and often at different times."""
    all_sections = _load_all(host, username) or {}
    all_sections[section] = data
    _save_all(host, username, all_sections)


def merge_updates(
    host: str, username: str, updates: dict[str, dict | None], section: Section = "live"
) -> None:
    """Patches just `updates` into one section of the on-disk cache, leaving
    every other cached entry (in either section) untouched -- for a caller
    that already knows exactly which few photoIds it wants fresher data for
    (inspect_photo.py's `--refresh-match`: find candidates in the cache
    cheaply, then bypass it for only those specific photos, no full listing
    walk or full re-fetch). A value of None removes that photoId from the
    section instead of setting it -- for a matched candidate that turns out
    to no longer belong there (purged, or restored back to live)."""
    all_sections = _load_all(host, username) or {}
    section_existed = section in all_sections
    data = all_sections.get(section, {})
    for photo_id, detail in updates.items():
        if detail is None:
            data.pop(photo_id, None)
        else:
            data[photo_id] = detail
    if section_existed or data:
        # Don't manufacture a present-but-empty section out of nothing but
        # no-op removals (e.g. every `updates` value was None, removing ids
        # that were never cached here anyway) -- that would read exactly like
        # "this section has genuinely been scanned and is empty" to a later
        # get_snapshot call, which is a different claim than this call ever
        # made. A section this call actually populates, even if a later call
        # empties it back out again, stays present -- only "never touched at
        # all" should read as absent.
        all_sections[section] = data
    _save_all(host, username, all_sections)


@dataclass
class SnapshotStats:
    from_cache: int
    fetched_fresh: int
    dropped_stale: int
    # False only for a pure cache hit (no flags, cache present) -- the one case
    # where "dropped_stale" is necessarily 0 because nothing live was actually
    # checked, not because nothing changed.
    listing_checked_live: bool


_LISTERS = {
    "live": library_scan.list_live_photo_ids,
    "trashed": library_scan.list_trashed_photo_ids,
}


def get_snapshot(
    api: api_client.ArchivistApi,
    host: str,
    username: str,
    progress,
    *,
    section: Section = "live",
    use_cache: bool = True,
    refresh_listing: bool = False,
    refresh_cache: bool = False,
    concurrency: int = library_scan.DEFAULT_CONCURRENCY,
) -> tuple[dict[str, dict], SnapshotStats]:
    """The photoId -> detail map this run should treat as "every asset
    currently in `section`" -- see the module docstring for what each flag
    actually costs, and for `section="trashed"` specifically: an asset can
    move between `section="live"` and `section="trashed"` (trash, restore),
    so the two are cached and refreshed completely independently -- there's
    no "reuse a live cache entry as a trashed one" shortcut, since the
    interesting cases (did this get trashed? was it restored?) are exactly
    the ones a shared entry would get wrong.

    `use_cache=False` ignores any cache file entirely (reads none, writes
    none, always a full live scan) -- the pre-cache behaviour, for "just
    don't touch disk at all"."""
    list_ids = _LISTERS[section]
    all_sections = _load_all(host, username) if use_cache else None
    # .get, not []: a cache file can exist with only the *other* section ever
    # populated -- that must still read as "nothing cached here yet" (None),
    # not KeyError and not {} (which would misread as "cached and empty").
    cached = all_sections.get(section) if all_sections is not None else None

    # No cache to fall back to: there is nothing "cheap" available, so this is
    # a full live scan regardless of what was asked for -- same cost as
    # refresh_cache, just for a different reason (nothing cached yet, not
    # "I don't trust what's cached").
    must_go_live = cached is None or refresh_listing or refresh_cache

    if not must_go_live:
        progress(f"  using {len(cached)} cached {section} photo(s) as-is (pass --refresh-listing to check live).")
        return dict(cached), SnapshotStats(
            from_cache=len(cached), fetched_fresh=0, dropped_stale=0, listing_checked_live=False
        )

    current_ids = list_ids(api, progress)
    cached = cached or {}

    if refresh_cache:
        to_fetch = current_ids
        reused: dict[str, dict] = {}
    else:
        current_id_set = set(current_ids)
        cached_id_set = set(cached.keys())
        to_fetch = [pid for pid in current_ids if pid not in cached_id_set]
        reused = {pid: cached[pid] for pid in cached_id_set & current_id_set}

    if to_fetch:
        progress(f"  {len(to_fetch)} {section} photo(s) need a fresh detail fetch ({len(reused)} reused)...")
        fetched = library_scan.fetch_details(api, to_fetch, progress, concurrency)
    else:
        progress(f"  all {len(reused)} {section} photo(s) served from cache, nothing to fetch.")
        fetched = {}

    merged = {**reused, **fetched}
    dropped = len(cached) - len(set(cached.keys()) & set(current_ids))

    if use_cache:
        _save_section(host, username, section, merged)

    return merged, SnapshotStats(
        from_cache=len(reused), fetched_fresh=len(fetched), dropped_stale=dropped, listing_checked_live=True
    )
