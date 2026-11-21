#!/usr/bin/env python3
"""Coordinates -> the matching GeoNames cities500 entry.

By default this is exactly the lookup the importer and the Android app do: the
bundled `gazetteer.bin` (gazetteer.py), nearest populated place, with a locality only
within 25 km and a region within 100 km. So what it prints is the `place` an upload
at those coordinates would get.

    .venv/bin/python3 geocode.py 45.7640,4.8357
    .venv/bin/python3 geocode.py "45.7640, 4.8357" -33.8688,151.2093
    .venv/bin/python3 geocode.py --json 48.8566 2.3522       # a pair as two args
    echo "35.0116,135.7681" | .venv/bin/python3 geocode.py   # one pair per line on stdin

    --host HOST   also apply HOST's region packs (its /geo/manifest.json), exactly as
                  an upload to that instance would, and print every layer
    --manifest F  the same with a pack set on disk (`make geo-build`'s manifest.json),
                  to try packs before publishing them
    --details     also print the full cities500 row of the matched locality
                  (population, feature code, timezone, alternate names...)
    --nearest K   also list the K nearest cities500 entries with their distance,
                  ignoring the 25/100 km caps -- handy when the answer surprises you
                  (a border, a city district winning over the city itself)

`--details`/`--nearest` read GeoNames `cities500.zip`, `admin1CodesASCII.txt` and
`countryInfo.txt` from --geonames-dir (default `.cache/locate/geonames`, shared
with locate_missing.py, downloaded from geonames.org on first use). Note that
`gazetteer.bin` is a pinned snapshot: a freshly downloaded cities500 can differ
from it slightly.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import zipfile

import gazetteer
import places
from locate_missing import DEFAULT_STATE_DIR, ensure_geonames

_NUM = r"[-+]?\d+(?:\.\d+)?"
PAIR = re.compile(rf"^\s*({_NUM})\s*[,;\s]\s*({_NUM})\s*$")
SINGLE = re.compile(rf"^\s*{_NUM}\s*$")

# cities500.txt columns (https://download.geonames.org/export/dump/readme.txt)
COLS = ("geonameid", "name", "asciiname", "alternatenames", "latitude", "longitude", "featureClass",
        "featureCode", "countryCode", "cc2", "admin1", "admin2", "admin3", "admin4", "population",
        "elevation", "dem", "timezone", "modified")


def parse_pairs(tokens: list[str]) -> list[tuple[float, float]]:
    """'lat,lon' / 'lat lon' tokens, or two bare numbers in a row."""
    out, pending = [], None
    for tok in tokens:
        m = PAIR.match(tok)
        if m:
            if pending is not None:
                raise ValueError(f"{pending!r} has no longitude")
            out.append((float(m[1]), float(m[2])))
        elif SINGLE.match(tok):
            if pending is None:
                pending = tok
            else:
                out.append((float(pending), float(tok)))
                pending = None
        else:
            raise ValueError(f"not coordinates: {tok!r}")
    if pending is not None:
        raise ValueError(f"{pending!r} has no longitude")
    for lat, lon in out:
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError(f"out of range: {lat},{lon}")
    return out


class Cities:
    """The full cities500 rows, for --details and --nearest."""

    def __init__(self, d: str):
        ensure_geonames(d)
        self.rows: list[dict] = []
        with zipfile.ZipFile(os.path.join(d, "cities500.zip")) as z, z.open("cities500.txt") as f:
            for line in io.TextIOWrapper(f, encoding="utf-8"):
                col = line.rstrip("\n").split("\t")
                if len(col) == len(COLS):
                    self.rows.append(dict(zip(COLS, col)))
        self.by_id = {r["geonameid"]: r for r in self.rows}
        self.admin1: dict[str, str] = {}
        with open(os.path.join(d, "admin1CodesASCII.txt"), encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    key, name = line.split("\t")[:2]
                    self.admin1[key] = name.strip()

    def nearest(self, lat: float, lon: float, k: int) -> list[tuple[float, dict]]:
        scored = [(gazetteer.haversine_km(lat, lon, float(r["latitude"]), float(r["longitude"])), r)
                  for r in self.rows]
        scored.sort(key=lambda p: (p[0], int(p[1]["geonameid"])))
        return scored[:k]

    def summary(self, r: dict) -> str:
        region = self.admin1.get(f"{r['countryCode']}.{r['admin1']}")
        where = ", ".join(p for p in (r["name"], region, r["countryCode"]) if p)
        return f"{where} · #{r['geonameid']} · {r['featureCode']} · pop {int(r['population'] or 0):,}"


def lookup(gaz: gazetteer.Gazetteer, lat: float, lon: float) -> tuple[dict | None, float | None]:
    """(place, distance in km to the nearest gazetteer entry). The distance comes from
    the same search `lookup` runs; it is None when nothing lies within 100 km."""
    best = gaz._nearest(lat, lon)  # noqa: SLF001 -- same tool, and lookup() drops the distance
    return gaz.lookup(lat, lon), (best[0] if best is not None and best[0] <= gazetteer.REGION_MAX_KM else None)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", action="store_true", help="one JSON object per coordinate pair")
    ap.add_argument("--details", action="store_true", help="the matched locality's full cities500 row")
    ap.add_argument("--nearest", type=int, default=0, metavar="K", help="also list the K nearest cities500 entries")
    ap.add_argument("--gazetteer", default=None, help="gazetteer.bin, as for main.py")
    packs = ap.add_mutually_exclusive_group()
    packs.add_argument("--host", default=None, help="apply this instance's region packs")
    packs.add_argument("--manifest", default=None, help="apply a local pack set (its manifest.json)")
    ap.add_argument("--geonames-dir", default=os.path.join(DEFAULT_STATE_DIR, "geonames"))
    # Coordinates are taken from what argparse doesn't recognise, so a negative
    # latitude ("-33.86,151.2") isn't mistaken for an option.
    args, rest = ap.parse_known_args()
    tokens = [t for t in rest if t != "--"]
    if not tokens and not sys.stdin.isatty():
        tokens = [line.strip() for line in sys.stdin if line.strip()]
    if not tokens:
        ap.print_usage(sys.stderr)
        return 2
    try:
        pairs = parse_pairs(tokens)
        gaz = gazetteer.load(args.gazetteer)
        geo = (places.load(args.host, args.gazetteer) if args.host
               else places.load_local(args.manifest, args.gazetteer) if args.manifest else None)
    except (ValueError, OSError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    cities = Cities(args.geonames_dir) if (args.details or args.nearest) else None

    for lat, lon in pairs:
        place, dist = lookup(gaz, lat, lon)
        loc_row = cities.by_id.get(place["locality"]["id"]) if cities and place and place.get("locality") else None
        near = cities.nearest(lat, lon, args.nearest) if cities and args.nearest else []
        full = geo.lookup(lat, lon) if geo is not None and geo.manifest is not None else None
        if args.json:
            rec: dict = {"lat": lat, "lon": lon, "place": place,
                         "distanceKm": round(dist, 3) if dist is not None else None}
            if full is not None or (geo is not None and geo.manifest is not None):
                rec["withPacks"] = full
            if args.details:
                rec["cities500"] = loc_row
            if near:
                rec["nearest"] = [{"distanceKm": round(d, 3), **r} for d, r in near]
            print(json.dumps(rec, ensure_ascii=False))
            continue
        print(f"{lat:.6f},{lon:.6f}")
        if place is None:
            print("  no place: nothing populated within 100 km")
        else:
            loc, reg = place.get("locality"), place.get("region")
            if loc:
                print(f"  locality  {loc['name']}  (geonameid {loc['id']}, {dist:.2f} km)")
            else:
                print(f"  locality  none: the nearest place is {dist:.1f} km away (> 25 km)")
            print(f"  region    {reg['name']} ({reg['id']})" if reg else "  region    none")
            print(f"  country   {place['cc']}")
            print(f"  src       {place['src']}")
        if geo is not None and geo.manifest is not None:
            print(f"  with region packs (set {geo.set_id}; tile {geo.tile_for(lat, lon) or 'none'}):")
            if full is None:
                print("    no place")
            else:
                for k in ("locality", "sub", "spot", "feature"):
                    if full.get(k):
                        print(f"    {k:<9} {full[k]['name']}  ({full[k]['id']})")
                for a in full.get("areas", []):
                    print(f"    area      {a['name']}  ({a['id']})")
                print(f"    src       {full['src']}")
        if loc_row:
            print("  cities500 row:")
            for k in COLS:
                v = loc_row[k]
                if k == "alternatenames" and len(v) > 200:
                    v = v[:200] + f"... ({v.count(',') + 1} names)"
                print(f"    {k:<15} {v}")
        if near:
            print(f"  {len(near)} nearest in cities500:")
            for d, r in near:
                print(f"    {d:8.2f} km  {cities.summary(r)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
