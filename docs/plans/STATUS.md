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
| 1.8 Key wrapping endpoints | done | Reconciled 2026-08-26: `POST /keys` no longer accepts `masterKeyVer` (server derives it from `#SETTINGS` via `getCurrentMasterKeyVersion`, added `rotatedAt` to `OwnerSettingsItem`/`design.md`/`sample-data.md` so it doesn't need echoing from the client either); added `POST /keys/version` (`allocateMasterKeyVer`, atomic `ADD`) and `PUT /keys/hash-secret` (`putHashSecret`), wired into `router.ts` and `terraform/api.tf`. Verified against DynamoDB Local: `test/lambda/keys.test.ts` — masterKeyVer field is ignored even when present, enrolment after allocation, **two concurrent `POST /keys/version` calls via `Promise.all` return `mk-1`/`mk-2`** (the specific race the step's "Done when" calls out), hash-secret round-trip. `test/repo/repo2.test.ts`'s existing wrap-invariant coverage (enrol two devices, delete-to-one rejected) still passes. |
| 1.9 Upload API | partial | `routes/uploads.ts` covers the documented sequence. Added this pass: the hash-check now distinguishes a live-but-trashed hit (record a blocked attempt, or restore if `reAddDeleted`) from an ordinary live duplicate, and a tombstone hit now records `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy` and pushes `expiresAt` forward — verified in `test/lambda/uploads.test.ts` against DynamoDB Local + MinIO (all three hash-check branches, S3 objects included). **Not verified**: the literal "IMG_1.CR3 then IMG_1.JPG → one asset, JPEG primary" and "concurrent uploads of both produce the same result" scenarios via `postUpload` itself — that grouping logic is only verified at the `repo/ingest.ts` layer (`test/repo/repo.test.ts`), not through the HTTP route end to end. |
| 1.10 S3 event handler | done | `test/lambda/s3event.test.ts` run against DynamoDB Local + MinIO this pass: status stays `processing` until every declared object (original + each thumb) exists with the declared size, flips to `ready` once they all do, and flips to `failed` on a size mismatch. |
| 1.11 Read endpoints | partial | `routes/photos.ts`, `routes/facets.ts` wired into `router.ts`. Not independently verified — no test exercises `GET /photos`, `/photos/{id}`, `/facets`, or `/facets/{type}/{value}` directly. |
| 1.12 Mutations | partial | Rename/delete/restore verified at the repo layer (`test/repo/repo2.test.ts`: rename moves the PATH pointer, primaryRend re-election, last-rendition trashes the asset). Added this pass: `GET /trash` now enriches each entry with its primary rendition's `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy` when non-zero — verified in `test/lambda/uploads.test.ts`. **Not verified**: `PATCH .../renditions/{rid}`, `DELETE /photos/{id}`, `DELETE .../renditions/{rid}`, `POST .../restore` through their actual route handlers (only through the repo functions they call). |
| 1.13 Purge sweep | done | Fixed this pass: `purgeAsset` now takes `tombstoneRetentionDays` and sets `expiresAt` (epoch seconds, via the new `epochSecondsAfterDays` helper) on every tombstone conversion, without touching pre-existing `blockedAttempts`/`lastAttemptAt`/`lastAttemptBy`; `src/lambda/purge/index.ts` reads `tombstoneRetentionDays` from owner settings (default 365) and threads it through. `terraform/dynamodb.tf` now has `ttl { attribute_name = "expiresAt" enabled = true }`. Verified: `test/repo/repo3.test.ts` confirms `expiresAt` is set on purge and pushed further out by a later blocked attempt; `terraform validate`/`fmt` clean. **Not verified**: TTL actually expiring an item on a real table (DynamoDB Local doesn't emulate TTL expiry; needs a live deployment and a wait). |
| 1.14 CloudFront, certificate, discovery | done | **Deployed live.** `curl https://photos.example.com/.well-known/archivist.json` → `200`, valid ACM cert (`SSL certificate verify ok`, issued by Amazon, matches the domain), correct body (`apiBase`, `region`, `cognito.userPoolId`/`clientId`, `cryptoVersion: 1`, `instanceName`). Direct S3 (`https://archivist-originals-....s3.eu-west-1.amazonaws.com/...` and the web bucket) → `403`. `/media/*`, `/thumbs/*`, `/api/*` all resolve through their CloudFront Functions and origins without routing errors. |
| 1.15 Account deletion | partial | `routes/account.ts` written and wired. `deleteOwnerData` verified at the repo layer this pass (`test/repo/repo2.test.ts`: removes media, a pre-existing purge tombstone, S3 objects, and the owner-registry row). The route handler itself (confirmation-token check, Cognito `AdminDeleteUser` call) is not independently verified — would need a real signed-in user to delete, which nobody should do against the live `prod` instance just to test it. |
| 1.16 Crypto format spec and conformance vectors | done | `docs/design/crypto-format.md` is the spec. `tools/gen-vectors/generate.py` produces all 22 required cases into `testdata/vectors/`; every case self-verified at generation time. |

**Deployment status, as of 2026-08-26:** `terraform apply` run against the operator's
real `prod` instance (see `private/instance/` for the actual account/region/domain)
using the operator's own AWS profile, at the user's explicit direction — 65 resources
added, 1 changed (DynamoDB TTL
enabled in-place), 0 destroyed, no errors. A follow-up `terraform plan` reports zero
drift. This is the operator's real self-hosted instance, not a throwaway `dev`
environment — there is no separate `dev`; `environment = "prod"` in
`private/instance/terraform.tfvars`. Live-verified this pass: `GET /health` (direct and
via CloudFront), unauthenticated 401s, the discovery document over a valid cert, and
direct-S3 403s on all three buckets. **Not live-verified:** anything requiring a signed-in
user (passkey enrolment, uploads, cross-owner isolation, account deletion) — all of it
needs an interactive WebAuthn ceremony this session cannot perform headlessly. The table
above marks each step's status independently of this note; read both.

## Plan 02 — Android MVP

Depended on plan 01 being deployed and 1.16's vectors existing — both are now true (see
the deployment note above; there is no separate `dev`, the app would connect to the
operator's real `prod` instance).

| Step | Status | Notes |
| --- | --- | --- |
| 2.1 Project scaffolding | done | `./gradlew assembleDebug` produces a debug APK. |
| 2.2 Crypto module | done | `:core:crypto:testDebugUnitTest` — all 22 conformance vectors pass, plus a 100 MB streaming round-trip and a truncated-ciphertext-fails test. |
| 2.3 Instance connection | partial | `data/remote/{DiscoveryDocument,DiscoveryApi,DiscoveryClient,NetworkModule}.kt`, `data/local/{InstanceStore,LocalStorageModule}.kt`, `data/repo/InstanceRepository.kt`, `ui/onboarding/{ConnectViewModel,ConnectScreen}.kt` written; `MainActivity` routes on connection state. HTTPS-only host input (bare host or explicit `https://`; `http://` and any other scheme rejected outright, no fallback); distinguishes `InvalidHost`/`HostNotFound`/`NotArchivist`/`ServerTooNew` per the plan's error-mode requirement; persists per-host to DataStore (map keyed by host + a "current" pointer, not a singleton). 22 unit tests pass (`:app:testDebugUnitTest`): `DiscoveryClientTest` (host normalization, all four error modes, cryptoVersion boundary) against a fake `DiscoveryApi`; `DiscoveryApiWireFormatTest` against a real MockWebServer, confirming the actual Retrofit/kotlinx.serialization stack throws `HttpException`/`SerializationException` where `DiscoveryClient` assumes it does; `InstanceStoreTest` (DataStore round-trip via a temp-file store, no Robolectric needed); `ConnectViewModelTest` (state machine, using a real `InstanceRepository`/`InstanceStore` backed by a temp-file DataStore sharing the test's `StandardTestDispatcher` scheduler — the naive version of this test was flaky/deadlocked because DataStore's default internal scope is real `Dispatchers.IO`, invisible to a test's virtual scheduler). `DiscoveryDocument`'s fields checked by hand against `terraform/wellknown.tf`'s real `jsonencode(...)` body — exact match. `./gradlew assembleDebug` still builds a debug APK; `:core:crypto:testDebugUnitTest` still green (no regression). **Not verified**: nothing has run on an emulator or device — no real `GET /.well-known/archivist.json` fetch from the app itself, no manual UI walkthrough of the error states, no Compose UI test. This build environment has JDK 21 + the Android SDK but no emulator/device attached, so device-level verification needs a follow-up pass. Introduced JUnit5 (Jupiter) for this module rather than the crypto module's JUnit4, matching the target test stack in `docs/design/android.md`; the two modules now use different test runners on purpose (`useJUnitPlatform()` set on `:app` only). |
| 2.4 Authentication | not started | |
| 2.5 Key enrolment and recovery code | not started | |
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
