from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import library_cache


class FakeApi:
    """Enough of ArchivistApi for library_scan's calls -- one page, no cursor,
    and a fetch/listing counter so tests can assert exactly what got called.
    `live_ids`/`trashed_ids` back the two different listing routes."""

    def __init__(self, live_ids: list[str] = (), trashed_ids: list[str] = (), details: dict[str, dict] = None):
        self.live_ids = list(live_ids)
        self.trashed_ids = list(trashed_ids)
        self.details = details or {}
        self.fetched: list[str] = []
        self.listing_calls = 0

    def get_photos_page(self, cursor=None):
        self.listing_calls += 1
        return {"items": [{"photoId": pid} for pid in self.live_ids], "cursor": None}

    def get_trash_page(self, cursor=None):
        self.listing_calls += 1
        return {"items": [{"photoId": pid} for pid in self.trashed_ids], "cursor": None}

    def get_photo_detail(self, photo_id: str):
        self.fetched.append(photo_id)
        return self.details.get(photo_id)


def _detail(path="a/IMG_1.jpg"):
    return {"meta": {"takenAt": "2018-01-01T00:00:00.000Z"}, "renditions": [{"path": path}], "facets": []}


class SnapshotRoundTrip(unittest.TestCase):
    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self._orig_cache_dir = library_cache.CACHE_DIR
        library_cache.CACHE_DIR = self._tmpdir.name

    def tearDown(self):
        library_cache.CACHE_DIR = self._orig_cache_dir
        self._tmpdir.cleanup()

    def test_first_run_has_no_cache_and_fetches_everything_live(self):
        # No choice here regardless of flags -- there's nothing to read yet.
        api = FakeApi(live_ids=["p1", "p2"], details={"p1": _detail(), "p2": _detail()})
        snapshot, stats = library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")
        self.assertEqual(set(snapshot.keys()), {"p1", "p2"})
        self.assertEqual(stats.from_cache, 0)
        self.assertEqual(stats.fetched_fresh, 2)
        self.assertTrue(stats.listing_checked_live)
        self.assertEqual(api.listing_calls, 1)
        self.assertEqual(sorted(api.fetched), ["p1", "p2"])

    def test_default_second_run_is_a_pure_cache_read_no_live_calls_at_all(self):
        details = {"p1": _detail(), "p2": _detail()}
        api1 = FakeApi(live_ids=["p1", "p2"], details=details)
        library_cache.get_snapshot(api1, "host", "user", lambda m: None, section="live")

        api2 = FakeApi(live_ids=["p1", "p2"], details=details)
        snapshot, stats = library_cache.get_snapshot(api2, "host", "user", lambda m: None, section="live")
        self.assertEqual(set(snapshot.keys()), {"p1", "p2"})
        self.assertEqual(stats.from_cache, 2)
        self.assertEqual(stats.fetched_fresh, 0)
        self.assertFalse(stats.listing_checked_live)
        self.assertEqual(api2.listing_calls, 0)  # the cheap listing itself wasn't even called
        self.assertEqual(api2.fetched, [])

    def test_default_does_not_notice_a_photo_added_since_the_cache_was_built(self):
        # Documents the actual tradeoff: without --refresh-listing, a genuinely
        # new photo is invisible until the next refresh.
        api1 = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        library_cache.get_snapshot(api1, "host", "user", lambda m: None, section="live")

        api2 = FakeApi(live_ids=["p1", "p2"], details={"p1": _detail(), "p2": _detail("b/IMG_2.jpg")})
        snapshot, stats = library_cache.get_snapshot(api2, "host", "user", lambda m: None, section="live")
        self.assertEqual(set(snapshot.keys()), {"p1"})  # p2 not seen
        self.assertEqual(api2.listing_calls, 0)

    def test_refresh_listing_fetches_only_the_new_photo(self):
        api1 = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        library_cache.get_snapshot(api1, "host", "user", lambda m: None, section="live")

        api2 = FakeApi(live_ids=["p1", "p2"], details={"p1": _detail(), "p2": _detail("b/IMG_2.jpg")})
        snapshot, stats = library_cache.get_snapshot(
            api2, "host", "user", lambda m: None, section="live", refresh_listing=True
        )
        self.assertEqual(set(snapshot.keys()), {"p1", "p2"})
        self.assertEqual(stats.from_cache, 1)
        self.assertEqual(stats.fetched_fresh, 1)
        self.assertTrue(stats.listing_checked_live)
        self.assertEqual(api2.listing_calls, 1)
        self.assertEqual(api2.fetched, ["p2"])  # p1 reused from cache, not re-fetched

    def test_refresh_listing_drops_a_photo_no_longer_in_this_section(self):
        api1 = FakeApi(live_ids=["p1", "p2"], details={"p1": _detail(), "p2": _detail()})
        library_cache.get_snapshot(api1, "host", "user", lambda m: None, section="live")

        api2 = FakeApi(live_ids=["p1"], details={"p1": _detail()})  # p2 trashed/purged elsewhere
        snapshot, stats = library_cache.get_snapshot(
            api2, "host", "user", lambda m: None, section="live", refresh_listing=True
        )
        self.assertEqual(set(snapshot.keys()), {"p1"})
        self.assertEqual(stats.dropped_stale, 1)

    def test_refresh_cache_refetches_everything_even_if_present(self):
        api1 = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        library_cache.get_snapshot(api1, "host", "user", lambda m: None, section="live")

        api2 = FakeApi(live_ids=["p1"], details={"p1": _detail("renamed.jpg")})
        snapshot, stats = library_cache.get_snapshot(
            api2, "host", "user", lambda m: None, section="live", refresh_cache=True
        )
        self.assertEqual(api2.listing_calls, 1)  # refresh_cache implies the live listing too
        self.assertEqual(api2.fetched, ["p1"])
        self.assertEqual(snapshot["p1"]["renditions"][0]["path"], "renamed.jpg")

    def test_no_cache_reads_and_writes_nothing_and_always_goes_live(self):
        api1 = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        library_cache.get_snapshot(api1, "host", "user", lambda m: None, section="live", use_cache=False)
        self.assertIsNone(library_cache._load_all("host", "user"))  # use_cache=False writes nothing at all

        api2 = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        snapshot, stats = library_cache.get_snapshot(
            api2, "host", "user", lambda m: None, section="live", use_cache=False
        )
        self.assertTrue(stats.listing_checked_live)
        self.assertEqual(api2.fetched, ["p1"])  # no cache was ever written, so still a fresh fetch

    def test_live_and_trashed_sections_are_completely_independent(self):
        api = FakeApi(
            live_ids=["p1"], trashed_ids=["p2"],
            details={"p1": _detail("live.jpg"), "p2": _detail("trashed.jpg")},
        )
        live, _ = library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")
        trashed, _ = library_cache.get_snapshot(api, "host", "user", lambda m: None, section="trashed")
        self.assertEqual(set(live.keys()), {"p1"})
        self.assertEqual(set(trashed.keys()), {"p2"})

        # Reading "live" again afterwards must not have lost the "trashed" write, or vice versa.
        cached = library_cache._load_all("host", "user")
        self.assertEqual(set(cached["live"].keys()), {"p1"})
        self.assertEqual(set(cached["trashed"].keys()), {"p2"})

    def test_different_host_or_username_gets_a_different_cache_file(self):
        self.assertNotEqual(
            library_cache._cache_path("host-a", "user"), library_cache._cache_path("host-b", "user")
        )
        self.assertNotEqual(
            library_cache._cache_path("host", "user-a"), library_cache._cache_path("host", "user-b")
        )

    def test_corrupt_cache_file_is_treated_as_no_cache(self):
        os.makedirs(library_cache.CACHE_DIR, exist_ok=True)
        with open(library_cache._cache_path("host", "user"), "w") as f:
            f.write("{not valid json")
        api = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        snapshot, stats = library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")
        self.assertEqual(stats.fetched_fresh, 1)  # recovered rather than crashing

    def test_old_v1_cache_file_is_treated_as_no_cache(self):
        # No "trashed" section at all -- must not be mistaken for valid.
        os.makedirs(library_cache.CACHE_DIR, exist_ok=True)
        import json

        with open(library_cache._cache_path("host", "user"), "w") as f:
            json.dump({"version": 1, "host": "host", "live": {"p1": _detail()}}, f)
        api = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        snapshot, stats = library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")
        self.assertEqual(stats.fetched_fresh, 1)  # re-scanned, not trusted as-is


