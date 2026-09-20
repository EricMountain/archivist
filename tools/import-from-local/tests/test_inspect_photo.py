from __future__ import annotations

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import library_cache
from inspect_photo import (
    find_matches,
    format_facets,
    format_meta,
    format_rendition,
    matches,
    print_asset,
    refresh_matches,
    status_tag,
)


def _detail(taken_at: str, renditions: list[dict], **meta_overrides) -> dict:
    meta = {
        "status": "ready",
        "stem": "2018/IMG_1",
        "takenAt": taken_at,
        "takenAtSrc": "exif",
        "tzOffsetMin": 0,
        "tzSrc": "assumed-utc",
        "width": 100,
        "height": 80,
        "mime": "image/jpeg",
        "primaryRend": renditions[0]["renditionId"] if renditions else None,
        "renditions": len(renditions),
        "groupSrc": "stem",
    }
    meta.update(meta_overrides)
    return {"meta": meta, "renditions": renditions, "facets": []}


def _rendition(rid, path, plain_bytes=1000, role="display"):
    return {
        "renditionId": rid,
        "role": role,
        "path": path,
        "width": 100,
        "height": 80,
        "plainBytes": plain_bytes,
        "bytes": plain_bytes + 16,
        "encChunkSize": 0,
        "contentHash": "hmac-sha256:deadbeef",
        "addedAt": "2018-07-13T14:02:25.000Z",
    }


class ExactMatch(unittest.TestCase):
    def test_exact_basename_match(self):
        self.assertTrue(matches("IMG_1.jpg", "IMG_1.jpg", contains=False))
        self.assertFalse(matches("IMG_1.jpg", "IMG_10.jpg", contains=False))
        self.assertFalse(matches("img_1.jpg", "IMG_1.jpg", contains=False))  # case-sensitive

    def test_contains_match_is_case_insensitive(self):
        self.assertTrue(matches("img_1", "IMG_1234.jpg", contains=True))
        self.assertFalse(matches("zzz", "IMG_1234.jpg", contains=True))


class FindMatches(unittest.TestCase):
    def test_matches_by_any_rendition_not_just_primary(self):
        details = {
            "p1": _detail(
                "2018-07-13T14:02:25.000Z",
                [_rendition("r1", "2018/IMG_1.CR3", role="raw"), _rendition("r2", "2018/IMG_1.JPG", role="display")],
            )
        }
        hits = find_matches(details, "IMG_1.CR3", contains=False)
        self.assertEqual(len(hits), 1)
        photo_id, detail, matched = hits[0]
        self.assertEqual(photo_id, "p1")
        self.assertEqual([r["renditionId"] for r in matched], ["r1"])

    def test_no_match_returns_empty(self):
        details = {"p1": _detail("2018-07-13T14:02:25.000Z", [_rendition("r1", "2018/other.jpg")])}
        self.assertEqual(find_matches(details, "IMG_1.jpg", contains=False), [])

    def test_multiple_assets_can_match(self):
        details = {
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a/IMG_1.jpg")]),
            "p2": _detail("2018-01-02T00:00:00.000Z", [_rendition("r2", "b/IMG_1.jpg")]),
        }
        hits = find_matches(details, "IMG_1.jpg", contains=False)
        self.assertEqual({h[0] for h in hits}, {"p1", "p2"})


class StatusTag(unittest.TestCase):
    def test_trashed_when_deleted_at_present(self):
        self.assertEqual(status_tag({"deletedAt": "2026-01-01T00:00:00.000Z"}), "TRASHED")

    def test_live_when_deleted_at_absent(self):
        self.assertEqual(status_tag({"status": "ready"}), "live")


class Formatting(unittest.TestCase):
    def test_format_meta_shows_deleted_at_and_deleted_by_when_trashed(self):
        text = format_meta({
            "status": "ready", "deletedAt": "2026-01-01T00:00:00.000Z", "deletedBy": "unknown device",
        })
        self.assertIn("2026-01-01T00:00:00.000Z", text)
        self.assertIn("unknown device", text)

    def test_format_meta_omits_deleted_at_when_live(self):
        text = format_meta({"status": "ready"})
        self.assertNotIn("deletedAt", text)
        self.assertNotIn("deletedBy", text)

    def test_format_rendition_marks_the_matched_one(self):
        r = _rendition("r1", "a/IMG_1.jpg")
        self.assertTrue(format_rendition(r, is_match=True).startswith("  → "))
        self.assertTrue(format_rendition(r, is_match=False).startswith("    "))
        self.assertNotIn("→", format_rendition(r, is_match=False))

    def test_format_facets_none_when_empty(self):
        self.assertIsNone(format_facets([]))

    def test_format_facets_lists_type_and_value(self):
        text = format_facets([{"facetType": "YEAR", "facetValue": "2018"}])
        self.assertIn("YEAR#2018", text)


