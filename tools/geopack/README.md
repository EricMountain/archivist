# Region packs

Extracts of OpenStreetMap that an instance can publish, so clients can label a photo
with the commune, village, hamlet, protected areas and natural feature around it rather
than just the nearest GeoNames town. The operator lists regions; the builder turns each
into a *region pack*, merges them, and cuts the result into *tiles* of at most about
1 MB, each named after its square on a fixed world grid. A client fetches and reads only
the one tile whose square contains the photo. Readers:

* the Android app (`domain/GeoPack*.kt`);
* `tools/import-from-local/geopack.py`.

Design: `docs/design/design.md`, "Location", "Detailed places: OpenStreetMap region
packs". Plan: `docs/plans/geo-packs.md`. Operator runbook: `docs/ops/geo-packs.md`.

| File | What |
| --- | --- |
| `build.py` | One Geofabrik extract (`.osm.pbf`) → one region pack. Needs `osmium` and `shapely` (`requirements.txt`). |
| `tile.py` | Region packs → tiles (merge, grid, clip). Standard library only. |
| `build_set.py` | A list of regions → a tile set: every tile plus `manifest.json`, ready to publish. |
| `packfmt.py` | The encoder `build.py`, `tile.py` and `make_fixture.py` share. Standard library only. |
| `reference.py` | Reference reader, grid keys and lookup, standard library only. Has a brute-force scan (the oracle) and a grid-indexed lookup. |
| `make_fixture.py` | Writes the synthetic region packs `testdata/fixture-a.bin` and `fixture-b.bin` (hand-placed geometry that exercises every lookup rule; no OSM data), and the tiles cut from them, `testdata/fixture/<key>.bin` with `fixture-manifest.json`. |
| `make_vectors.py` | Writes `vectors.json`, after asserting that the indexed lookup, the brute force and the lookup through the point's tile agree on the named cases and on random points. |
| `vectors.json` | Shared conformance vectors over the fixture. Every reader must reproduce every result exactly. |
| `regions.example.txt` | Example region list for `build_set.py`. An instance's real list goes in `private/`. |

## Licence and attribution

Packs are a derivative database of OpenStreetMap data, © OpenStreetMap contributors,
available under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Every pack carries the attribution string `© OpenStreetMap contributors, ODbL 1.0` in its
header, and so does the manifest. An instance that publishes packs publishes them under
ODbL. The app credits OpenStreetMap contributors on its About screen.

The fixture packs and `vectors.json` contain no OpenStreetMap data. The tools are part of
the project, under the repository's own licence.

## Building

Operators build packs on their own machine (decision G3); see `docs/ops/geo-packs.md`.

```sh
python3 -m venv ~/.venvs/geopack && ~/.venvs/geopack/bin/pip install -r tools/geopack/requirements.txt
~/.venvs/geopack/bin/python tools/geopack/build_set.py \
    --regions private/instance/geo-regions.txt --work ~/geo-work --out ~/geo-out
```

