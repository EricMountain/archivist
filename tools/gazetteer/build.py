#!/usr/bin/env python3
"""Build android/app/src/main/assets/gazetteer.bin from a GeoNames snapshot.

Inputs (https://download.geonames.org/export/dump/, CC BY 4.0):
  cities500.zip         populated places (population > 500, or admin seats to PPLA4)
  admin1CodesASCII.txt  first-level region names, keyed "<cc>.<code>"
  countryInfo.txt       ISO-3166 alpha-2 codes; used to validate every place's country

Usage:
  build.py --download-dir DIR             fetch the three files into DIR, then build
  build.py --input-dir DIR                build from three files already in DIR
  build.py ... [--snapshot-date YYYY-MM-DD] [--out PATH]

The snapshot date defaults to the date stamped on cities500.txt inside cities500.zip, so
the same input files always give the same output, byte for byte. The binary format is
specified in README.md next to this file; keep the two in step.

Standard library only. Run with the tools/ venv (direnv) or any Python >= 3.10.
"""
import argparse
import datetime
import decimal
import hashlib
import io
import pathlib
import re
import struct
import sys
import urllib.request
import zipfile

BASE_URL = "https://download.geonames.org/export/dump/"
INPUTS = ("cities500.zip", "admin1CodesASCII.txt", "countryInfo.txt")
REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "android/app/src/main/assets/gazetteer.bin"

MAGIC = b"AGAZ"
FORMAT_VERSION = 1
HEADER_FIXED = 32
MICRO = 1_000_000

CC_RE = re.compile(r"^[A-Z]{2}$")
# Same rules POST /uploads applies to place (design.md "Location", "Validation").
REGION_RE = re.compile(r"^[A-Z]{2}\.[0-9A-Z]{1,20}$")
CONTROL_RE = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def die(msg):
    sys.exit(f"build.py: {msg}")


