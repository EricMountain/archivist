# Geolocation: on-device place names, location search, map links, place in date bubbles

Adds location to the library: on-device reverse geocoding at upload, plaintext *approximate*
location in DynamoDB (searchable), precise coordinates on the detail screen with a tap-to-map
link, and the place name in the timeline's date bubbles.

Read `CLAUDE.md`, `android/AGENTS.md` (in full, before debugging any `./gradlew` failure),
`docs/design/design.md`'s "Encrypted EXIF" and "Stripping location on upload", and the 1.9,
1.11, 2.10–2.12 rows of `STATUS.md` before starting.

**This plan changes a settled design decision.** `design.md` currently says no location is
readable on the server, and calls reverse-geocoded `PLACE#` facets "a related trap for later
… worth deciding deliberately". This plan is that deliberate decision. Step 1 writes it
into `design.md`, `sample-data.md`, `api.md` and the privacy documents **before** any code
changes.

## What the user asked for

1. A reverse geocoder that runs **on the device**. Any third-party library must be OSS and
   maintained. Writing our own over an open, downloadable dataset is acceptable.
2. Precise GPS coordinates and approximate location are stored in DynamoDB for each media
   file whose original metadata has them.
3. Approximate location is searchable, **case-insensitively, on part of the name**:
   country, region, city/town.
4. The photo detail screen shows the approximate location, or the raw coordinates when there
   is no place name. Tapping it opens a maps app at that location.
5. The timeline's date bubbles show the location.
6. A manual "update location" for **one photo, in the app**, and for **the whole library**.
   The whole-library run can be in the app or a laptop-side script; a script is fine if it
   works.

## What exists today (verified 2026-10-25)

* **No ingest path writes any `F#` facet item.** `items.ts` declares the vocabulary and
  `repo/facets.ts` / `GET /facets*` read `facet_gsi`. But nothing in `repo/ingest.ts` or
  `routes/uploads.ts` creates `CAMERA`, `YEAR` or any other facet. Location facets would be
  the first ones actually written, so this plan builds the facet write path and doesn't
  just reuse one.
* `ExifBlob` (`domain/ExifExtractor.kt`) carries `gpsDateTimeUtc` but **no coordinates**.
  Already-uploaded photos therefore have no stored lat/lon anywhere, which is why step 9
  exists.
* `GET /facets` is a filtered **Scan** of `facet_gsi` (`listFacetVocabulary`). It's
  acceptable for a rarely opened screen, but it is not a typeahead backend.
* Android has no search screen. `android.md` describes one ("facet browse … no free text")
  that was never built.
* `tools/import-from-local/` is already a full laptop-side client. It handles Cognito
  sign-in, recovers the master key from the recovery code, has a crypto-format
  implementation, a library scan, and `modify_media.py` with per-photo `taken-at` and
  `orientation` subcommands. The whole-library update (step 10) is a new subcommand there,
  not a new tool. The importer is also a *client that uploads*, so it must geocode too
  (step 5); otherwise everything imported from a laptop arrives unlocated.
* `stripLocationOnUpload` and `ACCESS_MEDIA_LOCATION` already guarantee that no coordinate
  reaches `ExifExtractor` when either applies. The geocoder runs downstream of that, so it
  inherits both for free.

## Decisions (confirmed by the user, 2026-10-25)

