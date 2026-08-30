# Plan 01 — AWS backend

Everything server-side: Cognito, the API, the Lambdas, CloudFront, and the two shared
artefacts every client depends on.

Read `docs/design/design.md` before starting. `docs/design/sample-data.md` has the exact
key shapes and the queries — use it as the reference while writing data access code.

## Already done

DynamoDB table, both S3 buckets, and the Terraform state bootstrap all exist in
`terraform/`. Don't recreate them.

## Credentials

`source private/instance/env.sh` before anything that touches AWS. Never hardcode a
profile name, account ID or region into a committed file — including Makefiles, scripts
and CI config. Terraform reads the standard credential chain.

## Language: TypeScript on Node

The one decision this plan settles that the design left open. Lambdas never touch
encryption — they cannot decrypt anything — so the choice is free, and TypeScript wins
on shared types: the API request and response types are written once and consumed by
both the Lambda and any future web client. Node's AWS SDK v3 also has the least
friction with DynamoDB's document API.

Runtime `nodejs22.x`, ESM, bundled with esbuild. No transpilation-at-deploy, no layers.

---

## 1.1 — Repo scaffolding

**Goal.** A buildable TypeScript workspace that produces Lambda bundles.

**Files.** `package.json`, `tsconfig.json`, `Makefile`, `.gitignore` additions,
`src/`, `test/`.

**Details.**
- npm workspaces: `src/core` (shared logic), `src/lambda` (handlers).
- esbuild bundles each handler to `dist/<name>/index.mjs`, external `@aws-sdk/*` since
  the runtime provides it. Target `node22`, format `esm`, minify off (readable stack
  traces matter more than 200 KB).
- `make build` → bundles. `make test` → vitest. `make deploy` → build then
  `terraform apply`. Terraform must never be run without a fresh build; the Makefile is
  how that's enforced.
- Add `dist/`, `node_modules/`, `coverage/` to `.gitignore`.

**Done when.** `make build` produces `dist/api/index.mjs`, and `make test` runs zero
tests successfully.

---

## 1.2 — Key builders and item types

**Goal.** One module that constructs every DynamoDB key. Nothing else in the codebase
concatenates a `#`.

**Files.** `src/core/keys.ts`, `src/core/items.ts`, `test/keys.test.ts`.

**Details.**
- Functions, not string templates at call sites: `mediaPk(ownerId, photoId)`,
  `metaSk()`, `renditionSk(renditionId)`, `facetSk(type, value)`,
  `stemPtrPk(ownerId, stem)`, `pathPtrPk(ownerId, path)`, `hashPtrPk(ownerId, hmac)`,
  `idpPtrPk(issuer, subject)`, `devicesPk(ownerId)`, `keysPk(ownerId)`,
  `timelineGsi1Pk(ownerId)`, `trashGsi1Pk(ownerId)`, `sortKey(timestamp, ulid)`.
- The rule from the design: the variable-length user-controlled field goes **last**.
  Encode nothing; do not escape `#`.
- Timestamps are ISO-8601 UTC, fixed width, always `Z`, milliseconds always present.
  One formatter, used everywhere: `toIsoUtc(date)`.
- TypeScript types for each item shape (`MetaItem`, `RenditionItem`, `FacetItem`,
  `PointerItem`, `DeviceItem`, `KeyWrapItem`, `UserItem`, `OwnerSettingsItem`), with a
  discriminant so a `Query` result can be narrowed.
- Facet vocabulary as a union type: `LABEL | CAMERA | DEVICE | REND | LENS | YEAR |
  ALBUM | FAVOURITE | PERSON`. No ISO or FSTOP — deliberately removed.

**Done when.** Unit tests build every key in `sample-data.md` and assert byte-equality
with the documented values. This is the point of the step: the sample data becomes an
executable specification.

---

## 1.3 — DynamoDB access layer