def varint(v):
    if v < 0:
        raise ValueError(v)
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def vstr(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


def micro(text):
    """Decimal degrees string -> integer micro-degrees, exact (no float), half-even."""
    d = decimal.Decimal(text).scaleb(6).to_integral_value(rounding=decimal.ROUND_HALF_EVEN)
    return int(d)


def valid_name(s):
    return 1 <= len(s) <= 200 and not CONTROL_RE.search(s)


def download(dest):
    dest.mkdir(parents=True, exist_ok=True)
    for name in INPUTS:
        print(f"downloading {BASE_URL}{name}", file=sys.stderr)
        with urllib.request.urlopen(BASE_URL + name, timeout=300) as r:
            (dest / name).write_bytes(r.read())


def read_countries(path):
    ccs = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cc = line.split("\t")[0]
        if not CC_RE.match(cc):
            die(f"countryInfo: bad ISO code {cc!r}")
        ccs.add(cc)
    return ccs


def read_admin1(path):
    regions = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        key, name = line.split("\t")[:2]
        name = name.strip()
        if not REGION_RE.match(key):
            die(f"admin1: key {key!r} fails the region-id rule")
        if not valid_name(name):
            die(f"admin1: {key} has an invalid name {name!r}")
        regions[key] = name
    return regions


def read_places(zip_path, countries, admin1):
    """-> list of (geonameid, lat_micro, lon_micro, region_key, name), snapshot date."""
    places = []
    with zipfile.ZipFile(zip_path) as z:
        info = z.getinfo("cities500.txt")
        stamp = datetime.date(*info.date_time[:3])
        with z.open(info) as f:
            for lineno, line in enumerate(io.TextIOWrapper(f, encoding="utf-8"), 1):
                col = line.rstrip("\n").split("\t")
                if len(col) != 19:
                    die(f"cities500.txt:{lineno}: expected 19 columns, got {len(col)}")
                gid, name, cc = int(col[0]), col[1].strip(), col[8]
                lat, lon = micro(col[4]), micro(col[5])
                if cc not in countries:
                    die(f"cities500.txt:{lineno}: country {cc!r} not in countryInfo")
                if not valid_name(name):
                    die(f"cities500.txt:{lineno}: invalid name {name!r}")
                if not (-90 * MICRO <= lat <= 90 * MICRO and -180 * MICRO <= lon <= 180 * MICRO):
                    die(f"cities500.txt:{lineno}: coordinates out of range")
                if lon == 180 * MICRO:
                    lon = -180 * MICRO
                # A place whose admin1 code is missing or unknown ("00", blank) gets the
                # country-only pseudo-region "<cc>", i.e. no region in the output.
                key = f"{cc}.{col[10]}"
                places.append((gid, lat, lon, key if key in admin1 else cc, name))
    return places, stamp


def cell_of(lat, lon):
    lat_cell = min(lat // MICRO, 89)
    lon_cell = lon // MICRO
    return lat_cell, lon_cell


def cell_key(lat_cell, lon_cell):
    return (lat_cell + 90) * 360 + (lon_cell + 180)


def pad4(buf):
    buf.extend(b"\0" * (-len(buf) % 4))


def build(places, admin1, src):
    ids = [p[0] for p in places]
    if len(set(ids)) != len(ids):
        die("duplicate geonameid in cities500")

    # Region table: real admin1 regions plus a pseudo-region per country, ordered by
    # descending place count (small varints for common regions), ties by key.
    counts = {}
    for p in places:
        counts[p[3]] = counts.get(p[3], 0) + 1
    region_keys = sorted(counts, key=lambda k: (-counts[k], k))
    region_index = {k: i for i, k in enumerate(region_keys)}

    cells = {}
    for p in places:
        cells.setdefault(cell_key(*cell_of(p[1], p[2])), []).append(p)

    out = bytearray(HEADER_FIXED)
    src_b = src.encode("ascii")
    out += src_b
    pad4(out)

    region_index_off = len(out)
    out += b"\0" * (4 * len(region_keys))
    for i, k in enumerate(region_keys):
        struct.pack_into("<I", out, region_index_off + 4 * i, len(out))
        out += vstr(k) + vstr(admin1.get(k, ""))
    pad4(out)

    cell_index_off = len(out)
    out += b"\0" * (8 * len(cells))
    for i, ck in enumerate(sorted(cells)):
        rs = sorted(cells[ck])  # by geonameid
        if len(rs) > 0xFFFF:
            die(f"cell {ck} has {len(rs)} places, more than a u16 count holds")
        struct.pack_into("<HHI", out, cell_index_off + 8 * i, ck, len(rs), len(out))
        lat0 = (ck // 360 - 90) * MICRO
        lon0 = (ck % 360 - 180) * MICRO
        prev = 0
        for gid, lat, lon, rkey, name in rs:
            out += varint(gid - prev) + varint(lat - lat0) + varint(lon - lon0)
            out += varint(region_index[rkey]) + vstr(name)
            prev = gid

    struct.pack_into(
        "<4sHHIIIIII", out, 0, MAGIC, FORMAT_VERSION, len(src_b),
        len(places), len(region_keys), len(cells), region_index_off, cell_index_off, len(out),
    )
    return bytes(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--download-dir", type=pathlib.Path, help="download the inputs here first")
    g.add_argument("--input-dir", type=pathlib.Path, help="use inputs already in this directory")
    ap.add_argument("--snapshot-date", help="YYYY-MM-DD; default: cities500.txt's date in the zip")
    ap.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    src_dir = args.download_dir or args.input_dir
    if args.download_dir:
        download(src_dir)
    for name in INPUTS:
        p = src_dir / name
        if not p.is_file():
            die(f"missing input {p}")
        print(f"sha256 {hashlib.sha256(p.read_bytes()).hexdigest()}  {name}", file=sys.stderr)

    countries = read_countries(src_dir / "countryInfo.txt")
    admin1 = read_admin1(src_dir / "admin1CodesASCII.txt")
    places, stamp = read_places(src_dir / "cities500.zip", countries, admin1)
    date = args.snapshot_date or stamp.isoformat()
    if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
        die(f"bad snapshot date {date!r}")
    src = f"geonames-cities500@{date}"

    data = build(places, admin1, src)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    print(f"sha256 {hashlib.sha256(data).hexdigest()}  {args.out.name}", file=sys.stderr)
    print(f"src {src}, {len(places)} places, {len(data)} bytes -> {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
