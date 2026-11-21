#!/usr/bin/env python3
"""Write the synthetic conformance data: two region packs, testdata/fixture-a.bin and
fixture-b.bin, and the tile set cut from them, testdata/fixture/<key>.bin with
testdata/fixture-manifest.json. Hand-placed geometry around 45°N 5°E, no OpenStreetMap
data. The tiles use a tiny size cap (TILE_CAP) so that a handful of tiles at several
depths cover the fixture.

Each entity exists to exercise a rule in README.md "Lookup"; make_vectors.py names the
query points that hit them. At 45°N, 0.001° is about 111 m of latitude and 79 m of
longitude. Standard library only; the output is deterministic.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys

import packfmt as pf
import reference as ref
import tile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "testdata")
SET = "fixture"
TILE_CAP = 1200


def m(deg: float) -> int:
    return round(deg * 1_000_000)


def rect(lat0, lon0, lat1, lon1):
    return [(m(lat0), m(lon0)), (m(lat0), m(lon1)), (m(lat1), m(lon1)), (m(lat1), m(lon0))]


def admin(otype, oid, name, level, area, rings, label=0):
    return pf.Entity(pf.ADMIN, otype, oid, name, pf.POLYGONS, rings, level=level, area=area, label=label)


def place(oid, name, kind, lat, lon):
    return pf.Entity(pf.PLACE, "n", oid, name, pf.POINT, [[(m(lat), m(lon))]], kind=pf.PLACE_KINDS[kind])


def protected(otype, oid, name, rank, area, rings):
    return pf.Entity(pf.PROTECTED, otype, oid, name, pf.POLYGONS, rings, rank=rank, area=area)


def feature(otype, oid, name, kind, geom_type, parts, area=0):
    return pf.Entity(pf.FEATURE, otype, oid, name, geom_type, parts, kind=pf.FEATURE_KINDS[kind], area=area)


PACK_A = [
    # Admin areas. Alpha has a hole that Enclave fills; Delta City (level 5) overlaps
    # Alpha's east edge and all of Beta, and labels the city node "Delta".
    admin("r", 101, "Alpha", 8, 26_000_000, [rect(45.000, 5.000, 45.050, 5.060), rect(45.020, 5.020, 45.025, 5.025)], label=9001),
    admin("r", 106, "Enclave", 8, 220_000, [rect(45.020, 5.020, 45.025, 5.025)]),
    admin("r", 102, "Beta", 8, 26_000_000, [rect(45.000, 5.060, 45.050, 5.120)]),
    admin("r", 104, "Delta City", 5, 40_000_000, [rect(44.990, 5.055, 45.060, 5.130)], label=9002),
    admin("r", 103, "Gamma County", 6, 300_000_000, [rect(44.950, 4.950, 45.100, 5.200)]),
    # Overlapping level-8 areas: the smaller wins; at equal area, the smaller id (w < r).
    admin("r", 107, "Twin North", 8, 3_000_000, [rect(45.060, 5.000, 45.080, 5.020)]),
    admin("r", 108, "Twin South", 8, 2_000_000, [rect(45.070, 5.010, 45.090, 5.030)]),
    admin("r", 109, "Tie R", 8, 500_000, [rect(45.060, 5.100, 45.065, 5.110)]),
    admin("w", 110, "Tie W", 8, 500_000, [rect(45.062, 5.105, 45.067, 5.115)]),

    # Places.
    place(9001, "Alpha", "village", 45.030, 5.030),          # Alpha's label, and its name
    place(9002, "Delta", "city", 45.030, 5.090),             # Delta City's label
    place(9003, "Brook", "suburb", 45.010, 5.010),
    place(9004, "Hill", "village", 45.040, 5.045),
    place(9005, "Nook", "hamlet", 45.0115, 5.0115),
    place(9006, "Brook", "hamlet", 45.0102, 5.0102),         # the sub's name: never a spot
    place(9007, "Corner", "neighbourhood", 45.0450, 5.0050),
    place(9008, "Field", "locality", 45.0050, 5.0450),
    place(9009, "Southton", "town", 44.975, 5.060),
    place(9010, "Gamma County", "village", 44.960, 4.960),   # the locality's name
    place(9011, "Quay", "quarter", 45.0005, 5.1105),
    place(9012, "Twinville", "village", 45.075, 5.015),
    # Same coordinates, so exactly the same distance on every platform: id breaks the tie.
    place(9014, "Equal B", "hamlet", 45.0700, 5.0560),
    place(9013, "Equal A", "hamlet", 45.0700, 5.0560),

    # Protected areas: two ranks, nested; four contain one point, three are kept.
    protected("r", 201, "Great Park", 0, 26_000_000, [rect(45.000, 5.000, 45.050, 5.060)]),
    protected("r", 202, "Little Reserve", 0, 300_000, [rect(45.030, 5.040, 45.035, 5.050)]),
    protected("w", 203, "Heritage Zone", 1, 100_000, [rect(45.031, 5.041, 45.034, 5.049)]),
    protected("r", 204, "Wide Zone", 1, 90_000_000, [rect(44.990, 4.990, 45.060, 5.070)]),

    # Features.
    feature("n", 301, "Summit", "peak", pf.POINT, [[(m(45.045), m(5.015))]]),
    feature("w", 302, "Long Ridge", "ridge", pf.LINES, [[(m(45.046), m(5.020)), (m(45.046), m(5.040))]]),
    feature("w", 303, "Blue Lake", "water", pf.POLYGONS, [rect(45.005, 5.030, 45.008, 5.035)], area=80_000),
    feature("r", 304, "Big Island", "island", pf.POLYGONS, [rect(45.001, 5.001, 45.049, 5.059)], area=24_000_000),
    feature("w", 305, "Small Isle", "islet", pf.POLYGONS, [rect(45.013, 5.050, 45.016, 5.055)], area=130_000),
    feature("w", 306, "Sandy Beach", "beach", pf.POLYGONS, [rect(45.040, 5.100, 45.041, 5.110)], area=90_000),
    feature("n", 307, "Cape Far", "cape", pf.POINT, [[(m(45.000), m(5.212))]]),
    feature("w", 308, "Plateau Wide", "plateau", pf.POLYGONS, [rect(45.000, 5.300, 45.050, 5.360)], area=15_000_000),
    feature("w", 309, "Lone Glacier", "glacier", pf.POLYGONS, [rect(45.020, 5.310, 45.030, 5.320)], area=900_000),
    feature("n", 310, "Flat Bay", "bay", pf.POINT, [[(m(45.0101), m(5.0101))]]),  # reach 0: never
]

PACK_B = [
    # Alpha again, with different geometry and name: pack A is listed first, so A's wins.
    admin("r", 101, "Alpha (pack b)", 8, 40_000_000, [rect(45.000, 4.990, 45.060, 5.070)]),
    admin("r", 150, "Eastville", 8, 20_000_000, [rect(45.000, 5.420, 45.050, 5.480)]),
    place(9100, "Bravo", "hamlet", 45.0465, 5.0465),         # only in pack B
    place(9101, "Eastville", "town", 45.025, 5.450),
    place(9102, "Rim", "hamlet", 45.0480, 5.4500),
]


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    region_packs = []
    for name, ents in (("fixture-a", PACK_A), ("fixture-b", PACK_B)):
        bbox = pf.bbox_of(ents)
        data = pf.encode(ents, SET, name, bbox)
        with open(os.path.join(OUT, f"{name}.bin"), "wb") as f:
            f.write(data)
        region_packs.append(ref.read_pack(data))
        print(f"{name}: {len(ents)} entities, {len(data)} bytes, bbox {bbox}", file=sys.stderr)
    tiles = tile.tile(tile.merge(region_packs), SET, cap=TILE_CAP, min_depth=8, max_depth=16)
    tile_dir = os.path.join(OUT, SET)
    os.makedirs(tile_dir, exist_ok=True)
    for old in os.listdir(tile_dir):
        os.remove(os.path.join(tile_dir, old))
    entries = {}
    for key, data in tiles.items():
        with open(os.path.join(tile_dir, f"{key}.bin"), "wb") as f:
            f.write(data)
        entries[key] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    print(f"tiles: {', '.join(f'{k} ({e['bytes']} B)' for k, e in entries.items())}",
          file=sys.stderr)
    manifest = {"format": 2, "set": SET, "attribution": pf.ATTRIBUTION, "tiles": entries}
    with open(os.path.join(OUT, "fixture-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
