# Plan 02 — Archivist Android MVP

Read `docs/design/android.md` for the stack and the reasoning; this is the build order.

**MVP means:** connect to an instance, sign in, enrol a key, back up selected folders,
browse the timeline, view a photo, delete. Not: albums, search UI beyond facet browse,
video playback, sharing, or multi-instance.

**Depends on** plan 01 being deployed to a `dev` instance, and step 1.16's conformance
vectors existing.

---

## 2.1 — Project scaffolding

**Goal.** An app that builds and installs.

**Files.** `android/` — Gradle wrapper, `settings.gradle.kts`,
`gradle/libs.versions.toml`, `app/`.

**Details.**
- `applicationId` and `namespace` both `fr.enry.archivist`. Debug builds take
  `applicationIdSuffix = ".debug"`.
- `minSdk 28` (native HEIC decode), `targetSdk` current.
- Version catalog for every dependency — Renovate needs one file.
- Compose with Material 3, Hilt, Kotlin coroutines.
- Package layout from `android.md`: `crypto`, `data.{local,remote,repo}`, `domain`,
  `sync`, `ui`.
- Extract `:core:crypto` as its own Gradle module now, not later. It needs an isolated
  test suite and a desktop JVM tool will reuse it.

**Done when.** A debug APK installs and shows an empty Compose screen.

---

## 2.2 — Crypto module

**Goal.** Encrypt and decrypt exactly what every other client does.

**Files.** `core/crypto/src/main/kotlin/…`, and tests reading `testdata/vectors/`.

**Details.**
- Tink for streaming AEAD (`AES256_GCM_HKDF_1MB`) above 32 MB; plain AES-256-GCM below.
  Threshold and chunk size from `crypto-format.md`.
- Envelope operations: generate a DEK, wrap and unwrap with the master key, generate
  per-object IVs. **A nonce is never reused under one DEK** — generate per object,
  store per object.
- Streaming only. Never load a file into memory; a 480 MB video must encrypt through an
  `InputStream`.
- The master key is an in-memory, non-exportable key. Never write it to disk, never log
  it, clear it on `onTrimMemory`.

**Done when.** The conformance vectors from step 1.16 all decrypt correctly, the
truncated vector fails, and a 100 MB round-trip runs without the heap exceeding a few
megabytes.

**Do this step before anything that uploads.** A format mistake found later means
re-uploading a library.

---

## 2.3 — Instance connection

**Goal.** Point the app at a server.

**Files.** `data/remote/Discovery.kt`, `ui/onboarding/`.

**Details.**
- First screen asks for a hostname. No default, no fallback, HTTPS only — reject
  `http://` outright.
- `GET https://<host>/.well-known/archivist.json`, persist the result to DataStore.
- **Check `cryptoVersion`** and refuse an instance the app is too old for, with a "update
  Archivist to use this server" message. Instances lag by months; this is a real case.
- Distinguish the failure modes in the UI: host not found; reachable but no discovery
  document ("is this an Archivist server?"); server too new. A single "connection
  failed" makes typos undiagnosable.
- Show `instanceName` on success so the user can confirm they reached the right place.
- Store per-instance so a second instance is possible later — don't model this as a
  singleton.

**Done when.** Connecting to the `dev` instance persists its config; a typo'd hostname
and a valid non-Archivist host produce different, accurate errors.

---

## 2.4 — Authentication

**Goal.** Sign in with a passkey.

**Files.** `data/remote/Auth.kt`, `ui/onboarding/SignIn.kt`.

**Details.**
- AndroidX Credential Manager against the instance's Cognito pool from discovery.
- Tokens in `EncryptedSharedPreferences`, scoped per instance. Refresh proactively; the
  user should not see a login prompt daily.
- On first success call `POST /session/bootstrap`.
- OkHttp interceptor attaches the access token and refreshes on 401 exactly once.

**Done when.** A passkey registered on the `dev` instance signs in, survives an app
restart, and refreshes without re-prompting.

---

