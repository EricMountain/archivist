#!/usr/bin/env python3
"""Build a pack set: every region in a list, plus manifest.json. Format: README.md.

    build_set.py --regions private/instance/geo-regions.txt --work ~/geo-work --out ~/geo-out [--set ID]

The regions file has one region per line, `<name> <extract URL or local path>`; `#`
starts a comment. Manifest order is file order, and an entity in two packs is taken
from the earlier one, so list a region before its neighbours if it matters.

Writes `<out>/manifest.json` and `<out>/<set>/<name>.bin`. Publish with
`make geo-publish` (docs/ops/geo-packs.md). Needs build.py's dependencies.

The set id defaults to today's date plus a digest of the contents (builder, regions,
extracts), so a set that differs in any way gets a new id and clients redo every photo.

Also writes a build record, by default `geo-builds/<set>.json` beside the regions file:
for each region, the extract's URL, dated URL, cached file, size, hashes and OSM data
timestamp. Downloaded extracts are kept in `--work` and reused until `--refresh`, and so
are built packs (`--work/packs/`), so rerunning with the same regions file rebuilds the
same packs, and adding a region builds only that one.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import shutil
import struct
import sys
import time
import urllib.error
import urllib.request

import build
import packfmt as pf


def read_regions(path: str) -> list[tuple[str, str]]:
    out = []
    for lineno, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2 or not pf.NAME_RE.match(parts[0]):
            sys.exit(f"{path}:{lineno}: expected '<name> <url-or-path>', name matching {pf.NAME_RE.pattern}")
        out.append((parts[0], parts[1]))
    names = [n for n, _ in out]
    if len(set(names)) != len(names):
        sys.exit(f"{path}: duplicate region name")
    return out


def _resolve(url: str) -> str:
    """`url` after redirects. Geofabrik's `-latest` URL redirects to the day's dated file."""
    with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=60) as r:
        return r.geturl()


def _dated_url(src: str, timestamp: str | None) -> str | None:
    if not timestamp or not src.endswith("-latest.osm.pbf") or "geofabrik.de/" not in src:
        return None
    return src.replace("-latest.osm.pbf", f"-{timestamp[2:4]}{timestamp[5:7]}{timestamp[8:10]}.osm.pbf")


def dated_source(src: str, timestamp: str | None) -> tuple[str, str] | None:
    """For a Geofabrik `-latest` URL, the dated copy of the data with this timestamp, and
    its published md5. Geofabrik keeps `<region>-YYMMDD.osm.pbf`, named after the data's
    UTC date, next to `-latest`: daily ones for a few weeks, the 1st of each month for
    longer. None when `src` isn't such a URL or the copy can't be found."""
    dated = _dated_url(src, timestamp)
    if not dated:
        return None
    try:
        with urllib.request.urlopen(dated + ".md5", timeout=60) as r:
            word = r.read(200).decode("ascii", "replace").split()
    except (OSError, ValueError):
        return None
    return (dated, word[0].lower()) if word and len(word[0]) == 32 else None


def _part_timestamp(tmp: str) -> str | None:
    """The data timestamp of a partial download, from the PBF header at its start."""
    try:
        return osm_timestamp(tmp) if os.path.getsize(tmp) > 0 else None
    except Exception:
        return None


def _md5_file(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 22):
            h.update(chunk)
    return h.hexdigest()


def _download(src: str, tmp: str, attempts: int = 20) -> None:
    """Download into `tmp`, resuming what's already there. A stall (60 s without data,
    e.g. after the laptop sleeps) or a dropped connection resumes with a Range request."""
    for attempt in range(1, attempts + 1):
        have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
        req = urllib.request.Request(src, headers={"Range": f"bytes={have}-"} if have else {})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                if have and r.status != 206:
                    have = 0  # server ignored the range: start over
                total = r.headers.get("Content-Range", "").rpartition("/")[2] or r.headers.get("Content-Length")
                print(f"   {os.path.basename(tmp)}: {'resuming at ' + format(have, ',') if have else 'starting'}"
                      f"{' of ' + format(int(total), ',') if total and total.isdigit() else ''} bytes", file=sys.stderr)
                with open(tmp, "ab" if have else "wb") as f:
                    shutil.copyfileobj(r, f, 1 << 20)
            return
        except urllib.error.HTTPError as e:
            if e.code == 416 and have:  # already complete
                return
            if e.code < 500 or attempt == attempts:
                raise
        except OSError:
            if attempt == attempts:
                raise
        print(f"   {os.path.basename(tmp)}: interrupted, retrying ({attempt}/{attempts})", file=sys.stderr)
        time.sleep(min(60, 2 ** attempt))


def fetch(src: str, work: str, refresh: bool) -> str:
    """The extract at `src`: a local path as is, or a URL downloaded into `work` and kept
    there by file name. A `.part` left by an interrupted run is resumed."""
    if "://" not in src:
        return os.path.expanduser(src)
    dest = os.path.join(work, os.path.basename(src))
    if refresh or not os.path.exists(dest):
        tmp = dest + ".part"
        if refresh and os.path.exists(tmp):
            os.remove(tmp)
        print(f"downloading {src}", file=sys.stderr)
        for fresh in (False, True):
            if fresh:
                print(f"   {os.path.basename(tmp)}: md5 differs from Geofabrik's; downloading again from scratch",
                      file=sys.stderr)
                os.remove(tmp)
            # Download one fixed file, never `-latest` itself, which moves to the next day's
            # data overnight: a resume continues the dated file the `.part` started from.
            url = _dated_url(src, _part_timestamp(tmp)) if os.path.exists(tmp) else None
            if not url:
                try:
                    url = _resolve(src)
                except OSError:
                    url = src
            if url != src:
                print(f"   {os.path.basename(tmp)}: from {url}", file=sys.stderr)
            try:
                _download(url, tmp)
            except urllib.error.HTTPError as e:
                if e.code != 404 or not os.path.exists(tmp):
                    raise
                # Geofabrik no longer has the dated file the `.part` came from.
                print(f"   {os.path.basename(tmp)}: {url} is gone; starting over", file=sys.stderr)
                os.remove(tmp)
                url = _resolve(src)
                _download(url, tmp)
            ds = dated_source(src, osm_timestamp(tmp))
            if not ds or _md5_file(tmp) == ds[1]:
                break
        else:
            sys.exit(f"{src}: md5 differs from {ds[0]}.md5 after a fresh download")
        os.replace(tmp, dest)
    return dest



def osm_timestamp(path: str) -> str | None:
    """The extract's replication timestamp from its PBF header, if it has one."""
    import osmium
    r = osmium.io.Reader(osmium.io.File(path, "pbf"), osmium.osm.osm_entity_bits.NOTHING)
    try:
        return r.header().get("osmosis_replication_timestamp") or None
    finally:
        r.close()