`build_set.py` downloads each listed extract into `--work` (once; `--refresh` to fetch
again), builds each region pack, merges them (an entity in two extracts is taken from
the region listed first), cuts the result into tiles (`--tile-cap`, default 1,000,000
bytes; see "Tiles"), and writes `--out/manifest.json` and `--out/<set>/<key>.bin`,
removing any other set from `--out`. The set id defaults to today's UTC date plus a
digest of the builder, the tiler and its settings, the region names and their extracts
(`2026-11-21.d221a5`), so any change to a set changes its id and with it every new
`place.src`. Region packs are kept in `--work/packs/` by region, extract md5 and builder
hash, so adding a region builds only that one before tiling again. Building one region
takes seconds to minutes (Provence-Alpes-Côte d'Azur, 392 MB: about 30 s); tiling 23
regions (161 MB of region packs) takes a few minutes and gives 640 tiles, 198 MB in all,
median 284 KB.

Downloads happen first, three at a time (`--download-jobs`), into `<extract>.part`
files that a later run resumes. A Geofabrik `-latest` URL is resolved to the dated file
it redirects to (`<region>-YYMMDD.osm.pbf`) and that file is downloaded, so a resume never
joins two days' data; the result is checked against Geofabrik's `.md5`.

It also writes a build record, `geo-builds/<set>.json` beside the regions file
(`--record` to move it): for each region, the extract's URL, the dated URL of the exact
file (`dated`, when Geofabrik's md5 confirms it), cached file, size, md5, sha256 and OSM
data timestamp, the region pack's size, and a summary of the tiles. It names the
regions, so it stays out of the published directory.

What `build.py` keeps, all requiring a `name` tag:

| Layer | OSM objects |
| --- | --- |
| admin | areas with `boundary=administrative`, `admin_level` 5–8, with the relation's `label` member node if any |
| place | nodes with `place` = city, town, village, suburb, quarter, hamlet, neighbourhood, locality. **Not** `isolated_dwelling` or `farm` (decision G4) |
| protected | areas with `boundary=national_park`, `boundary=protected_area` or `leisure=nature_reserve`, all of them (decision G5) |
| feature | nodes `natural` = peak, volcano, saddle, cape; open ways `natural` = ridge, arête, valley, cliff, gorge; areas `natural` = mountain_range, massif, bay, beach, cape, glacier, water, valley, plateau, gorge, or `place` = island, islet |

Polygons and lines are simplified with a 15 m tolerance (shapely `simplify`,
`preserve_topology=True`, in a local metric projection per object), then rounded to
micro-degrees. Areas are computed before simplification. Building twice from the same
extract gives the same bytes.

## Manifest

`https://<domain>/geo/manifest.json`:

```json
{
  "format": 2,
  "set": "2026-11-21.d221a5",
  "attribution": "© OpenStreetMap contributors, ODbL 1.0",
  "tiles": {
    "300202120": {"bytes": 412305, "sha256": "…"},
    "3002021213": {"bytes": 987112, "sha256": "…"}
  }
}
```

* `format` is `2`; reject anything else (format 1, a list of packs with bounding boxes,
  is gone). `set` matches `^[0-9A-Za-z._-]{1,20}$`.
* `tiles` maps each tile's key (`^[0-3]{1,29}$`, see "Tiles") to its size and sha256. No
  key is a prefix of another. The tile is at `<set>/<key>.bin`, relative to the
  manifest's URL.
* A client verifies `bytes` and `sha256` before using a tile, and treats a tile whose
  header `set` and `name` aren't the manifest's set and the key as broken.

## Tiles

**The grid.** For a point `(lat, lon)` in degrees, in IEEE doubles:

```text
y = floor(lat × 10⁶) + 2²⁸        x = floor(lon × 10⁶) + 2²⁸        (both in [0, 2²⁹))
digit d (d = 1, 2, …) = 2 × bit(y, 29 − d) + bit(x, 29 − d)
key(depth) = digits 1 … depth, as the characters 0–3
```

Each digit halves the square along both axes: at depth `d` a square is `2^(29−d)`
micro-degrees across (depth 8: about 2.1°; depth 14: about 3.6 km of latitude). The key
of a square is the key of every point in it, truncated to its depth.

**Which tile.** The tile for a point is the one whose key is a prefix of the point's key
(computed to the longest key's depth). There is at most one, since keys are prefix-free.
If there is none, nothing is within reach of the point: the result is empty.

**What a tile holds** (normative for builders; clients need only the rules above). Every
entity of the merged region packs within 5,000 m of the tile's square, in the plane of
"Lookup", which is more than any lookup rule reaches (4,000 m). Concretely, the square
widened by `ceil(5000 / k × 10⁶)` micro-degrees of latitude and `ceil(5000 / (k ×
cos(φ)) × 10⁶)` of longitude, `φ` being the widened square's poleward latitude (at most
89°). A polygon ring is clipped to that box (Sutherland–Hodgman) and a line to it
(Liang–Barsky); new vertices are rounded to micro-degrees, and a ring left with fewer
than 3 points or a line with fewer than 2 is dropped. An entity's `areaM2` stays the
unclipped area. Clipping each ring separately keeps the even-odd interior exactly within
the box, and the edges it adds lie on the box's border, farther from any point in the
square than any rule looks, so **the lookup in a point's tile gives the same result as
the lookup over all the merged region packs.** `make_vectors.py` asserts this on the
fixture, and the builder's equivalence check found no difference on 3,450 random points
over 23 real regions.

A tile's header `name` is its key and its `bbox` is the widened box (clamped to ±90°,
±180°). The builder seeds every depth-8 square with something in reach and splits a
square in four while its tile is over the cap and it is shallower than depth 14.

## Binary format, version 1

Little-endian. *varint*: unsigned LEB128. *zigzag*: a signed integer `v` stored as the
varint `(v << 1) ^ (v >> 63)`. *vstr*: a varint byte length, then that many bytes of
UTF-8. The file is decoded front to back; there are no offsets.

### Header

| Offset | Type | Field |
| --- | --- | --- |
| 0 | 4 bytes | magic `AGEO` (`41 47 45 4F`) |
| 4 | u16 | format version, `1`. Reject anything else. |
| 6 | u16 | reserved, `0` |
| 8 | u32 | `entityCount` |
| 12 | u32 | `fileLength`, the file's total size. Reject a mismatch. |
| 16 | i32 × 4 | `bbox`: minLat, minLon, maxLat, maxLon, micro-degrees |
| 32 | vstr | `set` |
| | vstr | `name` |
| | vstr | `attribution` |

In a region pack, the `bbox` is the bounding box of every entity's geometry, widened by
30,000 micro-degrees (about 3 km) on each side; in a tile, it is the widened square (see
"Tiles"). Readers don't use it for the lookup. Then `entityCount` entities follow, back
to back.

### Entity

```text
u8      layer        1 admin, 2 place, 3 protected, 4 feature
u8      osmType      ASCII 'n', 'w' or 'r'
varint  osmId        ≥ 1
vstr    name         1–200 characters, no control characters
...     layer fields (below)
u8      geomType     1 point, 2 lines, 3 polygons
...     geometry (below)
```

Layer fields:

| Layer | Fields |
| --- | --- |
| admin | `u8 level` (5–8), `varint areaM2`, `varint labelNode` (an OSM node id; 0 = none) |
| place | `u8 kind`: 1 city, 2 town, 3 village, 4 suburb, 5 quarter, 6 hamlet, 7 neighbourhood, 8 locality |
| protected | `u8 rank` (0: `boundary=national_park` or `protect_class` 1a, 1b, 1–6; else 1), `varint areaM2` |
| feature | `u8 kind`: 1 peak, 2 volcano, 3 saddle, 4 ridge, 5 arete, 6 valley, 7 cliff, 8 gorge, 9 cape, 10 mountain_range, 11 massif, 12 bay, 13 beach, 14 glacier, 15 water, 16 plateau, 17 island, 18 islet; then `varint areaM2` (0 for points and lines) |

Geometry, in integer micro-degrees:

* **point**: `zigzag lat`, `zigzag lon`.
* **lines** and **polygons**: `varint partCount`, then per part `varint pointCount`
  followed by `pointCount` × (`zigzag dLat`, `zigzag dLon`). Deltas run across the whole
  entity, from the previous point; the entity's first point is relative to (0, 0).
  A line part has at least 2 points. A polygon part is a ring of at least 3 points,
  closed implicitly (the first point is not repeated). Outer rings and holes are not
  distinguished: containment is even-odd over all rings.

Admin and protected entities are polygons; places are points; features are any of the
three. An entity is identified by `(layer, osmType, osmId)`; ids order as
`n < w < r`, then numerically. Within a pack, entities are sorted by layer, then id.

## Lookup

**Normative: the result.** Input: a query `(lat, lon)` in degrees, and the entities of
the point's tile (see "Tiles"); equivalently, of the merged region packs. (The
conformance vectors also run the rules over the fixture's two region packs together, in
order: an entity in both is taken from the first.)

