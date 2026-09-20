from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dedupe_by_filename import (
    AssetInfo,
    DuplicateGroup,
    execute_deletes,
    find_duplicate_groups,
    revalidate_before_delete,
)


def _asset(photo_id, filename="IMG_1.jpg", taken_at="2018-07-13T14:02:25.000Z", size=1000, renditions=1, roles=None):
    return AssetInfo(
        photo_id=photo_id,
        taken_at=taken_at,
        filename=filename,
        width=100,
        height=100,
        max_plain_bytes=size,
        rendition_count=renditions,
        rendition_roles=roles or ["display"] * renditions,
    )


class NoDuplicates(unittest.TestCase):
    def test_unique_filenames_produce_no_groups(self):
        assets = [_asset("a", filename="one.jpg"), _asset("b", filename="two.jpg")]
        self.assertEqual(find_duplicate_groups(assets, include_multi_rendition=False), [])

    def test_same_filename_different_timestamp_is_not_a_duplicate(self):
        assets = [
            _asset("a", taken_at="2018-01-01T00:00:00.000Z"),
            _asset("b", taken_at="2018-01-02T00:00:00.000Z"),
        ]
        self.assertEqual(find_duplicate_groups(assets, include_multi_rendition=False), [])


class KeepsLargest(unittest.TestCase):
    def test_largest_plain_bytes_wins(self):
        small = _asset("small", size=500)
        big = _asset("big", size=900_000)
        medium = _asset("medium", size=50_000)
        groups = find_duplicate_groups([small, big, medium], include_multi_rendition=False)
        self.assertEqual(len(groups), 1)
        g = groups[0]
        self.assertEqual(g.keep.photo_id, "big")
        self.assertEqual({a.photo_id for a in g.delete}, {"small", "medium"})

    def test_only_two_way_duplicate(self):
        loser = _asset("loser", size=1)
        winner = _asset("winner", size=2)
        groups = find_duplicate_groups([loser, winner], include_multi_rendition=False)
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0].keep.photo_id, "winner")
        self.assertEqual([a.photo_id for a in groups[0].delete], ["loser"])


class MultiRenditionGuard(unittest.TestCase):
    def test_multi_rendition_loser_is_skipped_by_default(self):
        winner = _asset("winner", size=900_000)
        loser = _asset("loser", size=100, renditions=2, roles=["raw", "display"])
        groups = find_duplicate_groups([winner, loser], include_multi_rendition=False)
        self.assertEqual(len(groups), 1)
        g = groups[0]
        self.assertEqual(g.delete, [])
        self.assertEqual([a.photo_id for a in g.skipped_multi_rendition], ["loser"])

    def test_multi_rendition_loser_included_when_opted_in(self):
        winner = _asset("winner", size=900_000)
        loser = _asset("loser", size=100, renditions=2, roles=["raw", "display"])
        groups = find_duplicate_groups([winner, loser], include_multi_rendition=True)
        self.assertEqual(len(groups), 1)
        g = groups[0]
        self.assertEqual([a.photo_id for a in g.delete], ["loser"])
        self.assertEqual(g.skipped_multi_rendition, [])

    def test_multi_rendition_winner_is_never_flagged(self):
        # The KEEP side is never a candidate for skip/delete regardless of its own
        # rendition count -- only losers are ever considered for deletion.
        winner = _asset("winner", size=900_000, renditions=2, roles=["raw", "display"])
        loser = _asset("loser", size=100)
        groups = find_duplicate_groups([winner, loser], include_multi_rendition=False)
        self.assertEqual(groups[0].keep.photo_id, "winner")
        self.assertEqual([a.photo_id for a in groups[0].delete], ["loser"])


class ThreeWayGroup(unittest.TestCase):
    def test_mixed_single_and_multi_rendition_losers(self):
        winner = _asset("winner", size=900_000)
        single_loser = _asset("single", size=500)
        multi_loser = _asset("multi", size=1000, renditions=2, roles=["raw", "display"])
        groups = find_duplicate_groups([winner, single_loser, multi_loser], include_multi_rendition=False)
        self.assertEqual(len(groups), 1)
        g = groups[0]
        self.assertEqual(g.keep.photo_id, "winner")
        self.assertEqual([a.photo_id for a in g.delete], ["single"])
        self.assertEqual([a.photo_id for a in g.skipped_multi_rendition], ["multi"])