def _builder_hash() -> str:
    """Changes whenever the code that decides a pack's bytes does."""
    h = hashlib.sha256()
    for mod in (build, pf):
        with open(mod.__file__, "rb") as f:
            h.update(f.read())
    return h.hexdigest()


def _hashes(path: str) -> tuple[str, str]:
    """(md5, sha256) of an extract, remembered beside it by size and mtime: hashing every
    extract on every run would take minutes."""
    st = os.stat(path)
    memo = path + ".hashes"
    stamp = f"{st.st_size} {st.st_mtime_ns}"
    try:
        line = open(memo, encoding="ascii").read().split()
        if " ".join(line[:2]) == stamp:
            return line[2], line[3]
    except (OSError, IndexError):
        pass
    md5, sha = hashlib.md5(), hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 22):
            md5.update(chunk)
            sha.update(chunk)
    out = md5.hexdigest(), sha.hexdigest()
    with open(memo, "w", encoding="ascii") as f:
        f.write(f"{stamp} {out[0]} {out[1]}\n")
    return out


def default_set_id(regions: list[tuple[str, str]], md5s: list[str]) -> str:
    """Today's UTC date plus a digest of what the set contains: the builder, and every
    region's name and extract, in order. Adding, removing or reordering a region, a new
    extract, or a builder change gives a new id, so every stored `place.src` from the
    previous set no longer matches and `modify_media.py location --all` redoes it. In
    particular a photo placed while its region had no pack gets redone once one does."""
    h = hashlib.sha256(_builder_hash().encode())
    for (name, _), md5 in zip(regions, md5s):
        h.update(f"\n{name} {md5}".encode())
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d") + "." + h.hexdigest()[:6]


def _read_vstr_end(data: bytes, pos: int) -> int:
    n = shift = 0
    while True:
        b = data[pos]
        pos += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return pos + n


def with_set(data: bytes, set_id: str) -> bytes:
    """The same pack with another set id in its header (README "Header"): the set is the
    only thing in a pack that depends on the rest of the set."""
    out = bytearray(data[:32])
    pf._vstr(out, set_id)
    out += data[_read_vstr_end(data, 32):]
    struct.pack_into("<I", out, 12, len(out))
    return bytes(out)


