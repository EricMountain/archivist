#!/usr/bin/env python3
"""Build one region pack from an OpenStreetMap extract (.osm.pbf). Format: README.md.

    build.py EXTRACT.osm.pbf --name provence-alpes-cote-d-azur --set 2026-11-19 --out pack.bin

Needs `osmium` (pyosmium) and `shapely`: see requirements.txt. Same input, same bytes.
"""
from __future__ import annotations

import argparse
import math
import sys

import packfmt as pf

SIMPLIFY_M = 15.0
METRES_PER_DEG = 6371008.8 * math.pi / 180.0

PLACE_TAGS = {k for k in pf.PLACE_KINDS}  # never isolated_dwelling / farm (decision G4)
POINT_FEATURES = {"peak", "volcano", "saddle", "cape"}
LINE_FEATURES = {"ridge", "arete", "valley", "cliff", "gorge"}
AREA_FEATURES = {"mountain_range", "massif", "bay", "beach", "cape", "glacier", "water", "valley",
                 "plateau", "gorge"}
IUCN = {"1a", "1b", "1", "2", "3", "4", "5", "6"}


def admin_labels(path: str) -> dict[int, int]:
    """relation id -> its `label` member node id, for administrative boundaries."""
    import osmium

    out: dict[int, int] = {}
    fp = osmium.FileProcessor(path, osmium.osm.RELATION).with_filter(osmium.filter.TagFilter(("boundary", "administrative")))
    for r in fp:
        for m in r.members:
            if m.type == "n" and m.role == "label":
                out[r.id] = m.ref
                break
    return out


class Projector:
    """Degrees <-> metres around a reference latitude, for area and simplification."""

    def __init__(self, lat0: float):
        self.kx = METRES_PER_DEG * math.cos(math.radians(lat0))
        self.ky = METRES_PER_DEG

    def to_m(self, g):
        import shapely
        return shapely.transform(g, lambda a: a * [self.kx, self.ky])

    def to_deg(self, g):
        import shapely
        return shapely.transform(g, lambda a: a / [self.kx, self.ky])


def _quantize(coords) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for lon, lat in coords:
        p = (round(lat * 1e6), round(lon * 1e6))
        if not out or out[-1] != p:
            out.append(p)
    return out


def polygon_parts(g, proj: Projector) -> tuple[list, int]:
    """(rings as micro-degree point lists, area in m² before simplification)."""
    gm = proj.to_m(g)
    area = round(gm.area)
    s = proj.to_deg(gm.simplify(SIMPLIFY_M, preserve_topology=True))
    parts = []
    for poly in getattr(s, "geoms", [s]):
        if poly.is_empty or poly.geom_type != "Polygon":
            continue
        for ring in [poly.exterior, *poly.interiors]:
            pts = _quantize(ring.coords)
            if len(pts) > 1 and pts[0] == pts[-1]:
                pts.pop()
            if len(pts) >= 3:
                parts.append(pts)
    return parts, area


def line_parts(g, proj: Projector) -> list:
    s = proj.to_deg(proj.to_m(g).simplify(SIMPLIFY_M, preserve_topology=False))
    parts = []
    for line in getattr(s, "geoms", [s]):
        if line.is_empty:
            continue
        pts = _quantize(line.coords)
        if len(pts) >= 2:
            parts.append(pts)
    return parts


def build_entities(path: str, progress=lambda msg: None) -> list[pf.Entity]:
    import osmium
    import shapely.wkb

    labels = admin_labels(path)
    progress(f"{len(labels)} admin label nodes")
    wkb = osmium.geom.WKBFactory()
    ents: dict[tuple, pf.Entity] = {}
    skipped = 0

    def add(e: pf.Entity) -> None:
        ents.setdefault(e.key(), e)

    keys = ("boundary", "leisure", "natural", "place")
    fp = (osmium.FileProcessor(path)
          .with_areas(osmium.filter.KeyFilter(*keys))
          .with_filter(osmium.filter.KeyFilter(*keys)))
    for o in fp:
        t = o.tags
        name = (t.get("name") or "").strip()
        if not name or not pf.valid_name(name):
            continue
        try:
            if o.is_node():
                lat, lon = o.location.lat, o.location.lon
                pt = [[(round(lat * 1e6), round(lon * 1e6))]]
                place = t.get("place")
                natural = t.get("natural")
                if place in PLACE_TAGS:
                    add(pf.Entity(pf.PLACE, "n", o.id, name, pf.POINT, pt, kind=pf.PLACE_KINDS[place]))
                elif natural in POINT_FEATURES:
                    add(pf.Entity(pf.FEATURE, "n", o.id, name, pf.POINT, pt, kind=pf.FEATURE_KINDS[natural]))
            elif o.is_way():
                natural = t.get("natural")
                if natural in LINE_FEATURES and not o.is_closed():
                    g = shapely.wkb.loads(wkb.create_linestring(o), hex=True)
                    parts = line_parts(g, Projector(g.centroid.y))
                    if parts:
                        add(pf.Entity(pf.FEATURE, "w", o.id, name, pf.LINES, parts, kind=pf.FEATURE_KINDS[natural]))
            elif o.is_area():
                otype = "w" if o.from_way() else "r"
                oid = o.orig_id()
                boundary, natural, place = t.get("boundary"), t.get("natural"), t.get("place")
                admin = boundary == "administrative" and t.get("admin_level") in ("5", "6", "7", "8")
                protected = boundary in ("national_park", "protected_area") or t.get("leisure") == "nature_reserve"
                feature_kind = (natural if natural in AREA_FEATURES
                                else place if place in ("island", "islet") else None)
                if not (admin or protected or feature_kind):
                    continue
                g = shapely.wkb.loads(wkb.create_multipolygon(o), hex=True)
                parts, area = polygon_parts(g, Projector(g.centroid.y))
                if not parts:
                    continue
                if admin:
                    add(pf.Entity(pf.ADMIN, otype, oid, name, pf.POLYGONS, parts, level=int(t["admin_level"]),
                                  area=area, label=labels.get(oid, 0) if otype == "r" else 0))
                if protected:
                    cls = (t.get("protect_class") or "").strip().lower()
                    rank = 0 if boundary == "national_park" or cls in IUCN else 1
                    add(pf.Entity(pf.PROTECTED, otype, oid, name, pf.POLYGONS, parts, rank=rank, area=area))
                if feature_kind:
                    add(pf.Entity(pf.FEATURE, otype, oid, name, pf.POLYGONS, parts,
                                  kind=pf.FEATURE_KINDS[feature_kind], area=area))
        except Exception as e:  # noqa: BLE001 -- a broken multipolygon in the source
            skipped += 1
            progress(f"skip {o.type_str() if hasattr(o, 'type_str') else ''}{getattr(o, 'id', '?')} {name!r}: {type(e).__name__}")
    progress(f"{len(ents)} entities, {skipped} skipped")
    return list(ents.values())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("extract")
    ap.add_argument("--name", required=True)
    ap.add_argument("--set", required=True, dest="set_id")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    ents = build_entities(args.extract, progress=lambda m: print(m, file=sys.stderr))
    data = pf.encode(ents, args.set_id, args.name)
    with open(args.out, "wb") as f:
        f.write(data)
    counts = {layer: sum(1 for e in ents if e.layer == layer) for layer in (1, 2, 3, 4)}
    print(f"{args.out}: {len(data)} bytes; admin {counts[1]}, place {counts[2]}, protected {counts[3]}, "
          f"feature {counts[4]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
