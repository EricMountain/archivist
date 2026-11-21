"""The `place` a client stores for a pair of coordinates: the bundled gazetteer, plus
the instance's region packs when it publishes them.

Rules: tools/geopack/README.md, "Combining with the gazetteer". Design: design.md,
"Location" and "Detailed places: OpenStreetMap region packs".

`Geocoder` has the same two members the importer and modify_media.py already use on a
gazetteer, `src` and `lookup(lat, lon)`, so it drops in wherever a gazetteer did:

* no manifest (the instance publishes no packs): exactly the gazetteer;
* with a manifest: `src` is `osm@<set>;<gazetteer src>` for every place, and points in
  a tile's grid square get the tile's locality, sub, spot, areas and feature;
* a tile that can't be fetched or fails its checks: the gazetteer's place with the
  gazetteer's own `src`, so a later run redoes it.

Tiles are downloaded on first use and cached by sha256 under `.cache/geo/tiles/<host>/`,
one directory per instance; each time an instance's manifest is fetched, cached tiles it
no longer names are deleted. A set on disk (`load_local`) is read in place, not copied.
Thread-safe: modify_media.py geocodes from several threads.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sys
import threading
import urllib.error
import urllib.request

import geopack
import gazetteer as gz

DEFAULT_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache", "geo")
SET_RE = re.compile(r"^[0-9A-Za-z._-]{1,20}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PLACE_KEYS = ("cc", "region", "locality", "sub", "spot", "areas", "feature", "src")


class PackUnavailable(Exception):
    """The tile a point needs couldn't be fetched, or failed its size/sha256/set checks."""


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
    """A format-2 manifest (tools/geopack/README.md, "Manifest"): tiles keyed by grid square."""
    if m.get("format") != 2:
        raise ValueError(f"unsupported region-pack manifest format {m.get('format')!r}")
    if not isinstance(m.get("set"), str) or not SET_RE.match(m["set"]):
        raise ValueError(f"bad manifest set {m.get('set')!r}")
    tiles = m.get("tiles")
    if not isinstance(tiles, dict):
        raise ValueError("manifest has no tiles")
    for key, t in tiles.items():
        if not geopack.TILE_KEY_RE.match(key):
            raise ValueError(f"bad tile key {key!r}")
        if not isinstance(t, dict) or not SHA256_RE.match(t.get("sha256", "")) or not isinstance(t.get("bytes"), int):
            raise ValueError(f"tile {key}: bad sha256 or bytes")
    keys = sorted(tiles)
    for a, b in zip(keys, keys[1:]):
        if b.startswith(a):
            raise ValueError(f"tile {a} contains tile {b}")
    return m