class MergeUpdates(unittest.TestCase):
    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self._orig_cache_dir = library_cache.CACHE_DIR
        library_cache.CACHE_DIR = self._tmpdir.name

    def tearDown(self):
        library_cache.CACHE_DIR = self._orig_cache_dir
        self._tmpdir.cleanup()

    def test_updates_one_entry_leaves_others_untouched(self):
        api = FakeApi(live_ids=["p1", "p2"], details={"p1": _detail("old.jpg"), "p2": _detail()})
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")

        library_cache.merge_updates("host", "user", {"p1": _detail("renamed.jpg")})

        cached = library_cache._load_all("host", "user")["live"]
        self.assertEqual(cached["p1"]["renditions"][0]["path"], "renamed.jpg")
        self.assertEqual(cached["p2"]["renditions"][0]["path"], "a/IMG_1.jpg")  # untouched

    def test_none_value_removes_the_entry(self):
        api = FakeApi(live_ids=["p1", "p2"], details={"p1": _detail(), "p2": _detail()})
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")

        library_cache.merge_updates("host", "user", {"p1": None})

        cached = library_cache._load_all("host", "user")["live"]
        self.assertNotIn("p1", cached)
        self.assertIn("p2", cached)

    def test_can_add_a_brand_new_entry(self):
        api = FakeApi(live_ids=["p1"], details={"p1": _detail()})
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")

        library_cache.merge_updates("host", "user", {"p2": _detail("b.jpg")})

        cached = library_cache._load_all("host", "user")["live"]
        self.assertIn("p1", cached)
        self.assertIn("p2", cached)

    def test_works_even_with_no_cache_file_yet(self):
        library_cache.merge_updates("host", "user", {"p1": _detail()})
        cached = library_cache._load_all("host", "user")["live"]
        self.assertEqual(set(cached.keys()), {"p1"})

    def test_a_later_pure_cache_read_sees_the_merged_update(self):
        api = FakeApi(live_ids=["p1"], details={"p1": _detail("old.jpg")})
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")

        library_cache.merge_updates("host", "user", {"p1": _detail("new.jpg")})

        api2 = FakeApi(live_ids=["p1"], details={"p1": _detail("old.jpg")})  # would return stale data if actually called
        snapshot, stats = library_cache.get_snapshot(api2, "host", "user", lambda m: None, section="live")
        self.assertEqual(snapshot["p1"]["renditions"][0]["path"], "new.jpg")
        self.assertEqual(api2.fetched, [])  # confirms this was a pure cache read, not a live one

    def test_targets_the_trashed_section_when_asked(self):
        api = FakeApi(trashed_ids=["p1"], details={"p1": _detail("old.jpg")})
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="trashed")

        library_cache.merge_updates("host", "user", {"p1": _detail("new.jpg")}, section="trashed")

        cached = library_cache._load_all("host", "user")
        self.assertEqual(cached["trashed"]["p1"]["renditions"][0]["path"], "new.jpg")
        self.assertNotIn("live", cached)  # never fetched -- present-but-empty would be a different bug

    def test_updating_one_section_never_clobbers_the_other(self):
        api = FakeApi(
            live_ids=["p1"], trashed_ids=["p2"],
            details={"p1": _detail("live.jpg"), "p2": _detail("trashed.jpg")},
        )
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="live")
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="trashed")

        library_cache.merge_updates("host", "user", {"p1": _detail("live-renamed.jpg")}, section="live")

        cached = library_cache._load_all("host", "user")
        self.assertEqual(cached["live"]["p1"]["renditions"][0]["path"], "live-renamed.jpg")
        self.assertEqual(cached["trashed"]["p2"]["renditions"][0]["path"], "trashed.jpg")

    def test_a_no_op_removal_never_manufactures_a_phantom_empty_section(self):
        # Removing an id that was never cached in a section that was never
        # cached either must not leave that section looking "scanned and
        # empty" -- a later get_snapshot for it should still see "never
        # cached" (None), not "{}", or it would wrongly skip the live listing.
        library_cache.merge_updates("host", "user", {"p1": None}, section="trashed")
        cached = library_cache._load_all("host", "user") or {}
        self.assertNotIn("trashed", cached)  # absent, not present-and-empty

    def test_a_no_op_removal_leaves_an_already_existing_section_present(self):
        api = FakeApi(trashed_ids=["p1"], details={"p1": _detail()})
        library_cache.get_snapshot(api, "host", "user", lambda m: None, section="trashed")

        # Removing a *different*, never-cached id from a section that DOES
        # already exist (with p1 in it) must leave the section present, not
        # drop it because this particular update was a no-op.
        library_cache.merge_updates("host", "user", {"p2": None}, section="trashed")
        cached = library_cache._load_all("host", "user")
        self.assertIn("trashed", cached)
        self.assertIn("p1", cached["trashed"])


if __name__ == "__main__":
    unittest.main()