def _fresh_detail(taken_at="2018-07-13T14:02:25.000Z", path="a/IMG_1.jpg", plain_bytes=1000, renditions=None):
    rends = renditions or [{"renditionId": "r1", "role": "display", "path": path, "plainBytes": plain_bytes}]
    return {
        "meta": {"takenAt": taken_at, "primaryRend": rends[0]["renditionId"]},
        "renditions": rends,
        "facets": [],
    }


class FakeApi:
    def __init__(self, details: dict[str, dict | None], raise_on_detail: set[str] = frozenset(),
                 raise_on_delete: set[str] = frozenset()):
        self.details = details
        self.deleted: list[str] = []
        self._raise_on_detail = raise_on_detail
        self._raise_on_delete = raise_on_delete

    def get_photo_detail(self, photo_id: str):
        if photo_id in self._raise_on_detail:
            raise TimeoutError(f"simulated network failure for {photo_id}")
        return self.details.get(photo_id)

    def delete_photo(self, photo_id: str, deleted_by=None):
        if photo_id in self._raise_on_delete:
            raise TimeoutError(f"simulated network failure deleting {photo_id}")
        self.deleted.append(photo_id)


class RevalidateBeforeDelete(unittest.TestCase):
    def _group(self, keep_id="keep", loser_id="loser"):
        keep = _asset(keep_id, size=900_000)
        loser = _asset(loser_id, size=500)
        return DuplicateGroup("IMG_1.jpg", "2018-07-13T14:02:25.000Z", keep, [loser], [])

    def test_unchanged_group_is_confirmed_safe(self):
        group = self._group()
        api = FakeApi({
            "keep": _fresh_detail(plain_bytes=900_000),
            "loser": _fresh_detail(plain_bytes=500),
        })
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual([a.photo_id for a in safe], ["loser"])
        self.assertEqual(warnings, [])

    def test_candidate_already_gone_is_silently_skipped(self):
        group = self._group()
        api = FakeApi({"keep": _fresh_detail(plain_bytes=900_000), "loser": None})
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual(safe, [])
        self.assertEqual(warnings, [])  # already achieved, nothing to warn about

    def test_candidate_renamed_since_scan_is_not_deleted(self):
        group = self._group()
        api = FakeApi({
            "keep": _fresh_detail(plain_bytes=900_000),
            "loser": _fresh_detail(path="a/renamed.jpg", plain_bytes=500),
        })
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual(safe, [])
        self.assertEqual(len(warnings), 1)
        self.assertIn("loser", warnings[0])
        self.assertIn("renamed", warnings[0])

    def test_candidate_no_longer_smaller_is_not_deleted(self):
        # e.g. the "keep" asset shrank, or the candidate grew, since the cached scan
        group = self._group()
        api = FakeApi({
            "keep": _fresh_detail(plain_bytes=100),
            "loser": _fresh_detail(plain_bytes=500),
        })
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual(safe, [])
        self.assertEqual(len(warnings), 1)
        self.assertIn("no longer smaller", warnings[0])

    def test_keep_asset_gone_skips_the_whole_group(self):
        group = self._group()
        api = FakeApi({"keep": None, "loser": _fresh_detail(plain_bytes=500)})
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual(safe, [])
        self.assertEqual(len(warnings), 1)
        self.assertIn("KEEP", warnings[0])

    def test_keep_asset_renamed_skips_the_whole_group(self):
        group = self._group()
        api = FakeApi({
            "keep": _fresh_detail(path="a/renamed-keep.jpg", plain_bytes=900_000),
            "loser": _fresh_detail(plain_bytes=500),
        })
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual(safe, [])
        self.assertEqual(len(warnings), 1)
        self.assertIn("KEEP", warnings[0])

    def test_only_the_still_valid_candidate_survives_in_a_multi_loser_group(self):
        keep = _asset("keep", size=900_000)
        loser_a = _asset("loser-a", size=500)
        loser_b = _asset("loser-b", size=400)
        group = DuplicateGroup("IMG_1.jpg", "2018-07-13T14:02:25.000Z", keep, [loser_a, loser_b], [])
        api = FakeApi({
            "keep": _fresh_detail(plain_bytes=900_000),
            "loser-a": _fresh_detail(path="a/renamed.jpg", plain_bytes=500),  # renamed away
            "loser-b": _fresh_detail(plain_bytes=400),  # still valid
        })
        safe, warnings = revalidate_before_delete(api, group)
        self.assertEqual([a.photo_id for a in safe], ["loser-b"])
        self.assertEqual(len(warnings), 1)
        self.assertIn("loser-a", warnings[0])


