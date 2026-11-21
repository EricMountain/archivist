"""locate_missing.py: inference, clustering, name resolution and the interactive
flow (scripted input, no viewer), against a tiny hand-made GeoNames fixture."""

from __future__ import annotations

import builtins
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import locate_missing as lm

SRC = "geonames-cities500@2026-11-13"

# geonameid, name, asciiname, alternatenames, lat, lon, cc, admin1, population
_ROWS = [
    (2996944, "Lyon", "Lyon", "LYS,Lione,Lyons", 45.74846, 4.84671, "FR", "84", 522969),
    (2988507, "Paris", "Paris", "Lutece,Parigi", 48.85341, 2.3488, "FR", "11", 2138551),
    (4717560, "Paris", "Paris", "", 33.66094, -95.55551, "US", "TX", 24782),
    (2980291, "Saint-Étienne", "Saint-Etienne", "St Etienne", 45.43389, 4.39, "FR", "84", 176280),
    (2886242, "Köln", "Koeln", "Cologne,Koeln", 50.93333, 6.95, "DE", "07", 1024621),
]


def _geonames_dir() -> str:
    d = tempfile.mkdtemp()
    lines = []
    for gid, name, ascii_, alt, lat, lon, cc, a1, pop in _ROWS:
        col = [str(gid), name, ascii_, alt, str(lat), str(lon), "P", "PPLA", cc, "", a1, "", "", "",
               str(pop), "", "200", "Europe/Paris", "2026-01-01"]
        lines.append("\t".join(col))
    with zipfile.ZipFile(os.path.join(d, "cities500.zip"), "w") as z:
        z.writestr("cities500.txt", "\n".join(lines) + "\n")
    with open(os.path.join(d, "admin1CodesASCII.txt"), "w", encoding="utf-8") as f:
        f.write("FR.84\tAuvergne-Rhône-Alpes\tAuvergne-Rhone-Alpes\t11071621\n"
                "FR.11\tÎle-de-France\tIle-de-France\t3012874\n"
                "US.TX\tTexas\tTexas\t4736286\n"
                "DE.07\tNorth Rhine-Westphalia\tNorth Rhine-Westphalia\t2861876\n")
    with open(os.path.join(d, "countryInfo.txt"), "w", encoding="utf-8") as f:
        f.write("#ISO\tISO3\tISO-Numeric\tfips\tCountry\n"
                "FR\tFRA\t250\tFR\tFrance\n"
                "US\tUSA\t840\tUS\tUnited States\n"
                "DE\tDEU\t276\tGM\tGermany\n")
    return d


class _StubGaz:
    src = SRC

    def lookup(self, lat, lon):
        if abs(lat - 45.75) < 0.5 and abs(lon - 4.85) < 0.5:
            return {"cc": "FR", "region": {"id": "FR.84", "name": "Auvergne-Rhône-Alpes"},
                    "locality": {"id": "2996944", "name": "Lyon"}, "src": SRC}
        if abs(lat - 48.85) < 0.5 and abs(lon - 2.35) < 0.5:
            return {"cc": "FR", "region": {"id": "FR.11", "name": "Île-de-France"},
                    "locality": {"id": "2988507", "name": "Paris"}, "src": SRC}
        return None


GEO = lm.Geo(_geonames_dir(), _StubGaz())
T0 = datetime(2014, 6, 1, 12, 0, 0)
LYON = (45.75, 4.85)
PARIS = (48.85, 2.35)


def item(rel, minutes, gps=None, src="exif"):
    return lm.Item(rel, 1000, T0 + timedelta(minutes=minutes), src, gps)


OPTS = lm.Options(bracket=timedelta(hours=4), near=timedelta(minutes=45), agree_km=15, gap=timedelta(hours=6))


class FoldTest(unittest.TestCase):
    def test_diacritics_case_punctuation(self):
        self.assertEqual(lm.fold("Saint-Étienne"), "saint etienne")
        self.assertEqual(lm.fold("  KÖLN "), "koln")
        self.assertEqual(lm.fold("Straße"), "strasse")


