#!/usr/bin/env python3
"""Works out a coarse location for the photos in a local directory that carry no GPS
of their own, asking as few questions as possible, and writes the result to a
JSON Lines file that a later step can push through `PUT /photos/{photoId}/location`.

Nothing here signs in to an instance. Network: a one-time GeoNames download (the
same three files tools/gazetteer/build.py uses) and, with `--host`, the instance's
public region-pack manifest and packs. It only reads the source directory and writes
local state.

How it keeps the questions down:

1. **Scan.** Every media file's capture time (EXIF DateTimeOriginal, else the
   filename, else a video's container time, else mtime) and GPS (EXIF / the video
   container's location), with the importer's own readers. Cached by path, size and
   mtime, so a re-run doesn't re-read 16 GB.
2. **Infer, no questions asked.**
   * An edited copy (`-edited`, `-EFFECTS`...) whose original sits next to it with
     GPS takes the original's place (`sibling-gps`).
   * A photo bracketed in time by two GPS photos that agree (both within
     `--bracket-hours`, and within `--agree-km` of each other) takes the nearer
     one's place; a photo within `--near-minutes` of one GPS photo takes that one's
     (`neighbour-gps`). Only for photos whose time is real (EXIF/filename/video),
     never an mtime.
3. **Ask, once per burst.** What's left is grouped into clusters split wherever
   consecutive photos are more than `--gap-hours` apart. Each cluster is asked
   about once, with a contact sheet of up to 12 of its photos, and a suggestion:
   whichever known place (GPS, inferred, or an earlier answer) is nearest in time
   just before or after it. Enter accepts the suggestion. A cluster that turns out
   to be mixed can be split (`s`) at its biggest time gap, repeatedly.
4. **Review sheets for the long tail.** Once a place is known, the clusters that
   follow are assumed to be there too and shown as one numbered contact sheet
   (`--sheet` photos, default 24): "Which are NOT Lyon?". Enter confirms the lot;
   `5-8 12` sends those back as questions, each run of consecutive numbers as one
   (a trip is usually a run). Years of isolated photos taken at home cost one
   keypress per sheet instead of one per day.
5. **Resolve names against GeoNames cities500**, and with `--host` (or
   `--manifest`) against the instance's region packs too. Type part of a place name
   (any language GeoNames knows it in, diacritics optional), optionally qualified
   (`paris, tx`, `paris, france`), or a region/country name for something coarser.
   Candidates are ranked by match quality, then by population (a stand-in by kind
   for pack names) weighted by distance to the suggestion, so "paris" during a
   Texas trip offers Paris TX first. A pick resolves to no more than it names (see
   `Geo.entity_res`). You confirm once; the choice is remembered for that exact
   text and never asked again (prefix with `!` to ask anyway). `lat,lon` and
   `#<geonameid>` also work.

Answers are saved after every question, so `q` (or Ctrl-C) and a re-run resumes
where you stopped; changing the inference thresholds and re-running only changes
what is inferred, never what you answered.

The output (default `missing-locations.jsonl` in the current directory) has one
line per file that has no GPS and now has a place:

    {"path": "sub/IMG_20140612_101500.jpg", "size": 2412312,
     "takenLocal": "2014-06-12T10:15:00", "timeSrc": "filename",
     "method": "prompt" | "neighbour-gps" | "sibling-gps",
     "place": {"cc": "FR", "region": {...}, "locality": {...}, "src": "geonames-cities500@..."},
     "approx": {"lat": 45.748, "lon": 4.847} | null,
     "label": "Lyon, Auvergne-Rhône-Alpes, FR",
     "basis": {"path": "...", "gapS": 1200}}            # inferred lines only

`place` has exactly the shape POST /uploads and PUT /photos/{id}/location take (the
bundled gazetteer's `src`). `approx` is the chosen locality's centre (or the
coordinates you typed), not a measurement: it is there for inspection, and
shouldn't be written into a photo's encrypted EXIF as if it were GPS.

Usage (from tools/import-from-local/, with its venv):

    .venv/bin/python3 locate_missing.py --source ~/Pictures/some/folder
    .venv/bin/python3 locate_missing.py --source ... --no-prompt   # infer + report only
"""

from __future__ import annotations

import argparse
import base64
import bisect
import concurrent.futures
import hashlib
import io
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata
import urllib.request
import zipfile
from dataclasses import dataclass
from datetime import datetime, timedelta

import filename_time
import gazetteer
import geopack
import media_probe as mp
import places
import takenat_ladder as ladder

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_STATE_DIR = os.path.join(HERE, ".cache", "locate")
GEONAMES_URL = "https://download.geonames.org/export/dump/"
GEONAMES_FILES = ("cities500.zip", "admin1CodesASCII.txt", "countryInfo.txt")
SCAN_VERSION = 1


# --- Scan -------------------------------------------------------------------------


@dataclass
class Item:
    rel: str
    size: int
    t: datetime  # naive local capture time
    time_src: str  # exif | filename | filename-date (a date with a guessed noon) | video | mtime
    gps: tuple[float, float] | None
    is_video: bool = False

    @property
    def real_time(self) -> bool:
        return self.time_src != "mtime"

    @property
    def exact_time(self) -> bool:
        """Good to the minute: what inferring from a neighbouring GPS photo needs."""
        return self.time_src not in ("mtime", "filename-date")


# The filename_time patterns that carry a time of day; the others (WhatsApp's
# IMG-yyyymmdd-WAnnnn, a bare date) give a date with noon filled in.
_FILENAME_HAS_TIME = re.compile(r"\d{8}[_-]\d{6}|\d{4}_\d{4}_\d{8}|T\d{2}_\d{2}_\d{2}")


def _plausible(t: datetime | None) -> bool:
    return t is not None and datetime(1990, 1, 1) <= t <= datetime.now() + timedelta(days=2)


def probe_file(path: str) -> dict:
    """One file's scan record. Never raises: a file nothing can be read from still
    gets its mtime, and an `error` for the summary."""
    st = os.stat(path)
    ext = os.path.splitext(path)[1][1:].lower()
    name = os.path.basename(path)
    out: dict = {"size": st.st_size, "mtimeNs": st.st_mtime_ns, "gps": None, "t": None, "timeSrc": None}
    try:
        if mp.is_video(ext):
            loc = mp.probe_video_location(path)
            if loc is not None:
                out["gps"] = [loc[0], loc[1]]
            fn = filename_time.parse(name)
            if _plausible(fn):
                out["t"], out["timeSrc"] = fn, "filename"
            else:
                v = mp.probe_video(path)
                if v.creation_time_utc is not None:
                    # A container time is UTC; this machine's zone is the least-wrong
                    # guess at the wall clock it was shot at.
                    local = v.creation_time_utc.astimezone().replace(tzinfo=None)
                    if _plausible(local):
                        out["t"], out["timeSrc"] = local, "video"
        else:
            exif = mp.extract_exif(path)
            if exif.gps_lat is not None and exif.gps_lon is not None:
                out["gps"] = [exif.gps_lat, exif.gps_lon]
            t = ladder.parse_exif_naive_local(exif.date_time_original)
            if _plausible(t):
                out["t"], out["timeSrc"] = t, "exif"
            else:
                fn = filename_time.parse(name)
                if _plausible(fn):
                    out["t"], out["timeSrc"] = fn, "filename"
    except Exception as e:  # noqa: BLE001 -- a corrupt file is reported, not fatal
        out["error"] = f"{type(e).__name__}: {e}"
    if out["t"] is None:
        out["t"], out["timeSrc"] = datetime.fromtimestamp(st.st_mtime).replace(microsecond=0), "mtime"
    out["t"] = out["t"].isoformat()
    return out


def _probe_job(args: tuple[str, str]) -> tuple[str, dict]:
    path, rel = args
    return rel, probe_file(path)


def walk_media(source: str) -> list[tuple[str, str]]:
    found = []
    for root, dirs, files in os.walk(source):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        for f in sorted(files):
            if f.startswith("."):
                continue
            ext = os.path.splitext(f)[1][1:].lower()
            if mp.is_video(ext) or (mp.is_image(ext) and not mp.is_raw(ext)):
                path = os.path.join(root, f)
                found.append((path, os.path.relpath(path, source)))
    return found