## 2.4a — Keystore algorithm spike (throwaway)

**Goal.** Settle open question 1 in `design.md` with a number instead of an argument:
can we keep StrongBox while wrapping the master key with RSA-3072?

**Files.** A temporary debug screen or instrumented test — `debug/KeystoreSpike.kt`.
**Delete it once the result is recorded.** This is a measurement, not a feature.

**Details.**
- On a real device, not an emulator — emulators do not have StrongBox, so an emulator
  run answers nothing. Repeat on the oldest phone in the target set, since StrongBox
  capability varies by device and OS version.
- For each of RSA-3072, RSA-2048 and EC P-256, attempt `KeyGenParameterSpec` generation
  twice: once with `setIsStrongBoxBacked(true)`, once without. Record whether it throws
  `StrongBoxUnavailableException`, and time each generation.
- Also record `KeyInfo.getSecurityLevel()` for what actually got created — asking for
  StrongBox and silently landing in the TEE is the outcome that would otherwise go
  unnoticed.
- Then do one wrap/unwrap of a 32-byte key through the RSA key with an explicit
  `OAEPParameterSpec(SHA-256, MGF1-SHA-256)`, to confirm the parameters from
  `crypto-format.md` work on-device before 2.5 depends on them.

**What the result decides.**
- *RSA-3072 works in StrongBox, keygen is tolerable* → no change; open question 1
  closes as "RSA is fine", and 2.5 proceeds as written.
- *RSA-3072 is refused, or lands in the TEE, or keygen is slow enough to be felt* →
  ECDH-ES + AES-KW has to be added to `wrapAlg` first, along with the `epk` schema
  question. **That must happen before 2.5 enrols a real device**, since a fleet
  enrolled on RSA has to be re-enrolled.

**Done when.** The numbers and the StrongBox verdict are written into open question 1
in `design.md`, and the question is either closed or converted into a schema change.
The spike code is deleted in the same change.

---

## 2.5 — Key enrolment and recovery code

**Goal.** The device can decrypt, and the user has a way back.

**Files.** `crypto/KeyCustody.kt`, `ui/onboarding/Enrolment.kt`.

**Details.**
- First device: `POST /keys/version` to allocate `mk-<n>` — **never mint one locally**,
  see plan step 1.8 — then generate the master key on-device, generate the recovery
  code, wrap the master key under both an Android Keystore keypair and an Argon2id KEK
  from the code, and `POST` both wrappings against that version. Also generate the owner's `hashSecret`, wrap it with the master
  key and `PUT` it as `encHashSecret` — without it `contentHash` cannot be computed and
  dedup silently does nothing.
- **Recovery code format is `crypto-format.md`, not this step.** 26 Crockford base32
  characters — 25 of entropy plus a check symbol — printed
  `XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`. Implement the check symbol and the normalisation
  rules from the spec, and run conformance vectors 18–20 against them.
- **Enrolment is not complete until the user types the recovery code back.** Not a
  "saved it" checkbox — actually confirm it. There is no recovery path afterwards. The
  check symbol is what lets that screen say "that's mistyped" rather than making the
  user wait on Argon2id for an ambiguous failure.
- Later device: unwrap via the recovery code, then enrol a Keystore wrapping.
- **Keystore key: EC-P256**, `setUserAuthenticationRequired(true)`. 2.4a has reported:
  a real device's Keystore-resident RSA-3072 key refused `MGF1-SHA256` on decrypt
  outright (not a StrongBox-availability question — a plain TEE Keymaster limitation,
  with no software workaround since that operation never leaves hardware), while ECDH
  agreement against a Keystore-resident EC-P256 key worked correctly end to end. See
  the resolved open question 1 in `design.md` and "Master key wrapping" in
  `crypto-format.md`. Wrap/unwrap via `EcdhEs.kt` in `:core:crypto`, already written and
  conformance-vector-tested (vector 23) — this step wires it into enrolment, it doesn't
  write the primitive. **Not yet confirmed hardware-backed on every device**: the one
  phone tested generated its EC-P256 key in software, not the TEE, without an explicit
  StrongBox request — worth a broader device check before this step ships, not
  necessarily before it starts.
