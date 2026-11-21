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
    packs = geopack.load_manifest_packs(manifest, lambda p: open(os.path.join(base, p), "rb").read())
    return doc, manifest, packs


class SharedVectors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc, cls.manifest, cls.packs = load_fixture()

    def test_every_vector(self):
        self.assertGreaterEqual(len(self.doc["vectors"]), 32)
        for v in self.doc["vectors"]:
            with self.subTest(name=v["name"]):
                self.assertEqual(geopack.lookup_with_manifest(self.manifest, self.packs, v["lat"], v["lon"]), v["expect"])

    def test_header(self):
        a = self.packs[0]
        self.assertEqual((a.set, a.name), ("fixture", "fixture-a"))
        self.assertEqual(a.attribution, "© OpenStreetMap contributors, ODbL 1.0")
        self.assertEqual(list(a.bbox), self.manifest["packs"][0]["bbox"])

    def test_index_agrees_with_brute_force(self):
        rng = random.Random(7)
        ps = geopack.PackSet(self.packs)
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
