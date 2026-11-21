"""Cut region packs into tiles named after their grid square. Rules: README.md, "Tiles".

Standard library only (make_fixture.py tiles the synthetic fixture with it).

The grid: micro-degree coordinates offset by 2^28 into [0, 2^29), split in two at every
depth along both axes. A tile's key is its path down the tree, one digit 0-3 per depth
(`2 × latitude bit + longitude bit`), so a client finds the tile for a point by computing
the point's key and looking for the one tile in the manifest that is a prefix of it.

A tile holds every entity within MARGIN_M of its square, clipped to that widened square:
everything the lookup rules can reach from a point inside the square (MAX_REACH_M,
4 km, plus room for rounding). Clipping a ring to a rectangle (Sutherland-Hodgman) keeps
the even-odd interior exactly, and the artificial edges it adds lie on the widened square's
border, farther from any point in the square than any rule reaches, so a lookup in the
tile gives the same result as a lookup over the whole merged set.
"""
from __future__ import annotations

import math

import packfmt as pf
from reference import GRID_BITS, GRID_ORIGIN as ORIGIN, find_tile, point_key  # noqa: F401 (re-exported)
MARGIN_M = 5000.0  # > the lookup's reach (4000 m); README "Tiles"
K = 6371008.8 * math.pi / 180.0
DEFAULT_CAP = 1_000_000
DEFAULT_MIN_DEPTH = 8   # 2^21 µdeg ≈ 2.1°
DEFAULT_MAX_DEPTH = 14  # 2^15 µdeg ≈ 3.6 km of latitude; the margin dominates below that


# -- the grid ------------------------------------------------------------------------------

def cell(key: str) -> tuple[int, int, int, int]:
    """(minLatM, minLonM, maxLatM, maxLonM) of a key's square; max is exclusive."""
    y = x = 0
    for d, ch in enumerate(key, 1):
        v = int(ch)
        y |= (v >> 1) << (GRID_BITS - d)
        x |= (v & 1) << (GRID_BITS - d)
    size = 1 << (GRID_BITS - len(key))
    return y - ORIGIN, x - ORIGIN, y - ORIGIN + size, x - ORIGIN + size


def margins(lat_lo: int, lat_hi: int) -> tuple[int, int]:
    """(latitude, longitude) margin in micro-degrees for a square spanning these
    latitudes: MARGIN_M everywhere in it, so longitude at its poleward edge."""
    mlat = math.ceil(MARGIN_M / K * 1e6)
    pole = min(max(abs(lat_lo - mlat), abs(lat_hi + mlat)) / 1e6, 89.0)
    mlon = math.ceil(MARGIN_M / (K * math.cos(math.radians(pole))) * 1e6)
    return mlat, mlon


def tile_box(key: str) -> tuple[int, int, int, int]:
    """The square widened by the margin: what a tile holds."""
    a, b, c, d = cell(key)
    mlat, mlon = margins(a, c)
    return a - mlat, b - mlon, c + mlat, d + mlon


# -- clipping ------------------------------------------------------------------------------