| # | Question | Proposal | Why |
| --- | --- | --- | --- |
| D1 | Precise coordinates: plaintext or encrypted? | **Encrypted, inside `exifEnc`** (new `ExifBlob` fields `gpsLat`/`gpsLon`/`gpsAltM`). It is still "in DynamoDB", just not readable there. | None of the requested features needs the *server* to read metre-precision coordinates: search uses place facets, the bubbles use the place name, and the map link is opened by the client after it decrypts `exifEnc`, as the Details dialog already does. Plaintext would buy only server-side geo queries (map view, radius search, server-side re-geocoding), at the cost of handing the operator and AWS a metre-precision movement history. If you want those, plaintext is a deliberate, additive later change. |
| D2 | Where does reverse geocoding run? | **On the device**, at upload. | As asked. It sends nothing over the network and puts no third party in the path. (Server-side geocoding would need plaintext coordinates, see D1.) |
| D3 | Geocoder | **Our own, small Kotlin implementation over GeoNames `cities500` + `admin1CodesASCII` + `countryInfo`.** Nearest populated place with a distance cap. The user chose `cities500` over `cities1000` for better rural coverage: its zip is only ~2 MB larger, and the bundled binary keeps a fraction of the columns. | I found no maintained Android-native offline reverse geocoder. The candidates are wrappers around the platform `Geocoder` (which is network-backed on most devices), a Basic4Android wrapper, and Node.js ports. GeoNames is CC BY 4.0, updated continuously, and the nearest-place algorithm is ~200 lines. KDE's photo apps (Koko, MauiKit ImageTools) do the same thing with `cities1000`. |
| D4 | Ship the dataset how? | **Bundled in the APK**, generated by a committed tool from a pinned snapshot and committed as a compact binary. | Downloading at runtime from GeoNames contacts a third party. Downloading from the instance adds a deployment step. GeoNames keeps no versioned archives, so "regenerate at build time" isn't reproducible. Estimated APK growth is 3–5 MB with `cities500` (to measure in step 2). |
| D5 | Facet keys | `F#COUNTRY#<ISO-3166 alpha-2>`, `F#REGION#<GeoNames admin1 key, e.g. JP.22>`, `F#PLACE#<geonameid>`. **IDs, not names.** | Names collide (Springfield, Paris TX/FR), change between dataset versions, and differ by language. IDs are stable. Names for display and search live in `#META.place` and the place vocabulary (below). |
| D6 | Search mechanism | **An owner-level place vocabulary with counts, matched as a case- and diacritic-insensitive substring on the client, then one `facet_gsi` query for the chosen place.** See "Search: options and trade-offs". | It is the only option that gives true substring matching without new infrastructure or a per-search scan. |
| D7 | Which date bubbles show a place | A row is labelled when its **(date, place)** differs from the row above's. An unknown place never triggers a label. | Otherwise a day spent in two cities shows only the first city. The alternative (date-only trigger, place as decoration) is less cluttered. |
| D8 | Turning `stripLocationOnUpload` on later | Affects **future uploads only**, the same as it does for originals today. A bulk "remove stored locations" action is a separate follow-up. | Matches the setting's existing semantics. It is still worth stating in the Privacy screen copy. |
| D9 | Whole-library update: app or laptop? | **Laptop script** (`modify_media.py location --all`). The in-app per-photo action covers the one-off case. | The script can download and decrypt *any* asset's original, so it covers photos with no local source on the phone, which an in-app MediaStore-based backfill can't. It runs on mains power and fast network, with no WorkManager or metered-network plumbing, and reuses an existing authenticated, key-holding toolkit. An in-app whole-library worker stays a possible follow-up. |
| D10 | Two geocoder implementations (Kotlin + Python) | **Both read the same `gazetteer.bin`, and both must pass one shared vector file** (`tools/gazetteer/vectors.json`), the same way `crypto-format.md`'s vectors bind every client. | Otherwise the app and the script would disagree about where a photo was. That would show up as two facet values for one town, and as place churn when the other tool re-runs. |

## Data model (to be written into `design.md` + `sample-data.md` in step 1)

### `#META.place` (plaintext, new)

```text
place        { cc: "JP",
               region: { id: "JP.22", name: "Kyoto" },
               locality: { id: "1857910", name: "Kyoto" },
               src: "geonames-cities500@2026-10-20" }
```

* Every level is optional except `cc`. Above the distance cap there is no `locality`. In
  open sea there is no `place` at all (see "Geocoder").
* Country *names* are not stored. The client renders `cc` with
  `Locale("", cc).displayCountry`, so it shows in the viewer's language for free.
* `src` names the dataset and snapshot, so a later re-geocode can find exactly what an
  older dataset produced (same idea as the preview-clip engine tag).
* **Projected into `timeline_gsi`** (add `place` to `local.timeline_projection`), because
  the bubbles need it per grid cell. As with `preview`, this rebuilds the index. About 60–100
  bytes per located photo. `facet_gsi` keeps `grid_projection`.