- Handle `KeyPermanentlyInvalidatedException` — thrown when the user changes their lock
  screen — by re-enrolling from the recovery code rather than crashing.

**Done when.** A fresh install enrols, restarts and unlocks silently; a second install
recovers using only the code; a lock-screen change produces a re-enrolment prompt, not a
crash.

---

## 2.6 — Local storage

**Goal.** The timeline renders offline and the upload queue survives death.

**Files.** `data/local/` — Room entities, DAOs, database.

**Details.**
- Tables: `photos` (timeline cache mirroring GSI1 projections), `renditions`,
  `upload_queue`, `local_tombstones` (content hash → deleted, so a scan skips it),
  `sync_state` (folder selections, cursors).
- Room is the source of truth the UI reads. Never bind the UI to a network response.
- Thumbnail cache goes in `noBackupFilesDir` — decrypted thumbnails must not end up in
  a Google cloud backup.

**Done when.** Schema compiles, migrations are exported, DAO tests pass.

---

## 2.7 — Folder selection and scanning

**Goal.** Find files to back up, without finding all of them.

**Files.** `sync/Scanner.kt`, `ui/settings/Folders.kt`.

**Details.**
- Request `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` with a clear rationale screen first.
- List device folders via `MediaStore`; the user picks. Camera roll offered but not
  assumed.
- Scan produces candidates: not already uploaded (hash in `photos`), not in
  `local_tombstones`.
- Compute the content HMAC while reading. Do it once and store it.

**Done when.** Selecting a folder queues its unsynced files; deselecting stops future
uploads without touching what's already uploaded; a file in the tombstone table is never
re-queued.

---

## 2.8 — Metadata extraction

**Goal.** The timestamp and offset ladders, correctly.

**Files.** `domain/Timestamps.kt`, `domain/ExifExtractor.kt`, and thorough tests.

**Details.**
- `androidx.exifinterface`. Extract dimensions, MIME, camera make/model/serial, lens.
- **The `takenAt` ladder**, first hit wins, recording `takenAtSrc`: EXIF
  `DateTimeOriginal` → client file mtime → (server handles the rest). Reject future
  timestamps and anything before 1990.
- **The offset ladder**, recording `tzSrc`: upload-forced → EXIF `OffsetTimeOriginal` →
  GPS delta (`DateTimeOriginal − GPSDateStamp/GPSTimeStamp`, rounded to 15 minutes) →
  upload-fallback → device default → owner `homeTz` resolved against the photo's local
  date → assume UTC.
- `deviceKey` is `<make>|<model>|<serial>`, lowercased, whitespace collapsed, missing
  parts `-`.
- The raw EXIF blob is **encrypted** before leaving the device. It contains GPS.

**Done when.** A fixture set covering EXIF-with-offset, EXIF-with-GPS-only,
EXIF-with-neither, and no-EXIF-at-all each resolve to the documented rung. These tests
matter more than they look: a regression silently misplaces photos in time rather than
failing.

---

## 2.9 — Thumbnails

**Goal.** 256, 1024, 2048 — generated once, forever.

**Files.** `sync/Thumbnailer.kt`.

**Details.**
- Longest edge, aspect preserved. WebP.
- `ImageDecoder` with `setTargetSampleSize` — downsample during decode, never decode
  full-size then scale. A 50 MP image decoded whole will OOM.
- All three sizes, always. The server can never re-derive a missing one, and adding a
  size later costs a re-upload of the entire library.
- RAW is out of scope: Android can't decode CR3 or ARW. Skip RAW files without a
  sibling; the home-side importer owns those.

**Done when.** A 50 MP HEIC produces three correctly-sized WebPs without the heap
exceeding ~50 MB.

---

## 2.10 — Upload worker

**Goal.** Backup that survives reboots, flaky networks and impatience.

