# Region packs: detailed places from OpenStreetMap

Follow-up to [geolocation.md](geolocation.md). Adds an optional, per-region layer on top
of the bundled GeoNames gazetteer: the administrative area containing a photo (a
commune), the village or suburb and hamlet near it, the protected areas it is in, and a
natural feature. Design: `docs/design/design.md`, "Location", "Detailed places:
OpenStreetMap region packs". Normative format and lookup: `tools/geopack/README.md`.

Read `CLAUDE.md`, the design sections above, `docs/design/sample-data.md` A12/A13, and
this plan's rows in `STATUS.md` before starting a step.

## What the user asked for (2026-11-19)

1. Better place names than nearest-`cities500`: the hamlet (or at least the commune) for
   a photo in a hamlet that `cities500` names after a neighbouring village, and a sensible
   name for a photo on a ski slope that it names after a village across the ridge. The
   two reference points the user gave are kept out of the repository.
2. As accurate as possible while staying economical.
3. National parks and similar areas, without country-specific filtering rules.
4. `locate_missing.py` and `modify_media.py` use the new scheme too.

## Decisions (confirmed by the user, 2026-11-19)

| # | Decision |
| --- | --- |
| G1 | Data from OpenStreetMap (ODbL), as per-region packs built from Geofabrik extracts. GeoNames stays as the base layer for country and region, and as the fallback where no pack covers a photo. |
| G2 | **One `PLACE` facet for every named level**, with `kind` and `within` on the `L#` vocabulary row. Not separate facet types, not display-only. |
| G3 | **Operators build packs** for the regions they choose, and publish them to their own instance. The author publishes no world set. |
| G4 | **Plaintext down to hamlet level**, no single dwellings or farms (`place=isolated_dwelling`/`farm` excluded at build time). |
| G5 | Protected areas unfiltered, ordered: national parks and IUCN classes 1–6 first, then smallest area. |
| G6 | Packs are stored in S3 Standard in the existing `web` bucket under `geo/`, served by CloudFront's default behavior. No Intelligent-Tiering: a few hundred MB at most, hot, cached at the edge. |

## Steps

### 1. Design first

**Files:** `docs/design/design.md`, `docs/design/sample-data.md`, `docs/design/api.md`,
`docs/play/*.md`, `CLAUDE.md`, this plan, `docs/plans/README.md`, `STATUS.md`.

**Done when:** the design describes the layers, the `place` object, validation, facets,
vocabulary, delivery and licence; sample data has assets exercising a pack place
(A12, A13) with their facets, vocabulary rows and queries; api.md documents the new
`place` fields, `GET /places` fields and the `/geo/` surface; the privacy documents say
the readable place can be hamlet-level.

### 2. Pack format, builder, reference reader, vectors

**Files:** `tools/geopack/README.md` (normative format + lookup), `build.py`,
`build_set.py`, `reference.py`, `make_fixture.py`, `make_vectors.py`,
`testdata/fixture.bin`, `vectors.json`, `requirements.txt`,
`regions.example.txt`.

**Details:**

* The reference lookup is stdlib-only Python. The builder needs `osmium` and `shapely`
  (its own venv; operators run it, CI doesn't).
* Conformance runs against a **synthetic** fixture pack, built by `make_fixture.py`
  from hand-placed geometry that exercises every rule (containment, sea fallback,
  `sub`/`spot` radii and suppression, area ordering, feature reach and the half-locality
  cap, cross-pack dedupe). No OSM data is committed.
* `make_vectors.py` asserts the reference lookup agrees with a brute-force oracle before
  writing.

**Done when:** `build_set.py` builds a pack and manifest from a real Geofabrik extract;
`reference.py` reproduces the prototype's labels for the user's two reference points
(commune › village › hamlet with the regional park for one; commune › ski-station hamlet
with the reserve and park for the other); vectors generated and the oracle agrees.

### 3. Backend

**Files:** `src/core/items.ts`, `src/core/repo/places.ts`, `tools/backfill-places.mjs`,
`test/lambda/places.test.ts`, `test/dto.test.ts`.

**Details:** `parsePlace` accepts and validates `sub`, `spot`, `areas`, `feature` and OSM
ids; `locationFacets` writes one `PLACE` facet per named level, deduped; counter rows
carry `kind` and `within`, removing `within` when absent; `readPlaces` returns them.
Transactions stay under DynamoDB's 100-item limit (worst case: a move between two
seven-level places).

**Done when:** unit and DynamoDB-Local tests cover validation, facets, counter moves
between a GeoNames place and a pack place, and `GET /places` output. Deployed to dev.

### 4. Publishing

**Files:** `Makefile` (`geo-build`, `geo-publish`, `geo-publish-dev`),
`terraform/outputs.tf` (`web_bucket`), `docs/ops/geo-packs.md`.

**Done when:** a pack set built from at least one region is published to dev and
`curl https://<dev-domain>/geo/manifest.json` returns it with the right `Cache-Control`.

### 5. Laptop tools

**Files:** `tools/import-from-local/geopack.py` (reader + lookup, must pass
`vectors.json`), `places.py` (gazetteer + packs: manifest fetch, pack cache, `src`),
`importer.py`, `main.py`, `modify_media.py`, `locate_missing.py`, `geocode.py`, tests.

**Details:**

* The importer and `modify_media.py location` geocode with packs when the instance
  publishes them; `src` makes `location --all` redo every photo once.
* `locate_missing.py` searches names across every layer of the cached packs plus the
  gazetteer, and resolves a pick to a full place. A pick that is a point (a village, a
  peak) is geocoded at that point. A pick that is a park gives the park with the
  country and region only, since a park can span several communes.
* `geocode.py` prints the layered result and the pack candidates near a point.

**Done when:** unit tests pass, including the shared vectors; a dry run of
`modify_media.py location --all` against dev reports the expected changes.

### 6. Android: reader and lookup

**Files:** `android/app/src/main/kotlin/fr/enry/archivist/domain/GeoPack*.kt`, test
reading `tools/geopack/vectors.json` and `testdata/fixture.bin`.

**Done when:** the JVM test passes every vector.

### 7. Android: download, cache, upload, Update location, attribution

**Files:** a manifest/pack repository (ETag, sha256 check, cache under `noBackupFilesDir`),
`UploadRepository`, `LocationUpdateRepository`, `AboutScreen.kt`.

**Done when:** an upload of a photo inside a published region gets a pack place on dev;
offline, it falls back to the gazetteer `src`; About credits OpenStreetMap.

### 8. Android: display and search

**Files:** detail screen location row, place search suggestions (`within`, `kind`).
No Room change: the timeline keeps the locality as its place name, so the date bubble is
unchanged until open question 7(b) is decided.

**Done when:** the detail screen shows the layered place; searching "argen" suggests
"Argentière, Chamonix-Mont-Blanc".

### 9. Re-geocode and verify

Publish packs for the regions in use, run `modify_media.py location --all`, run
`make backfill-places-dev` as the no-drift check, and spot-check the app.

## Out of scope

* A world pack set published by the author (G3).
* Per-country `admin_level` tables (open question 7(a)).
* Clipping a parent extract into several packs (open question 7(c)).