def _pack_cache(work: str, name: str, extract_md5: str) -> str:
    return os.path.join(work, "packs", f"{name}-{extract_md5}-{_builder_hash()[:16]}.bin")


def build_pack(work: str, name: str, path: str, extract_md5: str, set_id: str, refresh: bool) -> bytes:
    """The pack for one region. Built packs are kept in `<work>/packs/` by name, extract and
    builder, so a set that only adds a region builds only that one; a kept pack gets the
    new set id in its header and is otherwise byte-identical to a fresh build."""
    cache = _pack_cache(work, name, extract_md5)
    if not refresh and os.path.exists(cache):
        print("   unchanged extract and builder: reusing the pack built before", file=sys.stderr)
        with open(cache, "rb") as f:
            return with_set(f.read(), set_id)
    ents = build.build_entities(path, progress=lambda m: print(f"   {m}", file=sys.stderr))
    data = pf.encode(ents, set_id, name, pf.bbox_of(ents))
    os.makedirs(os.path.dirname(cache), exist_ok=True)
    with open(cache + ".tmp", "wb") as f:
        f.write(data)
    os.replace(cache + ".tmp", cache)
    return data


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--regions", required=True)
    ap.add_argument("--work", required=True, help="where extracts are downloaded and kept")
    ap.add_argument("--out", required=True, help="where manifest.json and the packs are written")
    ap.add_argument("--set", dest="set_id", help="set id (default: today's date and a digest of the contents)")
    ap.add_argument("--refresh", action="store_true", help="download extracts again")
    ap.add_argument("--download-jobs", type=int, default=3, help="extracts downloaded at once (default 3)")
    ap.add_argument("--record", help="build record path (default: geo-builds/<set>.json beside --regions)")
    args = ap.parse_args()
    if args.set_id is not None and not pf.SET_RE.match(args.set_id):
        sys.exit(f"bad --set {args.set_id!r}")
    os.makedirs(args.work, exist_ok=True)

    regions = read_regions(args.regions)
    # Download everything first, a few at a time, so a long build isn't waiting on the
    # network region by region, and a network problem shows up before hours of building.
    with concurrent.futures.ThreadPoolExecutor(max(1, args.download_jobs)) as ex:
        paths = list(ex.map(lambda r: fetch(r[1], args.work, args.refresh), regions))
    hashes = [_hashes(p) for p in paths]
    if args.set_id is None:
        args.set_id = default_set_id(regions, [h[0] for h in hashes])
    print(f"set {args.set_id}", file=sys.stderr)
    os.makedirs(os.path.join(args.out, args.set_id), exist_ok=True)

    packs, sources = [], []
    for (name, src), path, (md5, sha) in zip(regions, paths, hashes):
        print(f"== {name}", file=sys.stderr)
        ts = osm_timestamp(path)
        ds = dated_source(src, ts)
        if ds and md5 != ds[1]:
            print(f"   warning: cached extract doesn't match {ds[0]}; GEO_REFRESH=1 to fetch it again",
                  file=sys.stderr)
        sources.append({"name": name, "src": src,
                        # A permanent URL for exactly this file, when Geofabrik's md5 confirms it.
                        "dated": ds[0] if ds and md5 == ds[1] else None,
                        "file": os.path.abspath(path), "bytes": os.path.getsize(path),
                        "md5": md5, "sha256": sha, "osmTimestamp": ts})
        rel = f"{args.set_id}/{name}.bin"
        data = build_pack(args.work, name, path, md5, args.set_id, args.refresh)
        with open(os.path.join(args.out, rel), "wb") as f:
            f.write(data)
        bbox = struct.unpack_from("<4i", data, 16)
        packs.append({"name": name, "path": rel, "bytes": len(data),
                      "sha256": hashlib.sha256(data).hexdigest(), "bbox": list(bbox)})
        print(f"   {len(data):,} bytes", file=sys.stderr)

    manifest = {"format": 1, "set": args.set_id, "attribution": pf.ATTRIBUTION, "packs": packs}
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
        f.write("\n")
    record = args.record or os.path.join(os.path.dirname(os.path.abspath(args.regions)), "geo-builds",
                                         f"{args.set_id}.json")
    os.makedirs(os.path.dirname(record), exist_ok=True)
    with open(record, "w", encoding="utf-8") as f:
        json.dump({"set": args.set_id,
                   "builtAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                   "regionsFile": os.path.abspath(args.regions),
                   "regions": [dict(s, pack=p) for s, p in zip(sources, packs)]},
                  f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"build record -> {record}", file=sys.stderr)
    print(f"manifest: {len(packs)} packs, set {args.set_id} -> {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