### `F#` location facets (new facet types)

`COUNTRY`, `REGION`, `PLACE` join the closed vocabulary in `items.ts`. Each located photo gets
up to three facet items, with the usual denormalised `takenAt`/`thumbs`/`encDek`. The
existing trash (`REMOVE facetPk, facetSk`), restore and `PATCH takenAt` paths already handle
any facet type generically.

### Place vocabulary partition (new, modelled on the histogram)

```text
pk  O#<ownerId>#PLACES   sk  #META                 version, total
pk  O#<ownerId>#PLACES   sk  C#<cc>                n
pk  O#<ownerId>#PLACES   sk  R#<admin1 key>        name, cc, n
pk  O#<ownerId>#PLACES   sk  L#<geonameid>         name, regionName, cc, n
```

* `n` counts **live** photos. It is updated *inside the same transactions* that maintain the
  histogram (create and attach in `repo/ingest.ts`, trash and restore in `repo/trash.ts`),
  and moved by a re-geocode. So it can't drift, for the same reasons given in "Per-day
  histogram". A place whose `n` reaches 0 stays as a zero row and is filtered on read.
* One `Query` returns the whole vocabulary. That replaces the Scan for this purpose.
  `version` is the ETag, as with `GET /photos/histogram`.
* Expected size: hundreds to a few thousand rows per household.
* Account deletion and the backfill tooling must cover this partition (step 4).

### `ExifBlob` (encrypted, gains fields)

`gpsLat`, `gpsLon` (decimal degrees, WGS84) and `gpsAltM`, all nullable. Video coordinates
come from ISO-6709 via `MediaMetadataRetriever.METADATA_KEY_LOCATION`. This is an ordinary
`exifEnc` content change: no crypto-format or AAD change.

### Manual updates: what "update location" does

The same procedure runs in both the app (one photo) and the script (one photo or all):

1. Fetch the asset (`GET /photos/{id}`). Unwrap its DEK and decrypt `exifEnc`.
2. Get coordinates. In order:
   1. The coordinates already in `ExifBlob`, if any (assets uploaded after step 5).
   2. Otherwise, read them from the **primary rendition's original**. The app downloads and
      decrypts the original, as "View original" already does. The script first looks for a
      matching file under `--source-dir`, then downloads.
   3. Use only the first chunk when the original is chunked and the container keeps its
      metadata near the start (JPEG/HEIC). MP4/MOV can keep `moov` at the end, so fetch the
      whole file there.
3. Geocode with the bundled gazetteer.
4. `PUT /photos/{id}/location`. When step 2 had to read the original, this also sends a
   re-encrypted `ExifBlob` with the coordinates added, so the next update and the detail
   screen don't need the original again.
5. If no coordinates are found anywhere, send `place: null`. That clears a stale place,
   which is the correct outcome when the original simply has no GPS.

* **Skip rule** (script only, overridable with `--force`): skip assets whose `place.src`
  equals the current gazetteer's `src`. This makes `--all` resumable and cheap to re-run;
  only new or older-dataset assets do work. Assets with no GPS at all are remembered in
  the script's local cache (`library_cache.py`) so a re-run doesn't re-download them.
* **Strip-on.** With `stripLocationOnUpload` on, the app hides the action. The script
  refuses unless given `--ignore-strip-setting`. Updating location on a library whose
  owner asked for no location is exactly the surprise the setting exists to prevent.

### API (to be written into `api.md` in step 1)

