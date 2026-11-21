"""The `place` a client stores for a pair of coordinates: the bundled gazetteer, plus
the instance's region packs when it publishes them.

Rules: tools/geopack/README.md, "Combining with the gazetteer". Design: design.md,
"Location" and "Detailed places: OpenStreetMap region packs".

`Geocoder` has the same two members the importer and modify_media.py already use on a
gazetteer, `src` and `lookup(lat, lon)`, so it drops in wherever a gazetteer did:

* no manifest (the instance publishes no packs): exactly the gazetteer;
* with a manifest: `src` is `osm@<set>;<gazetteer src>` for every place, and points a
  pack covers get the pack's locality, sub, spot, areas and feature;
* a pack that can't be fetched or fails its checks: the gazetteer's place with the
  gazetteer's own `src`, never a partial pack set, so a later run redoes it.

Packs are downloaded on first use and cached by sha256 under `.cache/geo/`. Thread-safe:
modify_media.py geocodes from several threads.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import threading
import urllib.error
import urllib.request

import geopack
import gazetteer as gz

DEFAULT_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache", "geo")
SET_RE = re.compile(r"^[0-9A-Za-z._-]{1,20}$")
NAME_RE = re.compile(r"^[a-z0-9-]{1,64}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PLACE_KEYS = ("cc", "region", "locality", "sub", "spot", "areas", "feature", "src")


class PackUnavailable(Exception):
    """A covering pack couldn't be fetched, or failed its size/sha256/set checks."""


def manifest_url(host: str) -> str:
    return f"https://{host}/geo/manifest.json"


def _http_get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "archivist-import-from-local"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def fetch_manifest(host: str, get=_http_get) -> dict | None:
    """The instance's manifest, or None when it publishes no packs (403/404: S3 behind
    CloudFront answers 403 for a missing key). Anything else is an error: deciding
    "no packs" on a network hiccup would give photos the wrong `src`."""
    try:
        data = get(manifest_url(host))
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):
            return None
        raise
    return validate_manifest(json.loads(data.decode("utf-8")))


def validate_manifest(m: dict) -> dict:
    if m.get("format") != 1:
        raise ValueError(f"unsupported region-pack manifest format {m.get('format')!r}")
    if not isinstance(m.get("set"), str) or not SET_RE.match(m["set"]):
        raise ValueError(f"bad manifest set {m.get('set')!r}")
    seen = set()
    for p in m.get("packs", []):
        if not NAME_RE.match(p.get("name", "")) or p["name"] in seen:
            raise ValueError(f"bad or duplicate pack name {p.get('name')!r}")
        seen.add(p["name"])
        if not SHA256_RE.match(p.get("sha256", "")) or not isinstance(p.get("bytes"), int):
            raise ValueError(f"pack {p['name']}: bad sha256 or bytes")
        b = p.get("bbox")
        if not (isinstance(b, list) and len(b) == 4 and all(isinstance(x, int) for x in b) and b[0] <= b[2] and b[1] <= b[3]):
            raise ValueError(f"pack {p['name']}: bad bbox")
        if not isinstance(p.get("path"), str) or ".." in p["path"] or p["path"].startswith("/"):
            raise ValueError(f"pack {p['name']}: bad path")
    return m


class PackStore:
    """Fetches packs named by a manifest, checks them, caches them by sha256."""

    def __init__(self, base_url: str, cache_dir: str = DEFAULT_CACHE_DIR, get=_http_get, progress=lambda m: None):
        self.base_url = base_url.rstrip("/")
        self.cache_dir = cache_dir
        self.get = get
        self.progress = progress

    def load(self, entry: dict, set_id: str) -> geopack.Pack:
        path = os.path.join(self.cache_dir, "packs", f"{entry['sha256']}.bin")
        data = None
        if os.path.exists(path):
            with open(path, "rb") as f:
                data = f.read()
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                data = None  # corrupt cache entry: fetch again
        if data is None:
            url = f"{self.base_url}/{entry['path']}"
            self.progress(f"downloading region pack {entry['name']} ({entry['bytes']:,} bytes)")
            try:
                data = self.get(url)
            except (OSError, urllib.error.URLError) as e:
                raise PackUnavailable(f"{url}: {e}") from e
            if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise PackUnavailable(f"{url}: size or sha256 differs from the manifest")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tmp = f"{path}.{os.getpid()}.tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, path)
        try:
            pack = geopack.read_pack(data)
        except (ValueError, IndexError, UnicodeDecodeError) as e:
            raise PackUnavailable(f"{entry['name']}: {e}") from e
        if pack.set != set_id:
            raise PackUnavailable(f"{entry['name']}: pack set {pack.set!r} isn't the manifest's {set_id!r}")
        return pack