def _group(name_suffix, keep_id=None, loser_id=None):
    keep_id = keep_id or f"keep-{name_suffix}"
    loser_id = loser_id or f"loser-{name_suffix}"
    keep = _asset(keep_id, filename=f"IMG_{name_suffix}.jpg", size=900_000)
    loser = _asset(loser_id, filename=f"IMG_{name_suffix}.jpg", size=500)
    return DuplicateGroup(f"IMG_{name_suffix}.jpg", "2018-07-13T14:02:25.000Z", keep, [loser], [])


class ExecuteDeletes(unittest.TestCase):
    """Covers the exact real bug this guards against: revalidate_before_delete
    raising (api_client.py's retries exhausted, a sustained outage) used to
    have nothing catching it in the execute loop, so one bad group crashed the
    whole run -- reported live against a 2,242-group run that died on group 1."""

    def test_all_groups_succeed(self):
        groups = [_group("a"), _group("b")]
        details = {}
        for g in groups:
            details[g.keep.photo_id] = _fresh_detail(path=g.filename, plain_bytes=900_000)
            details[g.delete[0].photo_id] = _fresh_detail(path=g.filename, plain_bytes=500)
        api = FakeApi(details)

        deleted, errors, warnings = execute_deletes(api, groups, lambda m: None)
        self.assertEqual(deleted, 2)
        self.assertEqual(errors, [])
        self.assertEqual(sorted(api.deleted), sorted(g.delete[0].photo_id for g in groups))

    def test_one_group_failing_revalidation_does_not_abort_the_run(self):
        good1, bad, good2 = _group("a"), _group("b"), _group("c")
        groups = [good1, bad, good2]
        details = {}
        for g in (good1, good2):
            details[g.keep.photo_id] = _fresh_detail(path=g.filename, plain_bytes=900_000)
            details[g.delete[0].photo_id] = _fresh_detail(path=g.filename, plain_bytes=500)
        api = FakeApi(details, raise_on_detail={bad.keep.photo_id})

        deleted, errors, warnings = execute_deletes(api, groups, lambda m: None)
        # The two healthy groups still got processed -- this is the actual bug fix.
        self.assertEqual(deleted, 2)
        self.assertEqual(sorted(api.deleted), sorted([good1.delete[0].photo_id, good2.delete[0].photo_id]))
        # The failing group is reported, not silently dropped.
        self.assertEqual(len(errors), 1)
        self.assertIn(bad.filename, errors[0][0])

    def test_one_delete_call_failing_does_not_abort_the_run(self):
        good, bad = _group("a"), _group("b")
        groups = [good, bad]
        details = {}
        for g in (good, bad):
            details[g.keep.photo_id] = _fresh_detail(path=g.filename, plain_bytes=900_000)
            details[g.delete[0].photo_id] = _fresh_detail(path=g.filename, plain_bytes=500)
        api = FakeApi(details, raise_on_delete={bad.delete[0].photo_id})

        deleted, errors, warnings = execute_deletes(api, groups, lambda m: None)
        self.assertEqual(deleted, 1)
        self.assertEqual(api.deleted, [good.delete[0].photo_id])
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0][0], bad.delete[0].photo_id)

    def test_progress_is_reported(self):
        groups = [_group(str(i)) for i in range(3)]
        details = {}
        for g in groups:
            details[g.keep.photo_id] = _fresh_detail(path=g.filename, plain_bytes=900_000)
            details[g.delete[0].photo_id] = _fresh_detail(path=g.filename, plain_bytes=500)
        api = FakeApi(details)

        messages = []
        execute_deletes(api, groups, messages.append)
        self.assertTrue(any("3/3" in m for m in messages))


if __name__ == "__main__":
    unittest.main()