| Change | Shape |
| --- | --- |
| `POST /uploads` | Optional `place` object. Validated: `cc` matches `^[A-Z]{2}$`; ids match `^[A-Z]{2}\.[0-9A-Z]{1,20}$` (region) and `^[0-9]{1,12}$` (locality); names are 1–200 chars with no control characters; `src` is ≤ 64 chars. Set on create. On attach, set only if `#META` has none ("improve, never replace", as with `takenAt`). |
| `GET /photos`, `GET /photos/{id}` | Items carry `place` when present. |
| `GET /places` (new) | The vocabulary partition, zero rows filtered out. `ETag`/`If-None-Match` → 304. |
| `PUT /photos/{photoId}/location` (new) | Body `{place \| null, exifEnc?, exifIv?}`. Replaces `#META.place`, rewrites the three facet items and moves vocabulary counts in one transaction. Optionally swaps in a re-encrypted `exifEnc` carrying the coordinates. Used by the in-app "Update location" and the script (steps 9–10), and by any future manual "set location". Idempotent: the same body twice is a no-op on the counts. Works on trashed assets too, but moves no vocabulary counts for them, same rule as the histogram. |
| `GET /facets/{type}/{value}` | Unchanged; already serves `COUNTRY`/`REGION`/`PLACE`. |

## Search: options and trade-offs

DynamoDB can only look up an exact partition key. "Case-insensitive, matches part of the
name" is a substring search, so something other than a plain `facet_gsi` query has to do
the matching. The options:

| Option | How | Substring? | Cost / latency | Verdict |
| --- | --- | --- | --- | --- |
| **A. Exact facets only** | `F#PLACE#kyoto` with folded names | No. Whole-word exact match on one level only. | 1 query | Not what was asked |
| **B. Prefix keys** | Put folded names in a GSI **sort** key (`begins_with`) | Prefix only (`kyo` → Kyoto, not `oto`) | 1 query, but a new GSI and per-level duplication | Partial; costs an index |
| **C. Scan with `contains()`** | `FilterExpression contains(placeFold, :q)` over the owner's timeline | Yes | Reads the **whole timeline** per search: seconds at 100k photos, growing linearly. `contains` is case-sensitive, so it needs a folded attribute anyway. | Simple, scales badly |
| **D. Vocabulary match (proposed)** | Match the query against the owner's *distinct places* (the `PLACES` partition, ≤ a few thousand rows), then run `facet_gsi` for the chosen place | **Yes**: substring, case- and accent-insensitive, any level | 1 small cached `Query` (304 most of the time) + 1 indexed query per result page | **Recommended** |
| **E. Local Room search** | `LIKE` over a fully synced local copy of the timeline | Yes, offline | Needs the *whole* library's metadata synced to every device (today Room holds pages, not everything). Android-only. | Good later complement, not v1 |
| **F. OpenSearch / Athena** | Real full-text index | Yes, plus fuzzy and boolean | OpenSearch Serverless has a monthly floor far above this stack's whole cost. Athena adds export plumbing and seconds of latency. | Out of proportion. `design.md` already reserves this for "real AND/OR/NOT search". |

**How D works in practice:**

* **Folding.** Fold both sides: NFKD, strip combining marks, `lowercase(Locale.ROOT)`,
  collapse whitespace. So `quebec` matches `Québec`, and `KYO` matches `Kyoto`. Doing it on
  the client also lets a query match the **localized** country name (`allemagne` →
  `DE`), which no server-side approach can do without shipping translations.
* **Suggestions, not merged results.** Typing `fr` lists matching places with counts and
  disambiguating context, e.g. "France · 3,201", "Fréjus · Provence-Alpes-Côte d'Azur ·
  France · 41", "Frankfurt am Main · Hesse · Germany · 54". Picking one runs a single
  `GET /facets/{COUNTRY|REGION|PLACE}/{id}`, which is paginated, newest-first and
  date-range-filterable like every other facet.
* **"All matches for `fr`"** would mean merging several facet streams by `takenAt`. It's
  doable client-side, with one cursor per stream, but deferred.
* **Matching runs on the client** against the downloaded vocabulary. That gives instant
  typeahead, works offline, and handles localized country names. A `?q=` filter on
  `GET /places` is a five-line addition for a future web client.
* **Limits.** It can't search places the dataset doesn't name (street, landmark, venue).
  Names come in GeoNames' primary spelling plus the client's own localized country names,
  not every language's spelling of every town. "Photos within 5 km of here" needs D1's
  plaintext option plus a geohash facet; out of scope.

## Geocoder

