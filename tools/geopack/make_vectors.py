#!/usr/bin/env python3
"""Write vectors.json: lookups over the fixture (make_fixture.py) that every reader must
reproduce exactly, through the tile manifest, plus grid keys for the tile lookup.

The oracle is the two region packs merged (an entity in both taken from fixture-a).
Before writing, asserts that the grid-indexed lookup and the brute-force scan agree, and
that the lookup through the one tile whose key prefixes the point's gives the same
result, on every named case and on random points. Standard library only.
"""
from __future__ import annotations

import json
import os
import random
import sys

import reference as ref

HERE = os.path.dirname(os.path.abspath(__file__))
TESTDATA = os.path.join(HERE, "testdata")

# (name, lat, lon, why). Expected results come from the reference; read them in
# vectors.json and check they say what `why` claims before committing.
CASES = [
    ("plain-commune", 45.0050, 5.0050, "inside Alpha only at level 8; Wide Zone and Great Park contain it"),
    ("hole", 45.0225, 5.0225, "in Alpha's hole: Enclave, not Alpha; Alpha's own node is outside Enclave, so no sub"),
    ("level-8-over-6", 45.0300, 5.1000, "Beta (8) wins over Gamma County (6) and Delta City (5)"),
    ("level-6-only", 44.9800, 5.0500, "south strip: only Gamma County contains it"),
    ("smaller-area", 45.0750, 5.0150, "Twin North and Twin South both contain it: smaller area wins"),
    ("id-tiebreak-admin", 45.0630, 5.1070, "Tie R and Tie W, equal level and area: w < r"),
    ("sea-fallback", 45.0000, 5.2150, "outside every admin; Gamma County's edge is ~1.2 km away"),
    ("sea-too-far", 45.0000, 5.2600, "outside every admin, nothing within 2 km: no locality"),
    ("label-suppressed", 45.0300, 5.0580, "inside Delta City: its label node Delta is never sub"),
    ("label-not-containing", 45.0300, 5.0520, "outside Delta City, so its label isn't suppressed, but Delta (city, 3 km) is in Beta, not the locality Alpha: Hill"),
    ("locality-name-suppressed", 44.9605, 4.9605, "village named like its locality Gamma County is skipped"),
    ("sub-and-spot", 45.0110, 5.0110, "Brook suburb as sub; Nook hamlet as spot; hamlet named Brook skipped"),
    ("spot-neighbourhood", 45.0452, 5.0052, "Corner neighbourhood within 500 m"),
    ("spot-lieu-dit-inside", 45.0050, 5.0495, "Field lieu-dit ~354 m away, inside its 400 m radius"),
    ("spot-lieu-dit-outside", 45.0050, 5.0503, "Field lieu-dit ~417 m away, outside its 400 m radius"),
    ("quarter", 45.0010, 5.1100, "Quay quarter in Beta"),
    ("equal-distance", 45.0703, 5.0560, "Equal A and Equal B share coordinates: smaller id wins"),
    ("areas-ordered-capped", 45.0320, 5.0450, "four protected areas: rank 0 by area, then rank 1; three kept"),
    ("peak", 45.0452, 5.0152, "Summit peak within 600 m beats the island and lakes"),
    ("ridge-line", 45.0470, 5.0300, "Long Ridge: distance to the line, not its vertices"),
    ("lake-margin", 45.0088, 5.0320, "~89 m north of Blue Lake: inside the 100 m water margin"),
    ("lake-outside-margin", 45.0095, 5.0320, "~167 m from Blue Lake: outside the margin, no feature"),
    ("big-island-capped", 45.0250, 5.0100, "Big Island contains it but is over half of Alpha: no feature"),
    ("islet", 45.0145, 5.0525, "Small Isle contains it and is small: feature"),
    ("beach-margin", 45.0420, 5.1050, "~110 m from Sandy Beach, within its 150 m margin"),
    ("cape-at-sea", 45.0010, 5.2130, "Cape Far within 300 m; locality from the sea fallback"),
    ("bay-point-ignored", 45.0101, 5.0101, "a bay point has reach 0: never a feature"),
    ("no-locality-cap", 45.0250, 5.3150, "no locality: Plateau Wide is over 10 km², Lone Glacier isn't"),
    ("cross-pack-dedupe", 45.0550, 5.0650, "outside pack A's Alpha but inside pack B's: A's Alpha wins, so not Alpha"),
    ("pack-b-only-entity", 45.0466, 5.0466, "Bravo hamlet exists only in pack B, which also covers this point"),
    ("pack-b-only-coverage", 45.0470, 5.4500, "only pack B has anything near this point"),
    ("nothing-in-reach", 46.0000, 6.0000, "inside a big tile's square, but nothing within reach: empty result"),
    ("no-tile", -33.8688, 151.2093, "no tile's square contains it: no tile, empty result"),
]