**Goal.** Typed repository functions over the table. No handler writes raw SDK calls.

**Files.** `src/core/db.ts`, `src/core/repo/*.ts`, tests.

**Details.**
- One `DynamoDBDocumentClient`, table name from `MEDIA_TABLE` env var.
- Implement the queries in `sample-data.md`'s Queries section, one function each.
  Reserved words (`status`, `path`, `role`, `bytes`, `size`) need
  `ExpressionAttributeNames` — the sample shows which.
- Cursors: `encodeCursor(lastEvaluatedKey)` / `decodeCursor(s)`, base64url of JSON.
  **A GSI cursor carries four attributes** — both index keys and both table keys. Test
  a round-trip explicitly; storing only the index half is the likely bug.
- Transaction helpers for the ingest shapes documented under "Writing them" in the
  design: create-asset (5 items) and attach-rendition (4 items + `ConditionCheck` on
  `attribute_exists(pk) AND attribute_not_exists(deletedAt)`).

**Done when.** Repository unit tests pass against DynamoDB Local or a `dev` deployment,
covering: create asset, attach rendition to existing stem, timeline page with cursor,
facet query, trash move and restore.

---

## 1.4 — Cognito user pool

**Goal.** Per-instance authentication.

**Files.** `terraform/cognito.tf`, outputs.

**Details.**
- User pool with email sign-in, WebAuthn/passkey sign-in enabled, MFA off (the passkey
  *is* the factor).
- App client: no secret (public client), refresh token 30 days, access token 1 hour.
- Optional Google federation behind a `var.enable_google_idp` flag, default false — an
  operator may not want a Google dependency.
- Output the user pool ID and client ID; the discovery document (1.14) serves them.
- **`admin_create_user_config.allow_admin_create_user_only = true`. This is not
  optional.** The discovery document (1.14) publishes the pool ID and client ID
  unauthenticated, by design — that's fine only because getting in is supposed to
  require an invitation. If self-service sign-up is left enabled (Cognito's
  default), anyone who finds the domain can call Cognito's public `SignUp` API
  directly, obtain a JWT with nothing but an email address, and `POST
  /session/bootstrap` (1.7) will happily mint them a fully-isolated library. That
  contradicts the invite-only model in `deployment.md` and was shipped live once
  before being caught and fixed — see `STATUS.md`. With this set, the operator
  invites people via `aws cognito-idp admin-create-user`.

**Done when.** `terraform apply` creates the pool, and a passkey can be registered and
used to obtain a JWT via the AWS CLI or hosted UI.

---

## 1.5 — API Gateway and the API Lambda

**Goal.** One HTTP API, one Lambda, JWT-authorised.

**Files.** `terraform/api.tf`, `terraform/iam.tf`, `src/lambda/api/index.ts`,
`src/lambda/api/router.ts`.

**Details.**
- HTTP API (not REST API — cheaper and sufficient) with a JWT authorizer pointing at
  the Cognito pool.
- **One Lambda with an internal router**, not one per route: simpler IAM, one bundle,
  fewer cold starts. A small hand-written router over `routeKey` is enough; don't add a
  framework.
- IAM: the execution role gets exactly the table and its two indexes, `s3:PutObject` on
  originals and derived, and nothing else. No wildcards on resources.
- Structured JSON logging with a request ID. **Never log paths, filenames or stems** —
  they're user content, and the privacy policy says so.
- Errors: a small `ApiError` type mapping to status codes. Never leak an SDK exception
  message to the client.

**Done when.** `GET /health` returns 200 through the deployed API Gateway URL, and an
unauthenticated call to any other route returns 401.

---

## 1.6 — Authorisation middleware

**Goal.** Turn a JWT into an authorised `ownerId`.

**Files.** `src/lambda/api/auth.ts`, tests.

**Details.**
- JWT claims → `IDP#<issuer>#<sub>` pointer → `userId` → membership query → `ownerId`.
- Cache the identity resolution per warm container, keyed by `sub`, with a short TTL.
  The membership check stays per-request.