* **Data:** GeoNames `cities500.txt` (~185k populated places: population > 500 or admin
  seats down to PPLA4), `admin1CodesASCII.txt`, `countryInfo.txt`. CC BY 4.0, so
  **attribution is mandatory**: About screen plus a `NOTICE` entry. The GPLv3 app can
  bundle CC BY data.
  * `cities500` was chosen over `cities1000` (~130k) for rural coverage (D3). If step 2
    measures the APK growth well above the estimate, raise it with the user rather than
    silently switching.
* **Build tool:** `tools/gazetteer/build.py`, using the existing `tools/` venv. It downloads
  the three files, records the snapshot date, and emits
  `android/app/src/main/assets/gazetteer.bin`:
  * a header with version, `src` string and counts;
  * a string table of names;
  * a region table;
  * place records as int32 micro-degree lat/lon, country index, region index, name offset
    and geonameid, sorted into a fixed 1°×1° cell grid with a cell index.
  * Commit both the tool and the `.bin`. Regenerate deliberately, e.g. yearly.
* **Two readers, one vector file:** Kotlin `ReverseGeocoder` (step 3) and Python
  `tools/import-from-local/gazetteer.py` (step 5) both read the `.bin`. `build.py` also emits
  `tools/gazetteer/vectors.json` (coordinates → expected `place`), and both test suites
  must pass it (D10).
* **Lookup:**
  1. Search the query's cell and its neighbours, widening ring by ring until the
     best-so-far distance is shorter than the next ring's minimum distance.
  2. Use haversine distance, and wrap longitude at ±180° (Fiji, Chukotka).
  3. Memory-map the asset (`AssetFileDescriptor` + `FileChannel.map`) so nothing is
     parsed up front.
  4. Results:
     * Nearest place ≤ **25 km**: full place.
     * ≤ **100 km**: `cc` + `region` only, no locality.
     * Beyond that: no `place`; the detail screen shows coordinates only.
  5. Thresholds go in one constants object, tuned on step 2's fixtures.
* **Known inaccuracy:** near a border, the nearest town can be across it (Geneva/Annemasse,
  Strasbourg/Kehl). A later refinement can decide the country by point-in-polygon against
  Natural Earth admin-0 (public domain) and restrict the nearest-place search to it. It is
  listed under "Out of scope" so the fixtures can show whether it's needed.
* **A cheap bonus, not in scope:** each GeoNames row carries an IANA `timezone`. That would
  make a better offset-ladder rung than the GPS-delta rule's 15-minute rounding. Flag it in
  `design.md`'s open questions rather than building it here.

## Files

Laptop tools: `tools/gazetteer/{build.py,README.md,vectors.json}` (new);
`tools/import-from-local/{gazetteer.py (new),importer.py,modify_media.py,media_probe.py,README.md}`
and its `tests/`.

Backend: `src/core/{items,keys}.ts`, `src/core/repo/{ingest,trash,account,places}.ts`
(`places.ts` new), `src/lambda/api/{router,dto}.ts`,
`src/lambda/api/routes/{uploads,photos,places}.ts` (`places.ts` new),
`terraform/locals.tf`, `test/repo/*`, `test/lambda/*`, `tools/backfill-places.mjs` (new).

Android (under `android/app/src/`):
* `main/assets/gazetteer.bin` (new)
* `main/kotlin/fr/enry/archivist/domain/{ExifExtractor,ReverseGeocoder,PlaceFold}.kt` (last two new)
* `data/repo/{UploadRepository,PhotoDetailRepository}.kt`
* `data/remote/ArchivistApi.kt`
* `data/local/db/{PhotoEntity,AppDatabase}.kt` (migration)
* `ui/timeline/DateBubbles.kt`
* `ui/detail/{DetailScreen,DetailViewModel}.kt`
* `ui/search/*` (new)
* `ui/settings/{AboutScreen,PrivacyScreen}.kt`
* matching tests under `test/`

Docs: `docs/design/{design,sample-data,api,android}.md`,
`docs/play/{data-safety,privacy-policy,instance-privacy-policy}.md`, `docs/plans/STATUS.md`,
`tools/gazetteer/README.md`.

## Steps

In order; each ends building and passing tests.

### 1. Design first

**Done 2026-10-25** (docs only; no code):

