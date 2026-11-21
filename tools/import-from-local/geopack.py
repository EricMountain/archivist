"""Region-pack reader and lookup (format and rules: tools/geopack/README.md).

A copy of tools/geopack/reference.py's indexed lookup, kept here so this tool stays
self-contained, the way gazetteer.py is. It must pass tools/geopack/vectors.json
(tests/test_geopack.py). places.py combines it with the gazetteer. Standard library only.
"""
from __future__ import annotations

import math
import struct
from dataclasses import dataclass

ADMIN, PLACE, PROTECTED, FEATURE = 1, 2, 3, 4
POINT, LINES, POLYGONS = 1, 2, 3
TYPE_ORDER = {"n": 0, "w": 1, "r": 2}

K = 6371008.8 * math.pi / 180.0
SEA_FALLBACK_M = 2000.0
SUB_RADIUS = {1: 4000.0, 2: 2500.0, 3: 1500.0, 4: 1200.0}            # city, town, village, suburb
SPOT_RADIUS = {5: 800.0, 6: 800.0, 7: 500.0, 8: 400.0}              # quarter, hamlet, neighbourhood, locality
REACH = {1: 600.0, 2: 600.0, 3: 300.0, 4: 500.0, 5: 400.0, 6: 300.0, 7: 200.0, 8: 300.0, 9: 300.0}
MARGIN = {13: 150.0, 15: 100.0}                                     # beach, water
NO_LOCALITY_AREA_CAP = 10_000_000
MAX_AREAS = 3
# Widest distance any rule looks at: the sea fallback.
MAX_REACH_M = max(SEA_FALLBACK_M, *SUB_RADIUS.values(), *SPOT_RADIUS.values(), *REACH.values(), *MARGIN.values())


@dataclass
class Entity:
    layer: int
    otype: str
    oid: int
    name: str
    level: int
    label: int
    kind: int
    rank: int
    area: int
    geom_type: int
    parts: list  # list of lists of (latM, lonM)
    bbox: tuple  # minLatM, minLonM, maxLatM, maxLonM

    @property
    def id(self) -> str:
        return f"{self.otype}{self.oid}"

    @property
    def id_key(self):
        return (TYPE_ORDER[self.otype], self.oid)

    @property
    def key(self):
        return (self.layer, self.otype, self.oid)


@dataclass
class Pack:
    set: str
    name: str
    attribution: str
    bbox: tuple
    entities: list


def _varint(buf, pos):
    v = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if b < 0x80:
            return v, pos
        shift += 7


def _zigzag(buf, pos):
    v, pos = _varint(buf, pos)
    return (v >> 1) ^ -(v & 1), pos


def _vstr(buf, pos):
    n, pos = _varint(buf, pos)
    return bytes(buf[pos:pos + n]).decode("utf-8"), pos + n


def read_pack(data: bytes) -> Pack:
    magic, version, _, count, file_len, *bbox = struct.unpack_from("<4sHHII4i", data, 0)
    if magic != b"AGEO" or version != 1 or file_len != len(data):
        raise ValueError("not a version-1 region pack")
    pos = 32
    set_id, pos = _vstr(data, pos)
    name, pos = _vstr(data, pos)
    attribution, pos = _vstr(data, pos)
    ents = []
    for _ in range(count):
        layer = data[pos]
        otype = chr(data[pos + 1])
        pos += 2
        oid, pos = _varint(data, pos)
        ename, pos = _vstr(data, pos)
        level = label = kind = rank = area = 0
        if layer == ADMIN:
            level = data[pos]
            area, pos = _varint(data, pos + 1)
            label, pos = _varint(data, pos)
        elif layer == PLACE:
            kind = data[pos]
            pos += 1
        elif layer == PROTECTED:
            rank = data[pos]
            area, pos = _varint(data, pos + 1)
        elif layer == FEATURE:
            kind = data[pos]
            area, pos = _varint(data, pos + 1)
        else:
            raise ValueError(f"unknown layer {layer}")
        geom_type = data[pos]
        pos += 1
        if geom_type == POINT:
            la, pos = _zigzag(data, pos)
            lo, pos = _zigzag(data, pos)
            parts = [[(la, lo)]]
        else:
            nparts, pos = _varint(data, pos)
            parts, la, lo = [], 0, 0
            for _ in range(nparts):
                npts, pos = _varint(data, pos)
                part = []
                for _ in range(npts):
                    d, pos = _zigzag(data, pos)
                    la += d
                    d, pos = _zigzag(data, pos)
                    lo += d
                    part.append((la, lo))
                parts.append(part)
        lats = [p[0] for part in parts for p in part]
        lons = [p[1] for part in parts for p in part]
        ents.append(Entity(layer, otype, oid, ename, level, label, kind, rank, area, geom_type, parts,
                           (min(lats), min(lons), max(lats), max(lons))))
    if pos != len(data):
        raise ValueError("trailing bytes")
    return Pack(set_id, name, attribution, tuple(bbox), ents)


