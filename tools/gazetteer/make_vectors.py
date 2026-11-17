#!/usr/bin/env python3
"""Write tools/gazetteer/vectors.json from gazetteer.bin.

Usage: make_vectors.py [--bin PATH] [--input-dir DIR] [--random N]

Each vector's expected `place` comes from reference.py's brute-force scan over every
record. Before writing, the script asserts that the cell-ring lookup agrees with the
brute force on every vector and on N extra pseudo-random points (fixed seed). With
--input-dir (the GeoNames files the .bin was built from), it also checks that the .bin
decodes back to exactly the places build.py read from them.
"""
import argparse
import json
import pathlib
import random
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import build  # noqa: E402
from reference import MICRO, Gazetteer  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent

# (id, lat, lon, what it exercises). The expected result is whatever the oracle returns;
# the note says why the point is here, not what it must return.
POINTS = [
    ("kyoto-centre", 35.01161, 135.76804, "Kyoto city centre (sample-data.md A1's coordinates)"),
    ("kyoto-geonames-point", 35.02107, 135.75385, "exactly on Kyoto's own GeoNames point"),
    ("paris-centre", 48.85660, 2.35220, "Paris, Hotel de Ville"),
    ("reykjavik-centre", 64.14660, -21.94260, "Reykjavik city centre"),
    ("village-luberon", 43.85200, 5.22000, "Provencal countryside, nearest place is a village"),
    ("village-highlands", 57.20000, -4.60000, "Scottish Highlands, small village under 25 km"),
    ("hokkaido-town", 44.00000, 142.00000, "Hokkaido, near a small town (locality)"),
    ("region-only-hokkaido", 43.60000, 143.20000, "Hokkaido interior, 25-100 km from any place"),
    ("region-only-nevada", 39.00000, -116.50000, "central Nevada, 25-100 km from any place"),
    ("region-only-mongolia", 46.00000, 100.00000, "Mongolian steppe, 25-100 km from any place"),
    ("null-sahara", 24.00000, 3.00000, "Algerian Sahara: on land, but over 100 km from any place"),
    ("atlantic", 30.00000, -40.00000, "open Atlantic: null"),
    ("null-island", 0.0, 0.0, "Gulf of Guinea, on a cell corner: null"),
    ("taveuni-east", -16.85000, -179.99000, "Taveuni, Fiji, east of the antimeridian; nearest place is west of it"),
    ("taveuni-west", -16.85000, 179.99000, "Taveuni, Fiji, just west of the antimeridian"),
    ("antimeridian-plus180", -16.85000, 180.0, "lon exactly +180 (normalises to -180)"),
    ("antimeridian-minus180", -16.85000, -180.0, "lon exactly -180"),
    ("chukotka-wrap", 66.30000, 179.95000, "Chukotka: lon +179.95, nearest place is at -179.12"),
    ("border-geneva-moillesulaz", 46.19480, 6.21300, "CH/FR border crossing, Thonex/Gaillard"),
    ("border-thonex-ch", 46.19900, 6.20500, "Swiss side (Thonex), near the CH/FR border"),
    ("border-geneva-centre", 46.20440, 6.14320, "Geneva centre"),
    ("border-annemasse-centre", 46.19340, 6.23410, "Annemasse centre, 1.5 km from the border"),
    ("border-strasbourg-kehl", 48.57340, 7.80030, "Europe bridge over the Rhine, FR/DE"),
    ("border-strasbourg-port-du-rhin", 48.57280, 7.79000, "French soil, west bank of the Rhine; nearest town is Kehl (DE)"),
    ("high-lat-longyearbyen", 78.22320, 15.62670, "Svalbard"),
    ("high-lat-alert", 82.50000, -62.30000, "Alert, Nunavut"),
    ("north-pole", 90.0, 0.0, "north pole: null"),
    ("south-pole", -90.0, 0.0, "south pole"),
    ("cell-corner-france", 48.0, 2.0, "lat and lon both exactly on a cell boundary"),
    ("cell-corner-kyoto", 35.0, 135.0, "lat and lon exactly on a cell boundary, west of Kyoto"),
    ("cell-edge-lat", 46.0, 6.5, "lat exactly on a cell boundary (Haute-Savoie)"),
    ("cell-edge-lon", 47.3, 8.0, "lon exactly on a cell boundary (Swiss plateau)"),
    ("cell-corner-negative", -35.0, -59.0, "negative lat and lon exactly on a cell boundary"),
    ("buenos-aires", -34.60370, -58.38160, "southern and western hemispheres"),
]


def check_source(gz, input_dir):
    countries = build.read_countries(input_dir / "countryInfo.txt")
    admin1 = build.read_admin1(input_dir / "admin1CodesASCII.txt")
    src_places, _ = build.read_places(input_dir / "cities500.zip", countries, admin1)
    want = sorted(src_places)
    got = sorted((g, la, lo, gz.region(r)[0], n) for g, la, lo, r, n in gz.places())
    assert got == want, "gazetteer.bin does not decode back to the source places"
    print(f"decode check: {len(got)} places match the source files", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", type=pathlib.Path, default=build.DEFAULT_OUT)
    ap.add_argument("--input-dir", type=pathlib.Path)
    ap.add_argument("--random", type=int, default=300)
    ap.add_argument("--out", type=pathlib.Path, default=HERE / "vectors.json")
    args = ap.parse_args()

    gz = Gazetteer(args.bin.read_bytes())
    if args.input_dir:
        check_source(gz, args.input_dir)

    vectors = []
    for vid, lat, lon, note in POINTS:
        best = gz.brute(lat, lon)
        assert gz.lookup(lat, lon) == best, f"ring lookup disagrees with brute force at {vid}"
        v = {"id": vid, "lat": lat, "lon": lon, "note": note, "place": gz.place(best)}
        if best:
            dist, (gid, la, lo, _, name) = best
            v["nearest"] = {"id": str(gid), "name": name, "distanceKm": round(dist, 3)}
        vectors.append(v)

    # Extra agreement checks, not written out: points near real places (so most hit
    # something) plus points anywhere on the globe.
    rng = random.Random(20261025)
    recs = list(gz.places())
    for i in range(args.random):
        if i % 3:
            _, la, lo, _, _ = rng.choice(recs)
            lat = max(-90.0, min(90.0, la / MICRO + rng.uniform(-1.5, 1.5)))
            lon = lo / MICRO + rng.uniform(-1.5, 1.5)
        else:
            lat, lon = rng.uniform(-90, 90), rng.uniform(-180, 180)
        assert gz.lookup(lat, lon) == gz.brute(lat, lon), f"disagreement at {lat},{lon}"
    print(f"ring lookup == brute force on {len(POINTS)} vectors + {args.random} random points",
          file=sys.stderr)

    doc = {
        "comment": "Shared reverse-geocoding conformance vectors (geolocation plan D10). "
                   "Generated by make_vectors.py from gazetteer.bin; see README.md. Clients "
                   "must produce `place` exactly (null = no place). `nearest` is informative.",
        "src": gz.src,
        "localityMaxKm": 25.0,
        "regionMaxKm": 100.0,
        "vectors": vectors,
    }
    args.out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(vectors)} vectors to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