- Every handler receives a resolved `{ userId, ownerId, role }` and must never accept an
  `ownerId` from the request body or path. This is the whole authorisation model —
  getting it wrong means cross-account reads.

**Done when.** A test proves that a JWT for user A cannot read user B's photos, by
attempting it against a `dev` deployment.

---

## 1.7 — First sign-in bootstrap

**Goal.** A new Cognito user gets a library.

**Files.** `src/lambda/api/routes/session.ts`.

**Details.**
- `POST /session/bootstrap`, idempotent. If the `IDP` pointer resolves, return the
  existing user. Otherwise mint `userId` and `ownerId` ULIDs and transactionally write:
  `IDP` pointer (conditional), `U#/#PROFILE`, `U#/M#<ownerId>` with role `owner`,
  `O#/#SETTINGS` with `homeTz`, `trashRetentionDays: 30` and
  `tombstoneRetentionDays: 365`.
- `homeTz` is an **IANA zone name**, not an offset. Take it from the client, default
  `UTC`.

**Done when.** Signing in with a fresh passkey produces exactly one owner, and calling
bootstrap twice produces no duplicates.

---

## 1.8 — Key wrapping endpoints

**Goal.** Device enrolment and the recovery code.

**Files.** `src/lambda/api/routes/keys.ts`, `src/core/repo/keys.ts`.

**Details.**
- **Partly built, and the existing code contradicts the current design.**
  `routes/keys.ts` today takes `masterKeyVer` as a required `POST /keys` body field and
  validates it — that field must be *removed* from the request contract and come from
  the allocator instead. `repo/keys.ts` has `listKeyWraps`, `putKeyWrap` and
  `deleteKeyWrap`; it needs `allocateMasterKeyVer` and the hash-secret write. Reconcile
  before extending, and check `KeyWrapResponse` still projects the right fields once
  `rotatedAt` exists.
- `GET /keys` lists `W#` items (never the wrapped material for other devices — return
  metadata only, plus the wrapping for the *requesting* device).
- `POST /keys` enrols a wrapping: `kind`, `label`, `wrapAlg`, `wrappedKey`, plus
  `credentialId`/`prfSalt` or `kdfSalt`/`kdfParams`. **`masterKeyVer` is not a request
  field** — see the next bullet.
- `POST /keys/version` allocates a master key version: an atomic `ADD 1` on
  `masterKeyVerSeq` on the `#SETTINGS` item, returning `mk-<n>` and stamping
  `rotatedAt`. The client calls this once at the start of enrolment or rotation and
  puts the returned value on every wrapping and every `encDek` it rewrites.
  **Clients must never mint a version themselves** — two concurrent rotations would
  label two different master keys identically and every `encKeyId` in the table would
  become ambiguous. `ADD` on a missing attribute starts from 0, so the first allocation
  naturally yields `mk-1` with no bootstrap special case.
- `PUT /keys/hash-secret` stores `encHashSecret` and `hashSecretKeyId` on `#SETTINGS`.
  Opaque to the server, like every other wrapped value, but `contentHash` cannot be
  computed without it.
- `GET /keys/hash-secret` returns the same, `404` until the first device has ever
  called the `PUT` above. Without this, only the device that generated the hash secret
  could ever compute `contentHash` — a second device (recovery, or the same device
  after a restart with nothing cached) unwraps the master key just fine but has no way
  to learn what the hash secret even is. Added 2026-08-30, closing design.md open
  question 4.
- `DELETE /keys/{wrapId}` refuses if it would leave fewer than two wrappings, or if it
  would remove the last `recovery` wrapping. **The invariant is enforced server-side**,
  not just in the UI.
