"""Reference reader and lookup for gazetteer.bin (format: README.md next to this file).

This is the oracle behind vectors.json, not the production reader. It has two lookups:

* `lookup()` is the cell-ring search every client implements (README "Lookup").
* `brute()` is a linear scan over every place. It is slow and obviously correct.

make_vectors.py asserts the two agree before it writes anything.
"""
import math
import struct

MICRO = 1_000_000
EARTH_RADIUS_KM = 6371.0
LOCALITY_MAX_KM = 25.0
REGION_MAX_KM = 100.0


def haversine_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(min(1.0, math.sqrt(a)))


def _varint(buf, pos):
    v = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if b < 0x80:
            return v, pos
        shift += 7


def _vstr(buf, pos):
    n, pos = _varint(buf, pos)
    return bytes(buf[pos:pos + n]).decode("utf-8"), pos + n


class Gazetteer:
    def __init__(self, data):
        self.buf = memoryview(data)
        (magic, version, src_len, self.place_count, self.region_count, cell_count,
         self.region_index_off, cell_index_off, file_len) = struct.unpack_from("<4sHHIIIIII", data, 0)
        if magic != b"AGAZ" or version != 1 or file_len != len(data):
            raise ValueError("not a version-1 gazetteer.bin")
        self.src = bytes(self.buf[32:32 + src_len]).decode("ascii")
        self.cells = {}
        for i in range(cell_count):
            key, count, off = struct.unpack_from("<HHI", data, cell_index_off + 8 * i)
            self.cells[key] = (count, off)

    def region(self, index):
        """-> (key, name): key is "<cc>.<code>", or just "<cc>" with name "" (no region)."""
        (off,) = struct.unpack_from("<I", self.buf, self.region_index_off + 4 * index)
        key, off = _vstr(self.buf, off)
        name, _ = _vstr(self.buf, off)
        return key, name

    def cell(self, lat_cell, lon_cell):
        """Yield (geonameid, lat_micro, lon_micro, region_index, name) for one cell."""
        key = (lat_cell + 90) * 360 + (lon_cell + 180)
        if key not in self.cells:
            return
        count, pos = self.cells[key]
        gid = 0
        for _ in range(count):
            d, pos = _varint(self.buf, pos)
            dlat, pos = _varint(self.buf, pos)
            dlon, pos = _varint(self.buf, pos)
            region, pos = _varint(self.buf, pos)
            name, pos = _vstr(self.buf, pos)
            gid += d
            yield gid, lat_cell * MICRO + dlat, lon_cell * MICRO + dlon, region, name

    def places(self):
        for key in sorted(self.cells):
            yield from self.cell(key // 360 - 90, key % 360 - 180)

    # -- results ---------------------------------------------------------------------

    def place(self, best):
        """(distance_km, record) or None -> the `place` object design.md "Location" defines."""
        if best is None or best[0] > REGION_MAX_KM:
            return None
        dist, (gid, _, _, region, name) = best
        key, region_name = self.region(region)
        out = {"cc": key[:2]}
        if len(key) > 2:
            out["region"] = {"id": key, "name": region_name}
        if dist <= LOCALITY_MAX_KM:
            out["locality"] = {"id": str(gid), "name": name}
        out["src"] = self.src
        return out

    @staticmethod
    def _better(cand, best):
        # Nearer wins; an exact distance tie goes to the smaller geonameid.
        return best is None or (cand[0], cand[1][0]) < (best[0], best[1][0])

    def brute(self, lat, lon):
        lon = (lon + 180.0) % 360.0 - 180.0
        best = None
        for rec in self.places():
            cand = (haversine_km(lat, lon, rec[1] / MICRO, rec[2] / MICRO), rec)
            if self._better(cand, best):
                best = cand
        return best if best and best[0] <= REGION_MAX_KM else None

    def lookup(self, lat, lon):
        lon = (lon + 180.0) % 360.0 - 180.0
        qlat, qlon = min(math.floor(lat), 89), math.floor(lon)
        best = None
        r = 0
        while True:
            for la in range(max(qlat - r, -90), min(qlat + r, 89) + 1):
                if row_cannot_matter(la, lat, best):
                    continue
                if abs(la - qlat) == r or 2 * r + 1 >= 360:
                    los = range(qlon - r, qlon + r + 1)
                else:
                    los = (qlon - r, qlon + r)
                seen = set()
                for lo in los:
                    lo = (lo + 180) % 360 - 180
                    if lo in seen:
                        continue
                    seen.add(lo)
                    for rec in self.cell(la, lo):
                        cand = (haversine_km(lat, lon, rec[1] / MICRO, rec[2] / MICRO), rec)
                        if self._better(cand, best):
                            best = cand
            bound = outside_bound_km(lat, lon, qlat, qlon, r)
            limit = REGION_MAX_KM if best is None else min(best[0], REGION_MAX_KM)
            if bound > limit:
                return best if best and best[0] <= REGION_MAX_KM else None
            r += 1


def row_cannot_matter(lat_cell, lat, best):
    """True when every place in the row of cells `lat_cell` is provably further than
    min(best, 100 km), so the row can be skipped without changing the result.

    A great-circle distance is at least the latitude difference times R, and places in
    row `lat_cell` have latitudes in [lat_cell, lat_cell + 1]. A skipped row can hold
    neither a nearer place, nor an equal-distance tie, nor anything within 100 km. The
    1 m slack keeps rounding in the bound from ever skipping a tie.

    It matters near the poles: above about 89° the east/west bound never exceeds 100 km,
    so without this the rings grow until they wrap the globe and decode nearly every
    place (over a second per lookup). The Kotlin reader applies the same prune
    (ReverseGeocoder.kt, `rowCannotMatter`); `brute()` is untouched and still the oracle.
    """
    if lat < lat_cell:
        gap = lat_cell - lat
    elif lat > lat_cell + 1:
        gap = lat - (lat_cell + 1)
    else:
        return False
    limit = REGION_MAX_KM if best is None else min(best[0], REGION_MAX_KM)
    return math.radians(gap) * EARTH_RADIUS_KM > limit + 0.001


def outside_bound_km(lat, lon, qlat, qlon, r):
    """Lower bound on the distance from (lat, lon) to any point outside the block of cells
    within ring r of (qlat, qlon). Infinite on a side that has no cells beyond it."""
    bounds = [math.inf]
    north, south = qlat + r + 1, qlat - r
    if north < 90:
        bounds.append(math.radians(north - lat) * EARTH_RADIUS_KM)
    if south > -90:
        bounds.append(math.radians(lat - south) * EARTH_RADIUS_KM)
    if 2 * r + 1 < 360:
        dlon = min(lon - (qlon - r), (qlon + r + 1) - lon)
        if dlon >= 90:
            bounds.append((math.pi / 2 - math.radians(abs(lat))) * EARTH_RADIUS_KM)
        else:
            x = math.sin(math.radians(dlon)) * math.cos(math.radians(lat))
            bounds.append(math.asin(min(1.0, x)) * EARTH_RADIUS_KM)
    return min(bounds)
