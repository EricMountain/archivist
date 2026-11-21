# Publishing region packs

Region packs let clients label photos with the commune, village, hamlet, park or natural
feature around them, not just the nearest town. They are optional. An instance with no
packs keeps working on the bundled GeoNames gazetteer alone. Design:
`docs/design/design.md`, "Detailed places: OpenStreetMap region packs". Format:
`tools/geopack/README.md`.

The operator builds packs on their own machine, for the regions their users take
photos in, and publishes them to the instance's own `web` bucket. Nothing is fetched
from or sent to a third party at photo time.

## 1. One-time setup

A Python with the builder's dependencies (`osmium`, `shapely`):

```sh
python3 -m venv ~/.venvs/geopack
~/.venvs/geopack/bin/pip install -r tools/geopack/requirements.txt
```

The region list, in `private/` because it says where your users go:

```sh
cp tools/geopack/regions.example.txt private/instance/geo-regions.txt
$EDITOR private/instance/geo-regions.txt
```

Each line is `<name> <extract>`. The name is lowercase letters, digits and dashes, and
becomes the pack's file name. The extract is a Geofabrik URL
(<https://download.geofabrik.de/>) or a local `.osm.pbf`.

Choosing extracts:

* **Bigger is safer at the edges.** Geofabrik cuts its extracts on a border, and drops
  any boundary that crosses it. The Scotland extract has no Dumfries and Galloway,
  for example. If photos are near a region's edge, list the parent extract
  (`great-britain` rather than `scotland`) or both neighbours.
* **Overlaps are fine.** An entity in two packs is taken from the one listed first.
* **Size.** A pack is roughly 0.3–1% of its extract: Provence-Alpes-Côte d'Azur
  (392 MB) gives a 1.9 MB pack, Scotland (330 MB) 3.6 MB. Clients only download the
  packs covering their photos.

## 2. Build

```sh
make geo-build GEO_PYTHON=~/.venvs/geopack/bin/python
```

This downloads each extract into `~/.cache/archivist-geo/work` (kept for next time;
`GEO_WORK=` to move it) and writes the set to `~/.cache/archivist-geo/out`
(`GEO_OUT=`): `manifest.json` and `<set>/<name>.bin`. The set id is today's UTC date
plus a digest of the set's contents (builder, regions, extracts), e.g. `2026-11-20.a38b5d`;
`GEO_SET=` overrides it, but then changing the set without changing the id is on you. It
ends up in every new photo's `place.src`, which is how `modify_media.py location --all`
knows which photos to redo: a photo placed from the gazetteer alone because no pack
covered it then gets redone once a set with its region is published. Expect seconds to
a minute or two per region.

**Repeatable builds.** The regions list holds every download URL, and `make geo-build`
reads only that. Use the form Geofabrik's pages link to,
`…/<parent>/<region>/<region>-latest.osm.pbf`; older shorter URLs redirect, some to a 404.

* **Downloads are cached and resumable.** Extracts are kept in the work directory and
  reused, never fetched again, until `make geo-build GEO_REFRESH=1`. Every missing
  extract is downloaded before any building starts, three at a time
  (`--download-jobs`). An interrupted download (a dropped connection, a laptop that
  slept) leaves a `.part` file; the build retries on its own, and the next run resumes it.
* **One fixed file per download.** `-latest` redirects to that day's dated file
  (`<region>-YYMMDD.osm.pbf`), so the builder downloads the dated file, and resumes the
  same one even after Geofabrik moves on to the next day. Each finished download is
  checked against Geofabrik's published md5.
* **A record per set.** Each build writes `private/instance/geo-builds/<set>.json`:
  for each region, the URL, the dated URL of the exact file used (`dated`), size, md5,
  sha256 and OSM data timestamp, and the pack it produced. Rerunning with the same list
  and cached extracts gives byte-identical packs. To rebuild a set after the cache is
  gone, put the record's `dated` URLs in a regions file. Geofabrik keeps daily files for
  a few weeks and the 1st of each month for longer, so for a set you may want to
  rebuild much later, keep the extracts too.

## 3. Publish

```sh
source private/instance/env.sh      # AWS credentials and Terraform backend, as for deploy
make geo-publish-dev                # the dev instance
make geo-publish                    # prod
```

It reads the bucket from `terraform output web_bucket`. That output was added with
region packs, so a workspace that hasn't been applied since needs one `make deploy`
(or `make deploy-dev`) first. It uploads the packs (`Cache-Control: public,
max-age=31536000, immutable`), then the manifest (`max-age=300`). Clients see the new
set within five minutes.

Check:

```sh
curl -sI https://photos.example.com/geo/manifest.json | grep -i -e '^HTTP' -e cache-control
curl -s https://photos.example.com/geo/manifest.json | jq '.set, (.packs[] | .name, .bytes)'
```

## 4. Re-geocode existing photos

Every place stored before has a `src` that doesn't name this set, so
`modify_media.py location --all` finds and redoes them all
(`tools/import-from-local/README.md`, "Updating locations"). Run it once after the first
publish, and again after each rebuild if you want old photos to pick up the new data.
Then run `make backfill-places` (or `-dev`): a dry run reporting `differences: 0` confirms
the vocabulary counters agree with the photos.

## Updating and removing

* **Rebuild** whenever you like (OSM changes daily; once or twice a year is plenty).
  `make geo-build`, then `make geo-publish`. Old sets stay in the bucket, which is
  harmless; delete `s3://<web bucket>/geo/<old set>/` once nothing needs them.
* **Add a region.** Add it to the list and run `make geo-build`: only the new region is
  built (built packs are kept in `<work>/packs/` by region, extract and builder, and
  reused with the new set id in their header), and the set gets a new id. Publish, then
  re-geocode (section 4) so photos in the new region pick it up.
* **Stop publishing.** Delete `geo/manifest.json`. Clients fall back to the gazetteer
  for new photos. Stored places stay as they are until re-geocoded.

## Licence

Packs are a derivative database of OpenStreetMap, © OpenStreetMap contributors,
under ODbL 1.0. Publishing them at `/geo/` with the manifest's `attribution` field
meets the attribution requirement. The app's About screen credits OpenStreetMap too.
If someone asks for the data, the packs themselves are the database you offer.