def scan(source: str, cache_path: str, jobs: int) -> tuple[list[Item], list[tuple[str, str]]]:
    cache: dict = {}
    try:
        with open(cache_path, encoding="utf-8") as f:
            loaded = json.load(f)
        if loaded.get("version") == SCAN_VERSION:
            cache = loaded["files"]
    except (OSError, ValueError, KeyError):
        pass

    files = walk_media(source)
    todo = []
    for path, rel in files:
        st = os.stat(path)
        c = cache.get(rel)
        if not c or c["size"] != st.st_size or c["mtimeNs"] != st.st_mtime_ns:
            todo.append((path, rel))
    if todo:
        print(f"scanning {len(todo)} of {len(files)} files...", file=sys.stderr)
        with concurrent.futures.ProcessPoolExecutor(max_workers=jobs) as pool:
            for i, (rel, rec) in enumerate(pool.map(_probe_job, todo, chunksize=16), 1):
                cache[rel] = rec
                if i % 250 == 0 or i == len(todo):
                    print(f"  {i}/{len(todo)}", file=sys.stderr)
        live = {rel for _, rel in files}
        cache = {k: v for k, v in cache.items() if k in live}
        _write_json(cache_path, {"version": SCAN_VERSION, "files": cache})

    items, errors = [], []
    for path, rel in files:
        c = cache[rel]
        if "error" in c:
            errors.append((rel, c["error"]))
        ext = os.path.splitext(rel)[1][1:].lower()
        src = c["timeSrc"]
        if src == "filename" and not _FILENAME_HAS_TIME.search(os.path.basename(rel)):
            src = "filename-date"
        items.append(Item(rel, c["size"], datetime.fromisoformat(c["t"]), src,
                          tuple(c["gps"]) if c["gps"] else None, mp.is_video(ext)))
    items.sort(key=lambda it: (it.t, it.rel))
    return items, errors


# --- GeoNames: forward lookup by name ---------------------------------------------

_CODE = re.compile(r"^[A-Z0-9]{2,4}$")
# Subtracted from a candidate's score (log10 population + proximity) by match quality:
# a big city that only prefix-matches can still beat a hamlet with the exact name,
# but not by much.
TIER_PENALTY = {-1: 0.0, 0: 0.3, 1: 1.0, 2: 1.5, 3: 2.5}
_PUNCT = re.compile(r"[\s\-_'’.,/()]+")