class Geocoder:
    """Gazetteer plus optional region packs. `src` and `lookup` as on a gazetteer."""

    def __init__(self, gazetteer, manifest: dict | None = None, store: PackStore | None = None):
        self.gazetteer = gazetteer
        self.manifest = manifest
        self.store = store
        if manifest is not None and store is None:
            raise ValueError("a manifest needs a PackStore")
        self.src = f"osm@{manifest['set']};{gazetteer.src}" if manifest else gazetteer.src
        self._lock = threading.Lock()
        self._packs: dict[str, geopack.Pack] = {}
        self._failed: dict[str, str] = {}
        self._sets: dict[tuple[str, ...], geopack.PackSet] = {}

    @property
    def set_id(self) -> str | None:
        return self.manifest["set"] if self.manifest else None

    def covering(self, lat: float, lon: float) -> list[dict]:
        if not self.manifest:
            return []
        return [e for e in self.manifest["packs"] if geopack.covers(e["bbox"], lat, lon)]

    def _pack_set(self, entries: list[dict]) -> geopack.PackSet:
        names = tuple(e["name"] for e in entries)
        with self._lock:
            ps = self._sets.get(names)
            if ps is not None:
                return ps
            for e in entries:
                if e["name"] in self._failed:
                    raise PackUnavailable(self._failed[e["name"]])
                if e["name"] not in self._packs:
                    try:
                        self._packs[e["name"]] = self.store.load(e, self.manifest["set"])
                    except PackUnavailable as err:
                        self._failed[e["name"]] = str(err)
                        raise
            ps = geopack.PackSet([self._packs[e["name"]] for e in entries])
            self._sets[names] = ps
            return ps

    def preload(self) -> None:
        """Fetch every pack in the manifest now (locate_missing.py searches them all)."""
        for e in (self.manifest or {}).get("packs", []):
            self._pack_set([e])

    def all_packs(self) -> list[geopack.Pack]:
        """The packs fetched so far, in manifest order."""
        order = [e["name"] for e in (self.manifest or {}).get("packs", [])]
        return [self._packs[n] for n in order if n in self._packs]

    def pack_layers(self, lat: float, lon: float) -> dict | None:
        """The packs' own result for a point ({} if covered but nothing found), or None
        when no pack covers it. Raises PackUnavailable."""
        entries = self.covering(lat, lon)
        if not entries:
            return None
        return self._pack_set(entries).lookup(lat, lon)

    def lookup(self, lat: float, lon: float) -> dict | None:
        base = self.gazetteer.lookup(lat, lon)
        if base is None or self.manifest is None:
            return base
        try:
            layers = self.pack_layers(lat, lon)
        except PackUnavailable as e:
            print(f"warning: {e}; using the gazetteer alone for {lat:.5f},{lon:.5f}", file=sys.stderr)
            return base
        return combine(base, layers, self.src)


def combine(base: dict, layers: dict | None, src: str) -> dict:
    """README "Combining with the gazetteer", steps 3-4: cc and region from the
    gazetteer, locality from the pack when it has one, the finer levels added."""
    place = dict(base)
    if layers:
        for k in ("locality", "sub", "spot", "areas", "feature"):
            if k in layers:
                place[k] = layers[k]
    place["src"] = src
    return {k: place[k] for k in PLACE_KEYS if k in place}


def load(host: str | None, gazetteer_path: str | None = None, cache_dir: str = DEFAULT_CACHE_DIR,
         progress=lambda m: print(m, file=sys.stderr), get=_http_get) -> Geocoder:
    """The geocoder a client of `host` must use: its manifest decides. No host: the
    gazetteer alone."""
    gaz = gz.load(gazetteer_path)
    if not host:
        return Geocoder(gaz)
    manifest = fetch_manifest(host, get=get)
    if manifest is None:
        progress(f"{host} publishes no region packs: places come from the gazetteer alone")
        return Geocoder(gaz)
    progress(f"{host} publishes region pack set {manifest['set']} ({len(manifest['packs'])} pack(s))")
    base = manifest_url(host).rsplit("/", 1)[0]
    return Geocoder(gaz, manifest, PackStore(base, cache_dir, get=get, progress=progress))


def load_local(manifest_path: str, gazetteer_path: str | None = None) -> Geocoder:
    """A geocoder over a pack set on disk, e.g. `make geo-build`'s output, to try it
    before publishing. Same rules as an instance's set."""
    gaz = gz.load(gazetteer_path)
    with open(manifest_path, "rb") as f:
        manifest = validate_manifest(json.loads(f.read().decode("utf-8")))
    base = os.path.dirname(os.path.abspath(manifest_path))

    def read(url: str) -> bytes:
        with open(os.path.join(base, url.split("/", 1)[1]), "rb") as f:
            return f.read()

    store = PackStore("local", cache_dir=DEFAULT_CACHE_DIR, get=read)
    return Geocoder(gaz, manifest, store)


def describe(place: dict | None) -> str:
    """A one-line human label, most specific first: 'Le Planet, Argentière, Chamonix-Mont-Blanc,
    Auvergne-Rhône-Alpes, FR · Réserve naturelle des Aiguilles Rouges'."""
    if not place:
        return "(none)"
    parts = [place[k]["name"] for k in ("spot", "sub", "locality", "region") if place.get(k)]
    s = ", ".join(parts + [place["cc"]])
    extra = [a["name"] for a in place.get("areas", [])] + ([place["feature"]["name"]] if place.get("feature") else [])
    return s + (" · " + " · ".join(extra) if extra else "")