def covers(bbox, lat: float, lon: float) -> bool:
    la, lo = lat * 1e6, lon * 1e6
    return bbox[0] <= la <= bbox[2] and bbox[1] <= lo <= bbox[3]


# -- geometry ----------------------------------------------------------------------------

class Plane:
    def __init__(self, lat: float, lon: float):
        self.lat, self.lon = lat, lon
        self.kc = K * math.cos(lat * math.pi / 180.0)

    def xy(self, p):
        d_lon = p[1] / 1e6 - self.lon
        if d_lon >= 180.0:
            d_lon -= 360.0
        if d_lon < -180.0:
            d_lon += 360.0
        return d_lon * self.kc, (p[0] / 1e6 - self.lat) * K


def _seg_dist(ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    den = dx * dx + dy * dy
    if den == 0.0:
        return math.hypot(ax, ay)
    t = -(ax * dx + ay * dy) / den
    if t < 0.0:
        t = 0.0
    elif t > 1.0:
        t = 1.0
    return math.hypot(ax + t * dx, ay + t * dy)


def _edges(pl: Plane, e: Entity, closed: bool):
    for part in e.parts:
        pts = [pl.xy(p) for p in part]
        n = len(pts)
        for i in range(n if closed else n - 1):
            yield pts[i], pts[(i + 1) % n]


def inside(pl: Plane, e: Entity) -> bool:
    res = False
    for (ax, ay), (bx, by) in _edges(pl, e, True):
        if (ay > 0.0) != (by > 0.0):
            if ax + (0.0 - ay) * (bx - ax) / (by - ay) > 0.0:
                res = not res
    return res


def dist(pl: Plane, e: Entity) -> float:
    if e.geom_type == POINT:
        x, y = pl.xy(e.parts[0][0])
        return math.hypot(x, y)
    if e.geom_type == POLYGONS and inside(pl, e):
        return 0.0
    return min(_seg_dist(ax, ay, bx, by) for (ax, ay), (bx, by) in _edges(pl, e, e.geom_type == POLYGONS))


# -- lookup ------------------------------------------------------------------------------

def _ref(e: Entity) -> dict:
    return {"id": e.id, "name": e.name}


def resolve(lat: float, lon: float, cands) -> dict:
    """The README's rules over `cands`, every entity that could matter for this point
    (a superset is fine: the rules check distances themselves)."""
    pl = Plane(lat, lon)
    admins = [e for e in cands if e.layer == ADMIN]
    containing = [e for e in admins if inside(pl, e)]
    loc = None
    inner = [e for e in containing if 6 <= e.level <= 8]
    if inner:
        loc = min(inner, key=lambda e: (-e.level, e.area, e.id_key))
    else:
        near = [(dist(pl, e), e) for e in admins if 6 <= e.level <= 8]
        near = [(d, e) for d, e in near if d <= SEA_FALLBACK_M]
        if near:
            loc = min(near, key=lambda de: (de[0], -de[1].level, de[1].area, de[1].id_key))[1]
    suppressed = {e.label for e in containing if e.label}
    if loc is not None and loc.label:
        suppressed.add(loc.label)
    loc_name = loc.name if loc else None
    # When the locality contains the point, sub and spot must lie inside it too: a town
    # across a river or a commune border is a different place, however close.
    bound = loc if inner else None

    def nearest_place(radii, other_name):
        best = None
        for e in cands:
            if e.layer != PLACE or e.kind not in radii:
                continue
            if e.otype == "n" and e.oid in suppressed:
                continue
            if e.name == loc_name or e.name == other_name:
                continue
            d = dist(pl, e)
            r = radii[e.kind]
            if d <= r and (bound is None or inside(Plane(e.parts[0][0][0] / 1e6, e.parts[0][0][1] / 1e6), bound)):
                score = (d / r, e.id_key)
                if best is None or score < best[0]:
                    best = (score, e)
        return best[1] if best else None

    sub = nearest_place(SUB_RADIUS, None)
    spot = nearest_place(SPOT_RADIUS, sub.name if sub else None)

    areas = sorted((e for e in cands if e.layer == PROTECTED and inside(pl, e)),
                   key=lambda e: (e.rank, e.area, e.id_key))[:MAX_AREAS]

    feature = None
    best = None
    for e in cands:
        if e.layer != FEATURE or e.geom_type == POLYGONS:
            continue
        r = REACH.get(e.kind, 0.0)
        if r <= 0.0:
            continue
        d = dist(pl, e)
        if d <= r:
            score = (d / r, e.id_key)
            if best is None or score < best[0]:
                best = (score, e)
    if best:
        feature = best[1]
    else:
        for e in cands:
            if e.layer != FEATURE or e.geom_type != POLYGONS:
                continue
            if loc is not None:
                if not e.area * 2 < loc.area:
                    continue
            elif e.area > NO_LOCALITY_AREA_CAP:
                continue
            m = MARGIN.get(e.kind, 0.0)
            ok = inside(pl, e) if m == 0.0 else dist(pl, e) <= m
            if ok:
                score = (e.area, e.id_key)
                if best is None or score < best[0]:
                    best = (score, e)
        if best:
            feature = best[1]

    out: dict = {}
    if loc is not None:
        out["locality"] = _ref(loc)
    if sub is not None:
        out["sub"] = _ref(sub)
    if spot is not None:
        out["spot"] = _ref(spot)
    if areas:
        out["areas"] = [_ref(e) for e in areas]
    if feature is not None:
        out["feature"] = _ref(feature)
    return out


CELL = 100_000  # 0.1° in micro-degrees


class PackSet:
    """Packs in manifest order. An entity in several packs is taken from the first."""

    def __init__(self, packs: list[Pack]):
        self.packs = packs
        seen = set()
        self.entities = []
        for p in packs:
            for e in p.entities:
                if e.key not in seen:
                    seen.add(e.key)
                    self.entities.append(e)
        self.grid: dict[tuple[int, int], list[int]] = {}
        # Index each entity under every cell its bbox, widened by MAX_REACH_M, touches.
        lat_pad = int(MAX_REACH_M / K * 1e6) + 1
        for i, e in enumerate(self.entities):
            # Longitude padding at the entity's poleward edge (plus the latitude padding),
            # where a metre is the most longitude: conservative for the whole entity.
            poleward = min(max(abs(e.bbox[0]), abs(e.bbox[2])) / 1e6 + lat_pad / 1e6, 89.9)
            lon_pad = int(MAX_REACH_M / (K * max(math.cos(math.radians(poleward)), 0.01)) * 1e6) + 1
            for cy in range((e.bbox[0] - lat_pad) // CELL, (e.bbox[2] + lat_pad) // CELL + 1):
                for cx in range((e.bbox[1] - lon_pad) // CELL, (e.bbox[3] + lon_pad) // CELL + 1):
                    self.grid.setdefault((cy, cx), []).append(i)

    def covering(self, lat: float, lon: float) -> "PackSet":
        return PackSet([p for p in self.packs if covers(p.bbox, lat, lon)])

    def lookup(self, lat: float, lon: float) -> dict:
        cell = (math.floor(lat * 1e6) // CELL, math.floor(lon * 1e6) // CELL)
        return resolve(lat, lon, [self.entities[i] for i in self.grid.get(cell, [])])

    def brute(self, lat: float, lon: float) -> dict:
        return resolve(lat, lon, self.entities)


def load_manifest_packs(manifest: dict, read_bytes) -> list[Pack]:
    """`read_bytes(path)` returns a pack's bytes; packs come back in manifest order."""
    if manifest.get("format") != 1:
        raise ValueError("unsupported manifest format")
    return [read_pack(read_bytes(p["path"])) for p in manifest["packs"]]


def lookup_with_manifest(manifest: dict, packs: list[Pack], lat: float, lon: float) -> dict:
    """The full pack-layer lookup: only the packs whose manifest bbox covers the point."""
    chosen = [pk for entry, pk in zip(manifest["packs"], packs) if covers(entry["bbox"], lat, lon)]
    return PackSet(chosen).lookup(lat, lon)