**Files.** `sync/UploadWorker.kt`, `data/repo/UploadRepository.kt`.

**Details.**
- WorkManager, one work item per file, state in `upload_queue`.
- Per file: extract metadata → thumbnails → `POST /uploads` → stream-encrypt and PUT
  each presigned URL → mark done.
- **Multipart parts of 8 MiB** — exactly 8 crypto chunks. Misaligned boundaries make
  range arithmetic painful later.
- Constraints from settings: `NetworkType.UNMETERED` by default,
  `setRequiresBatteryNotLow(true)`, optional `setRequiresCharging(true)`.
- Long-running worker with a foreground notification for large files, or Android kills
  it.
- Exponential backoff. Distinguish permanent failures (400s — stop) from transient ones
  (5xx, network — retry).
- Handle the `kind: purged` response by writing a local tombstone and never retrying.

**Done when.** Uploading 100 photos survives a process kill and an airplane-mode
toggle, resuming without duplicates; switching to metered network pauses it.

---

## 2.11 — Timeline

**Goal.** The screen people actually use.

**Files.** `ui/timeline/`, `data/repo/PhotoRepository.kt`,
`crypto/EncryptedImageFetcher.kt`.

**Details.**
- Paging 3 with `RemoteMediator`: network fills Room, Room feeds the pager.
- Justified grid, newest first, date headers by **local** day using `tzOffsetMin` — not
  UTC. Photos taken at 9am should group under that day.
- A custom Coil `Fetcher` fetches ciphertext from CloudFront, unwraps the asset DEK with
  the in-memory master key, decrypts, and returns the bitmap. Cache decrypted results in
  Coil's disk cache, rooted in `noBackupFilesDir`.
- Load the 256 thumbnail for instant paint, then 1024 for the grid.
- **Locked state:** if the master key is unavailable, show an explicit locked screen
  with an unlock action. Do not render a grid of grey rectangles — metadata renders fine
  without the key, so the app will look healthy while every image fails.

**Done when.** 1,000 photos scroll smoothly, the app opens offline showing cached
thumbnails, and revoking key access produces the locked state rather than broken images.

---

## 2.12 — Photo detail

**Goal.** Look at one photo.

**Files.** `ui/detail/`.

**Details.**
- 2048 thumbnail, pinch-zoom, swipe between photos.
- Metadata panel: date in local time, camera, dimensions, size, and the rendition list
  ("JPEG · RAW"). Decrypt `exifEnc` on-device for the details.
- Mark the date as approximate when `takenAtSrc != exif`.
- Original on demand only — it's a paid retrieval against a tiered object.

**Done when.** Detail opens from the grid, zooms, and shows an approximate-date marker
for a photo lacking EXIF.

---

## 2.13 — Delete

**Goal.** The three-way prompt from `android.md`.

**Files.** `ui/detail/DeleteDialog.kt`.

**Details.**
- Options: remove from archive (default, keeps the local file); remove from both;
  cancel.
- Archive removal calls `DELETE /photos/{id}`. Both-removal additionally deletes via
  `MediaStore`.
- **Write a local tombstone either way**, so the scanner doesn't immediately re-upload
  the file still sitting on the phone.
- Default keeps the local copy: the phone is one of the independent copies the whole
  design leans on, and deleting the last copy of something should never be a default.
- **Surface blocked re-uploads in the trash list.** `GET /trash` returns
  `blockedAttempts` / `lastAttemptBy`; show them as a warning on the entry — *"3
  attempts to re-upload this from home-server — delete it there too, or it returns."*
  Not decoration: purge tombstones expire on a TTL, and this warning is what makes that
  expiry safe. See "Tombstones expire, and blocked attempts are surfaced" in
  `design.md`.

**Done when.** Archive-only removal makes the photo vanish from the timeline, leaves it
in the gallery, and it is not re-uploaded on the next scan; and a trashed entry that
another source keeps re-offering shows the attempt warning.

---

## 2.14 — Settings

**Goal.** The minimum that isn't hostile.

**Files.** `ui/settings/`.

