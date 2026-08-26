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
| 1.3 DynamoDB access layer | partial | `src/core/repo/*.ts` written. Its own integration tests (`test/repo/repo.test.ts`, `repo2.test.ts`) are gated on `DYNAMODB_ENDPOINT`/`S3_ENDPOINT` env vars and are skipped without them — not run this pass. |
| 1.4 Cognito user pool | partial | `terraform/cognito.tf` written. Not verified applied — no `terraform plan`/`apply` run this pass. |
| 1.5 API Gateway and API Lambda | partial | `terraform/api.tf`, `iam.tf`, `src/lambda/api/index.ts`, `router.ts` written, routes wired. Not verified deployed; `GET /health` not curled against a live URL. |
| 1.6 Authorisation middleware | partial | `src/lambda/api/auth.ts` written. The cross-owner-read test the step requires needs a `dev` deployment — not run. |
| 1.7 First sign-in bootstrap | partial | `routes/session.ts` wired. Integration test exists in `repo2.test.ts` but is skipped (see 1.3). |
| **1.8 Key wrapping endpoints** | **partial, known-broken** | The plan's own "partly built, contradicts the design" note is still true as of 2026-08-26: `POST /keys` in `routes/keys.ts` still requires a client-supplied `masterKeyVer`; there is no `POST /keys/version` route, no `allocateMasterKeyVer` in `repo/keys.ts`, and no `PUT /keys/hash-secret` route. Needs the reconciliation the plan describes before anything else touches this file. |
| 1.9 Upload API | partial | `routes/uploads.ts` written (~400 lines), covers the documented sequence (validate, hash check, stem resolution, transaction write, presign). Not independently verified end to end against a deployment. |
| 1.10 S3 event handler | partial | `src/lambda/s3event/index.ts`, `terraform/s3_events.tf` written. `test/lambda/s3event.test.ts` exists but is skipped (see 1.3). |
| 1.11 Read endpoints | partial | `routes/photos.ts`, `routes/facets.ts` written and wired into `router.ts` (`GET /photos`, `/photos/{id}`, `/facets`, `/facets/{type}/{value}`). Not independently verified. |
| 1.12 Mutations | partial | `PATCH .../renditions/{rid}`, `DELETE /photos/{id}`, `DELETE .../renditions/{rid}`, `POST .../restore`, `GET /trash` all wired in `router.ts`. Not independently verified. |
| **1.13 Purge sweep** | **partial, gap found** | `src/lambda/purge/index.ts` + `src/core/repo/purge.ts` implement the S3 `DeleteObjects` + `BatchWriteItem` sweep and convert each `HASH` pointer to `kind: purged`. But **`expiresAt` is never set on the tombstone**, and `terraform/dynamodb.tf` has no TTL attribute configured. The step's "Done when" (a tombstone carrying an `expiresAt` DynamoDB will actually expire) is not met. |
| 1.14 CloudFront, certificate, discovery | partial | `terraform/cloudfront.tf`, `dns.tf`, `wellknown.tf` written. Not verified deployed; discovery document not curled. |
| 1.15 Account deletion | partial | `routes/account.ts` written and wired (`DELETE /account`). Not independently verified. |
| 1.16 Crypto format spec and conformance vectors | done | `docs/design/crypto-format.md` is the spec. `tools/gen-vectors/generate.py` produces all 22 required cases into `testdata/vectors/`; every case self-verified at generation time. |

**Deployment status is untracked here on purpose** — none of plan 01's `terraform apply` has been verified against a live `dev` instance in a Claude session (no AWS credentials sourced). "done"/"partial" above means *code*, not *deployed*. Plan 02 steps 2.3+ need an actual deployed `dev` instance regardless of what this table says about the code.

## Plan 02 — Android MVP

Depends on plan 01 being deployed to `dev` and 1.16's vectors existing (they do).

| Step | Status | Notes |
| --- | --- | --- |
| 2.1 Project scaffolding | done | `./gradlew assembleDebug` produces a debug APK. |
| 2.2 Crypto module | done | `:core:crypto:testDebugUnitTest` — all 22 conformance vectors pass, plus a 100 MB streaming round-trip and a truncated-ciphertext-fails test. |
| 2.3 Instance connection | not started | Blocked on plan 01 being deployed to `dev` to connect to. |
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

2026-08-26 — Claude (Sonnet 5). Method: read every step's "Files" against the actual
tree, ran `make test` (backend) and `./gradlew :core:crypto:testDebugUnitTest` (Android),
grepped for the specific gaps plan 01 step 1.8 already flags, and spot-checked
`repo/purge.ts` against step 1.13's tombstone requirement. Did not run the
DynamoDB-Local-gated integration tests, did not source AWS credentials, did not deploy
anything.