* D1–D10 were confirmed by the user, and D3 was changed to `cities500`.
* `design.md`:
  * a new "Decisions" bullet;
  * access patterns 16 and 4c;
  * `#META.place`;
  * the `COUNTRY`/`REGION`/`PLACE` facet types;
  * `place` added to the `timeline_gsi` projection;
  * a new "Place vocabulary (query 16)" section;
  * the coordinates added to the `exifEnc` blob shape;
  * the old "a related trap for later" paragraph replaced by the decision and a new
    "Location" section (storage split, geocoder, conformance vectors, ids-not-names,
    `src`, validation, ingest precedence, manual update, strip interplay, limits);
  * "Stripping location on upload" extended to cover `place`;
  * open question 6 (borders, timezone rung, bulk removal).
* `sample-data.md`:
  * A1 is a full place whose town and region share a name;
  * A3 is a second Kyoto photo;
  * A6/A7 are GPS at sea, with no place;
  * A8 is a video with a region-only place from ISO-6709;
  * A9 is a trashed located asset (facet keys removed, uncounted);
  * A10 is stripped, with no place;
  * plus the `#PLACES` rows, the facet_gsi slice, "What to check" item 7, a worked
    "Place search" query, and an updated "What you cannot do".
* `api.md`: `GET /places` and `PUT /photos/{id}/location`, plus the `place` additions to
  `POST /uploads`, `GET /photos` and `GET /photos/{id}`. All are marked **Planned** until
  step 4 implements them; drop the marker then.

**Deliberately not done here: the privacy documents.** `privacy-policy.md` is published
from each instance and must describe the app as shipped. The changes land in step 11, in
the same release as the feature (wording drafted there).

### 2. Gazetteer build tool and asset

* `tools/gazetteer/build.py` plus a README (snapshot date, licence, how to regenerate).
* The binary format is documented in the README.
* Commit `gazetteer.bin`.

**Done when** the tool reproduces the committed `.bin` byte-for-byte from the same input
files, and the README records the snapshot date, size, and record count.

### 3. `ReverseGeocoder` (pure Kotlin)

* Reads the asset through an abstraction, so JVM unit tests run on the real file.
* Returns `Place(cc, region?, locality?, src)?`.
* `PlaceFold.fold(String)` is used by both search and tests.

**Done when** fixture tests pass for:
* city centres (Kyoto, Paris, Reykjavík);
* a village under the cap;
* a point between 25 and 100 km (region only);
* open Atlantic (null);
* both sides of the antimeridian (Fiji);
* a border pair (record the actual result either way; it informs the polygon follow-up);
* lookup p99 < 5 ms on a JVM unit-test run (record the number).

### 4. Backend

* Add `FacetType` values and `place` to `MetaItem`, plus key helpers for `PLACES`.
* Ingest writes `#META.place`, the facet items and the vocabulary counters in the create
  and attach transactions.
* Trash and restore move the counters; account deletion removes `PLACES`.
* `GET /places`, `PUT /photos/{id}/location`, the `POST /uploads` validation, and the DTO
  `place`.
* `terraform/locals.tf`: add `place` to `timeline_projection`. Note in STATUS that applying
  it rebuilds `timeline_gsi`; apply to dev first.
* `tools/backfill-places.mjs` rebuilds `PLACES` from `facet_gsi`, overwriting rather than
  adding, like `backfill-histogram`.

**Done when** the gated suites (`make test-integration`) cover:
* create with a place (facets, vocab `n=1`, projected `place` visible via `GET /photos`);
* attach-fills-absent and attach-doesn't-replace;
* trash, then restore (counters 1→0→1);
* `PUT …/location` changing the place (old `n` down, new up, facets swapped);
* validation rejections;
* account deletion leaving no `PLACES` rows.

### 5. Extraction and upload: Android and the laptop importer

* `ExifExtractor`: `getLatLong()` for stills; `METADATA_KEY_LOCATION` for video, with an
  ISO-6709 parser.
* `ExifBlob` gains the coordinate fields.
* `UploadRepository` geocodes after extraction and sends `place`.
* Nothing changes for strip-on or a denied `ACCESS_MEDIA_LOCATION`, because both already
  yield no coordinates. Add tests that pin this.