**Details.**
- Sync: folders, network policy, charging requirement.
- Devices: list, edit timezone defaults, remove.
- Keys: enrolled wrappings, re-show recovery code confirmation flow.
- Storage: thumbnail cache size and a clear-cache action.
- Account: sign out, delete account (with confirmation).

**Done when.** Every setting persists across restart and takes effect.

---

## 2.15 — Upload queue UI

**Goal.** Visible progress.

**Files.** `ui/queue/`.

**Details.**
- List of pending, in-progress and failed uploads, with per-item errors and retry.
- Show *why* a queue is idle — "waiting for Wi-Fi", "waiting to charge". A silent
  stalled queue is the most common self-inflicted support problem in backup apps.

**Done when.** Pausing on a metered network shows the reason rather than nothing.

---

## 2.16 — CI and release

**Goal.** Ship it.

**Files.** `.github/workflows/`.

**Details.**
- PR: build, unit tests, instrumented tests on a Gradle-managed device.
- Tag: assemble, sign, upload to the Play internal track via the Play Developer API
  service account.
- Secrets: upload keystore (base64), keystore passwords, service account JSON.
- Renovate: grouped by ecosystem, automerge patch and minor on green, majors manual.
- `--no-daemon`, remote build cache.

**Done when.** A tagged commit appears on the internal track without manual steps.

---

## 2.17 — Play reviewer preview mode

**Goal.** Let a Play reviewer see every part of the app without an AWS account, an
instance, sign-in, or a recovery code — the alternative to standing up a dummy instance
and handing out credentials in the Play Console's account-access form.

**Files.** `ui/reviewer/ReviewerPreviewScreen.kt`, `ui/reviewer/ReviewerPreviewViewModel.kt`,
`ui/reviewer/ReviewerSettingsScreen.kt`, `ui/reviewer/ReviewerSettingsViewModel.kt`,
`ui/onboarding/ConnectScreen.kt`, `ui/onboarding/ConnectViewModel.kt`,
`data/repo/InstanceRepository.kt`, `data/local/InstanceStore.kt`, `MainActivity.kt`,
`res/drawable-nodpi/reviewer_sample_*.png`, `docs/play/privacy-policy.md`.

**Details.**
- New `ConnectUiState.ReviewerPreview` sibling to `NeedsConnection`/`Connected`.
  `ConnectScreen` gains a secondary "Preview without an account" text button below the
  existing `Connect` button — visually subordinate, since the primary path is still
  "connect to your own instance." Tapping it calls a new
  `ConnectViewModel.enterReviewerPreview()`.
- Persistence: a single `reviewerPreviewEnabled` boolean in the same DataStore
  `InstanceStore` already uses, independent of the per-host instance map. `connect()`'s
  `init` block checks it alongside `currentInstance` so a reviewer relaunching the app
  mid-review doesn't have to tap through again. `exitReviewerPreview()` clears it and
  returns to `NeedsConnection` — non-destructive, since preview mode never wrote
  anything to the per-host instance map or any session token in the first place.
- `MainActivity`'s `ArchivistApp` gets a third top-level branch,
  `is ConnectUiState.ReviewerPreview -> ReviewerPreviewScreen(...)`, structurally
  parallel to `NeedsConnection`/`Connected` and touching neither the `signedIn` nor
  `unlocked` local state — this path never constructs `AuthRepository`,
  `ArchivistApiFactory`, `CognitoAuthClient`, `UploadRepository` or any other
  network-capable type. That's the actual guarantee, not just a UI restriction: nothing
  reachable from `ReviewerPreviewScreen` can make a network call, because nothing in its
  dependency graph is capable of one.