- The server never sees an unwrapped master key. Treat `wrappedKey` as opaque bytes.
  This is also why there is no rotation endpoint that does the work: re-wrapping every
  `encDek` needs both the old and new master keys, so the sweep runs on a client and
  arrives here as ordinary per-photo writes.

**Done when.** Enrolling two devices works; deleting down to one wrapping is rejected
with a clear error; `POST /keys` no longer accepts a client-supplied `masterKeyVer`;
and two concurrent `POST /keys/version` calls return different versions — test it with
two in-flight requests, not two sequential ones, since a read-then-write allocator
passes the sequential version of that test.

---

## 1.9 — Upload API

**Goal.** The stem handshake and presigned URLs. The most intricate step here.

**Files.** `src/lambda/api/routes/uploads.ts`.

**Details.**

`POST /uploads` accepts, per file: `path`, `plainBytes`, `mime`, `width`, `height`,
`contentHash` (HMAC), `takenAt` + `takenAtSrc`, `tzOffsetMin` + `tzSrc`, `deviceKey`,
`exifEnc` + `exifIv`, `encDek` + `encKeyId` + `encIv` + `encChunkSize`, thumbnail
descriptors, and optional `reAddDeleted` / `groupWith` / `noGroup`.

Sequence:
1. **Validate.** Client metadata is untrusted. Reject `takenAt` in the future or before
   1990, clamp dimensions, cap `exifEnc` size, and ignore any client-supplied
   `ownerId`, `photoId` or `uploadedAt`.
2. **Hash check** with three outcomes: absent → continue; `kind: live` → return the
   existing photo, no upload; `kind: purged` → **skip** unless `reAddDeleted`. Getting
   this wrong means deleted photos resurrect on the next sync.

   A refused re-upload is silent *on the wire* — an unattended sync has nowhere useful
   to put an error — but it is recorded. One `UpdateItem` on the pointer: `ADD
   blockedAttempts 1`, set `lastAttemptAt` and `lastAttemptBy`, and on a tombstone also
   push `expiresAt` out by `tombstoneRetentionDays`. That last part is what makes the
   TTL safe: a tombstone survives exactly as long as some source keeps offering the file
   back, and expires once it goes quiet. Do this for a trashed `kind: live` hit too —
   the trash window is when the warning is most useful, because the user can still act
   before anything is purged.
3. **Stem resolution.** Derive the stem server-side from `path` — never trust a
   client-supplied stem. Conditional-put the `STEM` pointer: success → new asset;
   failure → read it and attach a rendition.
4. **Write** the appropriate transaction from 1.3, with `status: processing`.
5. **Presign** PUTs for the original and each thumbnail. Originals get
   `x-amz-storage-class: INTELLIGENT_TIERING`; thumbnails do not. 15-minute expiry.
6. Return `photoId`, `renditionId`, and the presigned URLs.

Also: `primaryRend` election (`display` beats `raw`, ties by earliest `addedAt`), and
the rule that a later rendition may only **improve** `takenAt` — re-resolve only if its
`takenAtSrc` outranks the stored one.

**Done when.** Uploading `IMG_1.CR3` then `IMG_1.JPG` yields one asset with two
renditions and one timeline entry; the JPEG becomes primary; concurrent uploads of both
produce the same result; and re-offering purged bytes increments `blockedAttempts` and
moves `expiresAt` forward.

---

## 1.10 — S3 event handler

**Goal.** Confirm arrival, flip status.

**Files.** `src/lambda/s3event/index.ts`, `terraform/s3_events.tf`.

**Details.**
- S3 `ObjectCreated` notification on both buckets → Lambda.
- Look up the rendition by S3 key (the key contains `renditionId`), verify the object
  size matches the declared `bytes`, mark `status: ready` when the original and all
  thumbnails have landed.
- **The Lambda never opens a file.** It cannot; the bytes are ciphertext. It checks
  existence and size only.
- Idempotent: S3 can deliver twice.

**Done when.** A completed upload transitions to `ready` without any client call, and a
replayed event changes nothing.