class SearchTest(unittest.TestCase):
    def test_partial_name(self):
        self.assertEqual(GEO.search("lyo", None)[0].res.place["locality"]["name"], "Lyon")

    def test_alternate_name(self):
        top = GEO.search("cologne", None)[0]
        self.assertEqual(top.res.place["locality"]["id"], "2886242")
        self.assertIn("as 'cologne'", top.detail)

    def test_proximity_breaks_name_ties(self):
        self.assertEqual(GEO.search("paris", None)[0].res.place["cc"], "FR")
        self.assertEqual(GEO.search("paris", (33.6, -95.5))[0].res.place["cc"], "US")

    def test_qualifier(self):
        cands = GEO.search("paris, tx", None)
        self.assertEqual([c.res.place["cc"] for c in cands], ["US"])
        self.assertEqual(GEO.search("paris, france", None)[0].res.place["cc"], "FR")

    def test_region_and_country(self):
        self.assertEqual(GEO.search("texas", None)[0].res.place, {"cc": "US", "region": {"id": "US.TX", "name": "Texas"}, "src": SRC})
        self.assertEqual(GEO.search("germany", None)[0].res.place, {"cc": "DE", "src": SRC})

    def test_place_shape_and_key_roundtrip(self):
        res = GEO.search("st etienne", None)[0].res
        self.assertEqual(res.place, {"cc": "FR", "region": {"id": "FR.84", "name": "Auvergne-Rhône-Alpes"},
                                     "locality": {"id": "2980291", "name": "Saint-Étienne"}, "src": SRC})
        self.assertEqual(GEO.from_key(res.key), res)
        self.assertEqual(lm.Resolution.from_json(json.loads(json.dumps(res.to_json()))), res)


class InferTest(unittest.TestCase):
    def anchors(self, items):
        return {it.rel: GEO.from_place(_StubGaz().lookup(*it.gps), it.gps) for it in items if it.gps}

    def test_bracketed_by_agreeing_gps(self):
        items = [item("a.jpg", 0, LYON), item("b.jpg", 150), item("c.jpg", 200, LYON)]
        got = lm.infer(items, self.anchors(items), OPTS)
        self.assertEqual(got["b.jpg"].method, "neighbour-gps")
        self.assertEqual(got["b.jpg"].basis, "c.jpg")  # the nearer one
        self.assertEqual(got["b.jpg"].res.place["locality"]["name"], "Lyon")

    def test_disagreeing_brackets_fall_back_to_near_rule(self):
        items = [item("a.jpg", 0, LYON), item("b.jpg", 30), item("c.jpg", 200, PARIS), item("d.jpg", 100)]
        got = lm.infer(items, self.anchors(items), OPTS)
        self.assertEqual(got["b.jpg"].basis, "a.jpg")  # 30 min from Lyon: near enough
        self.assertNotIn("d.jpg", got)  # 100 min from both, and they disagree: ask

    def test_mtime_only_items_never_inferred(self):
        items = [item("a.jpg", 0, LYON), item("b.jpg", 5, src="mtime")]
        self.assertEqual(lm.infer(items, self.anchors(items), OPTS), {})

    def test_date_only_filename_never_inferred(self):
        items = [item("a.jpg", 0, LYON), item("IMG-20140601-WA0000.jpg", 0, src="filename-date")]
        self.assertEqual(lm.infer(items, self.anchors(items), OPTS), {})

    def test_edited_sibling(self):
        items = [item("x/IMG_1.jpg", 0, PARIS), item("x/IMG_1-edited.jpg", 9999)]
        got = lm.infer(items, self.anchors(items), OPTS)
        self.assertEqual(got["x/IMG_1-edited.jpg"].method, "sibling-gps")


class ClusterTest(unittest.TestCase):
    def test_split_on_gap_and_mtime_apart(self):
        items = [item("a", 0), item("b", 60), item("c", 60 * 10), item("m", 30, src="mtime")]
        self.assertEqual([[i.rel for i in c] for c in lm.clusters(items, timedelta(hours=6))],
                         [["a", "b"], ["c"], ["m"]])

    def test_split_at_biggest_gap(self):
        cl = [item("a", 0), item("b", 10), item("c", 100), item("d", 110)]
        a, b = lm.split_at_biggest_gap(cl)
        self.assertEqual(([i.rel for i in a], [i.rel for i in b]), (["a", "b"], ["c", "d"]))


class SessionTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.viewer = lm.Viewer("none", self.d, os.path.join(self.d, "thumbs"))

    def session(self):
        return lm.Session(GEO, os.path.join(self.d, "state.json"), os.path.join(self.d, "aliases.json"), self.viewer)

    def run_session(self, todo, knowns, inputs, sheet=24):
        s = self.session()
        feed = iter(inputs)
        with mock.patch.object(builtins, "input", lambda *_: next(feed)), \
                mock.patch("sys.stdout", new=io.StringIO()):
            finished = s.run(todo, knowns, sheet)
        self.assertEqual(list(feed), [], "not every scripted input was consumed")
        return s, finished

    def day(self, n, k=1):
        return [item(f"d{n}-{i}.jpg", n * 1440 + i) for i in range(k)]

    def test_first_answer_then_review_sheet_with_a_trip(self):
        todo = [self.day(1), self.day(3, 2), self.day(5), self.day(6), self.day(9)]
        # Day 1: asked by name, confirmed once. Days 3-9 on one sheet: 5 and 6 aren't
        # Lyon, and as consecutive numbers they come back as ONE question.
        s, finished = self.run_session(todo, [], ["lyo", "", "2-3", "paris", ""])
        self.assertTrue(finished)
        ans = s.state["answers"]
        name = lambda rel: ans[rel]["res"]["place"]["locality"]["name"]  # noqa: E731
        self.assertEqual(name("d1-0.jpg"), "Lyon")
        self.assertEqual(name("d3-1.jpg"), "Lyon")
        self.assertEqual(name("d9-0.jpg"), "Lyon")
        self.assertEqual(name("d5-0.jpg"), "Paris")
        self.assertEqual(name("d6-0.jpg"), "Paris")
        self.assertEqual(s.aliases[lm.fold("lyo")], "L:2996944")

    def test_remembered_name_is_not_confirmed_again(self):
        self.run_session([self.day(1)], [], ["cologne", ""])
        s, _ = self.run_session([self.day(20)], [], ["cologne"])  # no pick prompt this time
        self.assertEqual(s.state["answers"]["d20-0.jpg"]["res"]["place"]["locality"]["id"], "2886242")

    def test_suggestion_from_gps_known_and_split(self):
        lyon = GEO.from_place(_StubGaz().lookup(*LYON), LYON)
        knowns = [lm.Known(T0, lyon, LYON, "GPS photo")]
        cl = self.day(1, 1) + self.day(2, 1)
        # Sheets off: split the cluster, accept the suggestion for the first half,
        # coordinates for the second.
        s, _ = self.run_session([cl], knowns, ["s", "", "48.85,2.35"], sheet=0)
        ans = s.state["answers"]
        self.assertEqual(ans["d1-0.jpg"]["res"]["place"]["locality"]["name"], "Lyon")
        self.assertEqual(ans["d2-0.jpg"]["res"]["kind"], "coords")
        self.assertEqual(ans["d2-0.jpg"]["res"]["place"]["locality"]["name"], "Paris")

    def test_undo_and_quit_resume(self):
        s, finished = self.run_session([self.day(1), self.day(10)], [], ["lyon", "", "u", "q"], sheet=0)
        self.assertFalse(finished)
        self.assertEqual(s.state["answers"], {})  # the undo took the only answer back

    def test_skip(self):
        s, _ = self.run_session([self.day(1)], [], ["-"])
        self.assertEqual(s.state["answers"]["d1-0.jpg"], {"method": "skipped"})


class OutputTest(unittest.TestCase):
    def test_lines(self):
        d = tempfile.mkdtemp()
        items = [item("gps.jpg", 0, LYON), item("inf.jpg", 10), item("ans.jpg", 5000), item("skip.jpg", 9000),
                 item("left.jpg", 20000)]
        res = GEO.search("paris, france", None)[0].res
        inferred = {"inf.jpg": lm.Inferred(GEO.from_place(_StubGaz().lookup(*LYON), LYON), "neighbour-gps", "gps.jpg", 600)}
        answers = {"ans.jpg": {"method": "prompt", "res": res.to_json(), "query": "paris"},
                   "skip.jpg": {"method": "skipped"}}
        out = os.path.join(d, "o.jsonl")
        counts = lm.write_output(out, items, inferred, answers)
        with open(out, encoding="utf-8") as f:
            lines = [json.loads(line) for line in f]
        self.assertEqual([r["path"] for r in lines], ["inf.jpg", "ans.jpg"])
        self.assertEqual(lines[0]["basis"], {"path": "gps.jpg", "gapS": 600})
        self.assertEqual(lines[1]["place"]["locality"]["id"], "2988507")
        self.assertEqual(lines[1]["approx"], {"lat": 48.85341, "lon": 2.3488})
        self.assertEqual(counts["skipped"], 1)
        self.assertEqual(counts["unresolved"], 1)


if __name__ == "__main__":
    unittest.main()


# --- Region packs: the synthetic fixture in tools/geopack/testdata --------------------

import places  # noqa: E402

_FIXTURE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "geopack",
                                         "testdata", "fixture-manifest.json"))
PACK_SRC = f"osm@fixture;{SRC}"
ARA = {"id": "FR.84", "name": "Auvergne-Rhône-Alpes"}


class _FixtureGaz:
    """Everything near the fixture is in one GeoNames town, 'Gazville'."""
    src = SRC

    def lookup(self, lat, lon):
        if 44.5 < lat < 45.5 and 4.5 < lon < 5.6:
            return {"cc": "FR", "region": ARA, "locality": {"id": "3000001", "name": "Gazville"}, "src": SRC}
        return None


