"""Offline reverse geocoder over the repo's bundled `gazetteer.bin` -- the laptop
importer's counterpart of the Android app's `domain/ReverseGeocoder.kt`.

Both read the same file and must reproduce `tools/gazetteer/vectors.json` exactly
(docs/plans/geolocation.md, decision D10); `tests/test_gazetteer.py` checks that. The
binary format and the lookup rule are specified in `tools/gazetteer/README.md`. This is
a port of `tools/gazetteer/reference.py`'s ring search, with the same result-preserving
row prune the Kotlin reader has (see `_row_cannot_matter`), kept as its own module so the
importer doesn't depend on the build tooling's files.

Where the `.bin` comes from, first match wins:

1. an explicit path (`main.py --gazetteer PATH`);
2. the `ARCHIVIST_GAZETTEER` environment variable;
3. the copy the Android app bundles, found relative to this file:
   `<repo>/android/app/src/main/assets/gazetteer.bin`.
"""

from __future__ import annotations

import math
import os
import struct

ENV_VAR = "ARCHIVIST_GAZETTEER"
DEFAULT_PATH = os.path.normpath(
    os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "..", "android", "app", "src", "main", "assets", "gazetteer.bin",
    )
)

MICRO = 1_000_000
EARTH_RADIUS_KM = 6371.0
LOCALITY_MAX_KM = 25.0
REGION_MAX_KM = 100.0


def resolve_path(explicit: str | None = None) -> str:
    """The `.bin` to load: `explicit`, else `$ARCHIVIST_GAZETTEER`, else the repo's copy."""
    return explicit or os.environ.get(ENV_VAR) or DEFAULT_PATH


def load(explicit: str | None = None) -> "Gazetteer":
    path = resolve_path(explicit)
    try:
        with open(path, "rb") as f:
            return Gazetteer(f.read())
    except FileNotFoundError:
        raise FileNotFoundError(
            f"gazetteer not found at {path}: pass --gazetteer PATH or set {ENV_VAR}"
        ) from None


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    # Same operation order as reference.py and ReverseGeocoder.kt, so the roundings match.
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(min(1.0, math.sqrt(a)))


def _varint(buf, pos: int) -> tuple[int, int]:
    v = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if b < 0x80:
            return v, pos
        shift += 7