**Done when** unit tests cover:
* a GPS-tagged JPEG fixture → `place` in the request + coordinates in `exifBlob`;
* a stripped copy → neither;
* an ISO-6709 parser table: `+35.0116+135.7681/`, with altitude, with no trailing slash,
  malformed.

Also verified once against the dev instance with a real photo.

**Laptop importer, same step.** Add `gazetteer.py`, reading the same `.bin` and passing
`vectors.json`. Have `media_probe.py` extract GPS (EXIF for stills; ISO-6709 / `loci` for
video, reusing `mp4box.py`). Have `importer.py` send `place` and put the coordinates into its
own `ExifBlob`. **Done when** the Python suite passes the shared vectors, and a `--limit 5`
import of GPS-tagged files into dev produces the same `place` the Android app produces for
the same files.

### 6. Room + timeline bubbles

* `PhotoEntity` gains `placeCc`, `placeName` (the locality, else region name, else null)
  and `placeKey` (locality, else region id, used for change detection). Add a Room
  migration.
* `TimelineRemoteMediator` maps the field.
* `DateBubbles.kt` gets two rules:
  * a row's place is the most common `placeKey` among its photos *on the row's date*,
    with ties going to the latest `takenAt`;
  * labelling follows D7.
* Bubble text is `14 Jul 2026 · Kyoto`. The place half is ellipsized, never the date.

**Done when** `DateBubblesTest` covers:
* same date, different place → new label;
* unknown place → no label;
* the tie-break;
* a row mixing dates (place taken only from the row's date's photos);

and the migration test passes and a scroll on the 8a AVD shows places.

### 7. Detail screen location row

* Label: locality, region (omitted when equal to locality) and the localized country.
* Second line: coordinates to 5 decimals.
* With no `place`: coordinates only. With no coordinates: no row.
* Tap fires `ACTION_VIEW geo:0,0?q=<lat>,<lon>(<label>)`, which works with Google Maps,
  OsmAnd, Organic Maps, etc.
* If no activity resolves: a snackbar, with the coordinates copied to the clipboard. No
  web-map fallback, since that would send coordinates to a third party.
* A long press copies the coordinates.
* The coordinates come from the decrypted `ExifBlob`, which `PhotoDetailRepository`
  already decrypts.

**Done when** the formatting is unit-tested (`DetailFormattingTest`) and the tap is
verified on a device with a maps app and on the clean 9a AVD (no maps app → fallback).

### 8. Search screen (places only)

* Entered from the floating `⋮` menu.
* The vocabulary is fetched via `GET /places` and cached with its ETag.
* Folded substring match runs over locality, region and the localized country name.
  Ranking: prefix matches first, then by `n`.
* Picking a suggestion opens a grid over `GET /facets/{type}/{id}`, reusing the timeline's
  cell composable and the thumbnail fetcher.

**Done when** matching and ranking are unit-tested (`kyo`, `KYO`, `quebec`/`Québec`,
`allemagne` under a French locale) and an end-to-end search works against dev.

### 9. In-app "Update location" (one photo)

* Detail screen `⋮` menu: **Update location**, next to "Edit date" and "Repair thumbnails".
  It runs the procedure in "Manual updates" and shows progress in the existing status
  line ("Locating…", then "Location updated." / "No location in this photo.").
* The action is hidden when strip is on (D8/strip rule).
* On success it refreshes the detail and the Room row, so the bubble updates too.
* The download-the-original path reuses the existing "View original" fetch and decrypt.

