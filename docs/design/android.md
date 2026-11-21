# Archivist (Android app)

The primary client. Captures, encrypts and uploads; browses the library; and is one of
the enrolled devices holding a wrapping of the master key.

Read `design.md` first — client-side encryption is what shapes almost every decision
below, because it moves work onto the device that a normal photo app leaves on a
server. Then `deployment.md`: **the app ships with no backend**, and connects to
whichever self-hosted instance the user points it at.

## Identity

| | |
| --- | --- |
| Name | Archivist |
| Application ID | `fr.enry.archivist` |
| Gradle namespace | `fr.enry.archivist` |
| Play Console | listing created, no build uploaded yet |

**The application ID is now permanent.** Once anything is published under
`fr.enry.archivist` it can never be changed — a rename means a new listing, losing
installs, reviews and history. It's a good id, so this is a note for the record rather
than a concern.

Debug builds take `applicationIdSuffix = ".debug"` so a debug and a release build can
sit on the same device without displacing each other. `namespace` stays constant, since
it's the R-class package and has nothing to do with install identity.

### Package layout

```
fr.enry.archivist
├── crypto        envelope encryption, Tink streaming, Keystore
├── data
│   ├── local     Room: timeline cache, upload queue, tombstones
│   ├── remote    Retrofit API, presigned uploads
│   └── repo      repositories — the only things that know the source
├── domain        models, the timestamp/offset ladders
├── sync          WorkManager workers, scan and queue
└── ui
    ├── timeline  detail, search, queue, settings
    ├── reviewer  Play reviewer preview mode — no network types reachable from here
    └── theme
```

Single Gradle module to start; multi-module build wiring costs more than it saves at
this size. The one worth extracting early is **`:core:crypto`** — it needs its own test
suite and the Tink conformance vectors, and a desktop JVM CLI would reuse it verbatim.

## Play Console consequences

**The listing must lead with "requires your own AWS account".** Anyone installing this
expecting a Google Photos replacement will be disappointed and will say so in a review,
fairly. Put the requirement in the first two lines of the store description and again
on the first screen of the app. Self-hosted apps that are upfront about this do fine;
the ones that bury it get punished for it.

The listing existing changes a few things from "later" to "now":

* **Play App Signing.** Google holds the app signing key; you hold an upload key. Back
  the upload key up somewhere real — losing it is recoverable via support, but slowly.
  This is a third key to add to the custody story, unrelated to the archive's crypto.
* **Publishing is automated.** A Google Cloud service account with Play Developer API
  access, its JSON key in GitHub Actions secrets, uploading to the internal track on
  tag. Free — service accounts, the API and the GCP project itself carry no charge.
  Grant it the narrowest role that works (release management on this app only), not
  account-wide admin.
* **`targetSdk` has a deadline.** Play requires new releases to target an API level
  within about a year of the latest. This is a recurring maintenance obligation, not a
  one-off — worth a Renovate-adjacent reminder, since it can block shipping a fix.
* **Data Safety declaration.** Needed before any release. The honest answers here are
  unusually good: data encrypted in transit *and* at rest, keys not held by the
  developer, deletion supported. Worth filling in carefully rather than defensively.
* **Account access for review.** Because the app requires the reviewer's own AWS
  account to reach any server functionality, the Play Console's account-access
  declaration should point reviewers at the in-app "Preview without an account" button
  (see "Reviewer preview mode" below) rather than supplying credentials for a
  purpose-built dummy instance — cheaper to maintain and nothing for a reviewer to get
  locked out of.

### Media permissions

`READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`, declared under Google's Photos and Video
Permissions policy with **backup and sync** as the justification — one of the permitted
cases. The Photo Picker is not sufficient: unattended sync means reading files the user
hasn't hand-picked at the moment of upload.

The declaration is submitted in the Play Console and reviewed. Two things strengthen
it, and both are already true of the design: the app syncs only folders the user
explicitly nominated rather than all media, and the stated purpose matches what the app
demonstrably does.

Worth getting the declaration in early. Rejection wouldn't merely change the
implementation — a Photo Picker–only app cannot do unattended sync at all, so the
product would change. The local-tombstone design depends on this too: skipping an
already-deleted file during a scan presupposes scanning.

`ACCESS_MEDIA_LOCATION` is a separate, ordinary dangerous permission, not believed to be
covered by this same Photos and Video Permissions declaration (only the broad
image/video read permissions are) — unconfirmed against a real Play Console submission,
but nothing in Google's policy text ties it to that process. See "Runtime permissions"
below for what it's for.

## Stack

| Concern | Choice | Why this one |
| --- | --- | --- |
| IDE | Android Studio, current stable | Compose previews, layout inspector and profiler aren't optional for this UI |
| Language | Kotlin | — |
| UI | Jetpack Compose + Material 3 | A photo grid is state-driven; Compose's recomposition suits paging far better than RecyclerView adapters |
| Min SDK | 28 | Native HEIC decode lands here; below it the phone can't read its own camera output |
| DI | Hilt | Enough for one app, and WorkManager integration is first-party |
| Async | Coroutines + Flow | — |
| Local DB | Room | Timeline cache and the upload queue both need durable, queryable state |
| Paging | Paging 3 + `RemoteMediator` | Maps directly onto GSI1 cursor pagination |
| HTTP | OkHttp + Retrofit + kotlinx.serialization | Interceptors for token refresh; OkHttp alone for presigned PUTs |
| Images | Coil 3 | Compose-native, and its `Fetcher` interface is the hook for decrypting thumbnails |
| Crypto | Google Tink + Android Keystore | Streaming AEAD already solves the chunking problem `design.md` specifies |
| EXIF | `androidx.exifinterface` | Reads `OffsetTimeOriginal` and GPS, which the platform API doesn't expose fully |
| Background | WorkManager | Constraint handling is the battery/data policy |
| Auth | AndroidX Credential Manager | Passkeys, and the same API covers Google sign-in |
| Build | Gradle + version catalogs (`libs.versions.toml`) | Renovate needs a single declarative place to bump |
| CI | GitHub Actions | Fully automated build, test and release; Renovate-driven |
| Test | JUnit5, Turbine, MockWebServer, Robolectric, Compose UI test | — |

**Not using AWS Amplify.** It would bring a large dependency tree to solve problems we
don't have — the app talks to our own API, and the only direct AWS interaction is
plain HTTP PUTs to presigned URLs. Cognito's token endpoints are ordinary REST.

## Architecture

Standard unidirectional flow: Compose screens observe `StateFlow` from ViewModels,
which call repositories, which are the only things that know whether data came from
Room or the network.

```
Compose UI  →  ViewModel  →  Repository  ─┬─  Room (source of truth for display)
                                          ├─  Retrofit (metadata API)
                                          ├─  OkHttp (presigned S3 PUT/GET)
                                          └─  CryptoService (Tink + Keystore)
```

Room is the source of truth the UI reads, never the network directly. The timeline must
render offline, and `RemoteMediator` is built on exactly that assumption: the network
fills Room, and Room feeds the pager.

