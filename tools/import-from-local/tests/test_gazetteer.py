"""gazetteer.py against the shared conformance vectors (geolocation plan D10): the same
`tools/gazetteer/vectors.json` the Android `ReverseGeocoderTest` passes, read from the
same `gazetteer.bin` the app bundles."""

from __future__ import annotations

import json
import os
import random
import sys
import time
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import gazetteer as gz

REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
VECTORS = os.path.join(REPO, "tools", "gazetteer", "vectors.json")
STANLEY = (-51.69382, -57.85701)  # Falkland Islands: GeoNames has no admin1 for it


def _load_vectors() -> dict:
    with open(VECTORS, encoding="utf-8") as f:
        return json.load(f)


class SharedVectors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.g = gz.load()
        cls.doc = _load_vectors()

    def test_every_vector(self):
        self.assertGreaterEqual(len(self.doc["vectors"]), 34)
        for v in self.doc["vectors"]:
            with self.subTest(id=v["id"]):
                self.assertEqual(self.g.lookup(v["lat"], v["lon"]), v["place"])

    def test_src_and_thresholds_match_the_vector_file(self):
        self.assertEqual(self.g.src, self.doc["src"])
        self.assertEqual(gz.LOCALITY_MAX_KM, self.doc["localityMaxKm"])
        self.assertEqual(gz.REGION_MAX_KM, self.doc["regionMaxKm"])

    def test_out_of_range_and_non_finite_inputs_give_none(self):
        for lat, lon in [(90.5, 0.0), (-91.0, 0.0), (float("nan"), 0.0), (0.0, float("inf"))]:
            with self.subTest(lat=lat, lon=lon):
                self.assertIsNone(self.g.lookup(lat, lon))

    def test_longitude_is_wrapped(self):
        lat, lon = 35.01161, 135.76804
        self.assertEqual(self.g.lookup(lat, lon + 360.0), self.g.lookup(lat, lon))
        self.assertEqual(self.g.lookup(lat, lon - 720.0), self.g.lookup(lat, lon))


class AgreesWithTheReference(unittest.TestCase):
    """The prune must not change a result. reference.py's ring lookup (which now has
    the same prune) is checked against its own brute force by make_vectors.py; here
    this reader is checked against that ring lookup on random points, half of them
    above 85° (the case the prune exists for), and against the brute force on two
    polar points."""

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, os.path.join(REPO, "tools", "gazetteer"))
        import reference  # noqa: PLC0415

        with open(gz.resolve_path(), "rb") as f:
            data = f.read()
        cls.ref = reference.Gazetteer(data)
        cls.g = gz.Gazetteer(data)

    def test_random_points_including_polar(self):
        rng = random.Random(5)
        points = [(rng.uniform(-90, 90), rng.uniform(-180, 180)) for _ in range(150)]
        points += [(rng.choice([1, -1]) * rng.uniform(85, 90), rng.uniform(-180, 180)) for _ in range(150)]
        for lat, lon in points:
            with self.subTest(lat=lat, lon=lon):
                self.assertEqual(self.g.lookup(lat, lon), self.ref.place(self.ref.lookup(lat, lon)))

    def test_polar_points_against_brute_force(self):
        for lat, lon in [(89.5, 0.0), (-89.2, 120.0)]:
            with self.subTest(lat=lat, lon=lon):
                self.assertEqual(self.g.lookup(lat, lon), self.ref.place(self.ref.brute(lat, lon)))


class PolarPerformance(unittest.TestCase):
    def test_polar_lookup_does_not_scan_the_globe(self):
        # Before the row prune, reference.py took about 1.2 s per lookup above 89°.
        g = gz.load()
        start = time.perf_counter()
        for lat in (89.5, 89.9, -89.5, -89.99):
            g.lookup(lat, 10.0)
        self.assertLess((time.perf_counter() - start) / 4, 0.1)


class Locating(unittest.TestCase):
    def test_default_is_the_bundled_android_asset(self):
        self.assertTrue(gz.DEFAULT_PATH.endswith(os.path.join("assets", "gazetteer.bin")))
        self.assertTrue(os.path.exists(gz.DEFAULT_PATH))

    def test_env_var_overrides_default_and_explicit_overrides_env(self):
        with mock.patch.dict(os.environ, {gz.ENV_VAR: "/from/env.bin"}):
            self.assertEqual(gz.resolve_path(), "/from/env.bin")
            self.assertEqual(gz.resolve_path("/explicit.bin"), "/explicit.bin")
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(gz.resolve_path(), gz.DEFAULT_PATH)

    def test_missing_file_names_the_flag_and_env_var(self):
        with self.assertRaises(FileNotFoundError) as cm:
            gz.load("/nonexistent/gazetteer.bin")
        self.assertIn("--gazetteer", str(cm.exception))
        self.assertIn(gz.ENV_VAR, str(cm.exception))

    def test_rejects_a_file_that_is_not_a_gazetteer(self):
        with self.assertRaises(ValueError):
            gz.Gazetteer(b"\x00" * 64)


class NoRegionLocality(unittest.TestCase):
    def test_locality_without_region_is_kept(self):
        # The server accepts {cc, locality}; the importer sends the lookup as-is.
        place = gz.load().lookup(*STANLEY)
        self.assertEqual(place, {"cc": "FK", "locality": {"id": "3426691", "name": "Stanley"}, "src": place["src"]})


if __name__ == "__main__":
    unittest.main()