class PrintAsset(unittest.TestCase):
    """Shared by --filename's per-match loop and --path's single-asset print
    (main()'s --path branch, api.md GET /photos/by-path) -- one asset's full
    detail, with whichever renditions are in `matched_rendition_ids` marked."""

    def test_marks_only_the_matched_rendition_and_shows_status(self):
        detail = _detail(
            "2018-07-13T14:02:25.000Z",
            [_rendition("r1", "2018/IMG_1.CR3", role="raw"), _rendition("r2", "2018/IMG_1.JPG", role="display")],
        )
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            print_asset("p1", detail, {"r2"})
        text = out.getvalue()
        self.assertIn("=== p1  [live] ===", text)
        self.assertIn("→ r2", text)
        self.assertNotIn("→ r1", text)

    def test_shows_trashed_tag_and_deleted_fields(self):
        detail = _detail(
            "2018-07-13T14:02:25.000Z",
            [_rendition("r1", "2018/IMG_1.jpg")],
            deletedAt="2026-01-01T00:00:00.000Z",
            deletedBy="dedupe_by_filename.py",
        )
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            print_asset("p1", detail, {"r1"})
        text = out.getvalue()
        self.assertIn("[TRASHED]", text)
        self.assertIn("dedupe_by_filename.py", text)


class FakeApi:
    def __init__(self, details: dict[str, dict | None]):
        self.details = details
        self.fetched: list[str] = []

    def get_photo_detail(self, photo_id: str):
        self.fetched.append(photo_id)
        return self.details.get(photo_id)


class RefreshMatches(unittest.TestCase):
    """The --refresh-match path: bypass the cache for exactly the matched
    photos, and patch just those into the on-disk cache -- no full listing
    walk, no full re-fetch."""

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self._orig_cache_dir = library_cache.CACHE_DIR
        library_cache.CACHE_DIR = self._tmpdir.name

    def tearDown(self):
        library_cache.CACHE_DIR = self._orig_cache_dir
        self._tmpdir.cleanup()

    def test_fetches_only_the_requested_ids(self):
        api = FakeApi({
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a/IMG_1.jpg")]),
        })
        fresh, removed = refresh_matches(api, {"p1"}, "host", "user", use_cache=False, progress=lambda m: None)
        self.assertEqual(api.fetched, ["p1"])
        self.assertEqual(set(fresh.keys()), {"p1"})
        self.assertEqual(removed, 0)

    def test_a_match_no_longer_live_is_reported_as_removed(self):
        api = FakeApi({"p1": None})  # 404 -- deleted since the cache was built
        fresh, removed = refresh_matches(api, {"p1"}, "host", "user", use_cache=False, progress=lambda m: None)
        self.assertEqual(fresh, {})
        self.assertEqual(removed, 1)

    def test_patches_only_the_matched_entry_into_the_live_cache_leaving_others_alone(self):
        library_cache.merge_updates("host", "user", {
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "old-name.jpg")]),
            "p2": _detail("2018-01-01T00:00:00.000Z", [_rendition("r2", "untouched.jpg")]),
        })
        api = FakeApi({"p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "new-name.jpg")])})

        refresh_matches(api, {"p1"}, "host", "user", use_cache=True, progress=lambda m: None)

        cached = library_cache._load_all("host", "user")["live"]
        self.assertEqual(cached["p1"]["renditions"][0]["path"], "new-name.jpg")
        self.assertEqual(cached["p2"]["renditions"][0]["path"], "untouched.jpg")

    def test_removes_a_no_longer_reachable_match_from_the_cache_too(self):
        library_cache.merge_updates("host", "user", {
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "gone.jpg")]),
        })
        api = FakeApi({"p1": None})

        refresh_matches(api, {"p1"}, "host", "user", use_cache=True, progress=lambda m: None)

        cached = library_cache._load_all("host", "user")["live"]
        self.assertNotIn("p1", cached)

    def test_use_cache_false_never_touches_disk(self):
        api = FakeApi({"p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a.jpg")])})
        refresh_matches(api, {"p1"}, "host", "user", use_cache=False, progress=lambda m: None)
        self.assertIsNone(library_cache._load_all("host", "user"))

    def test_a_match_that_turns_out_trashed_is_written_to_the_trashed_section(self):
        # Cached as "live" originally; the refresh reveals it's actually since
        # been trashed elsewhere -- the cache entry should move sections, not
        # just update in place under the wrong one.
        library_cache.merge_updates("host", "user", {
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a.jpg")]),
        }, section="live")
        api = FakeApi({
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a.jpg")],
                          deletedAt="2026-01-01T00:00:00.000Z", deletedBy="dedupe_by_filename.py"),
        })

        refresh_matches(api, {"p1"}, "host", "user", use_cache=True, progress=lambda m: None)

        cached = library_cache._load_all("host", "user")
        self.assertIn("p1", cached["trashed"])
        self.assertNotIn("p1", cached["live"])

    def test_a_match_that_turns_out_restored_is_written_back_to_the_live_section(self):
        library_cache.merge_updates("host", "user", {
            "p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a.jpg")],
                          deletedAt="2026-01-01T00:00:00.000Z"),
        }, section="trashed")
        api = FakeApi({"p1": _detail("2018-01-01T00:00:00.000Z", [_rendition("r1", "a.jpg")])})  # no deletedAt now

        refresh_matches(api, {"p1"}, "host", "user", use_cache=True, progress=lambda m: None)

        cached = library_cache._load_all("host", "user")
        self.assertIn("p1", cached["live"])
        self.assertNotIn("p1", cached["trashed"])


if __name__ == "__main__":
    unittest.main()