### Geometry

All distances are in metres in a local plane centred on the query:

```text
k   = 6371008.8 × π / 180
c   = cos(lat × π / 180)
for a vertex (latM, lonM) in micro-degrees:
  dLon = lonM / 1e6 − lon;  if dLon ≥ 180: dLon −= 360;  if dLon < −180: dLon += 360
  x = dLon × k × c
  y = (latM / 1e6 − lat) × k
```

* `dist(point)` = `hypot(x, y)`.
* `dist(lines)` = the minimum over every segment of the Euclidean distance from (0, 0)
  to the segment.
* `inside(polygons)`: even-odd over every ring's edges, including each closing edge.
  An edge from `a` to `b` toggles when `(a.y > 0) ≠ (b.y > 0)` and
  `a.x + (0 − a.y) × (b.x − a.x) / (b.y − a.y) > 0`.
* `dist(polygons)` = 0 if `inside`, else the minimum over every ring edge as for lines.

Compute in IEEE doubles, in the order written. Results at an exact boundary or an exact
tie in distance are defined below, but the vectors avoid relying on last-ulp agreement
between platforms' `cos`.

### Rules

1. **locality.** `C` = admin entities with `inside`. Among those with level 6–8, take the
   highest level, then the smallest `areaM2`, then the smallest id. If there are none,
   take the admin entity of level 6–8 with `dist ≤ 2000`, minimizing distance, then
   highest level, then smallest `areaM2`, then id. Otherwise there is no locality.
