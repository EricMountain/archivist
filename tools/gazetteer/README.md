# Gazetteer

The offline reverse geocoder's data: GeoNames populated places packed into one small
binary, `android/app/src/main/assets/gazetteer.bin`, which both clients read:

* the Android app's `ReverseGeocoder`;
* `tools/import-from-local/gazetteer.py`.

Design: `docs/design/design.md`, "Location". Plan: `docs/plans/geolocation.md`, step 2.

| File | What |
| --- | --- |
| `build.py` | Downloads the GeoNames files (or takes local copies) and writes `gazetteer.bin`. |
| `reference.py` | Reference reader: the ring lookup, plus a brute-force scan used as the oracle. |
| `make_vectors.py` | Writes `vectors.json`, after asserting that the ring lookup and the brute force agree. |
| `vectors.json` | Shared conformance vectors. Every reader must reproduce every `place` exactly. |

Everything is standard-library Python. The `tools/` direnv venv works, as does any
Python 3.10 or later.

## Current snapshot

| | |
| --- | --- |
| `src` | `geonames-cities500@2026-11-13` |
| Places | 237,024 (every row of `cities500.txt`) |
| Regions | 3,857 entries: the admin1 regions that have at least one place, plus a country-only entry per country that has places with no known admin1 |
| Grid cells | 9,925 non-empty 1°×1° cells; at most 1,172 places in one cell |
| `gazetteer.bin` | 4,971,538 bytes |
| `gazetteer.bin`, `zip -9` | 3,645,873 bytes (a proxy for a compressed APK entry) |
| sha256 | `bf35431f553543b3fb85b077b6411d6c98df455934aa335317fc988cdca9152e` |

