"""places.py: gazetteer + region packs, per tools/geopack/README.md "Combining with the
gazetteer". Uses the synthetic fixture packs and a stand-in gazetteer; no network."""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import places

REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
TESTDATA = os.path.join(REPO, "tools", "geopack", "testdata")
GAZ_SRC = "geonames-cities500@2026-11-13"
FR = {"id": "FR.84", "name": "Auvergne-Rhône-Alpes"}


class FakeGazetteer:
    src = GAZ_SRC

    def lookup(self, lat, lon):
        if lat > 60:  # "more than 100 km from any town"
            return None
        return {"cc": "FR", "region": FR, "locality": {"id": "3000000", "name": "Gazetteer Town"}, "src": GAZ_SRC}


class FakeHttp:
    """Serves the fixture manifest and packs from https://photos.example.com/geo/."""

    def __init__(self, manifest_status=200, broken=()):
        self.manifest_status = manifest_status
        self.broken = set(broken)
        self.calls: list[str] = []
        self.lock = threading.Lock()

    def __call__(self, url: str) -> bytes:
        with self.lock:
            self.calls.append(url)
        prefix = "https://photos.example.com/geo/"
        assert url.startswith(prefix), url
        name = url[len(prefix):]
        if name == "manifest.json":
            if self.manifest_status != 200:
                raise urllib.error.HTTPError(url, self.manifest_status, "nope", {}, io.BytesIO())
            name = "fixture-manifest.json"
        if name in self.broken:
            raise urllib.error.URLError("connection reset")
        with open(os.path.join(TESTDATA, name), "rb") as f:
            return f.read()


def geocoder(http: FakeHttp, cache_dir: str) -> places.Geocoder:
    manifest = places.fetch_manifest("photos.example.com", get=http)
    if manifest is None:
        return places.Geocoder(FakeGazetteer())
    return places.Geocoder(FakeGazetteer(), manifest,
                           places.PackStore("https://photos.example.com/geo", cache_dir, get=http))


class CombiningWithTheGazetteer(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_no_manifest_is_the_gazetteer_alone(self):
        for status in (403, 404):
            with self.subTest(status=status):
                g = geocoder(FakeHttp(manifest_status=status), self.tmp.name)
                self.assertEqual(g.src, GAZ_SRC)
                self.assertEqual(g.lookup(45.005, 5.005), FakeGazetteer().lookup(0, 0))

    def test_other_manifest_errors_are_raised(self):
        with self.assertRaises(urllib.error.HTTPError):
            places.fetch_manifest("photos.example.com", get=FakeHttp(manifest_status=500))

    def test_covered_point_gets_the_pack_layers_in_api_field_order(self):
        g = geocoder(FakeHttp(), self.tmp.name)
        self.assertEqual(g.src, f"osm@fixture;{GAZ_SRC}")
        place = g.lookup(45.0110, 5.0110)
        self.assertEqual(place, {
            "cc": "FR",
            "region": FR,
            "locality": {"id": "r101", "name": "Alpha"},
            "sub": {"id": "n9003", "name": "Brook"},
            "spot": {"id": "n9005", "name": "Nook"},
            "areas": [{"id": "r201", "name": "Great Park"}, {"id": "r204", "name": "Wide Zone"}],
            "src": f"osm@fixture;{GAZ_SRC}",
        })
        self.assertEqual(list(place), ["cc", "region", "locality", "sub", "spot", "areas", "src"])

    def test_covered_point_with_no_pack_locality_keeps_the_gazetteers(self):
        place = geocoder(FakeHttp(), self.tmp.name).lookup(45.0250, 5.3150)  # vector no-locality-cap
        self.assertEqual(place["locality"], {"id": "3000000", "name": "Gazetteer Town"})
        self.assertEqual(place["feature"], {"id": "w309", "name": "Lone Glacier"})
        self.assertEqual(place["src"], f"osm@fixture;{GAZ_SRC}")

    def test_uncovered_point_is_the_gazetteer_with_the_pack_set_src(self):
        place = geocoder(FakeHttp(), self.tmp.name).lookup(46.0, 6.0)
        self.assertEqual(place["locality"]["name"], "Gazetteer Town")
        self.assertEqual(place["src"], f"osm@fixture;{GAZ_SRC}")

    def test_no_gazetteer_place_is_no_place(self):
        self.assertIsNone(geocoder(FakeHttp(), self.tmp.name).lookup(70.0, 5.0))

    def test_unavailable_pack_falls_back_to_the_gazetteers_own_src(self):
        g = geocoder(FakeHttp(broken={"fixture-b.bin"}), self.tmp.name)
        place = g.lookup(45.0110, 5.0110)  # covered by both packs; B can't be had
        self.assertEqual(place, FakeGazetteer().lookup(0, 0))
        # Covered by A alone (north of B's bbox): still fine.
        self.assertEqual(g.lookup(45.1100, 5.0000)["src"], f"osm@fixture;{GAZ_SRC}")

    def test_tampered_pack_is_rejected(self):
        http = FakeHttp()
        manifest = places.fetch_manifest("photos.example.com", get=http)
        manifest["packs"][0]["sha256"] = "0" * 64
        g = places.Geocoder(FakeGazetteer(), manifest, places.PackStore("https://photos.example.com/geo", self.tmp.name, get=http))
        self.assertEqual(g.lookup(45.0050, 5.3500)["src"], GAZ_SRC)

    def test_packs_are_cached_by_sha256(self):
        http = FakeHttp()
        geocoder(http, self.tmp.name).lookup(45.0110, 5.0110)
        fetched = [c for c in http.calls if c.endswith(".bin")]
        self.assertEqual(len(fetched), 2)
        http2 = FakeHttp()
        geocoder(http2, self.tmp.name).lookup(45.0110, 5.0110)
        self.assertEqual([c for c in http2.calls if c.endswith(".bin")], [])

    def test_threads_share_one_download(self):
        http = FakeHttp()
        g = geocoder(http, self.tmp.name)
        results = []
        threads = [threading.Thread(target=lambda: results.append(g.lookup(45.0110, 5.0110))) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(len({json.dumps(r, sort_keys=True) for r in results}), 1)
        self.assertEqual(len([c for c in http.calls if c.endswith(".bin")]), 2)

    def test_manifest_validation(self):
        good = json.load(open(os.path.join(TESTDATA, "fixture-manifest.json"), encoding="utf-8"))
        for label, mutate in [
            ("format", lambda m: m.update(format=2)),
            ("set", lambda m: m.update(set="a b")),
            ("path escapes", lambda m: m["packs"][0].update(path="../x.bin")),
            ("bbox", lambda m: m["packs"][0].update(bbox=[1, 2, 0, 3])),
            ("duplicate name", lambda m: m["packs"].append(dict(m["packs"][0]))),
        ]:
            m = json.loads(json.dumps(good))
            mutate(m)
            with self.subTest(label), self.assertRaises(ValueError):
                places.validate_manifest(m)

    def test_describe(self):
        place = geocoder(FakeHttp(), self.tmp.name).lookup(45.0110, 5.0110)
        self.assertEqual(places.describe(place),
                         "Nook, Brook, Alpha, Auvergne-Rhône-Alpes, FR · Great Park · Wide Zone")


if __name__ == "__main__":
    unittest.main()