---

## 1.11 — Read endpoints

**Goal.** Timeline, facets, detail.

**Files.** `src/lambda/api/routes/photos.ts`, `routes/facets.ts`.

**Details.**
- `GET /photos?cursor=&limit=` — GSI1 descending, opaque cursor, default limit 50, max
  200.
- `GET /photos?from=&to=` — the same query with `BETWEEN`.
- `GET /photos/{photoId}` — full partition: meta, renditions, facets.
- `GET /facets` — the owner's distinct facet values, for the search UI's vocabulary.
- `GET /facets/{type}/{value}?cursor=&from=&to=` — GSI2.
- Responses carry the GSI-projected fields so a grid renders without a second call:
  `thumbs`, `encDek`, `encKeyId`, dimensions, `mime`, `tzOffsetMin`, `status`.

**Done when.** Paging through 200 test photos visits each exactly once, and a facet
query with a date range returns the documented subset.

---

## 1.12 — Mutations

**Goal.** Rename, delete, restore.

**Files.** `src/lambda/api/routes/photos.ts` (continued).

**Details.**
- `PATCH /photos/{id}/renditions/{rid}` for rename: the 3-item transaction — delete old
  `PATH` pointer, conditional-put the new one, update `R#.path`. Facets untouched.
- `DELETE /photos/{id}` — soft. Rewrite `gsi1pk` to the trash partition, set
  `gsi1sk = <deletedAt>#<photoId>`, set `deletedAt`/`deletedBy`, and `REMOVE gsi2pk,
  gsi2sk` from every facet item. Condition on `attribute_not_exists(deletedAt)` so a
  double-delete doesn't extend the retention window.
- `DELETE /photos/{id}/renditions/{rid}` — soft; re-elect `primaryRend`, drop the
  `F#REND#` facet, trash the asset if it was the last rendition.
- `POST /photos/{id}/restore` — the inverse, recomputing `gsi1sk` from `takenAt`.
- `GET /trash` — GSI1 trash partition, newest deleted first. Each entry carries its
  `HASH` pointer's `blockedAttempts` / `lastAttemptAt` / `lastAttemptBy` when non-zero,
  so the client can warn that a source still holds the file. Without this the warning
  has no way to reach the person who can act on it, and the tombstone TTL loses the
  thing that makes it safe.

**Done when.** Delete removes a photo from the timeline and adds it to the trash in one
call; restore returns it to its original timeline position.

---

## 1.13 — Purge sweep

**Goal.** Actually delete expired trash, and leave tombstones.

**Files.** `src/lambda/purge/index.ts`, `terraform/schedules.tf`,
`terraform/dynamodb.tf`.

**Details.**
- EventBridge rule, daily.
- Query the trash partition with `gsi1sk < now − trashRetentionDays`.
- Per asset: `DeleteObjects` for every rendition's `s3Key` and every thumbnail (batch
  1,000 keys per call), then `BatchWriteItem` the partition, then delete the `STEM` and
  `PATH` pointers, then **convert each `HASH` pointer to `kind: purged` with
  `purgedAt`** rather than deleting it.
- That last part is the point of the step. Without the tombstone, a phone that kept the
  local file re-uploads it on the next sync, a month after the user deleted it.
- Set `expiresAt` on the tombstone: `tombstoneRetentionDays` (default 365) past the
  later of `purgedAt` and `lastAttemptAt`. **Epoch seconds, not milliseconds and not
  ISO-8601** — DynamoDB silently never expires an item whose TTL attribute it cannot
  parse as epoch seconds, which fails as "nothing ever expired" months later.
- Resumable: process in batches, tolerate being killed.
- **The asset sweep is not DynamoDB TTL** — TTL would orphan the S3 objects. Tombstones
  are the exception, and only because nothing is behind them; enable TTL on the table
  for the `expiresAt` attribute in `terraform/dynamodb.tf`. Nothing but tombstones
  carries that attribute, so no other item is at risk.