- `ReviewerPreviewViewModel` depends on **`MediaStoreSource` alone** (`sync/MediaStoreSource.kt`,
  already Hilt-injectable, already network- and crypto-free — it's the seam `Scanner`
  and the folder-selection UI use to reach `ContentResolver`). Calls `listFolders()`
  then `listFiles(bucketId)` per folder, flattens and sorts by `dateModified` descending.
  Deliberately does **not** reuse `TimelineViewModel`/`PhotoDetailRepository`/Room/Paging
  3/`EncryptedImageFetcher` — those exist to solve problems (server pagination,
  ciphertext decryption) that don't exist here, and reusing them would be the easiest way
  to accidentally drag a network dependency into this screen.
- Thumbnails load straight from each file's `content://` URI via Coil's default loader —
  no decryption, since these are the phone's own plaintext originals. No custom
  `Fetcher`.
- Screen: a justified grid (visually similar to the real timeline, but its own
  Composable) plus a minimal pinch-zoom detail view (filename and size only — not EXIF;
  read-only, no upload). A persistent banner reading "Preview — no account, nothing
  uploaded" plus a "Settings" action, on every screen in this mode. A one-time dialog on
  first entry says plainly that every screen (including Settings) is reachable from
  here, that nothing is uploaded or sent anywhere, and that full functionality needs the
  reviewer to provision their own infrastructure and connect this app to it — pointed at
  this project's GitHub repository generically, never a literal URL (see CLAUDE.md's
  "Nothing personal in the committed tree" — the maintainer's own repo path must never
  land in a committed file). An always-visible "Exit preview" action.
- **Settings is reachable too** (`ReviewerSettingsScreen`), mirroring the real Settings
  menu's seven section labels verbatim so a reviewer sees the real information
  architecture. Storage reuses the real `StorageScreen`/`StorageViewModel` outright
  (verified network-free — `StorageRepository` only touches Coil's disk cache), so it's
  fully live. Sync shows real device folder names via `ReviewerSettingsViewModel`
  (`MediaStoreSource` again) with switches backed by plain `remember` state — visibly
  toggleable, nothing persisted, no upload started. Upload queue/Devices/Keys/Trash/
  Account are each a fixed explanation of what would be there with a real instance
  connected, not the real screens — those are wired to `AuthRepository`/
  `DeviceRepository`/`EnrolmentRepository` and would crash or silently no-op against a
  session that doesn't exist. No delete, no folder selection that does anything, in
  either the grid or Settings.
- If a device has zero photos (a bare emulator), the grid falls back to 4 tiny bundled
  sample images in `res/drawable-nodpi/` (self-authored, generated rather than sourced,
  to avoid asset-licensing questions in a publishable repo), labelled as samples rather
  than silently substituted — plain drawable resources rather than `assets/`, since Coil
  and `AsyncImage` take a resource id with zero extra plumbing.
- Guard test: a reflection-based unit test asserting `ReviewerPreviewViewModel`'s and
  `ReviewerSettingsViewModel`'s constructor parameter types contain none of the
  network-capable types listed above — cheap, and it's the thing that actually matters
  here, not the UI copy.
- Update `docs/design/android.md`: a new "Reviewer preview mode" section, plus a
  bullet under "Play Console consequences" noting the account-access declaration should
  point reviewers at the in-app button rather than supplying credentials for a
  purpose-built dummy instance. Update `docs/play/privacy-policy.md` (and sync
  `private/instance/privacy-policy.md`, per `private/README.md`) with a "Preview without
  an account" section and a permissions-bullet tweak, since the app now reads photos for
  a second reason besides backup.

**Done when.** A fresh install, with no network access at all (airplane mode), can reach
"Preview without an account" from the first screen, browse a grid and detail view of the
device's own photos, and navigate into Settings and back out through all seven sections;
exiting returns cleanly to the connect screen; the constructor guard tests pass;
`docs/design/android.md` and `docs/play/privacy-policy.md` are updated in the same
change. Verified end-to-end on a Pixel 8a emulator (`sdk_gphone16k_arm64`, Android 17
system image) — see STATUS.md for exactly what was exercised.

---

## Deliberately not in the MVP

Video playback, albums, favourites, people, free-text search, sharing, multi-instance,
web client, RAW handling on device. Each is a real feature; none is needed to prove the
architecture works end to end.