The place filter's tables (`facet_streams`, `facet_stream_rows`) are the exception to
"Room mirrors server items": they are a **client-only session cache** of the facet pages
this session has fetched, emptied when the filter is cleared and on a cold start. No
server item corresponds to a row, and nothing there survives the process (see "Search
(places)" below).

## The hard parts

### Encrypting on device

Uploads must never load a file into memory. A 480 MB video would OOM instantly, and
even a 50 MB RAW is careless. Tink's `StreamingAead` (`AES256_GCM_HKDF_1MB`) encrypts
over an `InputStream`, and that construction *is* the format — `crypto-format.md`
specifies it byte-for-byte, so on Android the correct implementation is Tink's, used
unmodified. Do not reimplement it, and do not tune its parameters.

The pieces the app still owns: generating the DEK, wrapping it with the master key
(AES-KW), building the associated-data string, and choosing whole-object mode below the
32 MB threshold. All four are specified; all four are covered by the conformance
vectors.

**Align S3 multipart parts to crypto segments.** Parts must be ≥5 MB, ciphertext
segments are exactly 1 MiB, so use 8 MiB parts — exactly 8 segments each. Misaligned
boundaries make the byte-range arithmetic for video seeking far more painful than it
needs to be.

### Thumbnails

Three sizes (256/1024/2048, longest edge) generated on device before upload, since no
Lambda can ever re-derive them. Decode with `ImageDecoder`, downsample with
`setTargetSampleSize` rather than decoding full-size then scaling, and encode WebP.

**The phone will not handle RAW.** Android cannot decode CR3 or ARW natively. That's
acceptable because RAWs arrive from the camera via the home server, not from the phone — but it
means the home-side importer, not this app, owns RAW thumbnailing. Worth stating
because it's easy to assume feature parity between the two ingest paths.

### Stripping location

When `stripLocationOnUpload` (fetched from `GET /settings`, cached alongside the other
owner-level settings) is on, the upload worker never opens the MediaStore original for
writing — it copies the bytes to a file in `cacheDir` (not `noBackupFilesDir`; it's
transient, deleted within the same work item, not a durable cache), opens that copy
with `androidx.exifinterface.media.ExifInterface`, nulls out every `TAG_GPS_*`
attribute, and calls `saveAttributes()`. That copy, not the original, is what
`ExifExtractor` reads and what the encrypting stream PUTs — see "Stripping location on
upload" in `design.md` for why doing it in that order (strip, then extract) is what
keeps GPS out of `exifEnc` and out of the offset ladder's GPS-delta rung, rather than
needing a second pass to scrub either.

`ExifInterface` can rewrite JPEG, PNG, WebP and HEIC in place; it cannot touch RAW
(already out of scope — see "The phone will not handle RAW" below) or MP4/MOV, which
carry location as an ISO-BMFF box instead of EXIF. For those, the same temp-copy
approach hands off to a small first-party box editor instead of `ExifInterface` — see
"Mechanism, video" in `design.md` for the two schemes it targets (3GPP `loci`,
QuickTime `©xyz`/keys+ilst) and why zeroing a box's payload and relabelling it `free`
in place, rather than removing or re-muxing anything, needs no `stco`/`co64` offset
recomputation regardless of the file's box order.

Any coordinates are also dropped outright with the setting on, which covers a format
there is no stripper for, so no `place` is computed either.

**The Settings copy for this toggle must say plainly that the original on the device
is never touched** — only the uploaded copy loses its location — since "strip
location" read the other way sounds like it edits the file sitting in the phone's own
gallery. It must also say the switch affects **future uploads only**: places already
stored for earlier photos stay (design.md "Location", "Strip-on wins").

### Reverse geocoding

`domain/ReverseGeocoder.kt` turns coordinates into a `place` (country, region, town)
entirely on the device. The platform `android.location.Geocoder` is not used: on most
devices it is backed by a network service, which would send every photo's coordinates to
a third party. Instead the app ships a gazetteer built from GeoNames `cities500`
(`assets/gazetteer.bin`, ~5 MB, generated by `tools/gazetteer/build.py` and committed).
It is stored uncompressed in the APK (`androidResources { noCompress += "bin" }`) so
`AssetGazetteerSource` can memory-map it (`openFd` + `FileChannel.map`) rather than
parse it; the first lookup maps the file, and cells are decoded on demand. Lookup is
nearest place over a 1°×1° grid, with the thresholds and the "locality may lack a
region" rule from design.md's "Location". The upload worker geocodes on a best-effort
basis: a lookup failure uploads the photo without a `place`.

The same `.bin` is read by `tools/import-from-local/gazetteer.py`, and both readers must
pass `tools/gazetteer/vectors.json` (a JVM unit test reads it from the repo), so the app
and the laptop tools never name one town two ways. GeoNames is CC BY 4.0: the About
screen carries the attribution.

**Region packs** (design.md "Detailed places: OpenStreetMap region packs"). Uploads and
"Update location" geocode through `data/repo/GeoPackRepository.kt`'s `PlaceGeocoder`:
the gazetteer, plus the connected instance's region packs where they cover the photo.
`GeoPackRepository` fetches `https://<host>/geo/manifest.json` at most every five
minutes (403/404: the instance publishes none), keeps the last good copy for offline
use, and downloads the packs a photo needs into `noBackupFilesDir/geo/packs/`, checked
against the manifest's size and sha256. Every request goes to the user's own instance.
A pack that can't be had (offline, a failed check) gives the gazetteer's place with the
gazetteer's own `src`, never a partial set, so a later re-geocode redoes the photo.
`domain/GeoPack.kt` is the reader and lookup, a port of `tools/geopack/reference.py`;
`GeoPackTest` runs `tools/geopack/vectors.json` over the synthetic fixture packs. The
detail screen shows the layers most specific first ("Le Planet, Argentière, Chamonix-Mont-Blanc,
Auvergne-Rhône-Alpes, France") with protected areas and the feature on a line of
their own; place search shows a village or hamlet with its commune. The timeline's date
bubble still shows `locality` (design.md open question 7(b)). OpenStreetMap is ODbL: the
About screen credits it.

### Runtime permissions

Requested once, in `PermissionOnboardingScreen`, right after sign-in and key unlock —
not any earlier, since nothing before that point needs them — and not gated behind a
persisted "already asked" flag. A denial (of the system dialog, or of the app's own
rationale screen) must be free to ask again on a later, genuinely fresh app launch
rather than being remembered as settled forever; the screen recomputes what's still
missing from live `ContextCompat.checkSelfPermission` state every time it's entered, and
stores nothing of its own. **Every step shows in reviewer preview mode too, notifications
included**, even though preview mode itself never fires one — these screens exist so a
Play reviewer sees the complete, real set of prompts the app can ever show, not just
whatever subset a given mode happens to use. The media-library step specifically is also
what actually lets `MediaStoreSource` see anything there at all: see "Media permissions"
above — without one of `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`/`READ_EXTERNAL_STORAGE`, a
MediaStore query only ever returns files this app itself created, which is none, since
it never takes photos. Answers made in preview mode carry over exactly as far as the OS
permission grant itself does — a real grant is a real grant regardless of which flow
asked for it, but a decline there leaves nothing recorded to block asking again once a
real account exists.

Four permissions, requested as separate steps since Android only ever shows one system
dialog at a time regardless of how many are asked together:

* **`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`** (API 33+) or **`READ_EXTERNAL_STORAGE`**
  (28–32) — core function; declining just means Sync's folder list comes up empty.

  **On API 34+, the system dialog offers a third option**, "Select photos and
  videos…", alongside Allow/Deny — granting `READ_MEDIA_VISUAL_USER_SELECTED` instead
  of the two permissions above, scoped to just the files the user picked.
  `PermissionOnboardingScreen` detects this (`mediaAccessState`, checking
  `READ_MEDIA_VISUAL_USER_SELECTED` when the full permissions aren't held) and shows a
  follow-up step explaining that Sync will only ever see the selected files, with an
  "Add more" button. There's no separate intent for reopening the system's selection
  UI — re-requesting the same permission array, with the already-granted
  `READ_MEDIA_VISUAL_USER_SELECTED` included, is what the platform documents for
  that. A user who already made this choice in an earlier session reaches the
  follow-up step directly on the next launch, skipping the request screen — there's
  nothing left to newly ask for.
* **`ACCESS_MEDIA_LOCATION`** (API 29+; doesn't exist below it, and isn't needed there
  either, since pre-scoped-storage reads were never redacted in the first place).
  Without it, `ContentResolver` hands back MediaStore originals with GPS location
  already zeroed — in EXIF *and* in video location boxes alike — for every read, by
  every app that lacks it, regardless of what that app's own code does. That's the
  platform mechanism the offset ladder's GPS-delta rung and `LocationStripper`/
  `Mp4BoxEditor` (2.18) both depend on for real location to reach the app at all:
  `AndroidMediaStoreSource.openInputStream` only calls `MediaStore.setRequireOriginal
  (uri)` when the permission is actually held at read time, falling back to the
  ordinary (redacted) `Uri` otherwise — calling `setRequireOriginal` without holding the
  permission throws `UnsupportedOperationException` on open rather than degrading
  gracefully, so the fallback has to be deliberate, not just an oversight this crashes
  loudly enough to catch.

  **Denying it is a legitimate, encouraged choice, not merely a fallback for someone who
  taps the wrong button.** The onboarding step offers "Don't allow" as its own button
  alongside the system dialog's, and Settings > Privacy explains it as a stronger,
  per-device alternative to "Strip location from uploads": that switch is an
  owner-level policy, synced via `GET`/`PATCH /settings` and applied to every device
  backing up to the library, whereas denying this permission is a phone-level guarantee
  the OS enforces below the app entirely — no app-level scrubbing logic to trust — but
  it covers only this one device, and it means this device can never resolve a photo's
  timestamp from its GPS fix, or name its place, either, since the app never sees the
  coordinate in the first place. **The two aren't substitutes for each other**: an owner who wants
  location removed from *every* device's uploads still needs the switch (a different
  device could still hold this permission and upload real coordinates); someone who
  doesn't trust the app with their own coordinates, even transiently, can additionally
  deny this on just their own phone regardless of what the owner's switch says.
* **`POST_NOTIFICATIONS`** (API 33+) — for the upload-progress notification
  and the `notifyWhenUploadNeedsUnlock` alert (see "Upload pipeline" above). Declared in
  the manifest since plan step 2.10 but, until this step, never actually requested —
  meaning neither notification could show on API 33+ regardless of the manifest
  declaration, since a declared-but-unrequested dangerous permission is never granted.

Nothing else needs a runtime prompt. `INTERNET` and `ACCESS_NETWORK_STATE` are normal/install-time permissions
Android grants automatically at install, with no dialog. No location permission is
requested either: `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` govern live device
location, which this app never reads — only EXIF- and box-embedded GPS already sitting
in existing files, which `ACCESS_MEDIA_LOCATION` alone governs. Key unlock's
device-credential confirmation (`KeyguardManager.createConfirmDeviceCredentialIntent`,
see "Auth and key unlock" below) isn't a permission dialog either — no `uses-permission`
backs it.

### Decrypting for display

CloudFront serves ciphertext, so `AsyncImage` can't fetch a URL directly. A custom Coil
`Fetcher` sits in the middle: fetch bytes → unwrap the asset DEK with the in-memory
master key → decrypt → hand Coil an `ImageBitmap`. Coil's disk cache must then hold
**plaintext** thumbnails, which is fine on-device but means the cache directory belongs
in `noBackupFilesDir` so it never lands in a Google cloud backup.

Local thumbnail cache is LRU with a configurable size ceiling — Coil
supports both, so this is configuration rather than code.

### Upload pipeline

Durable across process death, because a 500-photo import will outlive the UI:

1. User picks media (Photo Picker, or a watched folder via `MediaStore`).
2. Row per file into a Room `upload_queue` with state `PENDING`.
3. `WorkManager` picks it up: if the owner's `stripLocationOnUpload` setting is on and
   the file is a format the app knows how to strip (JPEG/PNG/WebP/HEIC via
   `ExifInterface`, MP4/MOV via the box editor), strip its location into a temporary
   copy first (see "Stripping location" below) — everything after this point works
   from that copy instead of the original. Extract EXIF (and, for a video, the
   container's ISO 6709 location) → resolve timestamp and offset → geocode the
   coordinates on the device (see "Reverse geocoding" below) → generate thumbnails →
   `POST` metadata, including the `place`, to get a `photoId` and presigned URLs →
   stream-encrypt and PUT → mark `DONE`. The temporary copy, if any, is deleted once
   this attempt finishes, success or not.
4. Failures retry with exponential backoff; the queue survives reboots. A missing master
   key (`UploadOutcome.NeedsUnlock`) retries the same way, plus — per the Sync setting
   `notifyWhenUploadNeedsUnlock` (default on) — a low-priority, alert-once notification
   asking the user to open the app, since nothing in the background can re-derive the
   key on its own (see "Key unlock" above for why the key isn't just kept around
   indefinitely to sidestep this — it is, deliberately, past the first unlock; this case
   is only the narrower one of never having unlocked yet, or having signed out).
   Classification of a failed `POST /uploads`: a network error or `5xx` is transient;
   a `4xx` is permanent (`FAILED`, manual Retry), **except** a `409` whose body carries
   `code: "TRANSACTION_CONFLICT"` (api.md "Errors"). That one means another write held
   a shared counter row (`HIST`, `#PLACES`) and nothing was written — several uploads
   running at once hit it routinely — so `TransactionConflictRetry` resends the same
   request in place: up to 5 tries, exponential backoff from 200 ms capped at 3 s, with
   equal jitter (each delay is half the exponential step plus a random share of the other
   half), on the coroutine clock. In place rather than via `Result.retry()` because
   WorkManager's backoff starts at 10 s, has no jitter, and a retry redoes extraction,
   thumbnailing and encryption from scratch. If the tries run out, the row ends exactly
   like a `5xx`: `attempts + 1`, `lastError` set, `UploadOutcome.Retry`, and WorkManager's
   own backoff takes over. A `409` without the code (a genuine conflict, or any `409`
   from a server older than the code) is still permanent. The same helper wraps the
   other counter-moving calls the app makes: `DELETE /photos/{id}` (trash),
   `PATCH /photos/{id}` (`takenAt`) and `PUT /photos/{id}/location`; when their tries run
   out they show an error as before.

The metadata `POST` happens *before* the bytes, matching the pending-`#META` handshake
in `design.md`. The server assigns identity and decides grouping; the client only
proposes a path — and, since plan step 2.10, a candidate `photoId` too, for the reason
design.md's "Why the client gets to propose a photoId" explains: the AAD that
encryption needs is chosen before the server would otherwise mint one.

**Not true S3 multipart, despite this doc's own "align S3 multipart parts to crypto
segments" above.** `POST /uploads` presigns one PUT per object (`src/lambda/api/routes/uploads.ts`
never did — and still doesn't — offer `CreateMultipartUpload`/per-part presigned URLs),
so 2.10's `UploadRepository` streams the whole ciphertext through a single OkHttp PUT
whose `RequestBody` wraps `StreamingCipher`'s/`WholeObjectCipher`'s encrypting
`OutputStream` — internally still exactly the 1 MiB Tink segments `crypto-format.md`
specifies, just not chunked into separate S3 parts at the transport layer. Real
multipart (parallel parts, true mid-object resume) would need a backend change this
step didn't need to make; see STATUS.md's note on plan step 2.10 for what resuming an
interrupted upload does instead.

### Battery and data policy

Backing up photos must not quietly drain a battery or a data allowance. This is mostly
WorkManager constraints rather than custom logic:

| Setting | Constraint |
| --- | --- |
| Wi-Fi only (default) | `NetworkType.UNMETERED` |
| Any network | `NetworkType.CONNECTED` |
| Pause below 20% battery | `setRequiresBatteryNotLow(true)` |
| Only while charging | `setRequiresCharging(true)` |

Uploads run as a plain background `CoroutineWorker` — deliberately not a foreground
service (removed 2026-10-03; it used to be a Settings toggle). That trades some
resilience (Android may defer or kill a background job under memory pressure; WorkManager
retries it) for no `FOREGROUND_SERVICE_DATA_SYNC` declaration and a notification that's
genuinely optional: **`showUploadProgressNotification`** (Settings > Sync, default on).

**Discovering new photos** (`ScanWorker`, added 2026-10-03; before that a scan only ran
when a folder was toggled or rescanned in Settings). Three triggers, one worker
(`ScanCoordinator.scanAndEnqueue()`: hash secret, `Scanner`, enqueue uploads):
a one-shot WorkManager request with a MediaStore content-URI trigger (images + video,
10 s update delay / 60 s max so a burst is one scan), which re-arms itself after every
run because content triggers fire once; an hourly periodic backstop for missed triggers
(reboot, force-stop, a change mid-run); and a scan on process start and each
`MainActivity.onStart`. All carry the upload network/charging/battery constraints, so a
scan only runs when its uploads could. Skipped (but re-armed) while uploads are paused —
resuming scans once — or without media permission. A force-stopped app gets no triggers
until next opened.
Encryption is CPU-heavy enough to be noticeable on
battery, so "only while charging" should be genuinely offered rather than buried.

## Screens

* **Timeline** — an immersive (system bars hidden, swipe from an edge to reveal), edge-to-edge
  grid of squares, Paging 3 over Room, flowing continuously with no header rows. While the
  position is changing, and for 2 s after, semi-transparent date bubbles float at the top-left
  of a row when its (date, place) differs from the row above's; the topmost row's bubble is
  sticky and is pushed off by the next. A row's date is the latest *local* date in it (via
  `tzOffsetMin`, not UTC). Its place is the most common place (locality, else region, by id)
  among its photos on that date, ties to the latest `takenAt`; an unknown place never
  starts a new bubble. The bubble reads `14 Jul 2026 · Kyoto`, and only the place half
  ellipsizes. The menu is a floating `⋮` button at top-end; the fast-scroll rail
  starts 8 + 40 + 8 dp below the cutout inset (below the chip's lowest position).
  **Top-band placement** (`ui/timeline/CornerPlacement.kt`, `placeInTopCorner`): `⋮` sits
  in the screen's top-end corner itself, as close as it can while its 40 dp circle stays
  8 dp inside the screen's rounded corner (`WindowInsets.getRoundedCorner`, API 31+; the
  top-left one in RTL). With the corner arc's radius `r` and the button's radius `q`,
  the button's centre goes on the 45° line from the arc's centre, at `r − q − 8dp` from
  it, so its box is `r − (r − q − 8dp)/√2 − q` from each edge (37 px, 14 dp, on a
  1080 px, 128 px-corner phone). No corner reported (API < 31, a square or tight corner):
  8 dp from both edges. It also stays 8 dp clear of every display cutout's bounding
  rectangle, dropping below one that it would touch (a centred punch hole doesn't touch
  it; a corner cutout does). In immersive mode it overlaps the hidden status bar's strip,
  and a status bar summoned by a swipe draws over it until it hides again; in the
  non-immersive states (unfiltered spinner, error, empty) it stays below the visible
  status bar. The card opens where it clears the cutouts by the same rule (on a centred
  punch hole, below it: the field must not sit under the camera), growing out of the
  button's spot and sliding there. The filter chip uses the same rule for the top-start
  corner, on the button's row (never above it) when its whole label fits there beside
  any cutout; a longer label drops below the cutout. The date bubbles' sticky top stays
  below the cutout inset: they span most of the width, so they would cross a centred
  punch hole.
  **Filtered by place** (see "Search (places)"), the same screen shows only the photos
  taken in the selected places: the same grid, bubbles and rail, over the filter's own
  pager, `LazyGridState` and lead, while the unfiltered pager, grid state and `photos`
  cache are left exactly as they were, so clearing the filter returns to the exact
  unfiltered position. A **filter chip** sits in the top band between the start margin
  and `⋮` ("Kyoto · 4", "paris · 21 places · 1,912": the query, the number of selected
  places, their photo count; "zzz · no places" for an empty selection). Its ✕ clears the
  filter; its body reopens the search with that query and those ticks. The chip **hides
  while the grid moves**, by swipe or by the rail, while the rail is held, and while the
  date bubbles linger afterwards (one shared scroll-activity state, so the chip and the
  sticky bubble cross-fade: in 150 ms, out 300 ms); `⋮` never hides. Back on the filtered
  timeline clears the filter. In filter mode the bubble's place comes from the most
  specific selected place that returned the photo (a town's name, a region's name, or
  nothing for a country, which shows the date alone), and the grid plays **no video
  previews** (facet items carry no `preview`; detail still plays the video). The bars stay
  hidden throughout filter mode, including its loading and empty states.
  The **rail is partial** in filter mode: the top `L/N` of the track (L photos loaded, N
  the exact total from the vocabulary's counts) is a density rail over the loaded rows,
  with month ticks, day labels and jumps answered from Room; the rest is a plain,
  unlabelled segment. Holding the thumb there keeps the grid at the oldest loaded photo,
  the pill reads "Loading older…", and pages load one at a time while the finger stays.
  What it can't do: jump straight to a date that isn't loaded yet, or show month labels or
  density for the unloaded part, since facets have no ascending order and no per-month
  counts, and fetching them would be a bulk download. Reaching the oldest photos of a big
  selection means paging through everything newer. The rail is hidden until the first
  pages are in.
* **Photo detail** — 2048 thumbnail, pinch-zoom, original on demand, rendition list
  ("JPEG · RAW") with per-rendition delete. A **location row** under the date: the label
  from `#META.place` (locality, then region unless absent or equal to the locality, then
  the country rendered from `cc` in the device locale: "Kyoto, Japan"), and under it the
  precise coordinates from the decrypted EXIF blob, as `35.01161, 135.76804` (signed
  decimal degrees, five decimals, `.` and `", "` whatever the locale, so the text pastes
  into any maps app or tool). Coordinates but no place: the coordinates alone. A place
  but no coordinates (EXIF not decrypted, older upload): the label, not tappable.
  Neither: no row. Tap sends `ACTION_VIEW geo:0,0?q=<lat>,<lon>(<label>)` at full
  precision with the label percent-encoded; with no maps app, the coordinates are
  copied and a line under the row says so. No web-map fallback, since that would hand
  the position to a third party. Long press copies the coordinates.
  **Update location** in the `⋮` menu (next to "Edit date"; hidden while the owner's
  `stripLocationOnUpload` is on, read the way upload reads it) runs design.md's "Manual
  update" for this photo (`LocationUpdateRepository`): the coordinates in the decrypted
  EXIF blob, else the primary original fetched and decrypted to a temp file by the
  "View original" path and read there (`ExifInterface` for a still, `VideoLocationReader`
  for a video; (0, 0) is none; the file is deleted afterwards), geocoded on the device,
  then `PUT /photos/{id}/location`. Coordinates from the original also go back as a
  re-encrypted `exifEnc` (same DEK and `x` AAD, fresh IV, other fields kept), so the next
  run needs no download. No coordinates anywhere sends `place: null`. A 409 refetches the
  detail and retries once; a 409 `TRANSACTION_CONFLICT` instead resends the same body with
  backoff (see the upload pipeline), since nothing about the photo changed. The status line shows "Locating…", then "Location updated." /
  "Coordinates saved. No place nearby." (coordinates found, nothing within range) / "No location in this photo." / the error. On success the detail is refetched, the Room
  row's place columns are updated (so the date bubble follows) and the cached place
  vocabulary's `ETag` is dropped, so search refetches it on next open. The filter's
  cached rows of the photo follow too: one whose place is still the filtered place keeps
  that place's name, and one that has left it **stays in the filter for the rest of the
  session**, showing its real place, so the photo doesn't vanish from under the detail
  pager; setting the filter again drops it.
  **Swipe scope:** opened from the timeline, the pager swipes over the cached timeline
  (all of `photos`). Opened from the filtered timeline, it swipes over the filter's
  loaded photos only, live from the stream cache, and reaching the last loaded photo
  loads the filter's next page, as scrolling the grid would. Back returns to the filtered
  grid at the same position. Edits made in the detail (date, rotate, repair, location,
  delete) patch, or for a delete remove, the filter's cached rows as well as `photos`; an
  edit made from the filtered detail updates a `photos` row only if the timeline window
  already holds that photo, and doesn't re-anchor the hidden unfiltered timeline.
* **Search (places)** — the timeline's `⋮` menu and the place filter (`ui/search/`,
  `ui/timeline/MenuPanel.kt`). Tapping `⋮` grows it into a card (about
  `min(width − 16dp, 360dp)` wide, from the button's corner) holding a "Search places"
  field and the menu entries (Settings): the button fades out while the card grows out of
  its 40 dp square, and closing shrinks the card back into it (150 ms, like every menu
  transition; the system "Remove animations" setting makes them instant). The `⋮` button,
  the card (and so the suggestion panel in it) and the filter chip share one translucent
  surface, the theme's surface colour at 60 % opacity (`OVERLAY_SURFACE_ALPHA`), with no
  shadow; their text, checkboxes and glyphs stay opaque. Tapping the field focuses it and opens the
  keyboard; the menu entries fade out (150 ms), the card widens to the top band, and a
  suggestion panel opens below it, at most 45 % of the height above the keyboard. There's
  no scrim: the live-filtered grid stays visible below the panel. States, Back and
  dismissing are one pure reducer (`PlaceSearchState.kt`): in the card, Back or an outside
  tap collapses it (the touch is consumed); while searching with the keyboard open, Back
  closes the keyboard and the IME Search action applies at once and dismisses; with it
  closed, Back or a tap on the grid below dismisses (that tap doesn't open a photo),
  **keeping** the filter. There is nothing to apply or discard: every change is live, and
  ✕ on the chip or editing the query is the undo.

  Matching runs against the owner's place vocabulary (`GET /places`, cached in Room with its `ETag` and
  revalidated with `If-None-Match` exactly like the histogram, so it is usually a 304 and
  works offline from the cache). Matching runs on the device: the query and every name
  are folded (`PlaceFold`: case, accents, whitespace) and the query must be a substring of
  a town's name, a region's name, or a country's name in the device locale *or* English
  ("allemagne" and "germany" both find `DE` on a French phone). A town matches on its own
  name only. Ranking: matches at the start of the name or of any word first, then by
  photo count, then by name; at most **200** shown, with a footer ("Showing 200 of 340 —
  type more to narrow") when there are more, and those beyond are not in the filter.
  Matches are **grouped** country → region → town (a town is linked to its region by
  `(cc, regionName)`; an ambiguous or missing match groups it under its country only). A
  matched ancestor is a row with a checkbox; an unmatched one is a plain header with
  "All · None". Every visible match arrives **ticked**; unticks are remembered while the
  query changes, so matches that appear as you type on arrive ticked. A ticked region
  implies its matched towns, and a ticked country its regions and towns: those show
  ticked, disabled and captioned "included", and only the maximal ticked entries become
  filter streams, so the total is an exact sum. A status line reads "1,912 photos in 21
  places", with a progress dot during the first load. An **empty query** lists the top
  places unticked, as quick picks; an empty query with nothing ticked is no filter. A
  query that matches nothing, or whose matches are all unticked, is an **active filter
  with no places**: the grid shows "No places selected." and Clear filter, never the
  unfiltered timeline. Other states: the vocabulary loading; "Couldn't load your places"
  with Retry (only with nothing cached); "No located photos yet."; "No places match.".

  **Live apply:** every keystroke and tick re-derives the selection; after **350 ms** with
  no change it becomes the active filter, provided the query has at least **2** folded
  characters, or the last change was a tick, or the query is empty (clearing the field
  applies). Below that the suggestions update but the previous filter stands; IME Search
  applies at once whatever the length. A filter whose places are the same as before
  keeps the filtered grid and its position; any other change starts it at the newest
  photo.

  **Data:** one facet stream per selected place, `GET /facets/{COUNTRY|REGION|PLACE}/{id}`
  (newest first, pages of 60), cached in the session cache (`facet_streams`,
  `facet_stream_rows`; see "Architecture"). The grid pages over a merged, deduplicated
  query of the selected streams, cut at a **watermark** (the newest "oldest row fetched"
  among the unfinished streams), so nothing shows until every selected stream has its
  first page, and rows never appear above ones already shown. A `RemoteMediator` fetches
  the next page of whichever stream holds the watermark only when the grid (or the
  detail pager, or the held rail) reaches the end: **load as you scroll, never in bulk**.
  At most 6 facet requests are in flight; a new selection cancels the in-flight and
  queued fetches of the streams it dropped and lets those it kept finish into the shared
  cache, so narrowing a query costs nothing and widening it pays only for the new places.
  The filter **does not survive the process**: the app always opens unfiltered at the
  present. While a filter is set, uploads and changes made elsewhere aren't seen until it
  is cleared and set again; this device's own edits patch the cached rows (see "Photo
  detail"). Filtered states: a spinner for the first load; "Couldn't load photos for these
  places." with Retry and Clear filter; "No photos in these places any more." (every
  stream came back empty: a stale vocabulary) with Clear filter; a Retry cell at the end
  when a later page fails. **Offline**, suggestions still work from the cached vocabulary
  and streams already cached this session show what they have; a new place is the error
  state. There's no fallback to `photos`, which is a window and can't answer a region.

  Other facets (labels, cameras, years), excluding a sub-place, and saved or persistent
  filters are not built.
* **Upload queue** — visible and cancellable. A silent queue that has stalled on a
  constraint is a support nightmare of one's own making.
* **Settings** — devices and their timezone defaults, home timezone, sync policy,
  cache ceiling, enrolled keys, and **Privacy** (strip location on upload — see "Hard
  parts" below; owner-level, synced via `GET`/`PATCH /settings`, not a per-device
  toggle like Sync's).

## Connecting to an instance

There is no default server. The first screen asks for one, before login and before any
permission request.

```
User types  photos.example.com
      ↓
GET https://photos.example.com/.well-known/archivist.json
      ↓
{ apiBase, region, cognito: { userPoolId, clientId }, cryptoVersion, instanceName }
      ↓
persist to DataStore, then sign in
```

Nothing about the backend can be a compile-time constant — user pool IDs, API base and
region all differ per deployment. Consequences worth designing for rather than
discovering:

* **No certificate pinning.** The app doesn't know the domain in advance. Rely on
  system trust; require HTTPS and refuse plain HTTP outright.
* **Check `cryptoVersion` at connect time** and refuse an instance the app is too old
  to read. An explicit "update Archivist to use this server" beats a decryption failure
  three screens later, and instances will lag by months.
* **Validate the URL properly.** Typos are the first thing every user does. Distinguish
  "no such host", "no discovery document here — is this an Archivist server?", and
  "server too new" in the error text.
* Show `instanceName` after connecting, so the user can see they reached the right
  place.
* **Support more than one instance eventually** — a family member's and your own. Not
  in v1, but don't build the account model as a singleton, because retrofitting that is
  miserable.

## Auth and key unlock

Two separate ceremonies, for the reasons in `design.md`:

* **Auth**: Credential Manager → passkey → Cognito tokens, against *this instance's*
  user pool from the discovery document. Refresh tokens live in
  `EncryptedSharedPreferences`, scoped per instance; the app should not prompt daily.
  **A returning user's passkey is only half the story.** An invited account
  (`docs/ops/create-user.md`) starts with nothing but a temporary password and no
  registered credential at all — there's no hosted UI to bridge that gap (the Cognito
  domain in `cognito.tf` only exists when Google federation is on), so the app itself
  has to: try a passkey first (`PREFERRED_CHALLENGE=WEB_AUTHN`; an account with none
  registered gets `SELECT_CHALLENGE` back, not an error — confirmed live against a real
  pool), fall back to password + `NEW_PASSWORD_REQUIRED`, then immediately offer to
  register a passkey via `StartWebAuthnRegistration`/`CompleteWebAuthnRegistration`
  before landing the user anywhere else. Implemented in `data/remote/CognitoAuthClient.kt`
  and `ui/onboarding/SignInViewModel.kt`. Cognito's user-facing API is plain unsigned
  HTTPS JSON-RPC (`X-Amz-Target` header, no SigV4) — confirmed live — which is what "not
  using AWS Amplify" above assumes but is worth stating plainly here too.
* **Key unlock**: an EC-P256 keypair in Android Keystore, `setUserAuthenticationRequired(true)`
  so it sits behind the lock screen. It unwraps the master key into memory at app start
  via ECDH-ES+AES-KW (`EcdhEs.kt` in `:core:crypto`) — not RSA-OAEP-256, which plan step
  2.4a found a real Keystore-resident RSA key's decrypt refuses on real hardware (see
  the resolved open question 1 in `design.md` and "Master key wrapping" in
  `crypto-format.md`). **Not confirmed hardware-backed on every device**: the one phone
  tested so far generated its EC-P256 key in software, not the TEE, when StrongBox
  wasn't requested — worth checking on more devices before assuming this key gets the
  same isolation the RSA one was meant to. **Needs API 31+**: `PURPOSE_AGREE_KEY` (the
  Keystore purpose that lets an EC key be used with `KeyAgreement` at all) doesn't exist
  before Android 12, and `minSdk` is 28 — see open question 3 in `design.md`, found
  while implementing plan step 2.5. `DeviceKeystore.ensureKeyPair()` (`:core:crypto`)
  fails clearly with `DeviceKeystoreUnsupportedException` below that rather than
  crashing, but there is currently no working replacement route for API 28–30.

  **Time-based auth, not auth-per-use — a deliberate deviation from the original
  "biometric prompt" framing above, found and fixed by actually running plan step 2.5 on
  an emulator (API 37), not assumed.** The key is `setUserAuthenticationParameters(300,
  AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`: usable for 5 minutes after the user
  last unlocked the device, with no explicit in-app prompt. The alternative —
  auth-per-use, a fresh prompt for every unwrap — was tried first and found unusable:
  it requires the operation to be driven through a `BiometricPrompt.CryptoObject`
  ceremony, and the `CryptoObject(KeyAgreement)` overload that needs only exists in
  `androidx.biometric` 1.4.0-alpha06+ — there is no stable release with it (stable is
  still 1.1.0, from 2021), so shipping it would mean an indefinite alpha dependency for
  this one feature. The platform-level `android.hardware.biometrics.BiometricPrompt`
  (no AndroidX) does support it without the alpha dependency, but re-plumbing the whole
  unwrap path through that ceremony wasn't attempted once the simpler, equally
  official, time-based mode confirmed working end to end on real Keystore hardware.
  `UserNotAuthenticatedException` (thrown when the window has lapsed — confirmed live
  this is the *ordinary* case on a freshly booted or long-idle device, not just a
  stale-window edge case) is handled explicitly: `EnrolmentRepository`/
  `EnrolmentViewModel`/`EnrolmentScreen` show a "Unlock your device" screen backed by
  `KeyguardManager.createConfirmDeviceCredentialIntent`, then retry.

The master key is held in memory only (`MasterKeyHolder`, `:app`) — never in
SharedPreferences, never on disk. **Not cleared just for the app being backgrounded, by
deliberate decision** (2026-09-07, revised from this step's original design after a real
threat-model discussion — see `docs/plans/STATUS.md`'s 2.11 row for the full account).
Two earlier attempts at a backgrounding-triggered clear were tried and both removed:
first `onTrimMemory` (any level — which also fires at `TRIM_MEMORY_RUNNING_LOW`/
`RUNNING_CRITICAL` while the app is still fully foreground, e.g. under general system
memory pressure while swiping through the photo viewer, wiping the key mid-session for
no benefit), then a `ProcessLifecycleOwner.onStop` observer (correctly scoped to actual
backgrounding, but still the wrong thing to clear on at all). The reason: per
"Encryption" in design.md, this app's threat model is AWS/the operator seeing plaintext,
not a local, physically-present attacker interacting with an already-unlocked device —
the device's own lock screen and OS sandboxing are that boundary, not this app clearing
key material out from under itself. Clearing the key merely for being backgrounded also
has a real cost: `UploadWorker`'s queue runs in the background too, and a cleared key
stalls every upload until the app is foregrounded again (see "Upload pipeline" below and
`UploadOutcome.NeedsUnlock`) — there is no way for a background worker to silently
re-derive the key on its own.

The master key is cleared only by two deliberate, session-ending user actions:
`AuthRepository.signOut()` and `AccountRepository.deleteAccount()` (Settings > Account).
Re-unwrapping after either needs the device to have been unlocked within the last 5
minutes (see above); if not, the app asks the user to unlock rather than crashing.
`TimelineScreen` re-runs the enrolment repository's `checkStep()` itself as soon as it
observes `MasterKeyHolder.current` go `null` (rather than trusting a cached
`EnrolmentViewModel`'s stale `Unlocked` state), so the rare case of the key going away
while it's on screen self-heals instead of hanging; anything else that reads the master
key should still check `MasterKeyHolder.current` rather than assume it stays set for the
app's whole lifetime.

Enrolment (`KeyCustody.kt` in `:core:crypto`, `EnrolmentRepository`/`EnrolmentViewModel`/
`EnrolmentScreen` in `:app`) writes a `kind: device` wrapping item — generated in memory
and only POSTed once the user has typed the recovery code back, per "Enrolment is not
complete until..." in `crypto-format.md`. The recovery code path exists here too, for a
phone that isn't the first device, and doubles as the re-enrolment path after
`KeyPermanentlyInvalidatedException` (a lock-screen change): the dead Keystore entry and
its server-side `W#` item are both retired, the latter only *after* a replacement wrap
exists, since the server refuses a delete that would leave fewer than two wrappings.
**A later device currently can't fetch the owner's `hashSecret`** — see open question 4
in `design.md`; not a gap in this step's own scope, but one plan step 2.10 will hit.

## Cross-client format compatibility

The sharpest risk in the whole project, and it isn't an Android problem specifically:
**Android, web and the home-side tooling must produce byte-identical ciphertext framing.** A mismatch
isn't a bug that shows up in testing, it's photos that one client can never open.

Tink has Java, Python, Go and C++ implementations but its JavaScript one is no longer
maintained, so the web client hand-rolls over WebCrypto regardless of what we choose.
Given that, **Tink's `AES256_GCM_HKDF_1MB` framing is the specification** — written up
in `docs/design/crypto-format.md`, which is authoritative over this document and over
`design.md` wherever the three describe bytes. It's already designed by cryptographers,
it solves the truncation problem correctly, and it gives Android, a home-side Python
importer and a Go CLI working implementations for free. Only the web is hand-written.

Kotlin Multiplatform was considered and rejected for this: it would share code across
Kotlin targets only, which leaves the Python and Go tooling exactly where it started.
See "Interoperability" in `design.md`.

Non-negotiable consequence: **the conformance vectors in `testdata/vectors/` run in
this app's test suite.** All 22 cases, including the ones that must fail. A
client that passes its own round-trip tests but not these is broken, and the way that
manifests is a user, years later, holding a photo nobody can read.

## Build and dependencies

Version catalogs (`gradle/libs.versions.toml`) so Renovate has one file to update. Renovate grouped by ecosystem, automerging patch and minor
updates once CI is green; majors always manual.

GitHub Actions runs build, unit tests and instrumented tests on a Gradle-managed device
for every PR, then assembles, signs and uploads to the Play internal track on tag.

The PR half is the part that isn't optional: the crypto round-trip and Tink conformance
tests fail silently by nature, and a format regression found by hand is one found years
late. Free for public repos; private repos get 2,000 minutes a month on the Free plan,
which at roughly 8 minutes a build is more headroom than a solo project will use.

`--no-daemon` and the Gradle remote build cache keep runs quick.

### CI and release, as built

`.github/workflows/android-ci.yml` (PR + push to `main`, paths-filtered to `android/**`)
runs `:core:crypto:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
then the instrumented suite on a Gradle Managed Device (`pixel2api30`, `aosp-atd`,
declared in `app/build.gradle.kts`'s `testOptions.managedDevices`) — GitHub's standard
Linux runners have hardware-accelerated Android virtualization once the job grants
itself KVM group access, which the workflow does as its own step rather than relying on
a third-party emulator-runner action. `gradle/actions/setup-gradle` provides the actual
caching (GitHub Actions cache standing in for a literal remote cache server, which a
self-hosted-per-user project has nowhere to run).

`.github/workflows/android-release.yml` triggers on a tag matching `android-v*.*.*` —
prefixed because this repo also holds the Terraform-deployed backend, which has its own
release path (none defined yet; not this plan's step). It decodes the keystore secret to
a runner-local file, derives `versionName` from the tag (`android-v1.2.3` → `1.2.3`), and
runs `:app:publishBundle` — the Gradle Play Publisher plugin (`com.github.triplet.play`)
wires that one task to the whole chain (build, sign, upload). `versionCode` is left
static in `app/build.gradle.kts` and overridden by the plugin's
`resolutionStrategy.set(ResolutionStrategy.AUTO)`, which reads the Play Console's
current value and increments it — no manual version-bump commit before tagging.

**A real GPP quirk, found by actually running `assembleRelease` locally while writing
this, not from its docs**: the plugin wires a Play API call into *every* release-variant
build (`processReleaseVersionCodes`), not just the publish tasks — so `assembleRelease`/
`bundleRelease` broke outright for anyone without Play credentials configured, which
would have meant every contributor's machine and this repo's own PR/`main` CI. Fixed with
`play { enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null) }` — the
plugin is inert unless that env var is set, which only the release workflow does. The
same env var *is* the credential (its documented convention is the JSON file's contents,
not a path to it — confirmed against the plugin's own parse-failure message, since the
opposite is the easy assumption to make and the more commonly-repeated one online), so
the workflow never writes the secret to disk at all.

**The Play Developer API cannot create an app's first release — only the Play Console
can**, confirmed against Gradle Play Publisher's own docs and multiple independent
reports of the same failure. `publishBundle` will fail on a listing that's never had a
manual upload, no matter how correct the secrets are — a one-time manual `bundleRelease`
+ Console upload has to happen before the first tag. See `docs/ops/android-release-secrets.md`
for the exact steps, both for that and for creating the secrets themselves.

### Secrets

Five, all of which must exist before the first release can ship (GitHub Actions repo
secrets, read by `android-release.yml`):

| Secret | Notes |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Upload keystore, base64-encoded; keep the original somewhere durable |
| `ANDROID_KEYSTORE_PASSWORD` | — |
| `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | — |
| `PLAY_SERVICE_ACCOUNT_JSON` | Raw JSON, scoped to release management on this app alone |

Worth creating these early rather than discovering them mid-release. None of it costs
anything: the Play Developer API, the service account and the GCP project are all free,
and the only money in this pipeline was the one-off developer registration.

**Not yet verified live**: nothing in this session could trigger a real GitHub Actions
run or a real Play Console upload — the workflow YAML, the Gradle Managed Device task
name, and the Gradle Play Publisher config are written to match each tool's documented
behavior, but the first actual PR run and the first actual tag are what confirm it, not
this write-up. See `docs/plans/STATUS.md`, step 2.16.

## Testing what matters

Ordinary UI tests are the easy part. The tests that earn their keep:

* **Round-trip crypto** — encrypt, upload to a fake, download, decrypt, compare bytes.
  Including a file larger than one chunk, because off-by-one on chunk boundaries is
  the likely bug.
* **Tink conformance vectors** — as above.
* **Timestamp ladder** — a fixture set covering EXIF-with-offset, EXIF-with-GPS-only,
  EXIF-with-neither, and no-EXIF-at-all. This logic is subtle, and a regression
  silently misplaces photos in the timeline rather than crashing.
* **Gazetteer vectors** — `ReverseGeocoder` against `tools/gazetteer/vectors.json`, the
  same file the laptop importer passes. A drift between the two shows up as one town
  under two facet values.
* **Upload queue survives process death** — kill the process mid-upload and assert
  resumption.

## Sync scope

**Explicit folder selection**, not watch-everything. The user picks which folders sync;
camera roll is offered on first run but still a choice. Auto-uploading everything is
what makes a photo app useful and also what uploads 4,000 WhatsApp memes into an
archive meant to last decades.

Selections live in Room and are re-evaluated on each scan, so removing a folder stops
future uploads without touching what's already in the archive.

## Locked state

If the master key is unavailable — biometric declined, Keystore key invalidated by a
lock-screen change, enrolment lost — the app **errors clearly and prompts to unlock**.
It does not degrade quietly.

This matters because the failure is asymmetric: the timeline is metadata and renders
perfectly, so the app looks healthy while every thumbnail fails to decrypt. A grid of
grey rectangles reads as data loss. An explicit locked state with an unlock action
reads as what it is.

Keystore keys are invalidated when the user changes their lock screen, which is a real
and confusing event — handle `KeyPermanentlyInvalidatedException` by re-enrolling this
device from the passkey or recovery code rather than showing a crash.

## Deleting on the phone

Deleting from the archive and deleting from the device are separate acts, so the app
**prompts** rather than guessing:

| Choice | Effect |
| --- | --- |
| Remove from archive (default) | Soft-deletes server-side; the file stays on the phone |
| Remove from both | Soft-deletes, then deletes the local file via `MediaStore` |
| Cancel | — |

The default keeps the local file, because the phone is one of the independent copies
the whole design leans on, and deleting the last copy of something should never be a
default.

**The removed file must not come back on the next scan.** Two layers:

* A **local tombstone** in Room keyed by content hash, so the scanner skips it
  immediately without a round-trip.
* The **server-side `HASH` tombstone** described in `design.md`, which survives app
  reinstalls, covers other devices holding the same file, and outlives the 30-day
  trash window. Without it the photo would return a month after deletion.

Re-adding deliberately is possible — it sets `reAddDeleted` on the upload, which is
also what stops a bulk sync from resurrecting anything.

## Reviewer preview mode

The Play Console offers two ways to satisfy the account-access requirement for an app
that gates functionality behind login: hand reviewers working credentials, or make full
functionality reachable without any. A self-hosted app has no account to hand out —
"working credentials" would mean standing up and maintaining a whole extra AWS instance
just for Google's reviewers. Plan step 2.17 takes the second path instead.

A "Preview without an account" button on the connect screen (`ConnectScreen`, before
sign-in and before any permission request) drops straight into a read-only, local-only
timeline and detail view built from `MediaStoreSource` — the same seam `Scanner` uses to
read the device's own photos — with no delete, and critically, **nothing in the screen's
dependency graph capable of a network call**: it depends on `MediaStoreSource` alone,
not `TimelineViewModel` or any of the authenticated/encrypted machinery those screens
are built on. The explanation of what this mode does and doesn't do lives on
`ConnectScreen` itself, folded in below the button as plain body text — not a popup
shown only after tapping through — saying plainly that every screen including Settings
is reachable, that nothing is uploaded or sent anywhere, and that full functionality
needs the reviewer (or anyone) to provision their own infrastructure and connect this
app to it, pointed at this project's GitHub repository (see "About", below, for the one
place the literal link actually lives). Once inside, a persistent banner repeats the
short version ("Preview — no account, nothing uploaded"). A device with no photos shows
a plain "No photos on this device" message — deliberately not a bundled-sample
fallback, which risked a reviewer mistaking fabricated content for the device's own.

**Settings is reachable too, not just the timeline** — a reviewer needs to see the whole
app, and "no settings at all" fell short of that once tried. `ReviewerSettingsScreen`
mirrors the real Settings menu's eight sections verbatim (same labels, same
descriptions) so the information architecture a reviewer sees is the real one, but every
section is either genuinely local or an explanation rather than a live, network-backed
screen:

* **Storage** and **About** reuse the real screens outright — verified network-free
  (`StorageRepository` only touches Coil's on-disk cache; `AboutScreen` is static text
  plus a `LocalUriHandler` link, no ViewModel at all) — so both are fully live and
  functional in preview mode.
* **Sync** shows real device folder names (`ReviewerSettingsViewModel`, `MediaStoreSource`
  again) with switches that visibly toggle, all backed by plain `remember` state —
  nothing persisted, nothing that starts an upload, since a real folder selection would
  need `Scanner`/`UploadScheduler`/a signed-in session this mode never has.
* **Upload queue, Devices, Keys, Trash, Account** are a fixed explanation each — what
  would be here with a real instance connected — rather than the real screens, which are
  wired to `AuthRepository`/`DeviceRepository`/`EnrolmentRepository` and would either
  crash or silently do nothing against a session that doesn't exist.

This is deliberately not "a demo mode" for ordinary users to discover and get confused
by — it only appears before an instance is connected, which for a real user is a
narrow window at first launch, and exiting it is non-destructive since it never wrote
anything. Verified end-to-end on a Pixel 8a emulator: fresh install → Preview → empty
state → Settings → all eight sections, including tapping the About screen's GitHub link
through to a real browser → back → Exit preview, no crash, no network calls.

### About

`ui/settings/AboutScreen.kt`, reachable from both the real Settings menu and
`ReviewerSettingsScreen` (same composable, since it's a plain screen with no ViewModel —
`LocalContext`/`LocalUriHandler` are Compose ambients, not injected dependencies). Shows
the installed version (`PackageManager`, not a build-time constant — no new
`buildConfig` Gradle feature needed for one string), the license, a link to this
project's repository, and the GeoNames attribution CC BY 4.0 requires for the bundled
gazetteer (title, geonames.org, the licence with a link, and that the data was adapted
into a compact lookup file). This is also where GPLv3's own suggestion of an "about box"
displaying Appropriate Legal Notices is satisfied. The page scrolls, so the notices stay
reachable on a small screen.

**`https://github.com/EricMountain/archivist` is the second of exactly two hardcoded
personal/identifying strings this repo allows** — see CLAUDE.md's "Nothing personal in
the committed tree". Unlike a domain or AWS account, the upstream repository isn't
deployment-specific: every self-hosted instance runs the same client built from this one
canonical source, so it's treated like the application ID rather than like
`photos.example.com`.

## Open questions

None outstanding. Cross-language format compatibility is settled under
"Interoperability" in `design.md`: a published spec plus Tink conformance vectors,
rather than Kotlin Multiplatform, which wouldn't have helped the Python and Go clients
that need to import and export.