def _pack_geo() -> lm.Geo:
    d = _geonames_dir()
    # Add Gazville, a GeoNames town whose centre is inside the fixture's commune Alpha.
    with zipfile.ZipFile(os.path.join(d, "cities500.zip")) as z:
        rows = z.read("cities500.txt").decode()
    col = ["3000001", "Gazville", "Gazville", "", "45.03", "5.01", "P", "PPL", "FR", "", "84", "", "", "",
           "9000", "", "200", "Europe/Paris", "2026-01-01"]
    with zipfile.ZipFile(os.path.join(d, "cities500.zip"), "w") as z:
        z.writestr("cities500.txt", rows + "\t".join(col) + "\n")
    geocoder = places.load_local(_FIXTURE)
    geocoder.gazetteer = _FixtureGaz()
    geocoder.src = f"osm@fixture;{SRC}"
    return lm.Geo(d, geocoder.gazetteer, geocoder)


class RegionPackSearchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.geo = _pack_geo()

    def top(self, text):
        return self.geo.search(text, None)[0].res

    def test_a_village_or_suburb_adds_itself_to_its_commune(self):
        self.assertEqual(self.top("brook").place, {
            "cc": "FR", "region": ARA, "locality": {"id": "r101", "name": "Alpha"},
            "sub": {"id": "n9003", "name": "Brook"}, "src": PACK_SRC})

    def test_a_hamlet_brings_the_village_it_is_near(self):
        self.assertEqual(self.top("nook").place, {
            "cc": "FR", "region": ARA, "locality": {"id": "r101", "name": "Alpha"},
            "sub": {"id": "n9003", "name": "Brook"}, "spot": {"id": "n9005", "name": "Nook"}, "src": PACK_SRC})

    def test_a_protected_area_names_no_commune(self):
        self.assertEqual(self.top("little reserve").place, {
            "cc": "FR", "region": ARA, "areas": [{"id": "r202", "name": "Little Reserve"}], "src": PACK_SRC})

    def test_a_feature_adds_itself_to_its_commune(self):
        place = self.top("summit").place
        self.assertEqual(place["locality"], {"id": "r101", "name": "Alpha"})
        self.assertEqual(place["feature"], {"id": "n301", "name": "Summit"})
        self.assertNotIn("areas", place)

    def test_a_commune_and_its_same_named_village_are_one_choice(self):
        alpha = [c for c in self.geo.search("alpha", None) if c.res.place.get("locality", {}).get("name") == "Alpha"
                 and set(c.res.place) == {"cc", "region", "locality", "src"}]
        self.assertEqual(len(alpha), 1)

    def _commune(self, text, name):
        return [c for c in self.geo.search(text, None)
                if set(c.res.place) == {"cc", "region", "locality", "src"} and c.res.place["locality"]["name"] == name]

    def test_a_commune_is_labelled_with_its_level_6_area(self):
        # Communes share names across départements ("Sainte-Colombe" in the Rhône
        # and in the Doubs): the label says which. The stored place doesn't change.
        [beta] = self._commune("beta", "Beta")
        self.assertEqual(beta.res.label, "Beta, Gamma County, Auvergne-Rhône-Alpes, FR")
        self.assertTrue(beta.detail.startswith("Beta, Gamma County, Auvergne-Rhône-Alpes, FR (admin area, level 8)"))
        self.assertEqual(beta.res.place, {"cc": "FR", "region": ARA, "locality": {"id": "r102", "name": "Beta"},
                                          "src": PACK_SRC})

    def test_a_commune_outside_every_level_6_area_has_no_extra_name(self):
        [east] = self._commune("eastville", "Eastville")
        self.assertEqual(east.res.label, "Eastville, Auvergne-Rhône-Alpes, FR")

    def test_the_level_6_area_qualifies_a_search(self):
        self.assertEqual(len(self._commune("beta, gamma", "Beta")), 1)
        self.assertEqual(self._commune("beta, nowhere", "Beta"), [])

    def test_a_geonames_town_is_replaced_by_the_commune_at_its_centre(self):
        res = self.top("gazville")
        self.assertEqual(res.key, "L:3000001")
        self.assertEqual(res.place["locality"], {"id": "r101", "name": "Alpha"})
        self.assertEqual(res.place["src"], PACK_SRC)

    def test_keys_round_trip(self):
        for text in ("brook", "nook", "little reserve", "summit", "gazville"):
            with self.subTest(text=text):
                res = self.top(text)
                self.assertEqual(self.geo.from_key(res.key), res)

    def test_a_gps_anchor_is_kept_at_commune_level(self):
        full = self.geo.geocoder.lookup(45.0110, 5.0110)
        self.assertIn("spot", full)
        res = self.geo.from_place(full, (45.0110, 5.0110))
        self.assertEqual(res.place, {"cc": "FR", "region": ARA, "locality": {"id": "r101", "name": "Alpha"},
                                     "src": PACK_SRC})
        self.assertEqual(self.geo.from_key(res.key).place, res.place)

    def test_typed_coordinates_get_every_layer(self):
        res = self.geo.coords_res(45.0110, 5.0110)
        self.assertEqual(res.place["spot"], {"id": "n9005", "name": "Nook"})