class Gazetteer:
    """One decoded `gazetteer.bin`, format version 1. Only the header and the cell index
    are read up front; cells are decoded when a lookup visits them."""

    def __init__(self, data: bytes):
        self._buf = memoryview(data)
        if len(data) < 32:
            raise ValueError("not a gazetteer.bin")
        (magic, version, src_len, _places, self._region_count, cell_count,
         self._region_index_off, cell_index_off, file_len) = struct.unpack_from("<4sHHIIIIII", data, 0)
        if magic != b"AGAZ":
            raise ValueError("not a gazetteer.bin")
        if version != 1:
            raise ValueError(f"unsupported gazetteer.bin version {version}")
        if file_len != len(data):
            raise ValueError(f"gazetteer.bin is {len(data)} bytes, header says {file_len}")
        self.src = bytes(self._buf[32:32 + src_len]).decode("ascii")
        self._cells: dict[int, tuple[int, int]] = {}
        for i in range(cell_count):
            key, count, off = struct.unpack_from("<HHI", data, cell_index_off + 8 * i)
            self._cells[key] = (count, off)

    # -- public ------------------------------------------------------------------

    def lookup(self, lat: float, lon: float) -> dict | None:
        """The `place` object (design.md "Location") for (lat, lon) in degrees, or None
        when no populated place lies within 100 km. Keys `cc`, `region`, `locality`,
        `src`, with absent levels omitted -- exactly vectors.json's shape. A latitude
        outside [-90, 90] or a non-finite input also gives None, as in the Kotlin reader."""
        if not (math.isfinite(lat) and math.isfinite(lon)) or lat < -90.0 or lat > 90.0:
            return None
        best = self._nearest(lat, lon)
        return self._place(best)

    # -- search ------------------------------------------------------------------

    def _nearest(self, lat: float, lon: float):
        """README "Lookup", recommended search, plus the row prune. Returns
        (distance_km, geonameid, region_index, name_offset, name_length) or None."""
        lon = (lon + 180.0) % 360.0 - 180.0
        qlat, qlon = min(math.floor(lat), 89), math.floor(lon)
        best = None
        r = 0
        while True:
            for la in range(max(qlat - r, -90), min(qlat + r, 89) + 1):
                if _row_cannot_matter(la, lat, best):
                    continue
                if abs(la - qlat) == r or 2 * r + 1 >= 360:
                    # A whole row of the ring, each column once even when it wraps.
                    n = min(2 * r + 1, 360)
                    for i in range(n):
                        best = self._scan_cell(la, _wrap_cell(qlon - r + i), lat, lon, best)
                else:
                    # Only the two side columns; 2r < 359 here, so they never coincide.
                    best = self._scan_cell(la, _wrap_cell(qlon - r), lat, lon, best)
                    best = self._scan_cell(la, _wrap_cell(qlon + r), lat, lon, best)
            bound = _outside_bound_km(lat, lon, qlat, qlon, r)
            limit = REGION_MAX_KM if best is None else min(best[0], REGION_MAX_KM)
            if bound > limit:
                return best
            r += 1

    def _scan_cell(self, lat_cell: int, lon_cell: int, lat: float, lon: float, best):
        entry = self._cells.get((lat_cell + 90) * 360 + (lon_cell + 180))
        if entry is None:
            return best
        count, pos = entry
        buf = self._buf
        gid = 0
        lat_base, lon_base = lat_cell * MICRO, lon_cell * MICRO
        for _ in range(count):
            d, pos = _varint(buf, pos)
            dlat, pos = _varint(buf, pos)
            dlon, pos = _varint(buf, pos)
            region, pos = _varint(buf, pos)
            name_len, pos = _varint(buf, pos)
            name_off = pos
            pos += name_len
            gid += d
            dist = haversine_km(lat, lon, (lat_base + dlat) / MICRO, (lon_base + dlon) / MICRO)
            # Nearer wins; an exact distance tie goes to the smaller geonameid.
            if best is None or dist < best[0] or (dist == best[0] and gid < best[1]):
                best = (dist, gid, region, name_off, name_len)
        return best

    # -- results -----------------------------------------------------------------

    def _place(self, best) -> dict | None:
        if best is None or best[0] > REGION_MAX_KM:
            return None
        dist, gid, region, name_off, name_len = best
        key, region_name = self._region(region)
        out: dict = {"cc": key[:2]}
        if len(key) > 2:
            out["region"] = {"id": key, "name": region_name}
        if dist <= LOCALITY_MAX_KM:
            out["locality"] = {"id": str(gid), "name": self._utf8(name_off, name_len)}
        out["src"] = self.src
        return out

    def _region(self, index: int) -> tuple[str, str]:
        (off,) = struct.unpack_from("<I", self._buf, self._region_index_off + 4 * index)
        n, off = _varint(self._buf, off)
        key = self._utf8(off, n)
        off += n
        n, off = _varint(self._buf, off)
        return key, self._utf8(off, n)

    def _utf8(self, off: int, n: int) -> str:
        return bytes(self._buf[off:off + n]).decode("utf-8")


def _row_cannot_matter(lat_cell: int, lat: float, best) -> bool:
    """Skip a row of cells whose latitude gap alone puts every place in it beyond
    min(best, 100 km): a great-circle distance is at least the latitude difference
    times R, so the result can't change. Above ~89° the east/west bound never exceeds
    100 km and, without this, the rings wrap the globe. 1 m slack so rounding never
    skips a tie. Same rule as reference.py and ReverseGeocoder.kt."""
    if lat < lat_cell:
        gap = lat_cell - lat
    elif lat > lat_cell + 1:
        gap = lat - (lat_cell + 1)
    else:
        return False
    limit = REGION_MAX_KM if best is None else min(best[0], REGION_MAX_KM)
    return math.radians(gap) * EARTH_RADIUS_KM > limit + 0.001


def _wrap_cell(lon_cell: int) -> int:
    return (lon_cell + 180) % 360 - 180


def _outside_bound_km(lat: float, lon: float, qlat: int, qlon: int, r: int) -> float:
    """Lower bound on the distance from (lat, lon) to anything outside the block of
    cells within ring r of (qlat, qlon); README "Lookup", step 3 of the search."""
    bound = math.inf
    north, south = qlat + r + 1, qlat - r
    if north < 90:
        bound = min(bound, math.radians(north - lat) * EARTH_RADIUS_KM)
    if south > -90:
        bound = min(bound, math.radians(lat - south) * EARTH_RADIUS_KM)
    if 2 * r + 1 < 360:
        dlon = min(lon - (qlon - r), (qlon + r + 1) - lon)
        if dlon >= 90:
            bound = min(bound, (math.pi / 2 - math.radians(abs(lat))) * EARTH_RADIUS_KM)
        else:
            x = math.sin(math.radians(dlon)) * math.cos(math.radians(lat))
            bound = min(bound, math.asin(min(1.0, x)) * EARTH_RADIUS_KM)
    return bound