def _clip_ring(pts, box):
    """Sutherland-Hodgman against an axis-aligned box. Points are (latM, lonM)."""
    lat0, lon0, lat1, lon1 = box
    edges = (
        (lambda p: p[1] >= lon0, lambda a, b: (a[0] + (b[0] - a[0]) * (lon0 - a[1]) / (b[1] - a[1]), lon0)),
        (lambda p: p[1] <= lon1, lambda a, b: (a[0] + (b[0] - a[0]) * (lon1 - a[1]) / (b[1] - a[1]), lon1)),
        (lambda p: p[0] >= lat0, lambda a, b: (lat0, a[1] + (b[1] - a[1]) * (lat0 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= lat1, lambda a, b: (lat1, a[1] + (b[1] - a[1]) * (lat1 - a[0]) / (b[0] - a[0]))),
    )
    out = list(pts)
    for inside, cross in edges:
        if not out:
            break
        src, out = out, []
        prev = src[-1]
        for cur in src:
            if inside(cur):
                if not inside(prev):
                    out.append(cross(prev, cur))
                out.append(cur)
            elif inside(prev):
                out.append(cross(prev, cur))
            prev = cur
    ring = []
    for la, lo in out:
        p = (round(la), round(lo))
        if not ring or ring[-1] != p:
            ring.append(p)
    while len(ring) > 1 and ring[0] == ring[-1]:
        ring.pop()
    return ring if len(ring) >= 3 else []


def _clip_line(pts, box):
    """Liang-Barsky, segment by segment; a line leaving and re-entering gives several parts."""
    lat0, lon0, lat1, lon1 = box
    parts, cur = [], []

    def emit(p):
        q = (round(p[0]), round(p[1]))
        if not cur or cur[-1] != q:
            cur.append(q)

    for a, b in zip(pts, pts[1:]):
        dla, dlo = b[0] - a[0], b[1] - a[1]
        t0, t1 = 0.0, 1.0
        ok = True
        for p, q in ((-dlo, a[1] - lon0), (dlo, lon1 - a[1]), (-dla, a[0] - lat0), (dla, lat1 - a[0])):
            if p == 0:
                if q < 0:
                    ok = False
                    break
            else:
                t = q / p
                if p < 0:
                    t0 = max(t0, t)
                else:
                    t1 = min(t1, t)
        if not ok or t0 > t1:
            if len(cur) >= 2:
                parts.append(cur)
            cur = []
            continue
        start = (a[0] + t0 * dla, a[1] + t0 * dlo)
        end = (a[0] + t1 * dla, a[1] + t1 * dlo)
        if cur and t0 > 0.0:  # re-entered: a new part
            if len(cur) >= 2:
                parts.append(cur)
            cur = []
        emit(start)
        emit(end)
        if t1 < 1.0:  # leaves here
            if len(cur) >= 2:
                parts.append(cur)
            cur = []
    if len(cur) >= 2:
        parts.append(cur)
    return parts


def _bbox(parts):
    lats = [la for p in parts for la, _ in p]
    lons = [lo for p in parts for _, lo in p]
    return min(lats), min(lons), max(lats), max(lons)


class _Item:
    """An entity as it goes down the tree: its (possibly clipped) geometry, bbox, bytes."""
    __slots__ = ("e", "bbox", "blob")

    def __init__(self, e: pf.Entity, bbox, blob: bytes):
        self.e, self.bbox, self.blob = e, bbox, blob


def _item(e: pf.Entity) -> _Item:
    return _Item(e, _bbox(e.parts), pf.encode_entity(e))


def _clip(it: _Item, box) -> _Item | None:
    b = it.bbox
    if b[2] < box[0] or b[0] > box[2] or b[3] < box[1] or b[1] > box[3]:
        return None
    if box[0] <= b[0] and b[2] <= box[2] and box[1] <= b[1] and b[3] <= box[3]:
        return it  # wholly inside: unchanged
    e = it.e
    if e.geom_type == pf.POINT:
        return None  # a point's bbox is the point: not inside means outside
    if e.geom_type == pf.POLYGONS:
        parts = [r for r in (_clip_ring(p, box) for p in e.parts) if r]
    else:
        parts = [q for p in e.parts for q in _clip_line(p, box)]
    if not parts:
        return None
    c = pf.Entity(e.layer, e.otype, e.oid, e.name, e.geom_type, parts, level=e.level, label=e.label,
                  kind=e.kind, rank=e.rank, area=e.area)
    return _Item(c, _bbox(parts), pf.encode_entity(c))


# -- tiling --------------------------------------------------------------------------------

def merge(packs) -> list[pf.Entity]:
    """Entities of reference.Pack objects in order; an entity in several is taken from the
    first (the regions file's order), as a client did with overlapping packs before."""
    seen, out = set(), []
    for pk in packs:
        for e in pk.entities:
            if e.key in seen:
                continue
            seen.add(e.key)
            out.append(pf.Entity(e.layer, e.otype, e.oid, e.name, e.geom_type, [list(p) for p in e.parts],
                                 level=e.level, label=e.label, kind=e.kind, rank=e.rank, area=e.area))
    return out


def tile(entities: list[pf.Entity], set_id: str, cap: int = DEFAULT_CAP, min_depth: int = DEFAULT_MIN_DEPTH,
         max_depth: int = DEFAULT_MAX_DEPTH, progress=lambda m: None) -> dict[str, bytes]:
    """key -> tile bytes. Every square at `min_depth` with something within its margin
    becomes a tile, split in four while its tile is over `cap` bytes and it is shallower
    than `max_depth`. Squares with nothing in reach get no tile."""
    if not 1 <= min_depth <= max_depth <= GRID_BITS:
        raise ValueError("bad depths")
    items = [_item(e) for e in entities]
    # Seed the min-depth squares each item can reach.
    size = 1 << (GRID_BITS - min_depth)
    seeds: dict[str, list[_Item]] = {}
    for it in items:
        mlat, mlon = margins(it.bbox[0], it.bbox[2])
        for y in range((it.bbox[0] - mlat + ORIGIN) // size, (it.bbox[2] + mlat + ORIGIN) // size + 1):
            for x in range((it.bbox[1] - mlon + ORIGIN) // size, (it.bbox[3] + mlon + ORIGIN) // size + 1):
                if 0 <= y < (1 << min_depth) and 0 <= x < (1 << min_depth):
                    key = "".join(str(((y >> (min_depth - d)) & 1) * 2 + ((x >> (min_depth - d)) & 1))
                                  for d in range(1, min_depth + 1))
                    seeds.setdefault(key, []).append(it)
    out: dict[str, bytes] = {}

    def visit(key: str, cand: list[_Item]) -> None:
        box = tile_box(key)
        kept = [c for c in (_clip(it, box) for it in cand) if c is not None]
        if not kept:
            return
        total = pf.header_size(set_id, key) + sum(len(c.blob) for c in kept)
        if total > cap and len(key) < max_depth:
            for d in "0123":
                visit(key + d, kept)
            return
        if total > cap:
            progress(f"tile {key} is {total:,} bytes at the maximum depth")
        hdr = (max(box[0], -90_000_000), max(box[1], -180_000_000), min(box[2], 90_000_000), min(box[3], 180_000_000))
        out[key] = pf.encode([c.e for c in kept], set_id, key, hdr, blobs={c.e.key(): c.blob for c in kept})

    for n, key in enumerate(sorted(seeds), 1):
        visit(key, seeds[key])
        progress(f"square {n}/{len(seeds)} ({key}): {len(out)} tiles so far")
    return dict(sorted(out.items()))
