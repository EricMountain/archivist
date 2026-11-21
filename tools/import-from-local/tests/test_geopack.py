"""geopack.py against the shared region-pack vectors (tools/geopack/vectors.json), the
same ones the Android reader passes, over the synthetic fixture packs."""

from __future__ import annotations

import json
import os
import random
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import geopack

REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
GEOPACK = os.path.join(REPO, "tools", "geopack")


def load_fixture():
    with open(os.path.join(GEOPACK, "vectors.json"), encoding="utf-8") as f:
        doc = json.load(f)
    manifest_path = os.path.join(GEOPACK, doc["manifest"])
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    base = os.path.dirname(manifest_path)
    region = [geopack.read_pack(open(os.path.join(GEOPACK, p), "rb").read()) for p in doc["regionPacks"]]
    return doc, manifest, base, region


class SharedVectors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc, cls.manifest, cls.base, cls.region = load_fixture()

    def read(self, path):
        with open(os.path.join(self.base, path), "rb") as f:
            return f.read()

    def test_every_vector_through_its_tile(self):
        self.assertGreaterEqual(len(self.doc["vectors"]), 32)
        for v in self.doc["vectors"]:
            with self.subTest(name=v["name"]):
                self.assertEqual(geopack.find_tile(self.manifest["tiles"].keys(), v["lat"], v["lon"]), v["tile"])
                self.assertEqual(geopack.lookup_with_manifest(self.manifest, self.read, v["lat"], v["lon"]), v["expect"])

    def test_every_vector_over_the_merged_region_packs(self):
        ps = geopack.PackSet(self.region)
        for v in self.doc["vectors"]:
            with self.subTest(name=v["name"]):
                self.assertEqual(ps.lookup(v["lat"], v["lon"]), v["expect"])

    def test_grid_keys(self):
        self.assertGreaterEqual(len(self.doc["keys"]), 8)
        for k in self.doc["keys"]:
            with self.subTest(k=k):
                self.assertEqual(geopack.point_key(k["lat"], k["lon"], k["depth"]), k["key"])

    def test_headers(self):
        a = self.region[0]
        self.assertEqual((a.set, a.name), ("fixture", "fixture-a"))
        self.assertEqual(a.attribution, "© OpenStreetMap contributors, ODbL 1.0")
        for key in self.manifest["tiles"]:
            t = geopack.read_pack(self.read(f"fixture/{key}.bin"))
            self.assertEqual((t.set, t.name), ("fixture", key))

    def test_index_agrees_with_brute_force(self):
        rng = random.Random(7)
        ps = geopack.PackSet(self.region)
        for _ in range(500):
            lat, lon = rng.uniform(44.9, 45.15), rng.uniform(4.9, 5.55)
            self.assertEqual(ps.lookup(lat, lon), ps.brute(lat, lon))

    def test_rejects_a_damaged_pack(self):
        data = bytearray(open(os.path.join(GEOPACK, "testdata", "fixture-b.bin"), "rb").read())
        with self.assertRaises(ValueError):
            geopack.read_pack(bytes(data[:-1]))  # fileLength mismatch
        data[4] = 2
        with self.assertRaises(ValueError):
            geopack.read_pack(bytes(data))  # unknown version


if __name__ == "__main__":
    unittest.main()