# (lat, lon, depth): grid keys every reader must compute identically, including the
# hemisphere splits, a point on a square's edge, and the extremes.
KEY_CASES = [
    (45.0225, 5.0225, 12), (0.0, 0.0, 6), (-0.000001, -0.000001, 6), (-33.8688, 151.2093, 14),
    (64.1466, -21.9426, 14), (89.999999, 179.999999, 29), (-90.0, -180.0, 29), (45.123456, 5.123456, 29),
]


def load():
    region = [ref.read_pack(open(os.path.join(TESTDATA, f"{n}.bin"), "rb").read()) for n in ("fixture-a", "fixture-b")]
    manifest = json.load(open(os.path.join(TESTDATA, "fixture-manifest.json"), encoding="utf-8"))
    tiles = {k: ref.PackSet([ref.read_pack(open(os.path.join(TESTDATA, manifest["set"], f"{k}.bin"), "rb").read())])
             for k in manifest["tiles"]}
    return ref.PackSet(region), manifest, tiles


def check(oracle, manifest, tiles, lat, lon):
    a, b = oracle.lookup(lat, lon), oracle.brute(lat, lon)
    if a != b:
        raise AssertionError(f"index and brute force disagree at {lat},{lon}: {a} vs {b}")
    key = ref.find_tile(manifest["tiles"].keys(), lat, lon)
    t = tiles[key].lookup(lat, lon) if key else {}
    if t != a:
        raise AssertionError(f"tile {key} disagrees with the merged packs at {lat},{lon}: {t} vs {a}")
    return key, a


def main() -> int:
    oracle, manifest, tiles = load()
    rng = random.Random(20261119)
    for _ in range(3000):
        check(oracle, manifest, tiles, rng.uniform(44.88, 45.17), rng.uniform(4.88, 5.55))

    vectors = []
    for name, lat, lon, why in CASES:
        key, expect = check(oracle, manifest, tiles, lat, lon)
        vectors.append({"name": name, "lat": lat, "lon": lon, "why": why, "tile": key, "expect": expect})
    out = {
        "about": "Region-pack lookup conformance vectors (tools/geopack/README.md, 'Tiles' and 'Lookup'). "
                 "For each vector: find the tile in testdata/fixture-manifest.json whose key is a prefix of "
                 "the point's grid key (`tile`, null for none), load testdata/<set>/<tile>.bin, and reproduce "
                 "`expect` exactly ({} when there is no tile). The same results come from the region packs "
                 "fixture-a.bin then fixture-b.bin merged (an entity in both taken from fixture-a). "
                 "`keys`: point_key(lat, lon, depth) for each case.",
        "manifest": "testdata/fixture-manifest.json",
        "regionPacks": ["testdata/fixture-a.bin", "testdata/fixture-b.bin"],
        "keys": [{"lat": la, "lon": lo, "depth": d, "key": ref.point_key(la, lo, d)} for la, lo, d in KEY_CASES],
        "vectors": vectors,
    }
    with open(os.path.join(HERE, "vectors.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{len(vectors)} vectors; index == brute force == tile on 3000 random points", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