class PackStore:
    """Fetches the tiles a manifest names, checks them, caches them by sha256."""

    def __init__(self, base_url: str, cache_dir: str | None, get=_http_get, progress=lambda m: None):
        """`cache_dir`: where tiles are kept by sha256, or None to fetch every time (a set
        on disk, which needs no copy)."""
        self.base_url = base_url.rstrip("/")
        self.cache_dir = cache_dir
        self.get = get
        self.progress = progress

    def load(self, key: str, entry: dict, set_id: str) -> geopack.Pack:
        path = os.path.join(self.cache_dir, f"{entry['sha256']}.bin") if self.cache_dir else None
        data = None
        if path and os.path.exists(path):
            with open(path, "rb") as f:
                data = f.read()
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                data = None  # corrupt cache entry: fetch again
        if data is None:
            url = f"{self.base_url}/{set_id}/{key}.bin"
            self.progress(f"downloading tile {key} ({entry['bytes']:,} bytes)")
            try:
                data = self.get(url)
            except (OSError, urllib.error.URLError) as e:
                raise PackUnavailable(f"{url}: {e}") from e
            if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise PackUnavailable(f"{url}: size or sha256 differs from the manifest")
            if path:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, path)
        try:
            pack = geopack.read_pack(data)
        except (ValueError, IndexError, UnicodeDecodeError) as e:
            raise PackUnavailable(f"tile {key}: {e}") from e
        if pack.set != set_id or pack.name != key:
            raise PackUnavailable(f"tile {key}: it says it is {pack.set}/{pack.name}")
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
        self._tiles: dict[str, geopack.PackSet] = {}
        self._failed: dict[str, str] = {}

    @property
    def set_id(self) -> str | None:
        return self.manifest["set"] if self.manifest else None

    def tile_for(self, lat: float, lon: float) -> str | None:
        """The key of the tile whose grid square contains the point, if any."""
        if not self.manifest:
            return None
        return geopack.find_tile(self.manifest["tiles"].keys(), lat, lon)

    def _tile(self, key: str) -> geopack.PackSet:
        with self._lock:
            ps = self._tiles.get(key)
            if ps is not None:
                return ps
            if key in self._failed:
                raise PackUnavailable(self._failed[key])
            try:
                pack = self.store.load(key, self.manifest["tiles"][key], self.manifest["set"])
            except PackUnavailable as err:
                self._failed[key] = str(err)
                raise
            ps = self._tiles[key] = geopack.PackSet([pack])
            return ps

    def preload(self) -> None:
        """Fetch every tile in the manifest now (locate_missing.py searches them all)."""
        for key in sorted((self.manifest or {}).get("tiles", {})):
            self._tile(key)

    def all_packs(self) -> list[geopack.Pack]:
        """The tiles fetched so far, in key order. An entity near a tile's edge is in
        several of them, each copy clipped to its own tile."""
        return [self._tiles[k].packs[0] for k in sorted(self._tiles)]

    def admins_at(self, lat: float, lon: float) -> list:
        """The admin areas containing a point, from its tile ([] without one)."""
        key = self.tile_for(lat, lon)
        return self._tile(key).admins_at(lat, lon) if key is not None else []

    def pack_layers(self, lat: float, lon: float) -> dict | None:
        """The tile's own result for a point ({} if it found nothing), or None when no tile
        covers it. Raises PackUnavailable."""
        key = self.tile_for(lat, lon)
        if key is None:
            return None
        return self._tile(key).lookup(lat, lon)

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


def host_cache_dir(cache_dir: str, host: str) -> str:
    return os.path.join(cache_dir, "tiles", re.sub(r"[^A-Za-z0-9.-]", "_", host))


def prune_cache(tile_dir: str, manifest: dict | None) -> int:
    """Delete cached tiles (and leftover temp files) the manifest doesn't name; all of
    them when the instance publishes none. Returns how many were deleted."""
    keep = {t["sha256"] for t in manifest["tiles"].values()} if manifest else set()
    n = 0
    try:
        names = os.listdir(tile_dir)
    except FileNotFoundError:
        return 0
    for name in names:
        if name.endswith(".bin") and name[:-4] in keep:
            continue
        try:
            os.remove(os.path.join(tile_dir, name))
            n += 1
        except OSError:
            pass
    return n


def load(host: str | None, gazetteer_path: str | None = None, cache_dir: str = DEFAULT_CACHE_DIR,
         progress=lambda m: print(m, file=sys.stderr), get=_http_get) -> Geocoder:
    """The geocoder a client of `host` must use: its manifest decides. No host: the
    gazetteer alone. Prunes the host's tile cache to the manifest it just fetched."""
    gaz = gz.load(gazetteer_path)
    if not host:
        return Geocoder(gaz)
    # Before tiles, packs of every instance shared one flat `packs/` directory.
    shutil.rmtree(os.path.join(cache_dir, "packs"), ignore_errors=True)
    tile_dir = host_cache_dir(cache_dir, host)
    manifest = fetch_manifest(host, get=get)
    pruned = prune_cache(tile_dir, manifest)
    if pruned:
        progress(f"removed {pruned} cached tile(s) {host} no longer publishes")
    if manifest is None:
        progress(f"{host} publishes no region tiles: places come from the gazetteer alone")
        return Geocoder(gaz)
    progress(f"{host} publishes region tile set {manifest['set']} ({len(manifest['tiles'])} tiles)")
    base = manifest_url(host).rsplit("/", 1)[0]
    return Geocoder(gaz, manifest, PackStore(base, tile_dir, get=get, progress=progress))


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

    store = PackStore("local", cache_dir=None, get=read)
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
