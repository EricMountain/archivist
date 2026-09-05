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
3. `WorkManager` picks it up: extract EXIF → resolve timestamp and offset → generate
   thumbnails → `POST` metadata to get a `photoId` and presigned URLs → stream-encrypt
   and PUT → mark `DONE`.
4. Failures retry with exponential backoff; the queue survives reboots.

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

Large uploads run as a long-running worker with a foreground notification, otherwise
Android will kill them. Encryption is CPU-heavy enough to be noticeable on battery, so
"only while charging" should be genuinely offered rather than buried.

## Screens

* **Timeline** — the justified grid, Paging 3 over Room, date headers by *local* day
  using `tzOffsetMin`, not UTC.
* **Photo detail** — 2048 thumbnail, pinch-zoom, original on demand, rendition list
  ("JPEG · RAW") with per-rendition delete.
* **Search** — facet browse (labels, cameras, devices, years). No free text; the
  design has no substring matching, so this is selection from a known vocabulary.
* **Upload queue** — visible and cancellable. A silent queue that has stalled on a
  constraint is a support nightmare of one's own making.
* **Settings** — devices and their timezone defaults, home timezone, sync policy,
  cache ceiling, enrolled keys.

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
SharedPreferences, never on disk — and cleared from `ArchivistApplication.onTrimMemory`,
per plan step 2.5. Re-unwrapping needs the device to have been unlocked within the last
5 minutes (see above); if not, the app asks the user to unlock rather than crashing.
Nothing past the initial unlock screen yet re-prompts if the key is cleared mid-session
by `onTrimMemory`; later steps that read the master key should check
`MasterKeyHolder.current` rather than assume it stays set for the app's whole lifetime.

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
`buildConfig` Gradle feature needed for one string), the license, and a link to this
project's repository. This is also where GPLv3's own suggestion of an "about box"
displaying Appropriate Legal Notices is satisfied.

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
