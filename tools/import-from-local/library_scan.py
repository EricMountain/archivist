"""Shared "walk the whole library through the API" machinery for
dedupe_by_filename.py and inspect_photo.py -- neither `GET /photos` nor
`GET /trash` project `path`/`plainBytes` into their lean listings (design.md:
"path is deliberately not denormalised" onto `timeline_gsi`), so both scripts
need the same two-phase shape: list every photoId cheaply, then fetch each
asset's full detail (concurrently -- independent, read-only requests) to get
at what the listing doesn't carry.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed

import api_client

DEFAULT_CONCURRENCY = 8


def _list_items(get_page, label: str, progress) -> list[dict]:
    items: list[dict] = []
    cursor = None
    while True:
        page = get_page(cursor=cursor)
        items.extend(page["items"])
        cursor = page.get("cursor")
        progress(f"  listed {len(items)} {label} photo(s) so far...")
        if not cursor:
            break
    return items


def list_live_items(api: api_client.ArchivistApi, progress) -> list[dict]:
    """Every lean `GET /photos` item -- `photoId`, plus what the timeline projects,
    `place` included (modify_media.py location's skip rule reads it from here, so a
    re-run skips without a per-asset detail fetch)."""
    return _list_items(api.get_photos_page, "live", progress)


def list_trashed_items(api: api_client.ArchivistApi, progress) -> list[dict]:
    """Every lean `GET /trash` item, same shape as `list_live_items`."""
    return _list_items(api.get_trash_page, "trashed", progress)


def list_live_photo_ids(api: api_client.ArchivistApi, progress) -> list[str]:
    return [item["photoId"] for item in list_live_items(api, progress)]


def list_trashed_photo_ids(api: api_client.ArchivistApi, progress) -> list[str]:
    return [item["photoId"] for item in list_trashed_items(api, progress)]


def fetch_details(
    api: api_client.ArchivistApi,
    photo_ids: list[str],
    progress,
    concurrency: int = DEFAULT_CONCURRENCY,
) -> dict[str, dict]:
    """photoId -> `{meta, renditions, facets}`. Skips (rather than raising for) a
    photoId that 404s between listing and fetching -- purged or restored out from
    under a long-running scan, both real and both fine to just skip."""
    results: dict[str, dict] = {}
    done = 0
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = {pool.submit(api.get_photo_detail, pid): pid for pid in photo_ids}
        for future in as_completed(futures):
            done += 1
            if done % 200 == 0 or done == len(photo_ids):
                progress(f"  fetched detail for {done}/{len(photo_ids)}...")
            photo_id = futures[future]
            detail = future.result()
            if detail is not None:
                results[photo_id] = detail
    return results