def fold(s: str) -> str:
    """Case-, diacritic- and punctuation-insensitive form: 'Saint-Étienne' -> 'saint etienne'."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c)).casefold()
    s = s.replace("ß", "ss").replace("ø", "o").replace("đ", "d").replace("ł", "l").replace("æ", "ae")
    return _PUNCT.sub(" ", s).strip()


@dataclass
class Resolution:
    """A place the user (or an inference) settled on."""

    kind: str  # locality | region | country | coords
    place: dict | None
    label: str
    approx: tuple[float, float] | None  # the place's centre / typed coordinates
    key: str  # what an alias stores: "L:<gid>", "R:<admin1 key>", "C:<cc>", "P:<lat>,<lon>"

    def to_json(self) -> dict:
        return {"kind": self.kind, "place": self.place, "label": self.label,
                "approx": list(self.approx) if self.approx else None, "key": self.key}

    @staticmethod
    def from_json(d: dict) -> "Resolution":
        return Resolution(d["kind"], d["place"], d["label"], tuple(d["approx"]) if d["approx"] else None, d["key"])


_OSM_TYPES = {"n": "node", "w": "way", "r": "relation"}
_MAP_ZOOM = {"coords": 16, "pack": 14, "locality": 13, "region": 8, "country": 5}


def osm_url(res: Resolution) -> str | None:
    """Where to look a resolution up on openstreetmap.org: a pack entity's own page (its
    outline on the map, its tags), else a map centred on its point (a marker for a town or
    typed coordinates, a wider view for a region or country)."""
    if res.key.startswith("O:"):
        osm_id = res.key.split(":", 2)[2]
        if osm_id[:1] in _OSM_TYPES and osm_id[1:].isdigit():
            return f"https://www.openstreetmap.org/{_OSM_TYPES[osm_id[0]]}/{osm_id[1:]}"
    if not res.approx:
        return None
    lat, lon = res.approx
    zoom = _MAP_ZOOM.get(res.kind, 12)
    marker = f"mlat={lat:.5f}&mlon={lon:.5f}" if res.kind in ("coords", "locality", "pack") else ""
    return f"https://www.openstreetmap.org/{'?' + marker if marker else ''}#map={zoom}/{lat:.5f}/{lon:.5f}"


def _link_line(res: Resolution, indent: str = "      ", label: str = "") -> str:
    url = osm_url(res)
    if not url:
        return ""
    line = indent + label + url
    return f"\x1b[2m{line}\x1b[0m" if sys.stdout.isatty() else line


@dataclass
class Candidate:
    res: Resolution
    tier: int  # -1 exact main name, 0 exact alternate, 1 prefix, 2 word prefix, 3 substring
    score: float
    detail: str


# Region-pack entities as search candidates: a stand-in for log10(population) by kind,
# so a commune ranks with a town of ~10k, a hamlet with a few hundred people.
PACK_WEIGHT = {
    ("admin", 6): 4.6, ("admin", 7): 4.3, ("admin", 8): 4.0,
    ("place", 1): 5.0, ("place", 2): 4.0, ("place", 3): 3.3, ("place", 4): 3.3,
    ("place", 5): 2.8, ("place", 6): 2.3, ("place", 7): 2.5, ("place", 8): 2.0,
    ("protected", 0): 4.5, ("protected", 1): 3.0, ("feature", 0): 3.0,
}
PLACE_KIND_NAMES = {1: "city", 2: "town", 3: "village", 4: "suburb", 5: "quarter", 6: "hamlet",
                    7: "neighbourhood", 8: "lieu-dit"}
SUB_KINDS = {1, 2, 3, 4}  # README "Lookup": the kinds that fill `sub`; the rest fill `spot`


_REP_CACHE: dict[tuple, tuple[float, float]] = {}


def _interior_points(e: geopack.Entity, n_side: int = 7) -> list[tuple[int, int]]:
    """Points of an n×n grid over a polygon's bbox that are inside it, nearest the
    middle first. Inside a tile's clipped copy means inside the real polygon too."""
    b = e.bbox
    mid = ((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    grid = [(round(b[0] + (b[2] - b[0]) * (i + 0.5) / n_side), round(b[1] + (b[3] - b[1]) * (j + 0.5) / n_side))
            for i in range(n_side) for j in range(n_side)]
    pts = [p for p in grid if geopack.inside(geopack.Plane(p[0] / 1e6, p[1] / 1e6), e)]
    return sorted(pts, key=lambda p: ((p[0] - mid[0]) ** 2 + (p[1] - mid[1]) ** 2, p))


def _rep_point(e: geopack.Entity) -> tuple[float, float]:
    """A point to geocode a pack entity at: itself, a line's middle vertex, or a point
    inside a polygon near the middle of its bounding box (the middle itself if the grid
    finds none, e.g. a sliver)."""
    if e.geom_type == geopack.POINT:
        la, lo = e.parts[0][0]
    elif e.geom_type == geopack.LINES:
        pts = e.parts[0]
        la, lo = pts[len(pts) // 2]
    else:
        ck = (e.layer, e.otype, e.oid, e.bbox)
        if ck in _REP_CACHE:
            return _REP_CACHE[ck]
        inner = _interior_points(e)
        la, lo = inner[0] if inner else ((e.bbox[0] + e.bbox[2]) // 2, (e.bbox[1] + e.bbox[3]) // 2)
        _REP_CACHE[ck] = (la / 1e6, lo / 1e6)
    return la / 1e6, lo / 1e6


class Geo:
    def __init__(self, geonames_dir: str, gaz: gazetteer.Gazetteer, geocoder: places.Geocoder | None = None):
        self.gaz = gaz
        # Gazetteer alone unless the instance publishes region packs (--host/--manifest).
        self.geocoder = geocoder or places.Geocoder(gaz)
        self.src = self.geocoder.src
        self.countries: dict[str, str] = {}
        for line in _read_text(os.path.join(geonames_dir, "countryInfo.txt")).splitlines():
            if line and not line.startswith("#"):
                col = line.split("\t")
                self.countries[col[0]] = col[4]
        self.admin1: dict[str, str] = {}
        for line in _read_text(os.path.join(geonames_dir, "admin1CodesASCII.txt")).splitlines():
            if line.strip():
                key, name = line.split("\t")[:2]
                self.admin1[key] = name.strip()
        self.admin1_fold = {k: fold(v) for k, v in self.admin1.items()}
        self.country_fold = {k: fold(v) for k, v in self.countries.items()}

        self.gid: list[int] = []
        self.name: list[str] = []
        self.cc: list[str] = []
        self.region: list[str | None] = []
        self.lat: list[float] = []
        self.lon: list[float] = []
        self.pop: list[int] = []
        self.primary: list[str] = []  # folded main name
        self.hay: list[str] = []  # "\x1f" + folded names joined by "\x1f" + "\x1f"
        with zipfile.ZipFile(os.path.join(geonames_dir, "cities500.zip")) as z:
            with z.open("cities500.txt") as f:
                for line in io.TextIOWrapper(f, encoding="utf-8"):
                    col = line.rstrip("\n").split("\t")
                    if len(col) != 19:
                        continue
                    cc = col[8]
                    key = f"{cc}.{col[10]}"
                    names = {fold(col[1]), fold(col[2])}
                    if col[3]:
                        # Skip IATA/ICAO-style codes ("LYO", "LFLL"): as alternates they
                        # out-match real names on short queries.
                        names.update(fold(a) for a in col[3].split(",")
                                     if a and len(a) <= 80 and not _CODE.match(a))
                    names.discard("")
                    self.gid.append(int(col[0]))
                    self.name.append(col[1].strip())
                    self.cc.append(cc)
                    self.region.append(key if key in self.admin1 else None)
                    self.lat.append(float(col[4]))
                    self.lon.append(float(col[5]))
                    self.pop.append(int(col[14] or 0))
                    self.primary.append(fold(col[1]))
                    self.hay.append("\x1f" + "\x1f".join(sorted(names)) + "\x1f")
        self.by_gid = {g: i for i, g in enumerate(self.gid)}
        # Regions' and countries' rough centres: the most populous place in each.
        self.region_centre: dict[str, int] = {}
        self.country_centre: dict[str, int] = {}
        for i in range(len(self.gid)):
            r = self.region[i]
            if r and (r not in self.region_centre or self.pop[i] > self.pop[self.region_centre[r]]):
                self.region_centre[r] = i
            c = self.cc[i]
            if c not in self.country_centre or self.pop[i] > self.pop[self.country_centre[c]]:
                self.country_centre[c] = i

        # Every entity of every region pack, searchable by name like the towns above.
        self.entities: list[geopack.Entity] = []
        self.entity_by_key: dict[str, geopack.Entity] = {}
        self.entity_fold: list[str] = []
        self._parent_cache: dict[str, str | None] = {}
        if self.geocoder.manifest is not None:
            self.geocoder.preload()
            # An entity near a tile's edge is in several tiles, each copy clipped to its
            # tile: keep the copy covering the most, for its representative point.
            best: dict[tuple, tuple[int, geopack.Entity]] = {}
            for pk in self.geocoder.all_packs():
                for e in pk.entities:
                    b = e.bbox
                    extent = (b[2] - b[0]) * (b[3] - b[1])
                    if e.key not in best or extent > best[e.key][0]:
                        best[e.key] = (extent, e)
            ents = [best[k][1] for k in sorted(best, key=lambda k: (k[0], geopack.TYPE_ORDER[k[1]], k[2]))]
            # A French arrondissement (level 7) is named after its chef-lieu, a commune
            # (level 8): offering both reads as a duplicate, and the commune is what
            # anyone typing the name means.
            level8 = {e.name for e in ents if e.layer == geopack.ADMIN and e.level == 8}
            for e in ents:
                if e.layer == geopack.ADMIN and (not 6 <= e.level <= 8 or (e.level < 8 and e.name in level8)):
                    continue
                self.entity_by_key[self._entity_key(e)] = e
                self.entities.append(e)
                self.entity_fold.append(fold(e.name))

    # -- building resolutions --

    def _label(self, *parts: str | None) -> str:
        return ", ".join(p for p in parts if p)

    def locality(self, i: int) -> Resolution:
        """A GeoNames town picked by name. With region packs covering its centre, the
        commune containing that centre stands in for it: what an upload there would
        get as its locality, without claiming the finer levels at the centre."""
        cc, r = self.cc[i], self.region[i]
        place: dict = {"cc": cc}
        if r:
            place["region"] = {"id": r, "name": self.admin1[r]}
        place["locality"] = {"id": str(self.gid[i]), "name": self.name[i]}
        src = self.src
        try:
            layers = self.geocoder.pack_layers(self.lat[i], self.lon[i])
        except places.PackUnavailable:
            layers, src = None, self.gaz.src
        if layers and layers.get("locality"):
            place["locality"] = layers["locality"]
        place["src"] = src
        return Resolution("locality", place, place_label(place), (self.lat[i], self.lon[i]), f"L:{self.gid[i]}")

    def region_res(self, key: str) -> Resolution:
        cc = key[:2]
        c = self.region_centre.get(key)
        return Resolution("region", {"cc": cc, "region": {"id": key, "name": self.admin1[key]}, "src": self.src},
                          self._label(self.admin1[key], cc), (self.lat[c], self.lon[c]) if c is not None else None,
                          f"R:{key}")

    def country_res(self, cc: str) -> Resolution:
        c = self.country_centre.get(cc)
        return Resolution("country", {"cc": cc, "src": self.src}, self.countries.get(cc, cc),
                          (self.lat[c], self.lon[c]) if c is not None else None, f"C:{cc}")

    def coords_res(self, lat: float, lon: float) -> Resolution:
        """Typed coordinates are exact, so every layer applies."""
        place = self.geocoder.lookup(lat, lon)
        return Resolution("coords", place, f"{place_label(place)} ({lat:.4f},{lon:.4f})", (lat, lon),
                          f"P:{lat:.6f},{lon:.6f}")

    # -- region-pack entities --

    @staticmethod
    def _entity_key(e: geopack.Entity) -> str:
        return f"O:{e.layer}:{e.id}"

    def entity_res(self, e: geopack.Entity) -> Resolution | None:
        """A pack entity picked by name, resolved to no more than the pick means: a
        commune is a locality; a village or hamlet adds itself (and, for a hamlet, the
        village it's near) to its commune; a protected area is just the area, since
        it can span communes; a feature adds itself to its commune."""
        lat, lon = _rep_point(e)
        base = self.gaz.lookup(lat, lon)
        if base is None:
            return None
        place: dict = {"cc": base["cc"]}
        if base.get("region"):
            place["region"] = base["region"]
        ref = {"id": e.id, "name": e.name}
        try:
            layers = self.geocoder.pack_layers(lat, lon) or {}
        except places.PackUnavailable:
            return None
        label = None
        if e.layer == geopack.ADMIN:
            place["locality"] = ref
            parent = self._parent_name(e)
            if parent:
                first, _, rest = place_label(place).partition(", ")
                label = self._label(first, parent, rest)
        elif e.layer == geopack.PLACE:
            if layers.get("locality"):
                place["locality"] = layers["locality"]
            if place.get("locality", {}).get("name") != e.name:
                if e.kind in SUB_KINDS:
                    place["sub"] = ref
                else:
                    if layers.get("sub") and layers["sub"]["name"] != e.name:
                        place["sub"] = layers["sub"]
                    place["spot"] = ref
        elif e.layer == geopack.PROTECTED:
            place["areas"] = [ref]
        else:
            if layers.get("locality"):
                place["locality"] = layers["locality"]
            place["feature"] = ref
        place["src"] = self.src
        place = {k: place[k] for k in places.PLACE_KEYS if k in place}
        return Resolution("pack", place, label or place_label(place), (lat, lon), self._entity_key(e))

    def _parent_name(self, e: geopack.Entity) -> str | None:
        """The level-6 area (a French département, a US county) a level-7/8 one lies in,
        named in its label since communes share names ("Sainte-Colombe" in the Rhône
        and in the Doubs). The one containing most of up to 9 points inside the commune,
        each asked of its own tile: interior points, since the two often share a border
        exactly (Manhattan and New York County). None if there is none, or it has the
        same name (Paris, Paris)."""
        if e.layer != geopack.ADMIN or e.level < 7 or self.geocoder.manifest is None:
            return None
        if e.id in self._parent_cache:
            return self._parent_cache[e.id]
        inner = _interior_points(e)
        sample = inner[:: max(1, len(inner) // 9)][:9]
        votes: dict[tuple, list] = {}
        for la, lo in sample:
            try:
                admins = self.geocoder.admins_at(la / 1e6, lo / 1e6)
            except places.PackUnavailable:
                continue
            for a in admins:
                if a.level == 6:
                    v = votes.setdefault(a.key, [0, a])
                    v[0] += 1
        best = min(votes.values(), key=lambda v: (-v[0], v[1].area, v[1].id_key), default=None)
        name = best[1].name if best is not None and best[1].name != e.name else None
        self._parent_cache[e.id] = name
        return name

    def _entity_detail(self, e: geopack.Entity) -> str:
        if e.layer == geopack.ADMIN:
            return f"admin area, level {e.level}"
        if e.layer == geopack.PLACE:
            return PLACE_KIND_NAMES.get(e.kind, "place")
        if e.layer == geopack.PROTECTED:
            return "protected area" + (" (national park / IUCN)" if e.rank == 0 else "")
        return "natural feature"

    def from_key(self, key: str) -> Resolution | None:
        if key.startswith("O:"):
            e = self.entity_by_key.get(key)
            return self.entity_res(e) if e is not None else None
        kind, _, v = key.partition(":")
        if kind == "L" and int(v) in self.by_gid:
            return self.locality(self.by_gid[int(v)])
        if kind == "R" and v in self.admin1:
            return self.region_res(v)
        if kind == "C" and v in self.countries:
            return self.country_res(v)
        if kind == "P":
            lat, lon = (float(x) for x in v.split(","))
            return self.coords_res(lat, lon)
        return None

    def from_place(self, place: dict | None, near: tuple[float, float] | None) -> Resolution | None:
        """The resolution a reverse-geocoded `place` stands for (used for GPS photos).
        Town (or commune) level, never finer: it becomes the suggestion for *other*
        photos taken nearby, which may not have been at the same hamlet."""
        if not place:
            return None
        loc = place.get("locality")
        if loc and loc["id"].isdigit() and int(loc["id"]) in self.by_gid:
            return self.locality(self.by_gid[int(loc["id"])])
        if loc and not loc["id"].isdigit():
            coarse = {k: place[k] for k in ("cc", "region", "locality", "src") if k in place}
            e = self.entity_by_key.get(f"O:{geopack.ADMIN}:{loc['id']}")
            return Resolution("pack", coarse, place_label(coarse), near or (_rep_point(e) if e else None),
                              f"O:{geopack.ADMIN}:{loc['id']}")
        if place.get("region") and place["region"]["id"] in self.admin1:
            r = self.region_res(place["region"]["id"])
        else:
            r = self.country_res(place["cc"])
        return Resolution(r.kind, place, r.label, near or r.approx, r.key)

    # -- search --

    def _qualifies(self, i: int, qual: str) -> bool:
        cc, r = self.cc[i], self.region[i]
        if qual == cc.casefold():
            return True
        if r and (qual == r.split(".", 1)[1].casefold() or self.admin1_fold[r].startswith(qual)):
            return True
        return self.country_fold.get(cc, "").startswith(qual)

    @staticmethod
    def _tier(hay: str, q: str) -> int:
        if f"\x1f{q}\x1f" in hay:
            return 0
        if f"\x1f{q}" in hay:
            return 1
        if f" {q}" in hay:
            return 2
        return 3

    def search(self, text: str, near: tuple[float, float] | None, limit: int = 9) -> list[Candidate]:
        name_part, _, qual = text.partition(",")
        q, qual = fold(name_part), fold(qual)
        if not q:
            return []
        cands: list[Candidate] = []

        def proximity(lat: float, lon: float) -> tuple[float, str]:
            if near is None:
                return 0.0, ""
            d = gazetteer.haversine_km(near[0], near[1], lat, lon)
            return -1.5 * math.log10(1 + d / 50), f" · {d:,.0f} km from the suggestion"

        for i, hay in enumerate(self.hay):
            if q not in hay:
                continue
            if qual and not self._qualifies(i, qual):
                continue
            tier = self._tier(hay, q)
            if tier == 0 and q == self.primary[i]:
                tier = -1
            pen, dist = proximity(self.lat[i], self.lon[i])
            res = self.locality(i)
            shown = fold(self.name[i])
            matched = "" if q in shown else f" (as '{_matched_name(hay, q)}')"
            cands.append(Candidate(res, tier, math.log10(self.pop[i] + 10) + pen - TIER_PENALTY[tier],
                                   f"{res.label}{matched} · pop {self.pop[i]:,}{dist}"))

        for key, f_name in self.admin1_fold.items():
            if q in f_name and (not qual or qual in (key[:2].casefold(), self.country_fold.get(key[:2], ""))
                                or self.country_fold.get(key[:2], "").startswith(qual)):
                res = self.region_res(key)
                pen, dist = proximity(*res.approx) if res.approx else (0.0, "")
                tier = self._tier(f"\x1f{f_name}\x1f", q)
                cands.append(Candidate(res, tier, 6.5 + pen - TIER_PENALTY[tier],
                                       f"{res.label} (region){dist}"))
        if not qual:
            for cc, f_name in self.country_fold.items():
                if q in f_name or q == cc.casefold():
                    res = self.country_res(cc)
                    tier = 0 if q == cc.casefold() else self._tier(f"\x1f{f_name}\x1f", q)
                    cands.append(Candidate(res, tier, 8.0 - TIER_PENALTY[tier], f"{res.label} (country)"))

        # Region-pack entities, resolved lazily (each one is a pack lookup): ranked
        # first, then only the best few are turned into resolutions.
        lazy: list[tuple[float, int, geopack.Entity]] = []
        for e, f_name in zip(self.entities, self.entity_fold):
            if q not in f_name:
                continue
            tier = self._tier(f"\x1f{f_name}\x1f", q)
            if tier == 0:
                tier = -1
            weight_key = (("admin", e.level) if e.layer == geopack.ADMIN else ("place", e.kind)
                          if e.layer == geopack.PLACE else ("protected", e.rank)
                          if e.layer == geopack.PROTECTED else ("feature", 0))
            pen, _ = proximity(*_rep_point(e))
            lazy.append((PACK_WEIGHT[weight_key] + pen - TIER_PENALTY[tier], tier, e))
        lazy.sort(key=lambda x: -x[0])
        for score, tier, e in lazy[: limit * 2]:
            res = self.entity_res(e)
            if res is None:
                continue
            parent = self._parent_name(e) if e.layer == geopack.ADMIN else None
            if qual and not (qual == res.place["cc"].casefold()
                             or (res.place.get("region") and self.admin1_fold.get(res.place["region"]["id"], "").startswith(qual))
                             or self.country_fold.get(res.place["cc"], "").startswith(qual)
                             or (parent and fold(parent).startswith(qual))):
                continue
            _, dist = proximity(*_rep_point(e))
            cands.append(Candidate(res, tier, score, f"{res.label} ({self._entity_detail(e)}){dist}"))

        cands.sort(key=lambda c: -c.score)
        # Two candidates that resolve to the same place (a GeoNames town and the commune
        # its centre is in) are one choice.
        out, seen = [], set()
        for c in cands:
            sig = json.dumps(c.res.place, sort_keys=True)
            if sig in seen:
                continue
            seen.add(sig)
            out.append(c)
            if len(out) == limit:
                break
        return out


def _matched_name(hay: str, q: str) -> str:
    names = hay.strip("\x1f").split("\x1f")
    for test in (lambda n: n == q, lambda n: n.startswith(q), lambda n: q in n):
        for n in names:
            if test(n):
                return n
    return q


def place_label(place: dict | None) -> str:
    if not place:
        return "open sea / nowhere near a town"
    return places.describe(place)


def _read_text(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def ensure_geonames(d: str) -> None:
    missing = [n for n in GEONAMES_FILES if not os.path.isfile(os.path.join(d, n))]
    if not missing:
        return
    os.makedirs(d, exist_ok=True)
    for name in missing:
        print(f"downloading {GEONAMES_URL}{name} -> {d}", file=sys.stderr)
        with urllib.request.urlopen(GEONAMES_URL + name, timeout=300) as r:
            data = r.read()
        with open(os.path.join(d, name), "wb") as f:
            f.write(data)


# --- Inference ----------------------------------------------------------------------


@dataclass
class Known:
    """Something with a place at a time: a GPS photo, an inference or an answer."""

    t: datetime
    res: Resolution
    near: tuple[float, float] | None  # best coordinates for distance ranking
    why: str
    tag: int = 0  # which answer added it, so an undo can take it back


@dataclass
class Inferred:
    res: Resolution
    method: str
    basis: str
    gap_s: int


@dataclass
class Options:
    bracket: timedelta
    near: timedelta
    agree_km: float
    gap: timedelta


def infer(items: list[Item], anchor_res: dict[str, Resolution], opts: Options) -> dict[str, Inferred]:
    """rel -> inference, for every item without GPS that the rules in the module
    docstring can place without asking. `anchor_res` maps each GPS item's rel to its
    reverse-geocoded resolution (absent when the GPS is beyond any town)."""
    out: dict[str, Inferred] = {}

    by_dir_stem: dict[tuple[str, str], Item] = {}
    for it in items:
        if it.gps is not None and it.rel in anchor_res:
            d, f = os.path.split(it.rel)
            by_dir_stem[(d, os.path.splitext(f)[0].casefold())] = it
    for it in items:
        if it.gps is not None:
            continue
        d, f = os.path.split(it.rel)
        for stem in mp.base_stem_chain(os.path.splitext(f)[0])[1:]:
            sib = by_dir_stem.get((d, stem.casefold()))
            if sib is not None:
                out[it.rel] = Inferred(anchor_res[sib.rel], "sibling-gps", sib.rel,
                                       int(abs((it.t - sib.t).total_seconds())))
                break

    anchors = [it for it in items if it.gps is not None and it.exact_time and it.rel in anchor_res]
    times = [a.t for a in anchors]
    for it in items:
        if it.gps is not None or it.rel in out or not it.exact_time:
            continue
        k = bisect.bisect_left(times, it.t)
        before = anchors[k - 1] if k > 0 else None
        after = anchors[k] if k < len(anchors) else None
        gb = (it.t - before.t) if before else None
        ga = (after.t - it.t) if after else None
        if before and after and gb <= opts.bracket and ga <= opts.bracket:
            if gazetteer.haversine_km(*before.gps, *after.gps) <= opts.agree_km:
                src, gap = (before, gb) if gb <= ga else (after, ga)
                out[it.rel] = Inferred(anchor_res[src.rel], "neighbour-gps", src.rel, int(gap.total_seconds()))
                continue
        best = min(((g, a) for g, a in ((gb, before), (ga, after)) if a is not None), default=None,
                   key=lambda p: p[0])
        if best and best[0] <= opts.near:
            out[it.rel] = Inferred(anchor_res[best[1].rel], "neighbour-gps", best[1].rel,
                                   int(best[0].total_seconds()))
    return out


def clusters(items: list[Item], gap: timedelta) -> list[list[Item]]:
    """Consecutive (time-sorted) items, split wherever two neighbours are more than
    `gap` apart. Items with only an mtime are kept apart from real-time ones: their
    order says little about where they were taken."""
    out: list[list[Item]] = []
    for group in (sorted([i for i in items if i.real_time], key=lambda i: (i.t, i.rel)),
                  sorted([i for i in items if not i.real_time], key=lambda i: (i.t, i.rel))):
        cur: list[Item] = []
        for it in group:
            if cur and it.t - cur[-1].t > gap:
                out.append(cur)
                cur = []
            cur.append(it)
        if cur:
            out.append(cur)
    return out


def split_at_biggest_gap(cl: list[Item]) -> tuple[list[Item], list[Item]]:
    gaps = [(cl[i + 1].t - cl[i].t, i) for i in range(len(cl) - 1)]
    big, i = max(gaps, key=lambda g: (g[0], -abs(g[1] - len(cl) / 2)))
    if big <= timedelta(0):  # all at the same instant: halve
        i = len(cl) // 2 - 1
    return cl[: i + 1], cl[i + 1:]


# --- Display ------------------------------------------------------------------------


class Viewer:
    def __init__(self, mode: str, source: str, thumb_dir: str):
        self.source, self.thumb_dir = source, thumb_dir
        os.makedirs(thumb_dir, exist_ok=True)
        if mode == "auto":
            mode = "inline" if _inline_capable() else "open"
        self.mode = mode
        self._seq = 0

    def thumb(self, it: Item, size: int = 256):
        from PIL import Image, ImageOps

        key = hashlib.sha1(f"{it.rel}\0{it.size}\0{size}".encode()).hexdigest()
        cached = os.path.join(self.thumb_dir, key + ".jpg")
        if os.path.exists(cached):
            return Image.open(cached)
        path = os.path.join(self.source, it.rel)
        img = None
        try:
            if it.is_video:
                if shutil.which("ffmpeg"):
                    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-vf",
                                    f"thumbnail,scale={size}:{size}:force_original_aspect_ratio=decrease",
                                    "-frames:v", "1", cached],
                                   check=True, timeout=60, stdin=subprocess.DEVNULL, capture_output=True)
                    return Image.open(cached)
            else:
                with Image.open(path) as im:
                    im.draft("RGB", (size * 2, size * 2))
                    img = ImageOps.exif_transpose(im).convert("RGB")
                    img.thumbnail((size, size))
                    img.save(cached, quality=85)
        except Exception:  # noqa: BLE001 -- a placeholder tile instead
            img = None
        if img is None:
            img = Image.new("RGB", (size, size), (60, 60, 60))
        return img

    def show(self, tiles: list[tuple[Item, str]], cols: int = 4) -> None:
        """A contact sheet of `tiles`, each (item, caption)."""
        if self.mode == "none" or not tiles:
            return
        cols = min(cols, len(tiles))
        data = self.render(tiles, cols)
        if self.mode == "inline":
            term = shutil.get_terminal_size((120, 40)).columns
            width = min(term - 2, 30 * cols)
            seq = (f"\x1b]1337;File=inline=1;size={len(data)};width={width};preserveAspectRatio=1:"
                   + base64.b64encode(data).decode() + "\x07")
            if os.environ.get("TMUX"):
                seq = "\x1bPtmux;" + seq.replace("\x1b", "\x1b\x1b") + "\x1b\\"
            sys.stdout.write(seq + "\n")
            sys.stdout.flush()
        else:
            self._seq += 1
            path = os.path.join(self.thumb_dir, f"sheet-{os.getpid()}-{self._seq}.jpg")
            with open(path, "wb") as f:
                f.write(data)
            subprocess.run(["open", "-g", path], check=False)

    def render(self, tiles: list[tuple[Item, str]], cols: int) -> bytes:
        """The contact sheet as JPEG bytes."""
        from PIL import Image, ImageDraw, ImageFont

        tile, cap = 256, 22
        rows = math.ceil(len(tiles) / cols)
        sheet = Image.new("RGB", (cols * tile, rows * (tile + cap)), (20, 20, 20))
        draw = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.load_default(size=16)
        except TypeError:
            font = ImageFont.load_default()
        for k, (it, caption) in enumerate(tiles):
            th = self.thumb(it)
            x, y = (k % cols) * tile, (k // cols) * (tile + cap)
            sheet.paste(th, (x + (tile - th.width) // 2, y + (tile - th.height) // 2))
            draw.text((x + 4, y + tile + 2), caption + (" ▶" if it.is_video else ""), fill=(235, 235, 235), font=font)
        buf = io.BytesIO()
        sheet.save(buf, "JPEG", quality=80)
        return buf.getvalue()

    def show_cluster(self, cl: list[Item], max_tiles: int = 12) -> None:
        self.show([(it, it.t.strftime("%Y-%m-%d %H:%M")) for it in _spread(cl, max_tiles)])

    def open_files(self, items: list[Item]) -> None:
        subprocess.run(["open", *[os.path.join(self.source, it.rel) for it in items[:60]]], check=False)


def _spread(items: list, n: int) -> list:
    """Up to n of `items`, evenly spaced, first and last included."""
    if len(items) <= n:
        return list(items)
    if n == 1:
        return [items[len(items) // 2]]
    step = (len(items) - 1) / (n - 1)
    return [items[round(k * step)] for k in range(n)]


def _inline_capable() -> bool:
    if os.environ.get("LC_TERMINAL") != "iTerm2" and os.environ.get("TERM_PROGRAM") != "iTerm.app":
        return False
    if os.environ.get("TMUX"):
        r = subprocess.run(["tmux", "show", "-gv", "allow-passthrough"], capture_output=True, text=True)
        return r.stdout.strip() in ("on", "all")
    return True


# --- The interactive session --------------------------------------------------------

HELP = """\
  <name>       place name, partial is fine; 'paris, tx' / 'paris, france' /
               'sainte-colombe, rhone' (a pack commune's département or county) to narrow;
               a region or country name for something coarser
  !<name>      same, but ignore what was remembered for this text
  lat,lon      exact coordinates          #<id>   a GeoNames id
  Enter        accept the suggestion      =       same as the previous answer
  s            split this cluster at its biggest time gap (repeat to narrow down)
  v            contact sheet of every photo in the cluster   o  open them in Preview
  -            skip (asked again only with --retry-skipped)
  u            undo the previous answer   q  save and quit
"""

SHEET_HELP = """\
  Enter        every cluster on the sheet is at the assumed place
  3 7-9 12     these clusters are NOT: each run of consecutive numbers is then asked
               about as one question (where you can still split it); the rest get the place
  *            none of them: ask about each cluster on its own
  v 7          every photo in cluster 7             o 7   open them in Preview
  u            undo the previous answer             q     save and quit
"""

COORDS = re.compile(r"^\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$")
RANGES = re.compile(r"^\s*\d+(\s*-\s*\d+)?(\s*[ ,]\s*\d+(\s*-\s*\d+)?)*\s*$")


@dataclass
class Pending:
    items: list[Item]
    ask: bool = False  # True: ask on its own, never put on a review sheet

    @property
    def start(self) -> datetime:
        return self.items[0].t


def refresh_answers(geo: Geo, answers: dict) -> int:
    """Answers given against other data (an older region-pack set or gazetteer) are
    resolved again from what was picked (their key), so the output carries current
    places and `src`. One whose pick no longer exists stays as it was. Returns how many
    changed."""
    n = 0
    memo: dict[str, Resolution | None] = {}
    for a in answers.values():
        r = a.get("res")
        if a.get("method") != "prompt" or not r or not r.get("key") or r.get("place", {}).get("src") == geo.src:
            continue
        key = r["key"]
        if key not in memo:
            try:
                memo[key] = geo.from_key(key)
            except places.PackUnavailable:
                memo[key] = None
        new = memo[key]
        if new is not None and new.to_json() != r:
            a["res"] = new.to_json()
            n += 1
    return n


class Session:
    def __init__(self, geo: Geo, state_path: str, aliases_path: str, viewer: Viewer,
                 suggest_within: timedelta = timedelta(days=90)):
        self.geo, self.viewer = geo, viewer
        self.suggest_within = suggest_within
        self.state_path, self.aliases_path = state_path, aliases_path
        self.state = _load_json(state_path, {"answers": {}})
        self.aliases: dict[str, str] = _load_json(aliases_path, {})
        # (what to put back on the queue, answers before, knowns tag)
        self.history: list[tuple[list[Pending], dict[str, dict | None], int]] = []
        self.last: Resolution | None = None
        self.knowns: list[Known] = []
        self._tag = 0

    def _nearest(self, cl: list[Item]) -> tuple[Known | None, Known | None]:
        """The known places just before and after `cl`, ignoring any further away in
        time than --suggest-days: a GPS photo five years off suggests nothing."""
        before, after = _nearest_knowns(self.knowns, cl[0].t, cl[-1].t)
        if before and cl[0].t - before.t > self.suggest_within:
            before = None
        if after and after.t - cl[-1].t > self.suggest_within:
            after = None
        return before, after

    def save(self) -> None:
        _write_json(self.state_path, self.state)
        _write_json(self.aliases_path, self.aliases)

    def _record(self, groups: list[Pending], value: dict, res: Resolution | None) -> None:
        answers = self.state["answers"]
        self._tag += 1
        prev = {}
        for g in groups:
            for it in g.items:
                prev[it.rel] = answers.get(it.rel)
                answers[it.rel] = value
            if res is not None:
                for it in (g.items[0], g.items[-1]):
                    bisect.insort(self.knowns, Known(it.t, res, res.approx, "your answer", self._tag),
                                  key=lambda k: k.t)
        self.history.append((groups, prev, self._tag))
        if res is not None:
            self.last = res
        self.save()

    def _undo(self, queue: list[Pending]) -> bool:
        if not self.history:
            print("  nothing to undo")
            return False
        groups, prev, tag = self.history.pop()
        for rel, v in prev.items():
            if v is None:
                self.state["answers"].pop(rel, None)
            else:
                self.state["answers"][rel] = v
        self.knowns = [k for k in self.knowns if k.tag != tag]
        queue.extend(groups)
        queue.sort(key=lambda p: p.start)
        self.save()
        print(f"  undone: {sum(len(g.items) for g in groups)} photo(s) back in the queue")
        return True

    def resolve(self, text: str, near: tuple[float, float] | None) -> Resolution | None:
        """A typed name -> a resolution, via what's remembered or one confirmation.
        None: the user backed out."""
        force = text.startswith("!")
        text = text.lstrip("!").strip()
        key = fold(text)
        if not force and key in self.aliases:
            res = self.geo.from_key(self.aliases[key])
            if res is not None:
                print(f"  {text} → {res.label}  (remembered; '!{text}' to choose again)")
                if link := _link_line(res, "    "):
                    print(link)
                return res
        while True:
            cands = self.geo.search(text, near)
            if not cands:
                text = input(f"  nothing matches '{text}'. Try again (empty to go back): ").strip()
                if not text:
                    return None
                continue
            for n, c in enumerate(cands, 1):
                print(f"   {n}. {c.detail}")
                if link := _link_line(c.res):
                    print(link)
            pick = input("  Pick [Enter = 1], a number, retype, or 'b' to go back: ").strip()
            if pick == "":
                choice = cands[0]
            elif pick.isdigit() and 1 <= int(pick) <= len(cands):
                choice = cands[int(pick) - 1]
            elif pick == "b":
                return None
            else:
                text = pick
                continue
            self.aliases[key] = choice.res.key
            if fold(text) != key:
                self.aliases[fold(text)] = choice.res.key
            return choice.res

    def run(self, todo: list[list[Item]], knowns: list[Known], sheet_tiles: int) -> bool:
        """Asks until every cluster is placed or skipped. False if the user quit early."""
        self.knowns = sorted(knowns, key=lambda k: k.t)
        queue = [Pending(c) for c in todo]
        while queue:
            remaining = sum(len(p.items) for p in queue)
            head = queue[0]
            before, _ = self._nearest(head.items)
            if sheet_tiles and not head.ask and before is not None:
                batch = self._take_batch(queue, sheet_tiles)
                if len(batch) > 1:
                    r = self._sheet(queue, batch, before, remaining)
                    if r is False:
                        return False
                    continue
            queue.pop(0)
            if self._ask(queue, head, remaining) is False:
                return False
        return True

    @staticmethod
    def _take_batch(queue: list[Pending], tiles: int) -> list[Pending]:
        """Leading run of sheet-able clusters whose tiles (1 for a small cluster, up to
        3 for a big one) fit on one sheet."""
        batch, used = [], 0
        for p in queue:
            if p.ask:
                break
            need = _tiles_for(p)
            if batch and used + need > tiles:
                break
            batch.append(p)
            used += need
        return batch

    def _sheet(self, queue: list[Pending], batch: list[Pending], base: Known, remaining: int):
        n_photos = sum(len(p.items) for p in batch)
        print()
        print(f"══ Review sheet: {len(batch)} clusters, {n_photos} photo(s), "
              f"{batch[0].start:%Y-%m-%d} → {batch[-1].items[-1].t:%Y-%m-%d}   [{remaining} photo(s) left]")
        print(f"   assuming {base.res.label}  ({base.why}, {_fmt_delta(batch[0].start - base.t)} before)")
        tiles = []
        for n, p in enumerate(batch, 1):
            extra = f" +{len(p.items) - 1}" if len(p.items) > 1 else ""
            for it in _spread(p.items, _tiles_for(p)):
                tiles.append((it, f"#{n} {it.t:%Y-%m-%d}{extra}"))
        self.viewer.show(tiles, cols=6)
        for n, p in enumerate(batch, 1):
            print(f"   #{n:<3}{p.start:%Y-%m-%d %a %H:%M}  {len(p.items):>3} photo(s)  {os.path.basename(p.items[0].rel)}")
        while True:
            try:
                raw = input(f"Which are NOT {base.res.label}? [Enter = none] (h for help): ").strip()
            except EOFError:
                return False
            if raw in ("h", "?"):
                print(SHEET_HELP)
                continue
            if raw == "q":
                return False
            if raw == "u":
                self._undo(queue)
                return None
            m = re.match(r"^([vo])\s*(\d+)$", raw)
            if m:
                k = int(m[2])
                if 1 <= k <= len(batch):
                    if m[1] == "v":
                        for s in range(0, len(batch[k - 1].items), 24):
                            self.viewer.show([(it, f"{it.t:%Y-%m-%d %H:%M}") for it in batch[k - 1].items[s:s + 24]], cols=6)
                    else:
                        self.viewer.open_files(batch[k - 1].items)
                else:
                    print(f"  there is no #{k}")
                continue
            if raw == "*":
                marked = set(range(1, len(batch) + 1))
            elif raw == "":
                marked = set()
            elif RANGES.match(raw):
                marked = set()
                for part in re.split(r"[ ,]+", raw.replace(" - ", "-").strip()):
                    a, _, b = part.partition("-")
                    marked.update(range(int(a), int(b or a) + 1))
                if not all(1 <= k <= len(batch) for k in marked):
                    print(f"  numbers go from 1 to {len(batch)}")
                    continue
            else:
                print("  numbers like '3 7-9', Enter, '*', or h for help")
                continue
            break

        del queue[:len(batch)]
        keep = [p for n, p in enumerate(batch, 1) if n not in marked]
        if keep:
            self._record(keep, {"method": "prompt", "res": base.res.to_json(), "query": "(review sheet)"}, base.res)
        # Each run of consecutive marked numbers becomes one question; '*' keeps them apart.
        runs: list[list[Item]] = []
        prev = None
        for n in sorted(marked):
            if raw != "*" and prev is not None and n == prev + 1:
                runs[-1].extend(batch[n - 1].items)
            else:
                runs.append(list(batch[n - 1].items))
            prev = n
        queue[0:0] = [Pending(r, ask=True) for r in runs]
        print(f"  {len(keep)} cluster(s) → {base.res.label}" + (f"; {len(runs)} question(s) to follow" if runs else ""))
        return None

    def _ask(self, queue: list[Pending], p: Pending, remaining: int):
        cl = p.items
        before, after = self._nearest(cl)
        suggestion = _pick_suggestion(before, after, cl)
        print()
        print(f"── {len(cl)} photo(s) · {cl[0].t:%Y-%m-%d %a %H:%M} → {cl[-1].t:%Y-%m-%d %H:%M}"
              f" ({_fmt_delta(cl[-1].t - cl[0].t)})   [{remaining} photo(s) left]")
        if not cl[0].real_time:
            print("   (no capture time in these files: ordered by file mtime, which may mean little)")
        for label, k, gap in (("before", before, before and cl[0].t - before.t),
                              ("after ", after, after and after.t - cl[-1].t)):
            if k:
                print(f"   {label}: {k.res.label}  ({k.why}, {_fmt_delta(gap)} {label.strip()})")
        print(f"   e.g. {cl[0].rel}")
        self.viewer.show_cluster(cl)
        if suggestion and (link := _link_line(suggestion, "   ", "suggestion: ")):
            print(link)
        while True:
            prompt = "Where? "
            if suggestion:
                prompt += f"[Enter = {suggestion.label}] "
            try:
                raw = input(prompt + "(h for help): ").strip()
            except EOFError:
                return False
            if raw == "" and suggestion:
                res = suggestion
            elif raw == "":
                continue
            elif raw in ("h", "?"):
                print(HELP)
                continue
            elif raw == "q":
                queue.insert(0, p)
                return False
            elif raw == "v":
                for k in range(0, len(cl), 24):
                    self.viewer.show([(it, f"{it.t:%Y-%m-%d %H:%M}") for it in cl[k:k + 24]], cols=6)
                continue
            elif raw == "o":
                self.viewer.open_files(cl)
                continue
            elif raw == "s":
                if len(cl) < 2:
                    print("  a single photo can't be split")
                    continue
                a, b = split_at_biggest_gap(cl)
                queue[0:0] = [Pending(a, ask=True), Pending(b, ask=True)]
                return None
            elif raw == "u":
                if self._undo(queue):
                    queue.insert(0, p)
                    queue.sort(key=lambda x: x.start)
                    return None
                continue
            elif raw == "-":
                self._record([p], {"method": "skipped"}, None)
                return None
            elif raw == "=":
                if not self.last:
                    print("  no previous answer yet")
                    continue
                res = self.last
            elif raw.startswith("#") and raw[1:].isdigit():
                res = self.geo.from_key(f"L:{raw[1:]}")
                if res is None:
                    print(f"  {raw[1:]} isn't a cities500 geonameid")
                    continue
                print(f"  → {res.label}")
            elif (m := COORDS.match(raw)) and abs(float(m[1])) <= 90 and abs(float(m[2])) <= 180:
                res = self.geo.coords_res(float(m[1]), float(m[2]))
                print(f"  → {res.label}")
            else:
                res = self.resolve(raw, suggestion.approx if suggestion else None)
                if res is None:
                    continue
            if res.place is None:
                print("  that's more than 100 km from any town: nothing to store. Try another.")
                continue
            self._record([p], {"method": "prompt", "res": res.to_json(), "query": raw}, res)
            return None


def _tiles_for(p: Pending) -> int:
    return 1 if len(p.items) < 4 else 2 if len(p.items) < 12 else 3


def _nearest_knowns(knowns: list[Known], start: datetime, end: datetime) -> tuple[Known | None, Known | None]:
    times = [k.t for k in knowns]
    i = bisect.bisect_left(times, start)
    j = bisect.bisect_right(times, end)
    return (knowns[i - 1] if i > 0 else None), (knowns[j] if j < len(knowns) else None)


def _pick_suggestion(before: Known | None, after: Known | None, cl: list[Item]) -> Resolution | None:
    gb = (cl[0].t - before.t) if before else None
    ga = (after.t - cl[-1].t) if after else None
    if before and (not after or gb <= ga):
        return before.res
    return after.res if after else None


def _fmt_delta(d: timedelta | None) -> str:
    if d is None:
        return ""
    s = int(d.total_seconds())
    if s < 3600:
        return f"{s // 60}m"
    if s < 86400 * 2:
        return f"{s // 3600}h{(s % 3600) // 60:02d}m"
    return f"{s // 86400}d{(s % 86400) // 3600}h"


# --- Persistence -------------------------------------------------------------------


def _load_json(path: str, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def _write_json(path: str, data) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1 if path.endswith("state.json") else None)
    os.replace(tmp, path)


def write_output(path: str, items: list[Item], inferred: dict[str, Inferred], answers: dict[str, dict]) -> dict:
    counts = {"prompt": 0, "neighbour-gps": 0, "sibling-gps": 0, "skipped": 0, "unresolved": 0}
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for it in items:
            if it.gps is not None:
                continue
            rec = {"path": it.rel, "size": it.size, "takenLocal": it.t.isoformat(), "timeSrc": it.time_src}
            a = answers.get(it.rel)
            if a and a["method"] == "prompt":
                res = Resolution.from_json(a["res"])
                rec.update(method="prompt", query=a.get("query"))
            elif a and a["method"] == "skipped":
                counts["skipped"] += 1
                continue
            elif it.rel in inferred:
                inf = inferred[it.rel]
                res = inf.res
                rec.update(method=inf.method, basis={"path": inf.basis, "gapS": inf.gap_s})
            else:
                counts["unresolved"] += 1
                continue
            counts[rec["method"]] += 1
            rec.update(place=res.place, label=res.label,
                       approx={"lat": round(res.approx[0], 5), "lon": round(res.approx[1], 5)} if res.approx else None)
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    os.replace(tmp, path)
    return counts


# --- main --------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="directory of photos/videos, walked recursively")
    ap.add_argument("--out", default="missing-locations.jsonl", help="output JSON Lines (default: %(default)s)")
    ap.add_argument("--state-dir", default=DEFAULT_STATE_DIR,
                    help="scan cache, answers, remembered names, GeoNames files (default: %(default)s)")
    ap.add_argument("--geonames-dir", default=None,
                    help="cities500.zip + admin1CodesASCII.txt + countryInfo.txt (default: <state-dir>/geonames, "
                    "downloaded from geonames.org on first use)")
    ap.add_argument("--gazetteer", default=None, help="gazetteer.bin, as for main.py")
    packs = ap.add_mutually_exclusive_group()
    packs.add_argument("--host", default=None,
                       help="the instance the output is for: its region packs (public /geo/manifest.json, no "
                       "sign-in) are used for places and searched by name, as an upload there would be")
    packs.add_argument("--manifest", default=None, help="a local region-pack set's manifest.json instead")
    ap.add_argument("--gap-hours", type=float, default=6.0,
                    help="start a new question after a gap this long between photos (default: %(default)s)")
    ap.add_argument("--bracket-hours", type=float, default=4.0,
                    help="infer from GPS photos on both sides within this long that agree (default: %(default)s)")
    ap.add_argument("--agree-km", type=float, default=15.0,
                    help="...and are at most this far apart (default: %(default)s)")
    ap.add_argument("--near-minutes", type=float, default=45.0,
                    help="infer from a single GPS photo at most this far away in time (default: %(default)s)")
    ap.add_argument("--sheet", type=int, default=24,
                    help="photos per review sheet, which asks 'which of these are NOT <place>?' about many "
                    "clusters at once; 0 asks about every cluster on its own (default: %(default)s)")
    ap.add_argument("--suggest-days", type=float, default=90.0,
                    help="only suggest (or assume, on a review sheet) a place known within this many days "
                    "(default: %(default)s)")
    ap.add_argument("--no-infer", action="store_true", help="ask about everything, infer nothing")
    ap.add_argument("--no-prompt", action="store_true", help="scan, infer and write the output; ask nothing")
    ap.add_argument("--retry-skipped", action="store_true", help="ask again about clusters you skipped with '-'")
    ap.add_argument("--viewer", choices=("auto", "inline", "open", "none"), default="auto",
                    help="contact sheets inline (iTerm2, tmux passthrough), in Preview, or not at all")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    args = ap.parse_args()

    source = os.path.abspath(os.path.expanduser(args.source))
    if not os.path.isdir(source):
        print(f"error: --source {source!r} is not a directory", file=sys.stderr)
        return 2
    src_key = hashlib.sha1(source.encode()).hexdigest()[:16]
    run_dir = os.path.join(args.state_dir, src_key)
    geonames_dir = args.geonames_dir or os.path.join(args.state_dir, "geonames")

    try:
        geocoder = (places.load(args.host, args.gazetteer) if args.host
                    else places.load_local(args.manifest, args.gazetteer) if args.manifest
                    else places.Geocoder(gazetteer.load(args.gazetteer)))
        gaz = geocoder.gazetteer
        ensure_geonames(geonames_dir)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    print("loading GeoNames cities500" + (" and region packs" if geocoder.manifest else "") + "...", file=sys.stderr)
    try:
        geo = Geo(geonames_dir, gaz, geocoder)
    except places.PackUnavailable as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    items, errors = scan(source, os.path.join(run_dir, "scan.json"), args.jobs)
    anchor_res: dict[str, Resolution] = {}
    for it in items:
        if it.gps is not None:
            r = geo.from_place(geocoder.lookup(*it.gps), it.gps)
            if r is not None:
                anchor_res[it.rel] = r
    opts = Options(timedelta(hours=args.bracket_hours), timedelta(minutes=args.near_minutes),
                   args.agree_km, timedelta(hours=args.gap_hours))
    inferred = {} if args.no_infer else infer(items, anchor_res, opts)

    session = Session(geo, os.path.join(run_dir, "state.json"), os.path.join(args.state_dir, "aliases.json"),
                      Viewer(args.viewer, source, os.path.join(args.state_dir, "thumbs")),
                      timedelta(days=args.suggest_days))
    answers = session.state["answers"]
    refreshed = refresh_answers(geo, answers)
    if refreshed:
        print(f"re-resolved {refreshed} earlier answer(s) against {geo.src}", file=sys.stderr)
        session.save()
    with open(os.path.join(run_dir, "source.txt"), "w", encoding="utf-8") as f:
        f.write(source + "\n")

    no_gps = [it for it in items if it.gps is None]
    pending = [it for it in no_gps
               if it.rel not in inferred
               and (it.rel not in answers or (args.retry_skipped and answers[it.rel]["method"] == "skipped"))]
    todo = clusters(pending, opts.gap)
    print(f"\n{len(items)} files: {len(items) - len(no_gps)} with GPS, {len(no_gps)} without; "
          f"{len(inferred)} of those inferred from nearby GPS photos, "
          f"{sum(1 for it in no_gps if answers.get(it.rel, {}).get('method') == 'prompt')} already answered, "
          f"{len(pending)} left in {len(todo)} cluster(s).", file=sys.stderr)
    if errors:
        print(f"{len(errors)} file(s) couldn't be read (they still get a place from their mtime):", file=sys.stderr)
        for rel, err in errors[:10]:
            print(f"  {rel}: {err}", file=sys.stderr)

    finished = True
    try:
        if todo and not args.no_prompt:
            knowns = [Known(it.t, anchor_res[it.rel], it.gps, "GPS photo") for it in items
                      if it.rel in anchor_res and it.real_time]
            knowns += [Known(it.t, inferred[it.rel].res, None, "inferred") for it in items if it.rel in inferred]
            for it in items:
                a = answers.get(it.rel)
                if a and a["method"] == "prompt":
                    r = Resolution.from_json(a["res"])
                    knowns.append(Known(it.t, r, r.approx, "your answer"))
            finished = session.run(todo, knowns, args.sheet)
    except KeyboardInterrupt:
        finished = False
        print()
    finally:
        session.save()
        counts = write_output(args.out, items, inferred, session.state["answers"])
        print(f"\nwrote {sum(counts[k] for k in ('prompt', 'neighbour-gps', 'sibling-gps'))} location(s) "
              f"to {os.path.abspath(args.out)}: {counts['prompt']} answered, {counts['neighbour-gps']} from "
              f"neighbouring GPS photos, {counts['sibling-gps']} from an edited copy's original; "
              f"{counts['skipped']} skipped, {counts['unresolved']} still unasked.", file=sys.stderr)
        if not finished:
            print("answers are saved; run the same command again to carry on.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
