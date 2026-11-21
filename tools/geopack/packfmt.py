"""Encoder for region packs (format: README.md next to this file). Standard library only,
so make_fixture.py can use it without osmium or shapely installed."""
from __future__ import annotations

import re
import struct
from dataclasses import dataclass, field

MAGIC = b"AGEO"
FORMAT_VERSION = 1
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"
BBOX_MARGIN_MICRO = 30_000

ADMIN, PLACE, PROTECTED, FEATURE = 1, 2, 3, 4
POINT, LINES, POLYGONS = 1, 2, 3

PLACE_KINDS = {"city": 1, "town": 2, "village": 3, "suburb": 4, "quarter": 5, "hamlet": 6,
               "neighbourhood": 7, "locality": 8}
FEATURE_KINDS = {"peak": 1, "volcano": 2, "saddle": 3, "ridge": 4, "arete": 5, "valley": 6,
                 "cliff": 7, "gorge": 8, "cape": 9, "mountain_range": 10, "massif": 11, "bay": 12,
                 "beach": 13, "glacier": 14, "water": 15, "plateau": 16, "island": 17, "islet": 18}

SET_RE = re.compile(r"^[0-9A-Za-z._-]{1,20}$")
NAME_RE = re.compile(r"^[a-z0-9-]{1,64}$")
CONTROL_RE = re.compile(r"[\x00-\x1f\x7f-\x9f]")
TYPE_ORDER = {"n": 0, "w": 1, "r": 2}


def valid_name(s: str) -> bool:
    return 1 <= len(s) <= 200 and not CONTROL_RE.search(s)


@dataclass
class Entity:
    layer: int
    otype: str  # 'n' | 'w' | 'r'
    oid: int
    name: str
    geom_type: int
    # POINT: [[(latM, lonM)]]; LINES/POLYGONS: list of parts, each a list of (latM, lonM)
    parts: list
    level: int = 0       # admin
    label: int = 0       # admin
    kind: int = 0        # place / feature
    rank: int = 0        # protected
    area: int = 0        # admin / protected / feature
    extra: dict = field(default_factory=dict)

    def key(self):
        return (self.layer, TYPE_ORDER[self.otype], self.oid)


def _varint(out: bytearray, v: int) -> None:
    if v < 0:
        raise ValueError(v)
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def _zigzag(out: bytearray, v: int) -> None:
    _varint(out, (v << 1) ^ (v >> 63))


def _vstr(out: bytearray, s: str) -> None:
    b = s.encode("utf-8")
    _varint(out, len(b))
    out += b


def _check(e: Entity) -> None:
    if e.otype not in TYPE_ORDER or e.oid < 1:
        raise ValueError(f"bad id {e.otype}{e.oid}")
    if not valid_name(e.name):
        raise ValueError(f"bad name {e.name!r}")
    want = {ADMIN: {POLYGONS}, PROTECTED: {POLYGONS}, PLACE: {POINT}, FEATURE: {POINT, LINES, POLYGONS}}[e.layer]
    if e.geom_type not in want:
        raise ValueError(f"{e.otype}{e.oid}: geometry {e.geom_type} not allowed in layer {e.layer}")
    if e.geom_type == POINT:
        if len(e.parts) != 1 or len(e.parts[0]) != 1:
            raise ValueError(f"{e.otype}{e.oid}: a point has one coordinate")
    else:
        minimum = 2 if e.geom_type == LINES else 3
        if not e.parts or any(len(p) < minimum for p in e.parts):
            raise ValueError(f"{e.otype}{e.oid}: part with fewer than {minimum} points")
    if e.layer == ADMIN and not 5 <= e.level <= 8:
        raise ValueError(f"{e.otype}{e.oid}: admin level {e.level}")
    if e.layer == PLACE and e.kind not in PLACE_KINDS.values():
        raise ValueError(f"{e.otype}{e.oid}: place kind {e.kind}")
    if e.layer == FEATURE and e.kind not in FEATURE_KINDS.values():
        raise ValueError(f"{e.otype}{e.oid}: feature kind {e.kind}")
    if e.layer == PROTECTED and e.rank not in (0, 1):
        raise ValueError(f"{e.otype}{e.oid}: rank {e.rank}")


def bbox_of(entities: list[Entity]) -> tuple[int, int, int, int]:
    lats = [la for e in entities for part in e.parts for la, _ in part]
    lons = [lo for e in entities for part in e.parts for _, lo in part]
    if not lats:
        raise ValueError("no entities")
    m = BBOX_MARGIN_MICRO
    return (max(min(lats) - m, -90_000_000), max(min(lons) - m, -180_000_000),
            min(max(lats) + m, 90_000_000), min(max(lons) + m, 180_000_000))


def encode(entities: list[Entity], set_id: str, name: str, bbox: tuple[int, int, int, int] | None = None) -> bytes:
    if not SET_RE.match(set_id):
        raise ValueError(f"bad set id {set_id!r}")
    if not NAME_RE.match(name):
        raise ValueError(f"bad pack name {name!r}")
    ents = sorted(entities, key=Entity.key)
    keys = [e.key() for e in ents]
    if len(set(keys)) != len(keys):
        raise ValueError("duplicate entity")
    for e in ents:
        _check(e)
    if bbox is None:
        bbox = bbox_of(ents)

    out = bytearray(32)
    _vstr(out, set_id)
    _vstr(out, name)
    _vstr(out, ATTRIBUTION)
    for e in ents:
        out.append(e.layer)
        out += e.otype.encode("ascii")
        _varint(out, e.oid)
        _vstr(out, e.name)
        if e.layer == ADMIN:
            out.append(e.level)
            _varint(out, e.area)
            _varint(out, e.label)
        elif e.layer == PLACE:
            out.append(e.kind)
        elif e.layer == PROTECTED:
            out.append(e.rank)
            _varint(out, e.area)
        else:
            out.append(e.kind)
            _varint(out, e.area)
        out.append(e.geom_type)
        if e.geom_type == POINT:
            la, lo = e.parts[0][0]
            _zigzag(out, la)
            _zigzag(out, lo)
        else:
            _varint(out, len(e.parts))
            pla = plo = 0
            for part in e.parts:
                _varint(out, len(part))
                for la, lo in part:
                    _zigzag(out, la - pla)
                    _zigzag(out, lo - plo)
                    pla, plo = la, lo
    struct.pack_into("<4sHHII4i", out, 0, MAGIC, FORMAT_VERSION, 0, len(ents), len(out), *bbox)
    return bytes(out)