**Done when** it is verified on the 8a AVD for:
* a photo uploaded before step 5 (no coordinates in `ExifBlob`, so it downloads the
  original; place appears, and a second tap doesn't re-download);
* a photo with no GPS (place stays absent / is cleared);
* a video with ISO-6709 location;

and `DetailViewModel`'s state transitions are unit-tested.

### 10. Laptop script: whole library (and one photo)

`modify_media.py location`, following the file's existing conventions:

* **Target:** `--photo-id`/`--path` for one asset, or **`--all`** for every live asset
  (via `library_scan.py`). `--include-trash` adds trashed assets.
* **Mode:** dry run by default; `--execute` applies. A dry run prints, per asset, `old
  place → new place` and the coordinate source (`exif-blob | source-dir | download |
  none`).
* **Options:**
  * `--source-dir` reads originals from a local backup before downloading.
  * `--force` ignores the skip rule.
  * `--concurrency N`, as `inspect_photo.py` has.
  * `--ignore-strip-setting`, as above.
* **Credentials:** recovery code via `ARCHIVIST_RECOVERY_CODE`, the same as `orientation`.
* **Summary at the end:** counts for updated, unchanged, skipped (current `src`), no GPS,
  and errors. Errors don't abort `--all`; they're listed with their photoIds.

**Done when** `tests/test_modify_media.py` covers:
* the skip rule;
* `--force`;
* the strip refusal;
* the no-GPS → `place: null` path;
* the source-dir-then-download order;

and a real `--all --execute` against dev:
* locates every GPS-bearing asset, including ones with no copy on the phone;
* is a no-op on immediate re-run (everything "skipped");
* leaves `GET /places` counts equal to a `facet_gsi` recount
  (`tools/backfill-places.mjs --dry-run` diff = 0).

Record wall time and bytes downloaded in STATUS.md. They tell us whether first-chunk-only
reads matter in practice.

### 11. Docs and status

* **Privacy documents**, in the same release that ships location. The Data Safety *form*
  answers don't change: Location is already declared, Optional, App functionality. The
  prose does. Drafted wording:
  * `privacy-policy.md`, "What the app sends": add the bullet "The approximate place each
    photo was taken — country, region and town — worked out on your device from the
    photo's own GPS data. No location is ever sent to a mapping or geocoding service." To
    the EXIF bullet, add "(including the exact GPS coordinates)".
  * `privacy-policy.md`, "Readable": add "the approximate place (country, region, town)
    a photo was taken". "Encrypted": keep "GPS coordinates", now worded as "exact GPS
    coordinates".
  * `privacy-policy.md`, the `ACCESS_MEDIA_LOCATION` paragraph and the closing location
    paragraph: "that location is uploaded encrypted" becomes "the exact location is
    uploaded encrypted, and the approximate place is uploaded readable".
  * `privacy-policy.md`, "Revise if" table: the "Reverse-geocoded place labels" row is
    now met. Replace it with "Precise coordinates readable on the server, or geocoding
    via an online service" → breaks "exact GPS coordinates stay encrypted" and "no
    location is sent to a geocoding service".
  * `privacy-policy.md`: GeoNames attribution (CC BY 4.0) in the licences section, if
    the policy has one; otherwise the About screen alone.
  * `instance-privacy-policy.md`: "Readable by the operator" gains "The approximate place
    a photo was taken: country, region and town". "Encrypted" keeps "exact GPS
    coordinates".
  * `data-safety.md`'s "Location" section: "neither the developer nor the server can read
    those coordinates" becomes "the server can read the approximate place (town, region,
    country) but not the exact coordinates, which stay encrypted. Place names are
    computed on the device, with no network call." Keep "This still does not make the
    answer 'no'."
  * Re-check that all three agree (CLAUDE.md requires it).

* `android.md`: the search, detail and bubble descriptions, the geocoder, and "Update
  location".
* `tools/import-from-local/README.md`: the `location` subcommand, and that imports now
  geocode.
* About screen: GeoNames attribution.
* Privacy screen copy for D8.
* `STATUS.md` rows for each step, stating what was verified.

## Out of scope

* Precise coordinates in plaintext, map/radius search, geohash facets (D1).
* Point-in-polygon borders (Natural Earth admin-0). Decide from step 3's border fixture.
* An offset-ladder rung from GeoNames timezones.
* Merged "all matches" search results, and combining place with other facets.
* An in-app whole-library location worker (D9: the laptop script covers it).
* A bulk "remove stored locations" action (D8).
* Manual "set location" UI (the endpoint supports it).
* Other facet types (`CAMERA`, `YEAR`, …), which are still unwritten (see "What exists
  today"). Step 4's facet write path makes them cheap to add next.