The inputs it was built from, as downloaded on 2026-11-13 (they aren't committed):

```text
d18e5db6d913ffc5aab65890698168a694f8552c3f43046f3ae3d870c9ac820a  cities500.zip
2e4867f5397acb056ea26377601b57af37cb4c81399770225018e9e439e91ad3  admin1CodesASCII.txt
79f8f59ea01cbb40438bf1856dd6c2ff201f03f8fc72d0530b2646aaf603ef04  countryInfo.txt
```

**APK size.** The format is meant to be memory-mapped (`AssetFileDescriptor` +
`FileChannel.map`). That only works if the asset is stored uncompressed in the APK:
`androidResources { noCompress += "bin" }`. The APK then grows by the raw size, about
5.0 MB. If the asset is left compressed instead, the APK grows by about 3.6 MB, but the app
has to copy the file out to its own storage once before it can map it.

## Licence and attribution

The data comes from [GeoNames](https://www.geonames.org/) and is licensed under
[Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
`gazetteer.bin` and `vectors.json` are adapted from it (filtered columns, re-encoded).
**Attribution is required wherever the data ships.** The app credits GeoNames on its About
screen. Anything else that redistributes `gazetteer.bin` must credit it too. Suggested
wording:

> Place names from GeoNames (geonames.org), licensed under CC BY 4.0. Converted to a
> compact binary format.

The CC BY licence covers the data. The tools in this directory are part of the project,
under the repository's own licence.

## Regenerating

GeoNames publishes a new dump every day and keeps no versioned archives. Re-downloading
next month gives different input, so the build can't be reproduced from a URL. That is
why the `.bin` is committed and only regenerated on purpose (say, once a year):

```sh
# from the repo root, with the tools/ venv active
python tools/gazetteer/build.py --download-dir /tmp/geonames   # fetch + build
python tools/gazetteer/make_vectors.py --input-dir /tmp/geonames
```

* Put the downloads somewhere outside the repo. They're about 14 MB and must not be
  committed.
* `build.py --input-dir DIR` rebuilds from files you already have. Given the same three
  files it writes the same bytes; the sha256 lines it prints let you check that.
* The snapshot date comes from the timestamp on `cities500.txt` inside the zip, so it
  depends only on the input. `--snapshot-date` overrides it.
* `make_vectors.py` takes about 3 minutes, almost all of it in the brute-force oracle.
  `--input-dir` adds a check that the `.bin` decodes back to exactly what `build.py` read
  from the source files.

After regenerating:

1. Update "Current snapshot" above.
2. Commit the `.bin` and `vectors.json` together.
3. Run both clients' vector tests.
4. Expect old assets' `place.src` to differ from the new one. That is what lets the
   location update find them (`docs/plans/geolocation.md`, "Manual updates").

`build.py` stops with an error rather than silently skipping data it can't encode:

* a row whose country isn't in `countryInfo.txt`;
* an admin1 key that fails the server's region-id rule;
* a name that fails the server's name rule (1–200 characters, no control characters);
* a cell with more than 65,535 places.

## Binary format, version 1

All integers are **little-endian**. A *varint* is unsigned LEB128: 7 bits per byte,
least-significant group first, high bit set on every byte except the last. A *vstr* is a
varint byte length followed by that many bytes of UTF-8. Offsets are absolute byte
offsets from the start of the file. Sections start on 4-byte boundaries (zero padding).
Readers need no alignment, though; every fixed-width field is read at an explicit offset.

### Header (32 bytes, then `src`)

| Offset | Type | Field |
| --- | --- | --- |
| 0 | 4 bytes | magic `AGAZ` (`41 47 41 5A`) |
| 4 | u16 | format version, `1`. Reject anything else. |
| 6 | u16 | `srcLen` |
| 8 | u32 | `placeCount` |
| 12 | u32 | `regionCount` |
| 16 | u32 | `cellCount` |
| 20 | u32 | `regionIndexOffset` |
| 24 | u32 | `cellIndexOffset` |
| 28 | u32 | `fileLength`, the file's total size. Reject a mismatch. |
| 32 | `srcLen` bytes | `src`, ASCII, e.g. `geonames-cities500@2026-11-13`. It goes into every `place.src`. |

### Region table

At `regionIndexOffset`: `regionCount` × u32, the offset of each region entry. Region *i*
is the *i*-th offset. Each entry is:

```text
vstr key     "<cc>.<code>" (e.g. "JP.22"), or just "<cc>" (e.g. "JP")
vstr name    the admin1 name (e.g. "Kyoto"); empty when key is just "<cc>"
```

* The country is always the first two characters of `key`.
* A key that is exactly 2 characters is a **country-only entry**. A place that points to it
  has no region: its `admin1` code is blank, `00` or missing from `admin1CodesASCII.txt`.
* Regions are ordered by descending place count, then by key, so the common ones get
  one-byte varints. The order carries no other meaning.
* Names are GeoNames' `name` column, not `asciiname`, so they keep their diacritics.

### Cell index

At `cellIndexOffset`: `cellCount` × 8 bytes, **sorted by `cellKey` ascending**. Only
non-empty cells are listed. Find a cell by binary search.

| Offset | Type | Field |
| --- | --- | --- |
| 0 | u16 | `cellKey = (latCell + 90) × 360 + (lonCell + 180)` |
| 2 | u16 | `count`, the number of places in the cell |
| 4 | u32 | offset of the cell's first place record |

* `latCell = floor(lat)`, clamped to 89, so latitude 90 belongs to the 89 row.
  `lonCell = floor(lon)` after normalizing lon to [-180, 180), so +180 is stored as -180.
* So `latCell` ∈ [-90, 89], `lonCell` ∈ [-180, 179], and `cellKey` ∈ [0, 64799].
* The inverse is `latCell = cellKey / 360 - 90` and `lonCell = cellKey % 360 - 180`.

### Place records

Each cell holds `count` consecutive records, **sorted by geonameid ascending**. They are
variable-length, so a cell is decoded front to back:

```text
varint idDelta    geonameid minus the previous record's geonameid in this cell
                  (minus 0 for the cell's first record)
varint dLat       latMicro - latCell × 1,000,000      (0 … 1,000,000)
varint dLon       lonMicro - lonCell × 1,000,000      (0 … 999,999)
varint region     index into the region table
vstr   name       the place's name (GeoNames `name` column)
```

* Coordinates are integer **micro-degrees**. The source's decimal strings are scaled
  exactly, with no float rounding: half-even, though the source has at most 5 decimals.
* Decode to degrees with `latMicro / 1_000_000.0` as an IEEE double. Both readers must do
  exactly this.
* Names are stored inline rather than in a shared string table. In a separate table they
  would need a ~4-byte offset per place, and only about 15% of names repeat. Inline is
  about 0.6 MB smaller.

## Lookup

**Normative: the result.** For a query (lat, lon) in degrees:

1. Normalize `lon = ((lon + 180) mod 360) - 180`, with a floor-mod, so the result is in
   [-180, 180).
2. Find the place minimizing the haversine distance:

   ```text
   φ1, φ2 = radians(lat1), radians(lat2);  Δφ = φ2 - φ1;  Δλ = radians(lon2 - lon1)
   a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
   d = 2 · 6371.0 km · asin(min(1, √a))
   ```

   * `radians(x) = x × (π / 180)`.
   * Use place coordinates decoded as above.
   * Longitude wraps by itself here: sin² is periodic, so Fiji at +179.3 is near -179.99.
   * On an exact distance tie, the smaller geonameid wins.
3. With the nearest place's distance `d`:
   * `d ≤ 25.0`: `{cc, region, locality: {id: "<geonameid>", name}, src}`;
   * `d ≤ 100.0`: `{cc, region, src}`;
   * otherwise: no place (`null`).
4. Omit `region` when the place's region entry is country-only. `cc` is always present.

**Recommended: the search.** Results are defined by the rule above, so any correct search
is conforming. `reference.py`'s `lookup()` is the one the clients are expected to port.

1. Set `qLat = min(floor(lat), 89)` and `qLon = floor(lon)`.
2. Visit ring r = 0, 1, 2, …: the cells with Chebyshev distance exactly r from
   (`qLat`, `qLon`).
   * Skip rows outside [-90, 89].
   * Wrap columns into [-180, 179], skipping duplicates once 2r + 1 ≥ 360.
3. After each ring, compute `bound`, a lower bound on the distance to anything outside
   the (2r+1)² block. It is the minimum of:
   * **north:** `radians(qLat + r + 1 - lat) × R`, only if `qLat + r + 1 < 90`;
   * **south:** `radians(lat - (qLat - r)) × R`, only if `qLat - r > -90`;
   * **east/west:** only if 2r + 1 < 360. Take
     `Δ = min(lon - (qLon - r), (qLon + r + 1) - lon)` in degrees. If Δ ≥ 90, use
     `(π/2 - radians(|lat|)) × R`. Otherwise use
     `asin(sin(radians(Δ)) · cos(radians(lat))) × R`. That is the cross-track distance
     to the block's edge meridian.
   * Infinity, if none of the above applies.
4. Stop when `bound > min(best, 100 km)`, where `best` is infinity if nothing has been
   found yet. The comparison is strict so that an equal-distance tie outside the block
   is still found.
5. Optionally, and both shipped readers and `reference.py` do it: skip a row of cells
   whose latitude gap alone puts every place in it beyond `min(best, 100 km)`, that is
   when `radians(gap) × R > min(best, 100 km) + 0.001` (1 m slack, so rounding never
   skips a tie), with `gap` the distance in degrees from `lat` to the row's band
   [`latCell`, `latCell + 1`]. A great-circle distance is at least the latitude
   difference times R, so this can't change a result. Without it, above about 89° the
   east/west bound never exceeds 100 km and the rings grow until they wrap the globe
   (about 1.2 s per lookup in Python; about 3 ms with it).

`make_vectors.py` checks this search, prune included, against the brute force on every vector, plus 600
pseudo-random points: two thirds near real places, one third anywhere on the globe.

Because results come from floating-point arithmetic, two languages could in principle
disagree at the last bit. That only matters for a near-tie between two different
places, or a distance within ~1e-12 km of 25 or 100. The vectors avoid both.

## `vectors.json`

* `src` is the gazetteer's `src`.
* `localityMaxKm` and `regionMaxKm` are the thresholds.
* Each vector has:
  * `id`, `lat`, `lon` and a `note`;
  * `place`, which is either `null` or exactly the object a client must produce,
    **including `src`**. So a vector also checks that the reader passes the header's
    `src` through;
  * `nearest` (`id`, `name`, `distanceKm` rounded to metres), which is informative only:
    for debugging, not for comparing.

Clients compare `place` for equality, with the keys `cc`, `region`, `locality` and `src`,
and absent keys omitted rather than null. Coverage:

* city centres: Kyoto, Paris, Reykjavík, Buenos Aires;
* villages under 25 km;
* region-only points (Hokkaido, Nevada, Mongolia);
* null points: open Atlantic, the Sahara, null island, both poles, the far Arctic;
* both sides of the antimeridian and lon = ±180 exactly (Taveuni/Fiji, Chukotka);
* border pairs (Geneva/Annemasse, Strasbourg/Kehl);
* Svalbard;
* points exactly on cell edges and corners, with positive and negative coordinates.

What the vectors show, as of this snapshot:

* **Borders.**
  * Geneva centre gives Geneva (CH) and Annemasse centre gives Annemasse (FR).
  * The Moillesulaz crossing gives Gaillard (FR, 1.2 km), and a point on the Swiss side
    in Thônex gives Chêne-Bourg (CH).
  * A point on French soil at Strasbourg's Port du Rhin gives **Kehl (DE)**. That is the
    known nearest-town-across-the-border error (design.md open question 6).
* **Neighbourhoods.** `cities500` includes `PPLX` rows ("section of populated place").
  Central Paris resolves to **"Paris 04 Hôtel-de-Ville"**, an arrondissement, not "Paris".
  Kyoto centre resolves to Kyoto itself.
* **Antimeridian.** Taveuni has no `cities500` place, so both sides of the line resolve to
  Savusavu at region level (70–72 km). A point at +179.95 in Chukotka resolves to
  Egvekinot at -179.12, across the line.
