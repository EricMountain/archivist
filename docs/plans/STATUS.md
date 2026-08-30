# Implementation status

Tracks progress against [01-aws-backend.md](01-aws-backend.md) and
[02-android-mvp.md](02-android-mvp.md), step by step. This file is the source of truth
for "is X done" — don't infer it from memory of a past session, and don't infer it from
a file's mere existence either: a file can exist and still not meet its step's "Done
when". See "Keeping this file honest" in [README.md](README.md) for the rule that keeps
it that way.

Status values:

- **done** — meets the step's "Done when" criterion. Verified, not assumed.
- **partial** — some of the step exists. The note says exactly what's missing.
- **blocked** — can't proceed until something else happens (usually: a dependency
  further up this table, or a human prerequisite from README.md).
- **not started**

## Plan 01 — AWS backend

| Step | Status | Notes |
| --- | --- | --- |
| 1.1 Repo scaffolding | done | `make test` runs typecheck + vitest clean. |
| 1.2 Key builders and item types | done | `test/keys.test.ts` — 22/22 pass, byte-equal against `sample-data.md`. |
| 1.3 DynamoDB access layer | done | Ran the DynamoDB-Local + MinIO gated suite this pass (`DYNAMODB_ENDPOINT`/`S3_ENDPOINT` etc. set): 47/47 pass across `test/keys.test.ts`, `test/repo/{repo,repo2,repo3}.test.ts`, `test/lambda/{s3event,keys,uploads}.test.ts` — create asset, attach rendition, timeline pagination + cursor, facet query, trash/restore, rename, primaryRend re-election, purge (S3 + DynamoDB), account deletion, key-wrap invariants, master-key allocation, hash secret, blocked-attempt tracking. |
| 1.4 Cognito user pool | partial | **Deployed live** 2026-08-26 (`terraform apply` to the operator's real `prod` instance). Confirmed via `aws cognito-idp describe-user-pool`: `Policies.SignInPolicy.AllowedFirstAuthFactors = [WEB_AUTHN, PASSWORD]`, `MfaConfiguration: OFF`, `UsernameAttributes: [email]`. `WebAuthnConfiguration` doesn't appear in that API's response at all (not even null vs. absent — the field seems to not be surfaced by `DescribeUserPool` in this AWS CLI/API version); indirectly confirmed instead via `terraform plan` reporting zero drift after apply, which reads the resource back through the provider's own (different) code path. **Not verified**: the step's literal "Done when" — a passkey registered and used to obtain a JWT — needs an interactive WebAuthn ceremony (a real browser + authenticator), which nothing in this session can perform headlessly. |
| — | **security fix, same day** | **`terraform/cognito.tf` shipped with self-service sign-up left on** (Cognito's default — `admin_create_user_config` was never set). Since the discovery document publishes the pool ID and client ID unauthenticated by design, this meant anyone who found the domain could call Cognito's public `SignUp` API directly and `POST /session/bootstrap` would mint them a fully-isolated library — not a read of the operator's data, but unauthorized resource provisioning against the operator's AWS bill, and a direct contradiction of `deployment.md`'s invite-only model. Caught by the user asking how bootstrap was actually gated, not by anything in this session's own review. Checked `aws cognito-idp list-users` before fixing: zero users existed, so the live exposure window (deploy → fix, a few hours) wasn't exploited. Fixed: `admin_create_user_config.allow_admin_create_user_only = true`, applied live, confirmed via `describe-user-pool` (`true`) and a zero-drift `terraform plan`. Step 1.4's plan text and `deployment.md` (new "Inviting someone" section, `admin-create-user`) updated so a future deployment doesn't reintroduce this. |
| 1.5 API Gateway and API Lambda | done | **Deployed live.** The API Gateway's own invoke URL's `/health` → `200 {"status":"ok"}`; the same URL's `/photos` (no auth header) → `401`. Both also verified through CloudFront at `https://photos.example.com/api/health` → `200`. |
| 1.6 Authorisation middleware | partial | `src/lambda/api/auth.ts` written and live (the 401s above prove the JWT authorizer is wired and rejecting unauthenticated calls). Every lambda-level *test* this pass still constructs `req.auth` directly rather than going through `resolveAuth`, so `resolveAuth`'s own JWT→ownerId resolution is unexercised by any test. The cross-owner-read test the step specifically requires needs two real signed-in identities — blocked on the same interactive passkey ceremony as 1.4. |
| 1.7 First sign-in bootstrap | done | `test/repo/repo2.test.ts`'s bootstrap test (run this pass): two calls for the same issuer/subject return the same userId/ownerId and only the first reports `created: true`. |
| 1.8 Key wrapping endpoints | done | Reconciled 2026-08-26: `POST /keys` no longer accepts `masterKeyVer` (server derives it from `#SETTINGS` via `getCurrentMasterKeyVersion`, added `rotatedAt` to `OwnerSettingsItem`/`design.md`/`sample-data.md` so it doesn't need echoing from the client either); added `POST /keys/version` (`allocateMasterKeyVer`, atomic `ADD`) and `PUT /keys/hash-secret` (`putHashSecret`), wired into `router.ts` and `terraform/api.tf`. Verified against DynamoDB Local: `test/lambda/keys.test.ts` — masterKeyVer field is ignored even when present, enrolment after allocation, **two concurrent `POST /keys/version` calls via `Promise.all` return `mk-1`/`mk-2`** (the specific race the step's "Done when" calls out), hash-secret round-trip. `test/repo/repo2.test.ts`'s existing wrap-invariant coverage (enrol two devices, delete-to-one rejected) still passes. **2026-08-27 addition**: `wrapAlg` closed set extended to include `ECDH-ES+AES-KW` (`items.ts`, `keys.ts`), with a new `epk` field required and validated when that `wrapAlg` is used — see 2.4a below for why. Verified against DynamoDB Local: new case in `test/lambda/keys.test.ts` (missing `epk` rejected, present `epk` accepted, 48/48 total passing). |
| 1.9 Upload API | partial | `routes/uploads.ts` covers the documented sequence. Added this pass: the hash-check now distinguishes a live-but-trashed hit (record a blocked attempt, or restore if `reAddDeleted`) from an ordinary live duplicate, and a tombstone hit now records `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy` and pushes `expiresAt` forward — verified in `test/lambda/uploads.test.ts` against DynamoDB Local + MinIO (all three hash-check branches, S3 objects included). **Not verified**: the literal "IMG_1.CR3 then IMG_1.JPG → one asset, JPEG primary" and "concurrent uploads of both produce the same result" scenarios via `postUpload` itself — that grouping logic is only verified at the `repo/ingest.ts` layer (`test/repo/repo.test.ts`), not through the HTTP route end to end. |
| 1.10 S3 event handler | done | `test/lambda/s3event.test.ts` run against DynamoDB Local + MinIO this pass: status stays `processing` until every declared object (original + each thumb) exists with the declared size, flips to `ready` once they all do, and flips to `failed` on a size mismatch. |
| 1.11 Read endpoints | partial | `routes/photos.ts`, `routes/facets.ts` wired into `router.ts`. Not independently verified — no test exercises `GET /photos`, `/photos/{id}`, `/facets`, or `/facets/{type}/{value}` directly. |
| 1.12 Mutations | partial | Rename/delete/restore verified at the repo layer (`test/repo/repo2.test.ts`: rename moves the PATH pointer, primaryRend re-election, last-rendition trashes the asset). Added this pass: `GET /trash` now enriches each entry with its primary rendition's `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy` when non-zero — verified in `test/lambda/uploads.test.ts`. **Not verified**: `PATCH .../renditions/{rid}`, `DELETE /photos/{id}`, `DELETE .../renditions/{rid}`, `POST .../restore` through their actual route handlers (only through the repo functions they call). |
| 1.13 Purge sweep | done | Fixed this pass: `purgeAsset` now takes `tombstoneRetentionDays` and sets `expiresAt` (epoch seconds, via the new `epochSecondsAfterDays` helper) on every tombstone conversion, without touching pre-existing `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy`; `src/lambda/purge/index.ts` reads `tombstoneRetentionDays` from owner settings (default 365) and threads it through. `terraform/dynamodb.tf` now has `ttl { attribute_name = "expiresAt" enabled = true }`. Verified: `test/repo/repo3.test.ts` confirms `expiresAt` is set on purge and pushed further out by a later blocked attempt; `terraform validate`/`fmt` clean. **Not verified**: TTL actually expiring an item on a real table (DynamoDB Local doesn't emulate TTL expiry; needs a live deployment and a wait). |
| 1.14 CloudFront, certificate, discovery | done | **Deployed live.** `curl https://photos.example.com/.well-known/archivist.json` → `200`, valid ACM cert (`SSL certificate verify ok`, issued by Amazon, matches the domain), correct body (`apiBase`, `region`, `cognito.userPoolId`/`clientId`, `cryptoVersion: 1`, `instanceName`). Direct S3 (`https://archivist-originals-....s3.eu-west-1.amazonaws.com/...` and the web bucket) → `403`. `/media/*`, `/thumbs/*`, `/api/*` all resolve through their CloudFront Functions and origins without routing errors. |
| 1.15 Account deletion | partial | `routes/account.ts` written and wired. `deleteOwnerData` verified at the repo layer this pass (`test/repo/repo2.test.ts`: removes media, a pre-existing purge tombstone, S3 objects, and the owner-registry row). The route handler itself (confirmation-token check, Cognito `AdminDeleteUser` call) is not independently verified — would need a real signed-in user to delete, which nobody should do against the live `prod` instance just to test it. |
| 1.16 Crypto format spec and conformance vectors | done | `docs/design/crypto-format.md` is the spec. `tools/gen-vectors/generate.py` produces all 23 required cases into `testdata/vectors/` (was 22 — case 23, ECDH-ES+AES-KW, added 2026-08-27 when Android's device-wrap route changed; see 2.4a below); every case self-verified at generation time. |

**Deployment status, as of 2026-08-26:** `terraform apply` run against the operator's
real `prod` instance (see `private/instance/` for the actual account/region/domain)
using the operator's own AWS profile, at the user's explicit direction — 65 resources
added, 1 changed (DynamoDB TTL
enabled in-place), 0 destroyed, no errors. A follow-up `terraform plan` reports zero
drift. Live-verified this pass: `GET /health` (direct and
via CloudFront), unauthenticated 401s, the discovery document over a valid cert, and
direct-S3 403s on all three buckets. **Not live-verified:** anything requiring a signed-in
user (passkey enrolment, uploads, cross-owner isolation, account deletion) — all of it
needs an interactive WebAuthn ceremony this session cannot perform headlessly. The table
above marks each step's status independently of this note; read both.

**A `dev` instance now exists (2026-08-29),** specifically to unblock the interactive
verification above without touching the real library — see "Dev instance" below for how
it's deployed and reached.

## Dev instance

A second, throwaway deployment for testing, at `archivist-dev.<the operator's domain>`
(exact hostname in `private/instance/dev.tfvars`). Same AWS account and region as `prod`,
same Terraform config, isolated by a separate Terraform workspace (`dev`) and
`environment = "dev"` — separate DynamoDB table, S3 buckets, Cognito pool, Lambdas and
CloudFront distribution; see "Running a second environment" in `terraform/README.md` for
the workflow (`make plan-dev` / `make deploy-dev`).

Deployed 2026-08-29: `terraform apply -var-file=../private/instance/dev.tfvars` against
the `dev` workspace — 79 resources added, 0 changed, 0 destroyed. One transient error on
first apply (`aws_s3_bucket_notification.derived`: `PutBucketNotificationConfiguration`
`InvalidArgument`, S3 validating the Lambda invoke permission before its IAM propagation
caught up) — a plain re-run of the same apply succeeded (1 added) with no config change
needed; if this recurs, it's this known race, not a bug. Live-verified: discovery
document and `/api/health` both `200` through CloudFront on the real domain;
`admin_create_user_config.allow_admin_create_user_only = true` and zero users on the new
pool (confirmed via `describe-user-pool`/`list-users` — the self-service-sign-up gap
that hit `prod` did not reoccur here, since the fix is already in `cognito.tf`).

Not yet done: no passkey has been enrolled against it, so it's ready for but hasn't yet
been used for the interactive verification plan 01/02 steps below still need.

## Plan 02 — Android MVP

Depended on plan 01 being deployed and 1.16's vectors existing — both are now true (see
the deployment note above). A `dev` instance now exists (see "Dev instance" above) for
interactive testing that would otherwise risk the operator's real `prod` instance.

| Step | Status | Notes |
| --- | --- | --- |
| 2.1 Project scaffolding | done | `./gradlew assembleDebug` produces a debug APK. |
| 2.2 Crypto module | done | `:core:crypto:testDebugUnitTest` — all 22 conformance vectors pass, plus a 100 MB streaming round-trip and a truncated-ciphertext-fails test. |
| 2.3 Instance connection | partial | `data/remote/{DiscoveryDocument,DiscoveryApi,DiscoveryClient,NetworkModule}.kt`, `data/local/{InstanceStore,LocalStorageModule}.kt`, `data/repo/InstanceRepository.kt`, `ui/onboarding/{ConnectViewModel,ConnectScreen}.kt` written; `MainActivity` routes on connection state. HTTPS-only host input (bare host or explicit `https://`; `http://` and any other scheme rejected outright, no fallback); distinguishes `InvalidHost`/`HostNotFound`/`NotArchivist`/`ServerTooNew` per the plan's error-mode requirement; persists per-host to DataStore (map keyed by host + a "current" pointer, not a singleton). 22 unit tests pass (`:app:testDebugUnitTest`): `DiscoveryClientTest` (host normalization, all four error modes, cryptoVersion boundary) against a fake `DiscoveryApi`; `DiscoveryApiWireFormatTest` against a real MockWebServer, confirming the actual Retrofit/kotlinx.serialization stack throws `HttpException`/`SerializationException` where `DiscoveryClient` assumes it does; `InstanceStoreTest` (DataStore round-trip via a temp-file store, no Robolectric needed); `ConnectViewModelTest` (state machine, using a real `InstanceRepository`/`InstanceStore` backed by a temp-file DataStore sharing the test's `StandardTestDispatcher` scheduler — the naive version of this test was flaky/deadlocked because DataStore's default internal scope is real `Dispatchers.IO`, invisible to a test's virtual scheduler). `DiscoveryDocument`'s fields checked by hand against `terraform/wellknown.tf`'s real `jsonencode(...)` body — exact match. `./gradlew assembleDebug` still builds a debug APK; `:core:crypto:testDebugUnitTest` still green (no regression). **Not verified**: nothing has run on an emulator or device — no real `GET /.well-known/archivist.json` fetch from the app itself, no manual UI walkthrough of the error states, no Compose UI test. This build environment has JDK 21 + the Android SDK but no emulator/device attached, so device-level verification needs a follow-up pass. Introduced JUnit5 (Jupiter) for this module rather than the crypto module's JUnit4, matching the target test stack in `docs/design/android.md`; the two modules now use different test runners on purpose (`useJUnitPlatform()` set on `:app` only). **2026-08-29 addition**: `ConnectViewModel.changeInstance()` lets a `Connected` instance back out to `NeedsConnection` (prefilled with the host being left) without touching what's stored — nothing is overwritten until a new `connect()` actually succeeds. Two new `ConnectViewModelTest` cases cover it (backs out with the right prefill; no-op when not connected). |
| 2.4 Authentication | partial | `data/remote/{CognitoAuthApi,CognitoAuthClient,CognitoNetworkModule,ArchivistApi,ArchivistApiFactory}.kt`, `data/local/{TokenStore,LocalStorageModule addition}.kt`, `data/repo/AuthRepository.kt`, `ui/onboarding/{SignInViewModel,SignInScreen,PasskeyCeremony}.kt` written and wired into `MainActivity` after a successful connection. Scope grew beyond the plan's literal "sign in with a passkey": an invited account has no registered passkey and no hosted UI to bridge that gap, so the app also handles the temporary-password → `NEW_PASSWORD_REQUIRED` → passkey-registration path — see the new note under "Auth and key unlock" in `android.md`. Cognito's user API confirmed live to be plain unsigned HTTPS JSON-RPC (`X-Amz-Target` header) — no AWS SDK, no SigV4 — and that `application/json` silently fails (200 with `UnknownOperationException` in the body, ignoring `X-Amz-Target` entirely) where `application/x-amz-json-1.1` is required; also confirmed live: requesting a `WEB_AUTHN` challenge for an account with no registered passkey returns `ChallengeName: SELECT_CHALLENGE`, not an error. 64 unit tests pass (`:app:testDebugUnitTest`, up from 22 in 2.3), covering the Cognito wire client against a fake API, the token store, and — the part that couldn't be checked live — the OkHttp `Authenticator`'s refresh-on-401-exactly-once logic, verified against a real MockWebServer (attaches the stored token; refreshes and retries once on 401; never retries a second time even if the retry also 401s; a failed refresh clears the session and gives up). Caught and fixed two real bugs this pass: a `parseToJsonElement` call that threw instead of returning a typed failure for malformed credential JSON, and a StandardTestDispatcher/real-network race in the ViewModel tests (same class of issue as 2.3's DataStore one, this time from OkHttp's real thread pool — fixed with a bounded real-time poll rather than `advanceUntilIdle()` alone). **Not verified, and not fully verifiable in this environment**: the actual Credential Manager ceremony (`PasskeyCeremony.kt`) — no device, and additionally blocked by a real infrastructure gap found in the course of this work: the relying-party domain has no `/.well-known/assetlinks.json`, which Credential Manager requires before it will create or use a passkey scoped to that domain at all. Written up as open question 2 in `design.md` rather than guessed at. **2026-08-29 addition**: the username-entry step now has a "Change server" action wired to `ConnectViewModel.changeInstance()`, so a wrong-instance sign-in (e.g. picked prod, meant dev) doesn't dead-end — previously there was no way back to `ConnectScreen` from anywhere past it. |
| 2.4a Keystore algorithm spike | done | Run for real on a physical device (Motorola Edge 20 Lite, Android 13/API 33, no StrongBox chip — user-supplied, connected via USB, deliberately the oldest device available rather than a newer one that wouldn't answer whether the oldest works). Results: RSA-3072/2048 keygen works in the TEE (~1.3s/~150–300ms) but refuses StrongBox (no chip); **RSA-OAEP-256 decrypt via the Keystore-resident private key fails outright** (`InvalidAlgorithmParameterException: Unsupported MGF1 digest: SHA-256. Only SHA-1 supported`) even though encrypt via the same key works fine — isolated to decrypt specifically by splitting the check into three probes across two runs, since the first run's stack trace was initially misread; EC-P256 keygen works but lands in software, not the TEE; ECDH agreement against a Keystore-resident EC-P256 key **works correctly end to end**, confirmed from both agreement directions. Verified live that `KeyAgreement.getInstance("ECDH")` (no explicit provider) auto-routes to the Keystore implementation, same as `Cipher.getInstance` already does for RSA — confirmed before relying on it in `EcdhEs.kt`. Open question 1 in `design.md` is resolved: Android device wrapping moves to `ECDH-ES+AES-KW`. `KeystoreSpike.kt` deleted per the plan's own instruction, results recorded in `design.md`/`crypto-format.md` instead. **Still open**: whether the software-not-TEE result for EC-P256 is this device's Keymaster specifically or broader — not re-tested on other hardware this session (see design.md open question 1's remainder). |
| 2.5 Key enrolment and recovery code | partial | `KeyCustody.kt`/`DeviceKeystore.kt` (`:core:crypto`); `EnrolmentRepository.kt`/`EnrolmentViewModel.kt`/`EnrolmentScreen.kt`, `data/local/EnrolmentStore.kt`, `data/repo/MasterKeyHolder.kt` (`:app`); wired into `MainActivity` after sign-in and into `ArchivistApplication.onTrimMemory`. First-device enrolment, later-device recovery, silent unlock on restart, `KeyPermanentlyInvalidatedException` re-enrolment, and (added after real-device testing, see below) `UserNotAuthenticatedException` handling are all implemented, covered by **90 passing JVM unit tests across both modules** (`:core:crypto` 10, `:app` 80), and now also **run for real on an emulator** (Pixel_8a, API 37/Android 17) — see the "2026-08-27 (emulator pass)" audit entry below for the full account, including two real Keystore-behavior bugs unit tests could never have caught (a `setUserAuthenticationRequired` default that demands a biometric specifically, and an unusable auth-per-use mode that would have needed an alpha-only AndroidX dependency) and three real bugs the JVM test run itself caught (a `MasterKey` API that made recovered-key re-wrapping impossible, a 204-response `NullPointerException`, and Keystore/Argon2id work running unguarded on the Main dispatcher). Two real product-level gaps remain, written up as open questions 3 and 4 in `design.md` rather than guessed around: (3) `PURPOSE_AGREE_KEY` needs API 31 but `minSdk` is 28, so there is no working hardware-backed device-wrap route at all below that; (4) no endpoint lets a later device fetch the owner's wrapped `hashSecret`, which plan step 2.10 will need. **Still not verified**: the actual first-device-enrolment and recovery-code flows end to end against a real backend — the emulator pass deliberately stopped short of pointing at the operator's live `prod` instance (would consume a real master-key-version allocation and produce a real recovery code for an actual library) and no throwaway/dev instance exists to test against instead; also unverified is a real biometric (as opposed to PIN/pattern/password) satisfying the auth window, and the `NeedsDeviceUnlock` UI path's `KeyguardManager.createConfirmDeviceCredentialIntent` hand-off (logically sound and compiles, but not exercised live — same category of gap `PasskeyCeremony` already documents for Credential Manager). Retrying `finishFirstEnrolment()` after a partial failure is not idempotent (a retry re-POSTs fresh wraps rather than resuming) — wasteful but not unsafe, not fixed since it's beyond this step's literal "Done when". |
| 2.6 Local storage | not started | |
| 2.7 Folder selection and scanning | not started | |
| 2.8 Metadata extraction | not started | |
| 2.9 Thumbnails | not started | |
| 2.10 Upload worker | not started | |
| 2.11 Timeline | not started | |
| 2.12 Photo detail | not started | |
| 2.13 Delete | not started | |
| 2.14 Settings | not started | |
| 2.15 Upload queue UI | not started | |
| 2.16 CI and release | not started | |

## Last audit

2026-08-29 — Claude (Sonnet 5). User-reported gap: from the onboarding flow's sign-in
email step there was no way back to the instance-selection (Connect) screen, so picking
the wrong site (e.g. prod instead of dev) meant restarting the app to fix it — `signedIn`
was a one-way `MainActivity` ratchet and `ConnectUiState` only ever moved
`NeedsConnection` → `Connected`, never back. Added `ConnectViewModel.changeInstance()`
(`Connected` → `NeedsConnection`, prefilled with the host being left; a no-op unless
currently connected) and a "Change server" action on `SignInScreen`'s username step that
calls it, wired through `MainActivity`. Deliberately non-destructive: `changeInstance()`
doesn't touch `InstanceStore` — the previous instance and any session against it stay on
disk until a new `connect()` actually succeeds, so backing out and closing the app
without reconnecting leaves things exactly as they were. Two new `ConnectViewModelTest`
cases added.

While verifying, found `:app:testDebugUnitTest` was unrunnable on the Gradle 9.5 /
AGP 9.3.2 upgrade earlier the same day (`65994e8`/`27e1842`/`0f1c012`, all untested at the
time — no STATUS.md rows depend on test runs made between that bump and this entry, so
nothing already recorded here is contradicted): `Failed to load JUnit Platform...
junit-platform-launcher`, reproduced on both JDK 21 and the system JDK 26 — Gradle
stopped auto-resolving the JUnit 5 platform launcher from `junit-jupiter` alone
somewhere between 8.10 and 9.5. Fixed with an explicit
`testRuntimeOnly(libs.junit.platform.launcher)` (new `junit-platform-launcher:1.11.3`
catalog entry, matching the existing `junit-jupiter:5.11.3`). Confirmed:
`:app:testDebugUnitTest` and `:core:crypto:testDebugUnitTest` both green, **92 tests
total** (up from 90 — the two new `ConnectViewModelTest` cases), `./gradlew assembleDebug`
still builds. Flagging it anyway since a same-day Gradle/AGP bump that silently breaks
test execution is exactly the kind of gap this file exists to catch before it's trusted
by a future session. Nothing ran on an emulator or device this pass — same environment
constraint as usual.

2026-08-27 (emulator pass) — Claude (Sonnet 5). The user pointed out Android Studio and
an emulator (Pixel_8a AVD, API 37/Android 17) were now available in this environment,
and asked to actually verify plan step 2.5 rather than leave it at "not verified, no
device" — the first time any Android step in this plan has run on real (if emulated)
Keystore hardware. Booted the emulator headless (`emulator -no-window`), wrote a
throwaway instrumented test (`DeviceKeystoreSpikeTest.kt`, deleted once its results were
recorded, per 2.4a's own convention) exercising `DeviceKeystore`/`EcdhEs`/`KeyCustody`
against the real `AndroidKeyStore`. Found two real bugs no JVM unit test could have
caught, both via `adb logcat`, not by inspection:

1. **`setUserAuthenticationRequired(true)` alone defaults to requiring a biometric
   specifically.** Keygen threw `InvalidAlgorithmParameterException: At least one
   biometric must be enrolled...` on the emulator, which had a PIN but no fingerprint —
   an entirely ordinary device state, not an edge case. Fixed by explicitly ORing
   `AUTH_DEVICE_CREDENTIAL` into `setUserAuthenticationParameters`'s allowed-authenticator
   bitmask.
2. **Auth-per-use (a 0-second validity window) is unusable as designed.** It requires
   the `KeyAgreement` operation to be driven through a `BiometricPrompt.CryptoObject`
   ceremony — confirmed live, the raw call throws `ProviderException`/`KeyStoreException`
   ("User not authenticated... No operation auth token received") — and the
   `CryptoObject(KeyAgreement)` constructor needed for that only exists in
   `androidx.biometric` 1.4.0-alpha06+ (confirmed via the Jetpack releases page: stable
   is still 1.1.0, from 2021, with no `KeyAgreement` support). Rather than take on an
   indefinite alpha dependency, switched to the platform's other documented mode:
   `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`
   — the key is usable for 5 minutes after the user last unlocked the device via any
   allowed authenticator, no `CryptoObject`/prompt/dependency needed. Confirmed this
   works end to end: set a PIN (`adb shell locksettings set-pin`), then actually
   performed a credential-verification event (`adb shell locksettings verify`) to stamp
   Keystore's "last authenticated" timestamp — before that, both spike tests failed
   with a clean, correctly-typed `UserNotAuthenticatedException`; after it, both passed,
   including the full `KeyCustody.enrolFirstDevice` → `unwrapForDevice` /
   `unwrapWithRecoveryCode` round trip against a real Keystore-resident key. Also
   confirmed via `KeyInfo.isInsideSecureHardware` that this emulator's EC-P256 key is
   software-backed (`securityLevel=0`) — expected for an emulator (no real TEE) and not
   new information toward design.md open question 1's still-open real-hardware
   question, but confirms the crypto is correct without hardware backing regardless.

Since `UserNotAuthenticatedException` is a real, ordinary failure mode this exposed
(not just a stale-window corner case — a freshly booted device has *never* satisfied
the window), added handling for it rather than leaving it to crash: a new
`EnrolmentStep.NeedsDeviceUnlock` / `EnrolmentUiState.NeedsDeviceUnlock`, surfaced as a
"Unlock your device" screen backed by `KeyguardManager.createConfirmDeviceCredentialIntent`
that retries `checkStep()` on success. Added `EnrolmentRepositoryTest`'s matching case
(`FakeDeviceKeyProvider.requireAuthenticationOnNextPrivateKeyUse`). 90 tests pass
across both modules (up from 89), `./gradlew assembleDebug` still succeeds.

Also installed and launched the real debug APK on the emulator and drove it — not just
launched it: the Connect screen renders correctly, and typing a deliberately
nonexistent host and tapping Connect correctly reaches the `HostNotFound` error state
(red-bordered field, "Couldn't reach that address...") — the first live confirmation of
plan step 2.3's error-state UI, previously flagged as "no manual UI walkthrough of the
error states" in that step's own status note. Deliberately did **not** point the app at
the operator's real `prod` instance to test sign-in/enrolment further: doing so would
allocate a real master-key version and produce a real recovery code against the actual
library, and no throwaway/dev instance exists to test against instead. Updated
`android.md`'s "Key unlock" section with the corrected time-based-auth design (a
deviation from its previous "biometric prompt" framing, found and fixed the same way
2.4a's RSA→ECDH pivot was — by actually running it, not by inspection) and updated this
step's own status row above.

2026-08-27 (later still) — Claude (Sonnet 5). Implemented plan step 2.5 (key enrolment
and recovery code): `KeyCustody.kt`/`DeviceKeystore.kt` in `:core:crypto`, wiring them
into `EnrolmentRepository`/`EnrolmentViewModel`/`EnrolmentScreen` in `:app`, plus a
`MasterKeyHolder` and an `onTrimMemory` hook in `ArchivistApplication`. Designed
`KeyCustody` to take `PublicKey`/`PrivateKey` rather than touching `AndroidKeyStore`
directly (mirroring `EcdhEs`'s own existing design), specifically so its logic is a
plain JVM unit test; introduced a `DeviceKeyProvider` interface so `EnrolmentRepository`
could be tested the same way against a `FakeDeviceKeyProvider`. That paid off
immediately: writing the tests caught a real design bug before it reached a device —
the first draft made it *impossible* to wrap a recovered master key for a new device,
since `MasterKey` hides its raw bytes by design and the wrap functions demanded them
from outside — fixed by moving `wrapForDevice`/`wrapForRecovery` onto `MasterKey`
itself. Also caught, the same way: `ArchivistApi`'s new `deleteKey`/`putHashSecret`
endpoints threw `NullPointerException` against a real 204 response with a non-null
suspend return type — fixed with `Response<ResponseBody>` and an explicit
`isSuccessful` check where it matters. 89 tests pass across both modules (`:core:crypto`
10 — up 7 for `KeyCustodyTest`, from 3 — and `:app` 79, up from 64), `./gradlew
assembleDebug` succeeds. Found and wrote up two
real gaps rather than guessing around them (open questions 3 and 4 in `design.md`,
same section 2.4a's RSA finding resolved in): `PURPOSE_AGREE_KEY` needs API 31 but
`minSdk` is 28, so there's no working hardware-backed device-wrap route at all below
that (a product decision, not a 2.5 fix); and no endpoint exists yet for a later device
to fetch the owner's wrapped `hashSecret` (plan step 2.10 will need one plan 01 doesn't
have). Nothing here ran on a real device or emulator — same environment constraint as
every Android step since 2.3 — so the real `AndroidKeyStore` integration, a real
biometric prompt, and a real lock-screen-triggered `KeyPermanentlyInvalidatedException`
remain unverified beyond what a plain-EC-keypair fake can stand in for.

2026-08-27 (later same day) — Claude (Sonnet 5). Ran 2.4a for real, on a physical
device the user connected (Motorola Edge 20 Lite, Android 13, no StrongBox — their
oldest, on purpose: a newer device passing wouldn't answer whether the oldest one
does). Found that RSA-OAEP-256 decrypt against a Keystore-resident key fails outright
on real hardware (`Unsupported MGF1 digest: SHA-256. Only SHA-1 supported`), a plain
TEE Keymaster limitation independent of StrongBox availability — worse than the
StrongBox-availability question 2.4a was originally written to answer, and with no
software workaround since that operation never leaves hardware. Confirmed ECDH-ES
works correctly on the same device as the replacement. Iterated the spike twice more
on-device to pin this down precisely: the first run's own stack trace was initially
misread as an encrypt failure (it was decrypt), corrected by splitting the check into
three independent probes; then added an ECDH agreement probe that also caught a
try/catch scoping bug (`StrongBoxUnavailableException` thrown outside where it was
being caught) before it produced a false "works" reading. Also verified live, before
relying on it in code, that `KeyAgreement.getInstance("ECDH")` with no explicit
provider auto-routes to the Keystore implementation — same as `Cipher.getInstance`
already does for RSA — which is what let `EcdhEs.kt` share one code path for both a
real Keystore key and a plain test key.

At the user's explicit direction ("I want a mechanism that works with this device"),
wrote up the full change: `crypto-format.md`'s "Master key wrapping" section rewritten
for `ECDH-ES+AES-KW` (P-256, HKDF-SHA256 salted with the ephemeral public key, then
AES-KW), `design.md`'s open question 1 marked resolved with the real numbers and a new
remaining sub-question (EC-P256 landed in *software*, not the TEE, on this device — a
separate, still-open gap), `sample-data.md`'s device `W#` row updated, `android.md`'s
and `02-android-mvp.md`'s RSA-specific prose corrected. Added conformance vector 23 to
`tools/gen-vectors/generate.py` (fixed static + ephemeral P-256 keypairs, checked from
both agreement directions) and regenerated `testdata/vectors/` — note this also
regenerated the non-deterministic existing cases' random material (streaming salts,
the RSA-17 keypair) as a side effect of re-running the generator; still 23/23
self-verified. Wrote `EcdhEs.kt` in `:core:crypto` and a matching conformance-test
case; all 23 vectors pass. Updated the backend: `wrapAlg` closed set and a new `epk`
field on `KeyWrapItem` (`items.ts`), required-and-validated in `POST /keys`
(`routes/keys.ts`) when that `wrapAlg` is used — spun up a throwaway DynamoDB Local +
MinIO (no committed compose file for this exists; started manually, table/buckets
created by hand from `terraform/dynamodb.tf`'s schema, torn down after) specifically to
verify this against real infrastructure rather than typecheck alone: 48/48 backend
tests pass. Deleted `KeystoreSpike.kt` per the plan's own instruction now that its
result is recorded. Did not test on a second, different device — the user offered a
newer one but reasoned (correctly) that it wouldn't answer whether the oldest device
works, which is the one that has to.

2026-08-27 — Claude (Sonnet 5). Started plan 02 step 2.4 (Authentication) and 2.4a
(Keystore spike), plus wrote `docs/design/api.md` (every endpoint, method, and what
gates access to it — JWT authorizer, `authMode`, and the CloudFront surfaces that
bypass it). Before writing any Cognito client code, verified its wire format live
against the real pool with a throwaway test user rather than working from memory:
confirmed unsigned HTTPS JSON-RPC works with no AWS SDK/SigV4 (design.md's claim was
correct), that `application/x-amz-json-1.1` is load-bearing (not `application/json`),
the real shape of `StartWebAuthnRegistration`'s `CredentialCreationOptions`, and that a
`WEB_AUTHN` challenge for an account with no registered passkey returns
`SELECT_CHALLENGE` rather than erroring. Built the Cognito REST client, encrypted token
store, an authenticated Archivist API client with an OkHttp `Authenticator` doing
refresh-on-401-exactly-once, the auth repository, and a `SignInViewModel`/`SignInScreen`
— scope grew to include the temporary-password-to-passkey-registration bridge an
invited account actually needs, since there's no hosted UI to do it instead. Added 42
tests (22 → 64 total for `:app`), including a real-MockWebServer-backed test of the
Authenticator (the trickiest logic, and the one thing that couldn't be checked live).
Fixed two bugs the test run caught: an uncaught `parseToJsonElement` exception on
malformed credential JSON, and a StandardTestDispatcher/real-network race in the
ViewModel tests (same shape as 2.3's DataStore race, this time from OkHttp real
threads). Wrote 2.4a's `KeystoreSpike.kt` per the plan and confirmed it compiles; did
not and could not run it — no device or emulator here. In the course of 2.4, found a
real infrastructure gap: passkey creation needs a `/.well-known/assetlinks.json` on
each instance's domain that nothing in `terraform/` serves yet, and the app's own
signing certificate (needed to fill it in) doesn't exist since Play App Signing hasn't
happened. Wrote this up as open question 2 in `design.md` rather than guessing at a
fingerprint or the exact asset-links relation string. Updated `create-user.md`'s and
`android.md`'s claims about 2.4/2.5 status to match. Neither 2.4's Credential Manager
ceremony nor 2.4a could be exercised on real hardware in this session — both need a
follow-up pass on an actual device once the asset-links gap is closed.

---

2026-08-26 (later same day) — Claude (Sonnet 5). Picked up from the audit below.
Reconciled step 1.8 (removed client-supplied `masterKeyVer` from `POST /keys`, added
`POST /keys/version` and `PUT /keys/hash-secret`, added `rotatedAt` to `#SETTINGS` —
documented in `design.md`/`sample-data.md`); fixed step 1.13 (tombstone `expiresAt` +
DynamoDB TTL); added the blocked-re-upload-attempt tracking steps 1.9/1.12 required
(trashed-live hit records-or-restores, tombstone hit records and pushes `expiresAt`
forward, `GET /trash` surfaces it). Ran the full DynamoDB-Local + MinIO gated suite throughout
(47/47 passing by the end, across 7 test files — 11 tests added this pass for the new
coverage) plus `make build`/`typecheck`/`terraform fmt`+`validate`. Then, at the user's explicit direction,
sourced the operator's AWS profile and ran `terraform plan` (65 add / 1 change / 0 destroy,
reviewed before proceeding) and `terraform apply` against the real `prod` instance —
succeeded with 0 errors, confirmed with a zero-drift `terraform plan` afterward and live
`curl`s of `/health`, an unauthenticated route, the discovery document, and direct-S3
403s. Did not perform any interactive-auth-gated verification (passkey enrolment,
authenticated uploads, cross-owner isolation, account deletion) — see the per-step notes
above for exactly what that leaves unverified.

**Same day, follow-up.** The user asked what actually stops a stranger from calling
`POST /session/bootstrap` for themselves — a question this session's own review hadn't
raised. Investigation found self-service Cognito sign-up was still enabled on the live
pool (`admin_create_user_config` was never set in `cognito.tf`), which combined with the
discovery document's unauthenticated `clientId`/`userPoolId` meant anyone who found the
domain could self-register and get a fully-isolated library. Checked for actual
exploitation (`aws cognito-idp list-users` — zero users) before fixing live. See the "—
| security fix, same day" row under 1.4 above for the full account. Worth naming
directly: this should have been caught while writing 1.4/1.7, not after deploying, and
wasn't until asked about it.

---

2026-08-26 (later still) — Claude (Sonnet 5). Started plan 02 step 2.3 (Instance
connection), the first Android step past scaffolding/crypto. Added Retrofit +
kotlinx.serialization + OkHttp + DataStore-preferences to the version catalog (none of
this existed in the module before); wrote the discovery fetch, host validation, DataStore
persistence, repository and ViewModel/Compose layers; wrote 22 unit tests (JUnit5, this
module's first tests — the crypto module uses JUnit4, kept separate on purpose). Actually
ran the build: found and fixed a wrong import (`retrofit2.converter...` instead of the
real `com.jakewharton.retrofit2.converter...` package) that `:app:compileDebugKotlin`
caught immediately, and a real test-flakiness bug in the ViewModel test (DataStore's
default coroutine scope is real `Dispatchers.IO`, which a test's virtual `StandardTestDispatcher`
can't see or wait for — fixed by binding DataStore's scope to the same test dispatcher and
passing it into `runTest` too). `assembleDebug` and `:core:crypto:testDebugUnitTest` both
still pass. Compared `DiscoveryDocument`'s fields against `terraform/wellknown.tf`'s real
`jsonencode(...)` by hand — exact match. Did not run anything on an emulator or device —
this environment has the Android SDK but no emulator; the plan step's literal "Done when"
(connecting to a real instance from the running app) is therefore unverified, and stays
`partial` in the table above until someone does that pass.

---

2026-08-26 — Claude (Sonnet 5). Method: read every step's "Files" against the actual
tree, ran `make test` (backend) and `./gradlew :core:crypto:testDebugUnitTest` (Android),
grepped for the specific gaps plan 01 step 1.8 already flags, and spot-checked
`repo/purge.ts` against step 1.13's tombstone requirement. Did not run the
DynamoDB-Local-gated integration tests, did not source AWS credentials, did not deploy
anything.