**Done when.** An asset with a backdated `deletedAt` is fully removed, its S3 objects
are gone, its `HASH` pointer survives as a tombstone that a subsequent upload of the
same bytes skips, and that tombstone carries an `expiresAt` in epoch seconds that
DynamoDB accepts.

---

## 1.14 — CloudFront, certificate, discovery document

**Goal.** The instance has a domain and the app can find it.

**Files.** `terraform/cloudfront.tf`, `terraform/dns.tf`, `terraform/wellknown.tf`.

**Details.**
- ACM certificate in `us-east-1` (CloudFront's requirement) for `var.domain_name`,
  DNS-validated via Route 53.
- Distribution with behaviours: `/api/*` → API Gateway; `/media/*` → originals bucket
  via OAC; `/thumbs/*` → derived bucket via OAC; default → the web app bucket (a
  placeholder `index.html` is fine for now).
- Cache policy: thumbnails immutable for a year — their keys contain a ULID and never
  change. Originals not cached at the edge; they're large and rarely re-fetched.
- Buckets stay private; OAC only. Public access blocks remain on.
- `/.well-known/archivist.json` served from the web bucket, generated by Terraform with
  `apiBase`, `region`, `cognito.userPoolId`, `cognito.clientId`, `cryptoVersion: 1`,
  `instanceName` (a new variable, default `"Archivist"`). Cache for 5 minutes.

**Done when.** `curl https://<domain>/.well-known/archivist.json` returns the document
over a valid certificate, and a direct S3 URL returns 403.

---

## 1.15 — Account deletion

**Goal.** The privacy policy's deletion promise, honoured.

**Files.** `src/lambda/api/routes/account.ts`.

**Details.**
- `DELETE /account` with an explicit confirmation token, not a bare call.
- Deletes every partition for the owner: media, pointers **including purge tombstones**,
  devices, key wrappings, settings, memberships, the `IDP` pointer, and all S3 objects.
- Then deletes the Cognito user.
- The tombstone deletion matters: the policy says account deletion removes them.

**Done when.** After deletion, no item with the owner prefix remains and no object with
the owner's S3 prefix remains.

---

## 1.16 — Crypto format spec and conformance vectors

**Goal.** The artefact that keeps every client — Android, web, Python, Go, 2029's
version of any of them — mutually readable.

**Files.** `testdata/vectors/*`, `tools/gen-vectors/`. Spec: `docs/design/crypto-format.md`.

**Details.**
- `docs/design/crypto-format.md` is **written** — the spec exists and is authoritative.
  This step is the executable half: the vectors that prove implementations match it.
  Read it first; do not re-derive the format from `design.md`.
- Generate vectors with Tink (Python is easiest) covering the 22 cases the spec's
  "Conformance vectors" table lists, including the boundary cases at `P = C0` and
  `P = C0 + Cn` that settle whether a full final segment can be the last one, and every
  case marked **fail**: truncated stream, mid-segment truncation, swapped segments,
  altered header salt, altered AAD.
- Case 22 is the byte-range table — plaintext ranges mapped to the ciphertext ranges a
  client must actually request. It's what stops a seeking bug from being found by a
  user scrubbing a video.
- Commit the vectors with the key material, since they're test fixtures and protect
  nothing.
- `manifest.json` drives the suites: id, mode, keys as hex, AAD, files, expected result.
  Adding a case must not mean editing four test suites.
- Document the rule: every client's test suite decrypts these; a client that can't is
  broken regardless of what its own round-trip tests say.

**Done when.** The vectors exist, a Python script verifies them, every case marked
**fail** fails, and the range table in case 22 round-trips.

---

## Out of scope for this plan

Web client, Rekognition or any labelling, shared libraries, the account-deletion web
page, and monitoring beyond CloudWatch defaults.