2. **Suppressed.** The `labelNode` ids (non-zero) of every entity in `C`, and of the
   locality.
3. **sub.** Place entities of kind city (radius 4000), town (2500), village (1500) or
   suburb (1200) with `dist ≤ radius`, not suppressed, and with a name different from the
   locality's. If the locality came from `C` (it contains the query), the place must be
   inside the locality too: `inside(locality)` computed in the plane centred on the
   place's own coordinates. A town across a river or a commune border is a different
   place, however close. Minimize `dist / radius`, then id.
4. **spot.** The same for quarter (800), hamlet (800), neighbourhood (500) and locality
   (400), also excluding the sub's name.
5. **areas.** Protected entities with `inside`, sorted by `rank`, then `areaM2`, then
   id. The first three.
6. **feature.** Point and line features with `dist ≤ reach(kind)`, where reach is peak
   600, volcano 600, saddle 300, ridge 500, arete 400, valley 300, cliff 200, gorge 300,
   cape 300, and 0 (never) for the other kinds: minimize `dist / reach`, then id.
   Only if there is none: polygon features with `dist ≤ margin(kind)` (beach 150,
   water 100, otherwise 0, i.e. `inside`) whose `areaM2 × 2 < locality.areaM2` (or
   `areaM2 ≤ 10,000,000` when there is no locality): minimize `areaM2`, then id.

Names are compared as exact strings. The result is

```text
{ locality?: {id, name}, sub?: {id, name}, spot?: {id, name},
  areas?: [{id, name}, …],  feature?: {id, name} }
```

with ids written `<osmType><osmId>` (`r1250029`), and empty fields omitted.

### Combining with the gazetteer

What a client stores as `place`:

1. Run the gazetteer lookup (`tools/gazetteer/README.md`). If it returns no place, there
   is no place: stop.
2. With no manifest, the gazetteer's place is the answer.
3. With a manifest, `src` becomes `osm@<set>;<gazetteer src>`. If no tile's square
   contains the point, the answer is the gazetteer's place with that `src`.
4. Otherwise run the lookup above over the point's tile. Its `locality`, if any,
   replaces the gazetteer's. `sub`, `spot`, `areas` and `feature` are added. `cc` and
   `region` stay the gazetteer's.
5. If the tile can't be obtained, use the gazetteer's place with the gazetteer's own
   `src`, so that a later re-geocode redoes it.
