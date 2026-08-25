# Design

Currently covers the data model, storage layout and the identity model. Ingest API and
CDN to follow.

**Archivist is self-hosted.** Every user runs this stack in their own AWS account under
their own domain; there is no shared service. Read `deployment.md` for what that means
for responsibility, versioning and distribution — several decisions below only make
sense in that light, particularly the single-tenant scaling assumptions.

Single DynamoDB table (`archivist-media`), on-demand billing, two GSIs.

**Naming convention:** every AWS resource is prefixed `archivist-`. Tables are
`archivist-<purpose>`, so if the single-table design is ever abandoned the
successors are `archivist-media`, `archivist-audit` and so on. Buckets add
the account ID for global uniqueness. Non-production environments insert the
environment name: `archivist-dev-media`.

`sample-data.md` holds a worked example of eight assets as real rows. **It is part of
this design and must be updated alongside any change here** — key structure, item
types, attribute names, GSI keys or projections. A stale sample is worse than none,
because it contradicts this document silently.

`crypto-format.md` is the wire format: exactly which bytes an encrypted object is made
of, how keys are wrapped, and the conformance vectors every client must pass. This
document decides *what* is encrypted and *why*; that one decides *how*, and **it wins
wherever the two describe bytes.**

## Decisions

* **Owner, user and identity are separate concepts**, 1:1 today. `ownerId` is the
  namespace for all media; external IdP subjects resolve to a `userId` through a
  pointer item, so the identity provider stays swappable.
* **Identity is an immutable ULID (`photoId`), not the path.** Paths are mutable
  attributes reached through a pointer item. Renames cost 3 writes instead of a
  partition rewrite.
* **A photo is an asset with one or more file renditions.** Files sharing a path stem
  (`IMG_4021.CR3` + `IMG_4021.JPG`) group into one asset with one timeline entry.
* **Deletion is soft**, for 30 days by default. Trashing rewrites `timelinePk` to a trash
  partition of the same index, so an asset is in the timeline or the trash, never both.
* **All timestamps are stored in UTC**, ISO-8601, fixed width, always `Z`-suffixed:
  `2026-07-14T09:22:05.000Z`. The local UTC offset is preserved alongside as
  `tzOffsetMin` so the UI can still render wall-clock time.
* **The UTC offset comes from a documented priority ladder**, with an explicit
  per-upload override at the top and a configurable per-device default in the middle.
  Whichever rung won is recorded in `tzSrc`.
* **Privacy perimeter: AWS may see metadata, not pixels.** Metadata is stored in
  DynamoDB in plaintext and is searchable server-side, which is what makes this whole
  schema viable. Image bytes are encrypted *client-side* before upload; S3 holds
  ciphertext only. See "Encryption" below for what this costs.
* **The raw EXIF blob is encrypted too**, because it carries precise GPS. Only derived,
  query-shaped metadata stays in the clear.
* **A random master key, wrapped separately per enrolled device, plus a mandatory
  recovery code.** Every route to the master key is a wrapping, and at least two must
  exist at all times. Enrolment is not complete until the recovery code is confirmed.
* **Chunked encryption above a client-configured threshold**, default 32 MB. The chunk
  size used is recorded per object, so the policy can change without breaking old
  files.
* **Separate buckets for originals and derivatives.** Originals in Intelligent-Tiering,
  thumbnails in Standard at 256/1024/2048 longest edge. Buckets are recorded per object
  so the layout can change by background rewrite.

## Access patterns

| # | Query | Served by |
| --- | --- | --- |
| 1 | Get a photo by `photoId` | Base table, `GetItem` |
| 1b | Get a photo by full path | `PATH` pointer → `GetItem` (2 reads) |
| 1c | Get a photo *and* its renditions *and* its facets | Base table, `Query` on PK |
| 1d | Find the asset owning a path stem, during ingest | `STEM` pointer, `GetItem` |
| 2 | List photos, newest first, by owner (paginated) | `timeline_gsi`, `ScanIndexForward=false` |
| 3 | List photos in a time range, by owner | `timeline_gsi`, SK `BETWEEN` |
| 4 | List photos containing a certain object, by owner | `facet_gsi` |
| 5 | List photos by camera / other EXIF attribute, by owner | `facet_gsi` |
| 4b/5b | Any of the above, restricted to a time range | `facet_gsi`, SK `BETWEEN` |
| 6 | List an owner's configured devices | Base table, `Query` on PK |
| 6b | Get one device's default offset | Base table, `GetItem` |
| 7 | List every photo from a given device (for retro-correction) | `facet_gsi`, `F#DEVICE#…` |
| 8 | List photos I hold a RAW for | `facet_gsi`, `F#REND#raw` |
| 9 | Resolve a login to a user | `IDP` pointer, `GetItem` |
| 10 | List the libraries a user can access | Base table, `Query` on `U#<userId>` |
| 11 | List the trash, most recently deleted first | `timeline_gsi`, `O#<owner>#TRASH` |
| 12 | Find assets whose retention has expired | `timeline_gsi`, trash partition, SK `<` cutoff |
| 13 | List every owner in the deployment (for the purge sweep) | `REGISTRY#OWNERS`, `Query` on PK |

Queries 4 and 5 collapse into one index: "contains a dog" and "shot on a Canon R5"
are both *facets* — a `(type, value)` pair attached to a photo. One index, one code
path, and new facet types (album, favourite, person) cost nothing to add later.

## Key encoding

Separator is `#`. No "unused character" hunt is needed, because of one rule:

> **The variable-length, user-controlled field always goes last in a composite key.**

Paths and facet values may contain `#` freely — they're the final segment, so parsing
is split-on-known-prefix, never split-on-all. The only hard constraint is that owner
IDs must not contain `#`, which is free since we mint them (ULID).

With ULID identity, every sort key is now **fixed width and immutable**:
`<24-char timestamp>#<26-char ULID>`. Parseable by offset, and unaffected by renames.

## Users and owners

Three concepts, deliberately kept apart even though today they're 1:1:

* **Owner** — the namespace that owns media. Every `pk` in this design starts with one.
  A library.
* **User** — a human principal who can authenticate. Has a profile, devices, keys.
* **Identity** — an external credential proving a user is who they claim (a Cognito
  `sub`, a Google `sub`). A user may accumulate several over time.

Collapsing these into one id would be tempting at this scale and expensive later:
adding a second sign-in method, migrating off an IdP, or letting two people share one
library each become a rewrite of every partition key. Keeping them separate costs one
indirection on login, resolved once per session.

`ownerId` and `userId` are both ULIDs, minted by us — never an IdP's subject, so the
identity provider stays swappable.

### Items

```text
pk  O#<ownerId>            sk  #SETTINGS       the library
pk  U#<userId>             sk  #PROFILE        the person
pk  U#<userId>             sk  M#<ownerId>     membership: role = owner|editor|viewer
pk  IDP#<issuer>#<subject> sk  #PTR            → userId
pk  REGISTRY#OWNERS        sk  O#<ownerId>     one row per owner — see below
```

```text
# O#<ownerId> / #SETTINGS
ownerId       01J7XQ…
displayName        "Home photos"
homeTz             Europe/Paris   # IANA zone, not an offset — see below
trashRetentionDays 30
tombstoneRetentionDays 365    # TTL on purge tombstones; refreshed by a blocked
                              # re-upload — see "Purge tombstones"
encHashSecret      <b64>          # the owner's hash secret, wrapped by the master
hashSecretKeyId    mk-3           # key. Written by the first client at enrolment;
                                  # absent until then. See "`contentHash` is HMAC'd"
masterKeyVerSeq    3              # allocator for master key versions — see below
createdAt          2026-08-08T09:12:00.000Z

# U#<userId> / #PROFILE
userId        01J7XR…
email         …                 # for notification only, never an identity key
displayName   "Sam"
createdAt     2026-08-08T09:12:00.000Z
```

The `IDP` pointer is the same conditional-write pattern as `STEM` and `PATH`: issuer
plus subject resolves to a `userId`, and the subject goes last so it may contain
anything. Adding Google alongside Cognito later is a second pointer to the same user,
not a migration.

Memberships are stored on the user (`U#<userId>` + `M#<ownerId>`), so "which libraries
can I see" is one `Query` at session start. If shared libraries ever happen, the
reverse direction — "who can see this library" — needs a mirrored item under
`O#<ownerId>#MEMBERS`; not worth writing until there's a second member.

### Owner registry

Every other partition in this design is reachable only if you already know its
`ownerId` — that's deliberate, it's what keeps most access patterns to a single
partition. One job breaks that assumption: the purge sweep (see "Purging") has to
visit *every* owner in the deployment on a schedule, not one named at request time.

`REGISTRY#OWNERS` is a single sparse partition holding one small item per owner
(`ownerId`, `createdAt`), written in the same transaction as user bootstrap. Listing
owners is then one `Query` against a fixed pk, never a table `Scan`. At the
single-tenant scale this design targets it's normally one row, but the sweep
shouldn't assume that — a shared instance, or an operator running several
households' worth of owners, still has to be swept correctly.

### `homeTz` is a zone, not an offset

Rung 6 of the offset ladder says "owner's default timezone", and it must resolve an
IANA zone against the photo's local date rather than storing a fixed number. A stored
`+60` would be wrong for half of every year in Paris. The photo's `tzOffsetMin` stays a
resolved integer — correct, since by then it refers to one instant — but the *default*
it falls back to cannot be.

### Authentication

Recommend a **Cognito user pool, passkey-first, with optional Google federation**. It
gives API Gateway a JWT authorizer with no custom code, and its refresh tokens are
long-lived enough to avoid re-prompting for sign-in every day without building session
management.

Authorisation is then: JWT → `IDP` pointer → `userId` → membership → the `ownerId`
allowed in the request path. Cache the first three per session; only the membership
check is per-request, and it's a key lookup.

**Auth and key custody stay separate**, which is worth being explicit about because
both now involve passkeys. Cognito proves *who you are* to the API. The WebAuthn PRF
ceremony from "Key custody" produces *key material* the server must never see. They are
orthogonal, and combining them is likely impossible anyway: when Cognito performs the
WebAuthn assertion it isn't obliged to hand your app the PRF output. Assume a separate,
app-controlled credential for PRF, and a cold start on a new browser costs two
ceremonies. Every later visit is silent, courtesy of the non-extractable IndexedDB key.

### Sharing is not free

The schema would support a shared library — memberships already model it. Encryption is
the real obstacle: a second user needs the master key, and today `O#<ownerId>#KEYS`
holds wrappings for one person's devices.

Two shapes, if it ever comes up:

* **Shared master key** — the second user enrols devices against the same key. Simple,
  and honest for a couple sharing one family library, but there is no per-asset
  granularity and no revocation short of rotating everything.
* **Per-asset re-wrapping** — share individual assets by wrapping their DEKs to the
  recipient's public key. Genuinely granular, and possible only because DEKs are
  already per-asset. Costs one extra wrapping item per shared asset.

Neither needs building now. Both are reachable, which is the point of recording it.

Note that sharing is always *within* one deployment. There is no cross-instance
sharing, no federation and no central directory — two people on two instances have two
unrelated accounts, and the only way to give someone access to your photos is to give
them an account on your instance.

## Table: `archivist-media`

| | Attribute | Value |
| --- | --- | --- |
| PK | `pk` | `O#<ownerId>#M#<photoId>` |
| SK | `sk` | `#META`, `R#<renditionId>`, or `F#<type>#<value>` |

Every item belonging to one photo shares a partition, so query 1c is a single
`Query`, and deleting a photo is `Query` + `BatchWriteItem` over one partition.
There are no LSIs, so no 10 GB item-collection limit applies.

**A photo is an asset, not a file.** A RAW and its JPEG sibling are two `R#` renditions
of one `#META`, which is what makes them a single entry in the timeline. See "Rendition
grouping" for the mechanics.

### `#META` item

The logical photo — the thing the timeline shows. One per asset.

```text
pk           O#01J7X…#M#01K2M9F3QR8VTYA6H0WNXC4B7D
sk           #META
ownerId      01J7X…
photoId      01K2M9F3QR8VTYA6H0WNXC4B7D
stem         2026/summer/IMG_4021        # path without extension; the grouping key
primaryRend  01K2M9G7…                   # which rendition supplies display + metadata
renditions   2                           # count, for the UI badge; authoritative list
                                         # is the R# items in this partition
mime         image/heic                  # of the primary rendition
width        4032                        # of the primary rendition
height       3024
enc          AES-256-GCM
encDek       <b64>                       # one DEK per asset; every object in the group
encKeyId     mk-3                        # (renditions + thumbs) shares it, distinct IVs
takenAt      2026-07-14T09:22:05.000Z    # UTC
tzOffsetMin  540                         # +09:00; render local as takenAt + offset
tzSrc        upload-forced | exif-offset | gps | upload | device |
             owner-default | assumed-utc
deviceKey    canon|eos r5|042024001234   # normalised; null if EXIF has no make/model
takenAtSrc   exif | file-mtime | s3-mtime | upload
uploadedAt   2026-08-04T11:31:00.000Z    # UTC
thumbs       { 256: {bucket, key, iv, bytes}, 1024: …, 2048: … }  # also ciphertext
exifEnc      <b64>                       # the EXIF blob, encrypted with this asset's
exifIv       <b64>                       # DEK. Contains GPS — see "Encrypted EXIF"
groupSrc     stem | manual               # how the renditions came to be grouped
status       ready | processing | failed # orthogonal to deletion
deletedAt    (absent unless trashed)     # UTC; presence is the trash flag
deletedBy    "Pixel 9"                   # which device did it, for the trash UI
```

### `R#` rendition items

One per physical file. A lone JPEG has exactly one; a RAW+JPEG pair has two; a Live
Photo has the HEIC and its MOV.

```text
pk           O#01J7X…#M#01K2M9F3QR8VTYA6H0WNXC4B7D
sk           R#01K2M9G7…
renditionId  01K2M9G7…                   # ULID
role         display | raw | motion | sidecar
path         2026/summer/IMG_4021.HEIC   # this file's full path; mutable
ext          heic                        # normalised lowercase
mime         image/heic
s3Bucket     archivist-originals
s3Key        raw/01J7X…/01K2M9F3QR8V…/01K2M9G7…   # <ownerId>/<photoId>/<renditionId>
                                         # keyed by ids, not path, so renames never
                                         # touch S3. photoId is included, not just
                                         # renditionId, so the S3-event Lambda can
                                         # resolve which asset an object belongs to
                                         # from the key alone — see plan step 1.10
contentHash  hmac-sha256:…               # HMAC of plaintext, keyed by the owner's
                                         # hash secret — see "`contentHash` is HMAC'd"
bytes        4823935                     # ciphertext size as stored in S3;
                                         # whole-object is plainBytes + a 16-byte tag
plainBytes   4823919
width / height                           # this file's own dimensions
encIv        <b64>                       # 96-bit nonce, whole-object mode only —
                                         # absent when encChunkSize > 0, where the
                                         # salt and nonce prefix are in the ciphertext
                                         # header. See crypto-format.md
encChunkSize 0                           # 0 = whole-object, else 1048576 (1 MiB)
addedAt      2026-08-04T11:31:00.000Z    # UTC
```

`path` lives here rather than on `#META`, because a path identifies a file and the
asset has several. The DEK stays on `#META` — one key per asset, a distinct IV per
object, so a rendition carries only its own nonce.

Because `s3Key` is derived from the ULID rather than the path, a rename is purely a
metadata operation — no S3 copy, no CloudFront invalidation.

### Pointer items

Secondary lookups by a mutable or externally-supplied value, living in the owner's
namespace and holding nothing but the IDs they resolve to.

```text
pk  O#<ownerId>#STEM#<stem>       sk  #PTR   → photoId
pk  O#<ownerId>#PATH#<path>       sk  #PTR   → photoId, renditionId
pk  O#<ownerId>#HASH#<hmac>       sk  #PTR   → photoId, renditionId  (kind: live)
                                          or → nothing               (kind: purged)
```

A `HASH` pointer outlives the asset it described: on purge it becomes a tombstone
recording that these bytes were deliberately deleted. See "Purge tombstones".

The `STEM` pointer is the grouping mechanism: ingest resolves it to decide whether a
file joins an existing asset or starts a new one.

The `PATH` pointer enforces per-file path uniqueness via a conditional write
(`attribute_not_exists(pk)`), and resolves an exact filename to the rendition it names.
Rename of a single file is a 3-item `TransactWriteItems`: delete old pointer, put new
pointer (conditional), update `R#…​.path`. Renaming the whole asset also moves the
`STEM` pointer and every rendition's path — still bounded at a handful of items, and
still untouched by however many labels the photo has.

The `HASH` pointer makes re-imports idempotent — rescanning a folder recognises
already-ingested files instead of duplicating them. It's keyed per rendition, since
dedup is a property of file bytes.

### Why pointers and not GSIs

A GSI on `path` would serve the lookup, but it can't do the other half of the job.
GSIs are eventually consistent and cannot be the subject of a condition expression, so
they can enforce nothing. A conditional `PutItem` against a base-table item is
simultaneously the uniqueness constraint, the duplicate check and the concurrency
arbiter — and it's strongly consistent. Four GSIs would give none of that, and would
cost a write to each on every media write.

Pointers also stay out of both GSIs, since they carry no index keys. Same sparse-index
property that keeps renditions out of the timeline.

`IDP` is the one pointer not scoped to an owner, necessarily: at login there is no
owner yet, and resolving the pointer is what produces one.

### Writing them

A pointer and its target must not be able to disagree, so they're written together in
one `TransactWriteItems`. Creating an asset is five items, all-or-nothing:

```text
Put  O#<owner>#STEM#<stem>        cond: attribute_not_exists(pk)
Put  O#<owner>#PATH#<path>        cond: attribute_not_exists(pk)
Put  O#<owner>#HASH#<hmac>        cond: attribute_not_exists(pk)
Put  O#<owner>#M#<photoId> #META
Put  O#<owner>#M#<photoId> R#<renditionId>
```

Attaching a rendition to an existing asset is four, and needs a guard the first case
doesn't:

```text
ConditionCheck  O#<owner>#M#<photoId> #META   cond: attribute_exists(pk)
                                                    AND attribute_not_exists(deletedAt)
Put             O#<owner>#PATH#<path>         cond: attribute_not_exists(pk)
Put             O#<owner>#HASH#<hmac>         cond: attribute_not_exists(pk)
Put             O#<owner>#M#<photoId> R#<renditionId>
Update          O#<owner>#M#<photoId> #META   renditions +1, maybe primaryRend
```

The `ConditionCheck` closes the gap between reading the `STEM` pointer and writing: the
asset could have been purged in between, and without it the rendition would attach to a
partition that no longer exists. The `deletedAt` half covers the softer version of the
same race — an upload in flight attaching to something trashed a second ago.

Transactions matter most on the failure path. Writing the pointer first and the target
second, non-atomically, means a crash in between leaves an orphan pointer that blocks
that path or stem **forever**, with nothing to reconcile it against. The doubled WCU
cost of a transaction is worth avoiding a class of bug that needs a repair job.

Cleanup needs no index: every pointer an asset owns is derivable from its own partition
— `STEM` from `#META.stem`, `PATH` and `HASH` from each `R#`. So deleting an asset is
still one `Query` followed by one batch of deletes covering items and pointers alike.

### Device config items

Per-device settings, chiefly a default UTC offset for cameras that don't record one.

| | Attribute | Value |
| --- | --- | --- |
| PK | `pk` | `O#<ownerId>#DEVICES` |
| SK | `sk` | `D#<deviceKey>` |

```text
deviceKey     canon|eos r5|042024001234
label         "Canon R5 (Dad's)"        # user-facing, editable
tzOffsetMin   540                       # optional; absent means "no default set"
firstSeenAt   2026-03-02T08:14:00.000Z  # UTC
photoCount    4821                      # maintained by ingest, for the settings UI
```

All of an owner's devices share one partition, so the settings screen is a single
`Query` and a lookup during ingest is a `GetItem`. Ingest caches the whole device map
per warm lambda container — it's a handful of small items, and a batch import would
otherwise re-read it once per photo.

`deviceKey` is derived from EXIF as `<Make>|<Model>|<BodySerialNumber>`, lowercased
with whitespace collapsed, missing components replaced by `-`. The serial matters:
two bodies of the same model can live in different timezones, which is exactly the
case a per-device default exists to solve. Devices are auto-registered on first sight
with no offset set, so they appear in the settings UI ready to configure.

### `F#` facet items

One per label or indexed EXIF attribute. Written by the ingest lambda after
Rekognition / EXIF extraction.

```text
pk           O#01J7X…#M#01K2M9F3QR8VTYA6H0WNXC4B7D
sk           F#LABEL#dog
facetType    LABEL
facetValue   dog
confidence   0.94                        # LABEL only
labelSrc     rekognition | client | manual   # LABEL only; see "Labelling" below
takenAt      2026-07-14T09:22:05.000Z    # denormalised, feeds facet_gsi SK
tzOffsetMin  540                         # denormalised, for grid rendering
thumbs       { 256: {key, iv, bytes} }   # denormalised; ULID-derived, so immutable
encDek / encKeyId                        # needed to decrypt the thumb in the grid
width / height
```

Facet types (closed vocabulary): `LABEL`, `CAMERA` (`<Make> <Model>`), `DEVICE`
(`<deviceKey>`), `REND` (`display|raw|motion|sidecar`), `LENS`, `YEAR`, `ALBUM`,
`FAVOURITE`, `PERSON`.

No `ISO`, `FSTOP` or shutter speed. Browsing by exposure setting is a photographer's
fantasy rather than a real access pattern, and every facet type is a write and an index
entry per photo. They stay inside the encrypted EXIF blob, where they're available for
display on the detail screen without costing anything to store.

`CAMERA` and `DEVICE` look redundant but serve different jobs: `CAMERA` is the
human-facing search facet ("shot on my R5"), `DEVICE` includes the body serial and
exists so that setting a device's default offset can retroactively find and correct
exactly the affected photos (access pattern 7). One extra item per photo buys that.

**`path` is deliberately not denormalised here** — that's what keeps renames O(1)
instead of O(labels).

Labels are written whenever `confidence ≥ 0.55` and filtered at query time, so the
display threshold can be raised or lowered later without re-running inference. The
long tail costs a few extra items per photo, which is the cheaper side of the trade.

Cost: ~13–23 items per photo (10–20 labels + 3 or so derived), ~250 B each. A 100k-photo
library is ~2M items / ~600 MB. Trivial at DynamoDB pricing.

## timeline_gsi (queries 2, 3)

| | Attribute | Value |
| --- | --- | --- |
| PK | `timelinePk` | `O#<ownerId>` |
| SK | `timelineSk` | `<takenAt>#<photoId>` |

Projection: `INCLUDE [thumbs, encDek, encKeyId, width, height, mime, tzOffsetMin,
status]` — enough to render a grid page without a second round-trip, decryption
material included. Note `path` is not projected; the grid doesn't need it, and
excluding it keeps renames cheap.

Written **only on `#META` items**, so the index is sparse and holds exactly one entry
per photo. The `#<photoId>` suffix breaks ties between burst shots sharing a timestamp
and guarantees a total order, which is what makes cursor pagination correct.

Pagination: pass `LastEvaluatedKey` back to the client as an opaque base64 cursor.
Don't expose the key shape — see the sharding note below.

## facet_gsi (queries 4, 5)

| | Attribute | Value |
| --- | --- | --- |
| PK | `facetPk` | `O#<ownerId>#F#<type>#<value>` |
| SK | `facetSk` | `<takenAt>#<photoId>` |

Projection: same `INCLUDE` list.

Written **only on `F#` items**. Because the SK is the timestamp, every facet query is
free-ordered newest-first *and* range-filterable by date — "dogs, newest first" and
"photos from my R5 in July 2026" are the same query with a different `BETWEEN`.

## Rendition grouping

Files whose paths differ only by extension are one asset. `2026/summer/IMG_4021.CR3`
and `2026/summer/IMG_4021.JPG` share the stem `2026/summer/IMG_4021`, so they become
two `R#` items under one `#META` and one entry in the timeline.

Only `#META` items carry timeline_gsi keys, so this falls out of the existing sparse-index
design rather than needing a filter: the timeline literally cannot show a rendition.

### Who decides

The client is the authority on *bytes and metadata* — encryption leaves no choice. It
is deliberately **not** the authority on grouping. The client submits a path; the API
derives the stem and performs the conditional write.

Three reasons the split falls here:

* **Three clients must agree.** Android, web and the home-side sync would each need an
  identical stem rule, and they will drift. One server-side implementation cannot.
* **The client can't see what it doesn't have.** A phone uploading `IMG_4021.JPG` has
  no idea the home server already uploaded `IMG_4021.CR3`. Only the table knows.
* **Concurrency needs a single arbiter.** If clients chose grouping, two simultaneous
  uploads would mint two `photoId`s and produce two assets. The conditional put on the
  `STEM` pointer is what makes the outcome deterministic, and only one writer can hold
  it.

DynamoDB being passive is exactly why this works: the conditional write *is* the
arbiter, so no lock and no coordinator is needed — but something server-side has to
issue it. Clients may still apply the stem rule locally to preview "this will attach to
an existing RAW" in the UI; that's a hint, never the decision.

The client can override rather than predict: an explicit `groupWith: <photoId>` or
`noGroup: true` on upload covers the cases the heuristic gets wrong, and sets
`groupSrc: manual` so a later rescan won't undo it.

### Ingest

For each incoming file: compute the stem, then conditionally put the `STEM` pointer
with `attribute_not_exists(pk)`.

* **Succeeds** — this is a new asset. Mint a `photoId`, write `#META` plus the first
  `R#`.
* **Fails** — an asset already owns this stem. Read the `photoId` from the pointer and
  write only an `R#` into its partition.

The conditional put also settles the race when a RAW and a JPEG upload concurrently:
one creates the asset, the loser re-reads the pointer and attaches. No locking, and the
outcome is identical either way.

Extensions are lowercased for role detection, but stems compare case-sensitively —
`IMG.JPG` and `img.JPG` are different assets, matching how the paths themselves behave.

### Primary rendition

`primaryRend` selects which file supplies display metadata and thumbnails, ranked
`display` (JPEG/HEIC) over `raw`, ties broken by earliest `addedAt`. A RAW that lands
first is primary until its JPEG arrives, then hands over.

The handover is cheap by construction: `photoId` and `takenAt` don't change, so
`timelineSk` is untouched and the asset doesn't move in the timeline. Only `primaryRend`,
`mime` and the dimensions on `#META` are rewritten. Existing thumbnails are kept —
regenerating them from a newly-arrived JPEG is optional polish, not correctness.

### Metadata precedence

`takenAt` is resolved when the asset is created and **later renditions may only improve
it, never replace it** — an arriving rendition re-resolves only if its `takenAtSrc`
outranks the stored one on the ladder above. So a JPEG with real EXIF upgrades an asset
created from an extension-less file that fell back to `file-mtime`, but a second file
with equally good EXIF changes nothing.

This matters because `takenAt` is baked into `timelineSk` and denormalised into every facet
item. Without the rule, a JPEG landing after its RAW would rewrite the whole partition
for no gain.

Facets attach to `#META`, not to renditions — a label describes the image, not the
file — so labelling runs once per asset and there are no duplicate `F#` items to
reconcile.

### What else this catches

Live Photos come along for free: `IMG_4021.HEIC` and `IMG_4021.MOV` share a stem, so
the MOV attaches as a `motion` rendition instead of appearing as a separate video in
the timeline. XMP sidecars likewise land as `sidecar` and stay out of the way.

The `REND` facet type indexes which roles an asset has, so "photos I have RAW for" is
an ordinary facet_gsi query (`F#REND#raw`) rather than a scan.

### Deleting

Deleting a single rendition is a real operation, which is useful: discarding a RAW you
don't want while keeping its JPEG. It needs three cases handled — re-elect
`primaryRend` if the deleted file held it, drop the corresponding `F#REND#…` facet, and
trash the whole asset if it was the last rendition.

Both this and whole-asset deletion are soft; see "Trash and deletion" for what actually
happens to the keys, and for why the pointer items deliberately stay behind.

### Where this can be wrong

Grouping by stem is a heuristic, and it will occasionally be wrong — two genuinely
unrelated images exported as `sunset.jpg` and `sunset.png` into one folder become one
asset. It's rare enough to accept as the default, but it argues for a manual **split**
operation eventually: mint a new `photoId`, move the rendition, re-point `STEM`.
Recording `groupSrc: stem | manual` on `#META` now means a future split has somewhere
to record that it happened, and keeps a later re-scan from silently re-merging.

## Encryption

**Client-side, not SSE.** SSE-S3, SSE-KMS and SSE-C all have AWS perform the
encryption, which means AWS handles plaintext in memory — they protect against a
stolen disk, not against AWS. "AWS never sees raw image data" only holds if the client
encrypts before the bytes leave the device. So: the client generates a random 256-bit
data key (DEK) per photo, encrypts with AES-256-GCM, wraps the DEK with the owner's
master key, and uploads ciphertext to a presigned URL. The wrapped DEK lives on the
`#META` item — safe, because AWS never holds the master key that unwraps it.

The original and each thumbnail share the photo's DEK but get **distinct random IVs**.
Reusing a nonce under one key in GCM is catastrophic, so IVs are generated per object
and stored per object, never derived from anything reusable.

### Key rotation is cheap

Rotation is cheap by construction: rotating the master key
re-wraps `encDek` and nothing else. Image bytes in S3 are never touched — no download,
no re-encrypt, no re-upload, no egress. It's one small DynamoDB write per photo, and
`encKeyId` tells you which photos still hold an old wrapping, so the sweep is resumable
and idempotent.

**The sweep runs on a client, not on a Lambda.** Re-wrapping `encDek` means unwrapping
it with the old master key and re-wrapping with the new one, and the server has never
held either. So it is a client reading each `#META`, re-wrapping, and writing back
through the API — one call per photo, not a background job on the server side. That
distinction matters more than it looks:

* It is interruptible by things the server doesn't control — an app backgrounded, a
  phone that runs out of battery halfway through a 40,000-photo library.
* `encKeyId` as a resume cursor stops being a nicety and becomes the mechanism. A
  restarted sweep queries for photos still on the old version and continues.
* Two clients could start one. That's what `masterKeyVerSeq` below is for.

Rotating a *data* key is the expensive one, but there's no routine reason to.

#### Master key versions

`encKeyId`, `masterKeyVer` and `hashSecretKeyId` all hold the same value: `mk-<n>`,
where `n` is an integer starting at 1.

**The server allocates it**, atomically, by an `ADD 1` on `masterKeyVerSeq` on the
`#SETTINGS` item, returning the new value. The client cannot: the master key is
generated client-side and the server never sees it, so two clients rotating at once
would both pick the same `n` for two *different* keys. `encKeyId` would then be
actively lying — some photos wrapped with one key, some with another, both carrying the
same label, and nothing in the table able to tell them apart. A counter that can
collide is worse than no counter, because it looks trustworthy.

Allocation costs no extra round trip, since rotation already has to POST the new `W#`
items. It also gives the natural place to refuse a rotation while one is in flight —
two concurrent sweeps chase a moving target.

An earlier draft used `mk-<YYYY>-<MM>`. That encoded *data* in an *identifier*, the
same mistake this design already rejects for `photoId` versus `path`, and it collided
on a second rotation in one month — precisely the case an incident produces, when you
revoke one device and then discover a second. The date it was encoding belongs in
`rotatedAt` on the `W#` item, where it is exact rather than month-granular.

### Chunked encryption

Client policy, default threshold **32 MB**. Below it, whole-object GCM
(`encChunkSize: 0`); above it, 1 MiB chunks. Photos sit below and stay simple, RAW
files straddle it, video is always above. Because the value actually used is recorded
per object, the threshold is a pure client decision — it can be retuned any time and
old objects keep working, with nothing server-side aware it exists.

Three properties are non-negotiable here, and hand-rolled streaming AEAD gets the
third one wrong almost every time:

* **No nonce reuse within a DEK**, or GCM fails catastrophically.
* **Chunks can't be relocated** — between files, or within one.
* **Dropping trailing chunks must be detectable.** Otherwise truncation is a silent,
  undetectable attack: a video that simply ends early, an archive missing its tail.

Rather than invent a construction with those properties, adopt one — **Tink's
`AES256_GCM_HKDF_1MB`**, which is the same choice "The crypto format is a published
spec, not a shared library" arrives at from the interoperability side. It satisfies all
three: a per-stream key derived by HKDF from a random salt, a 12-byte nonce built from
a random per-stream prefix, a big-endian segment index and a final-segment flag byte.
The index binds position, the flag makes truncation a decryption failure, and neither
costs an attribute in DynamoDB because both live in the ciphertext.

The exact byte layout is `crypto-format.md`, not this document — segment sizes, the
40-byte header, where the associated data enters, and the plaintext↔ciphertext offset
arithmetic that makes video seeking work. **That spec is authoritative over this
paragraph**, and it is what the conformance vectors test.

One consequence worth carrying here, because it surprises people reading sizes in
`sample-data.md`: the stream header is charged against the first segment, so segment 0
carries slightly less plaintext than the rest, and a file can need one more segment
than dividing by 1 MiB suggests.

### What the server can no longer do

The pipeline inverts. A Lambda cannot read pixels, so everything derived from pixels
moves to the client, at upload time:

* **Thumbnails.** Generated on-device and uploaded encrypted alongside the original.
  Pick the size ladder now — the server can never re-derive a size you didn't upload,
  and adding one later means re-uploading from the originals.
* **EXIF extraction, dimensions, MIME sniffing.** Client-side, submitted as part of
  the metadata write. This is now client-supplied input crossing a trust boundary:
  validate types, clamp ranges, cap the `exif` blob size, and never let it set
  server-controlled fields (`ownerId`, `uploadedAt`, `photoId`).
* **Edge image transforms.** CloudFront serves opaque ciphertext, so no resizing or
  format negotiation at the edge. The client fetches bytes and decrypts, which for the
  web app means a Service Worker or WASM decrypt path in front of the grid.

Ingest therefore becomes: client extracts metadata and thumbs → API call writes the
pending `#META` → presigned PUTs of ciphertext → S3 event marks `status: ready`. The
S3-event Lambda's job shrinks to confirming arrival and sizes; it never opens a file.

### Encrypted EXIF

The perimeter is "AWS may see metadata, not pixels" — but raw EXIF contains precise GPS
for most phone photos, which is arguably more revealing than the pixels. A year of it
is a movement history. So the EXIF blob is encrypted client-side with the asset's DEK,
under its own IV, and stored as `exifEnc`.

What stays in the clear is everything a query actually needs: `takenAt`, the resolved
`tzOffsetMin`, dimensions, MIME, sizes, and the derived facets. The offset ladder runs
on the *client* at ingest and stores only its result, so the GPS-delta trick keeps
working without the coordinates ever reaching the server.

Nothing is lost from search, because no query ever wanted raw coordinates. What is lost
is the ability to derive *new* facets from EXIF later — the same shape of problem as
the thumbnail ladder. If focal-length browsing becomes desirable in a year, the server
can't mine it from data it can't read; a client holding the originals has to
re-submit. **Extract at ingest anything you might plausibly want to index**, because
going back means a pass over the local backup.

A related trap for later: reverse-geocoded `PLACE#Kyoto` facets would be plaintext, and
city-granularity location is most of what encrypting the coordinates was protecting.
Worth deciding deliberately rather than adding it as an obvious feature.

### `contentHash` is HMAC'd

Dedup needs a stable hash of the *plaintext*, but a raw SHA-256 handed to DynamoDB
would let anyone with table access confirm whether you hold a specific known image —
a real leak even under "metadata is visible". Keying it as
`HMAC-SHA256(hashSecret, plaintext)` keeps dedup working exactly as before while
making the value meaningless outside your own library.

The hash secret is a third key, and the constraint that shapes it is that **it must
survive master key rotation unchanged**. Deriving it from the master key is the obvious
move and is a bug: every `HASH#` pointer in the table would become unreachable the
moment the master key rotated, silently breaking dedup and orphaning the purge
tombstones. So it is its own random 256-bit key, generated once by the first client and
wrapped by the master key exactly as a DEK is — `encHashSecret` on `#SETTINGS`, with
`hashSecretKeyId` recording the wrapping version so the rotation sweep picks it up
alongside the photos. The value itself never changes.

The server stores it and never unwraps it, which is why the attribute is written at
enrolment by the client rather than by the bootstrap that creates the settings item.

### Labelling, if Rekognition ever happens

Rekognition needs plaintext and cannot read S3 ciphertext, so using it means a
transient decrypt path — handing bytes to a Lambda that calls Rekognition and persists
nothing. That's a genuine, if narrow, hole in the perimeter, and it's why the decision
can stay open: the schema doesn't care who produced a label. `labelSrc` on each `LABEL`
facet records the producer, so client-side or home-side labelling (the digiKam route,
which keeps the perimeter intact) writes identical items through the same path, and a
later change of engine can find and replace exactly its own output.

If you later decide Rekognition is worth it *and* the transient decrypt isn't, the
fallback is SSE-KMS with your own CMK — AWS-side encryption where Lambda and
Rekognition can read normally. That restores server-side thumbnails and EXIF too, but
it concedes plaintext access to AWS. It's the fork worth being explicit about; this
design takes the other branch.

## Key custody and enrolment

The master key is a random 256-bit key, generated once per owner, which exists only to
wrap per-photo DEKs. It never leaves a client in plaintext. Every route to it — a
phone, a browser, the recovery code — is just a *wrapping*, and they're all one item
type.

### Key wrapping items

| | Attribute | Value |
| --- | --- | --- |
| PK | `pk` | `O#<ownerId>#KEYS` |
| SK | `sk` | `W#<wrapId>` |

```text
wrapId        01K3…                    # ULID
kind          device | passkey | recovery
label         "Pixel 9" | "Firefox on desktop" | "Recovery code"
masterKeyVer  mk-3                     # which master key version this wraps
rotatedAt     2026-03-14T08:02:11.000Z # when that version was minted
wrapAlg       AES-KW | RSA-OAEP-256
wrappedKey    <b64>
credentialId  <b64>                    # passkey only
prfSalt       <b64>                    # passkey only
kdfSalt       <b64>                    # recovery only
kdfParams     { alg: argon2id, m: 64MiB, t: 3, p: 1 }   # recovery only
createdAt / lastUsedAt                 # UTC
```

One partition per owner, so the settings screen lists every enrolled device in a single
`Query`. KDF params are stored per item rather than globally, so they can be raised for
new enrolments without invalidating an existing recovery code.

**Invariant: at least two wrappings exist at all times, and one is the recovery code.**
That's what makes a lost phone or a wiped browser profile an inconvenience rather than
a catastrophe.

### Android

Android Keystore holds a hardware-backed RSA keypair that is non-exportable by
construction, optionally gated behind biometrics. The master key is wrapped to its
public half with RSA-OAEP-256 (`kind: device`). Straightforward, because the platform
provides exactly the primitive needed.

RSA rather than EC only because `wrapAlg` has no ECDH mode in v1 — an EC Keystore key
would be cheaper and better supported, and has nothing to record itself as. See open
question 2.

### Web

The browser has no single store that is both hardware-backed *and* survivable, so the
web client uses three mechanisms, each covering the others' gap.

**1. Steady state — silent unlock.** WebCrypto can generate a key with
`extractable: false`, producing a `CryptoKey` whose raw bytes JavaScript can never
read, and the object itself is structured-cloneable into IndexedDB. So the browser
holds an origin-bound, non-readable unwrapping key that survives reloads. On load it
fetches this browser's `W#` item and unwraps the master key into *another*
non-extractable `CryptoKey`. Since `unwrapKey` can also produce non-extractable DEKs,
raw key material is never a readable JS value at any point in the chain.

**2. Enrolment — passkey PRF.** WebAuthn's `prf` extension returns a stable
per-credential secret from the authenticator: hardware-backed, synced by the platform
passkey store, and — unlike IndexedDB — untouched by clearing site data. Used as a KEK
for a `kind: passkey` wrapping, it makes the web equal to Android: touch to unlock.
Support is broad by now (Chrome, Safari 18+, Firefox) but not universal, hence the
third mechanism. This is also the piece that should help with the SSO-persistence
problem — a passkey outlives an OIDC session by a wide margin.

**3. Fallback — recovery code.** Argon2id over the code yields a KEK that unwraps the
master key. Slow and manual, but it works in any browser, including one you've never
used before, and it's the path when PRF is unavailable or the passkey lives on another
device.

#### Browser floor

Target Firefox, Chrome and Safari, all current. PRF is a property of
browser × OS × authenticator, not of the browser alone, so the constraints are:

* **Firefox ≥ 139** for platform authenticators, **≥ 148** for full correctness
  (Windows Hello on both create and authenticate). This is the highest floor of the
  three and the one to verify per machine — Firefox's PRF path leans on the OS platform
  authenticator, which is thinnest on Linux.
* **Chrome** is the most complete implementation across platforms.
* **Safari** since macOS 15 for platform authenticators. Open WebKit bugs affect PRF
  with CTAP2 security keys on recent versions.

**Hardware security keys are effectively Chrome-only for PRF.** If a YubiKey was ever
part of the plan, it isn't a portable unlock path — design around platform and synced
passkeys (iCloud Keychain, Google Password Manager, Windows Hello).

#### If 1Password is the passkey provider

1Password can hold passkeys, which would normalise behaviour across all three browsers
in one step — attractive given it's already in use. Two things to check first:

1. There is a reported spec-compliance issue with PRF in the 1Password browser
   extension. Verify it produces stable, correct PRF output before depending on it,
   because a provider that silently returns different bytes locks you out of your own
   library.
2. More importantly: if 1Password holds *both* the PRF passkey **and** the recovery
   code, the two-wrapping invariant is satisfied on paper but not in practice — both
   routes sit behind one vault, and the library inherits exactly its blast radius.
   Keeping the recovery code somewhere genuinely independent restores the property the
   invariant is there to provide.

In practice: first visit in a browser goes through passkey or recovery code, then
generates a non-extractable device key and writes a new `W#` item. Every later visit is
silent.

**Storage eviction is expected, not exceptional.** Browsers evict IndexedDB under
storage pressure and wipe it with site data. Call `navigator.storage.persist()`, but
design for it to fail — eviction simply drops that browser back to the enrolment path.
It is never data loss, because of the rule that makes this safe: **the browser's device
key must never be the only wrapping of the master key.**

**Service worker.** Since the grid fetches ciphertext, a service worker intercepting
image requests and decrypting in flight is the natural design — it keeps `<img src>`
working unchanged. Service workers are killed and restarted freely, so the SW must be
able to re-unwrap silently on every start, which is exactly what the non-extractable
IndexedDB key provides. Never persist the unwrapped master key; always re-derive it.

### Recovery code

**26 Crockford base32 characters: 125 bits of entropy, plus a check symbol**, grouped
for transcription (`XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`). **Mandatory at enrolment** —
signup is not complete until the user has confirmed the code back, since there is no
support path afterwards.

125 bits rather than a round 128 is a consequence of generating the code one character
at a time instead of bit-packing a random integer, and it is not a security question.
The margin is absurd either way: strip Argon2id out entirely, assume an ASIC farm at
10^14 guesses a second, and 2^125 still takes 10^15 years. Chasing the round number
costs something real — 128 bits needs 26 characters, which hold 130, so you are packing
128 bits into a 130-bit space and three quarters of the possible final characters become
unreachable. Implementations then disagree about whether a code is malformed, which is
precisely the drift the conformance vectors exist to prevent.

**The 26th character is a check symbol instead**, which is worth far more than three
bits of entropy. It catches every single-character error and every length error locally
and instantly — before the client spends a second on Argon2id and comes back with
"that didn't work". In a system with no support path that distinction matters: "you
typed it wrong" is a different message from "this is the wrong code, or your library is
unrecoverable", and without a checksum the client cannot tell them apart. It also gives
the enrolment confirmation step something to check against short of a full unwrap.

It is not a security boundary — an uncaught error just falls through to the KDF and
fails there, as it would anyway. `crypto-format.md` fixes the check symbol's
computation and the normalisation rules, which matter more than the bit count:
uppercase, strip the hyphens, and map `I`/`L`→`1` and `O`→`0`, so that a code
transcribed by hand still unwraps.

Two copies: 1Password, and the OS keychain on the laptops — with the laptops backed up,
and the 1Password vault itself backed up to those laptops. The copies aren't fully
independent (a laptop typically has the vault on it too), but the redundancy is real
and the failure that matters — losing one device — is covered several times over.

Beyond that this is the user's responsibility, and deliberately so. There is no
support path, no reset link and no recovery on our side; that's what "AWS never sees
the key" costs, and pretending otherwise would be worse than saying it plainly.

**The independent photo copy is the real recovery story**, though, and it changes the
threat model more than the code does. An unencrypted copy of every photo outside this
system, itself backed up, means losing the master key stops being catastrophic: the
photos still exist, and what's lost is the cloud copy's usefulness, not the archive.
It's what keeps the archive from being hostage to one AWS account, and — see the
storage section — it's what makes changing the thumbnail ladder possible at all.

That copy is a design assumption, not a nice-to-have. Several decisions here lean on
it: key loss being survivable, derivative regeneration being feasible, and the archive
outliving the AWS account.

### Revocation

Deleting a `W#` item stops that device from unwrapping *future* master key versions,
but a device that already unwrapped the master key still knows it. Real revocation is
therefore a master key rotation: generate a new one, re-wrap every photo's `encDek`,
write fresh `W#` items for the remaining wrappings, delete the revoked one.

Cheap in the sense established above — no S3 traffic, no re-encryption of image bytes —
but it is one DynamoDB write per photo, driven by a client that holds both the old and
new master keys, with `encKeyId` as the resume cursor. See "Key rotation is cheap" for
why the server cannot run it.

## Establishing `takenAt`

`timelineSk` is built from `takenAt`, and a sort key can't be null, so every photo needs a
timestamp even when EXIF has none. First hit wins; the winner is recorded in
`takenAtSrc`:

| | Source | `takenAtSrc` |
| --- | --- | --- |
| 1 | EXIF `DateTimeOriginal` | `exif` |
| 2 | Client filesystem mtime, supplied at upload | `file-mtime` |
| 3 | S3 object `LastModified` | `s3-mtime` |
| 4 | `uploadedAt` | `upload` |

Rung 2 does the work that "fall back to S3 `LastModified`" implies. Since the client
encrypts before upload, the S3 object is created at upload time — its `LastModified` is
`uploadedAt` wearing a different hat, and a photo from 2009 would land in the timeline
as if taken today. The original file's mtime is knowable only on the device, so the
client reads it and sends it with the metadata write. Like the rest of the client's
metadata, it's untrusted: reject timestamps in the future or before ~1990.

Rung 3 only differs from rung 4 for objects that arrive in S3 without going through the
client API — a bulk backfill, or a home-side sync pushing directly. Worth
keeping distinct for exactly that path.

Rungs 2–4 are absolute instants (epoch-based), so `takenAt` is exact even when it's a
fallback; only the *display* offset is a guess. Note that a photo resolved this way has
no EXIF, so the offset ladder below skips straight to rung 4 or lower.

The UI should render anything other than `exif` as an approximate date. Ordering stays
total and pagination stays correct regardless, which is the point of never leaving it
null.

## Resolving the UTC offset at ingest

Since `takenAt` is stored in UTC but EXIF `DateTimeOriginal` is naive local time, the
ingest lambda resolves an offset. Priority order, first hit wins, and the winning rung
is recorded in `tzSrc`:

| | Source | `tzSrc` |
| --- | --- | --- |
| 1 | Upload-supplied offset, `offsetMode=force` | `upload-forced` |
| 2 | EXIF `OffsetTimeOriginal` (2.31+, most phones and recent cameras) | `exif-offset` |
| 3 | GPS delta | `gps` |
| 4 | Upload-supplied offset, `offsetMode=fallback` (the default) | `upload` |
| 5 | Device default, from the device config item | `device` |
| 6 | Owner's `homeTz`, resolved against the photo's local date | `owner-default` |
| 7 | Assume UTC | `assumed-utc` |

**GPS delta** (rung 3): `GPSDateStamp`/`GPSTimeStamp` are recorded in UTC, so
`DateTimeOriginal − GPS UTC`, rounded to the nearest 15 minutes, recovers the offset.
Covers most geotagged photos predating EXIF 2.31.

### Upload-supplied offset

The call that mints the presigned upload URL accepts an optional `tzOffsetMin` plus an
`offsetMode` of `fallback` (default) or `force`. Both are stashed on the pending
`#META` item (`status: processing`) for the ingest lambda to pick up from the S3 event.

The two modes exist because the parameter has two quite different uses, and collapsing
them into one priority slot makes one of them a footgun:

* *"This batch is from my old camera that has no clock zone"* — a hint, and correct
  EXIF should still beat it. That's `fallback`, and it's the default precisely so a
  bulk upload can't silently clobber good per-file EXIF with one batch-wide guess.
* *"My camera's clock was set wrong for this whole trip"* — an override, and it must
  beat EXIF, because the EXIF is what's wrong. That's `force`.

### Device defaults

Rung 5 reads the device config item keyed by the photo's `deviceKey`. It sits *below*
EXIF and GPS because it's a standing guess about a body, not evidence about a frame,
but *above* the owner default because it's the more specific of the two guesses — which
is the point when a DSLR lives at home in one zone and a phone travels.

Devices are auto-registered on first sight with no offset, so configuring one is a
matter of filling in a device that's already listed rather than typing an identifier.

### Retroactive correction

Because `tzSrc` records which rung won, setting or changing a device default can
correct precisely the photos that were guessed: query `facet_gsi` for
`O#<owner>#F#DEVICE#<deviceKey>` (access pattern 7), keep the ones whose `tzSrc` is
`device`, `owner-default` or `assumed-utc`, and leave anything resolved from `exif-offset`,
`gps` or an explicit upload override alone. No library-wide reprocessing, and no risk
of trampling a better answer that was already found.

Note this rewrites `takenAt`, and therefore `timelineSk` and every affected photo's facet
items — see the last known limitation below. It's bounded to one partition per photo
and should run as a batched background job, not inline in the settings request.

## Interoperability

Scripts and tools in other languages — Python, Go, whatever a home server runs — must be able
to import and export. Two consequences.

### Third parties use the API, not the datastore

Tempting to hand a script AWS credentials and let it write DynamoDB directly. Don't.
Identity assignment, stem grouping, pointer transactions and the trash rules are server
logic, and a second implementation of them will drift from the first — the same
argument that put grouping on the server rather than the client. Direct datastore
access also means distributing credentials that can read every partition.

So there is one HTTP API, and a reference CLI over it. An importer is then a script
that authenticates, encrypts locally, and PUTs to presigned URLs — the same path the
Android app takes, with no privileged position.

### The crypto format is a published spec, not a shared library

**Kotlin Multiplatform is the wrong tool here.** It lets one Kotlin codebase target
JVM, native, JS and WASM, which would cover Android plus a Kotlin web client — but it
does nothing for Python or Go. Sharing code only helps the platforms your language
reaches, and the requirement is broader than that.

The portable answer is a written format specification that any language can implement,
which reinforces the choice already made in "Chunked encryption": **Tink's
`AES256_GCM_HKDF_1MB` framing**. Tink ships Java, Python, Go, C++ and Obj-C
implementations, so Android, a home-side Python importer and a Go CLI all get a
correct implementation without writing crypto. Only the browser hand-rolls it over
WebCrypto, because Tink's JavaScript implementation is unmaintained.

That specification is `crypto-format.md`. **It is authoritative over this document
wherever the two describe bytes** — this one argues for properties, that one fixes the
layout, and where prose here drifts from the spec it is this document that is wrong.

**Commit conformance test vectors generated from Tink.** Every implementation decrypts
the same fixtures in its own test suite. Without that, format drift surfaces years
later as a photo nobody can open — and no amount of shared Kotlin would have covered
the Python case anyway.

## Trash and deletion

Deleting is soft by default, with a retention window (`trashRetentionDays` on owner
settings, default 30) after which a sweep purges for real.

### Trashing reuses timeline_gsi

Soft-delete doesn't add an index or a filter. It **rewrites `timelinePk`**:

| State | `timelinePk` | `timelineSk` |
| --- | --- | --- |
| live | `O#<ownerId>` | `<takenAt>#<photoId>` |
| trashed | `O#<ownerId>#TRASH` | `<deletedAt>#<photoId>` |

An item has exactly one `timelinePk` value, so it is in the timeline or in the trash and
never both — the mutual exclusion is structural rather than enforced by a filter. The
timeline query is completely unchanged, and a trashed asset disappears from it the
instant the write lands.

The trash listing is then the same query against a different partition, newest-deleted
first. Sorting by `deletedAt` also makes the purge sweep a range query: everything with
`timelineSk < now − retention` is due.

Facet items get `facetPk` and `facetSk` **removed** rather than rewritten, since there's
no useful "search the trash by label" case. Removing the attributes drops them out of
facet_gsi entirely — the sparse-index property again.

```text
# on trash
UPDATE #META  SET timelinePk = O#<owner>#TRASH,
                  timelineSk = <deletedAt>#<photoId>,
                  deletedAt = <now>, deletedBy = <deviceLabel>
UPDATE F#…     REMOVE facetPk, facetSk        # one write per facet item
```

Restore is the exact inverse, recomputing `timelineSk` from the still-present `takenAt`.
Both directions cost one write per facet item — the same order as the `takenAt`
correction already described, bounded to one partition, and rare.

### Pointers stay put

`STEM`, `PATH` and `HASH` pointers are **not** removed on trash. The path stays
reserved, which makes restore trivial and gives a re-upload to the same path a clear
"that path is in the trash" error rather than a silent collision on restore.

The `HASH` pointer needs more care. A re-imported file whose hash matches a *trashed*
asset must not silently resurrect it — a home-side sync pushing the whole local library
would otherwise undelete the entire trash in one pass, which is a spectacular way to
lose a deletion. So a hash hit on a trashed asset is a conflict, and re-adding requires
explicit intent: a `reAddDeleted` flag on the upload call, defaulting to **false**. A
person re-adding one photo passes it; a bulk sync never does.

### Purge tombstones

That guard has a hole with a 30-day fuse. The common case — delete from the archive,
keep the file on the phone — works fine while the asset is in the trash, because the
`HASH` pointer is still there to be recognised. Once the purge removes it, the next
sync sees an unknown file and dutifully re-uploads it. The photo comes back a month
after being deleted, which is precisely the behaviour deletion is supposed to prevent.

So **purge does not delete the `HASH` pointer, it converts it to a tombstone**:

```text
pk    O#<ownerId>#HASH#<hmac>
sk    #PTR
kind  purged            # was: live, with photoId + renditionId
purgedAt  <UTC>
expiresAt 1818640811    # epoch seconds, the TTL attribute; retention past the
                        # LAST attempt, not past purgedAt
blockedAttempts 3       # re-uploads refused, across trash and purge
lastAttemptAt   <UTC>   # each attempt bumps these and pushes expiresAt out
lastAttemptBy   "home-server"
```

Ingest treats a tombstone hit as "deliberately deleted, skip" unless `reAddDeleted` is
set — the same flag, covering both the trashed and purged cases. Silent *on the wire*,
since an automated sync has nowhere useful to put an error, but never silent in the
library: the attempt is recorded and surfaced. See below.

`PATH` and `STEM` pointers *are* deleted on purge. The asymmetry is deliberate: a path
is a name and should become reusable, while a hash is content identity and is exactly
what you don't want back.

#### Tombstones expire, and blocked attempts are surfaced

A tombstone kept forever is a permanent record that specific bytes passed through this
library. The hash is HMAC'd, so table access alone reveals only that deletions happened
and when — an attacker cannot test a candidate image against it. The exposure is
*deferred*: if the `hashSecret` is ever obtained, every tombstone becomes a confirmable
record of past possession. That is a real cost to carry indefinitely for a guard whose
job is finished once the source file is gone.

So tombstones expire, on a **DynamoDB TTL** — which fits cleanly here and nowhere else
in this design, because a tombstone has no S3 object behind it, so expiring one leaves
nothing orphaned. `tombstoneRetentionDays` on owner settings, default 365.

What makes the expiry safe is that **the user is warned before it can hurt them**:

* Every blocked re-upload bumps `blockedAttempts`, `lastAttemptAt` and
  `lastAttemptBy` on the pointer — during the trash window and after purge alike. It's
  a write to an item that already exists.
* The trash UI surfaces it: *"3 attempts to re-upload this from home-server — delete it
  there too, or it returns."* A silent skip is the correct wire behaviour and the wrong
  product behaviour; the user is the only one who can remove the source, and they can't
  act on something they're never told about.
* **A blocked attempt refreshes the TTL.** This is what makes a fixed window
  unnecessary: while a source keeps offering the file, the tombstone keeps living;
  once the source goes quiet, it expires after the retention window and the record is
  gone. The tombstone lasts exactly as long as it is doing something.

The honest gap: the warning only fires if something *tries* to re-upload. A copy on a
laptop that is offline for two years, or an SD card in a drawer, produces no attempt, no
warning, and no TTL refresh — so the photo can still return when that source eventually
reappears. That case is unavoidable, since nothing here can see a disk it is never
shown, and it is the same class of problem as the independent photo copy being outside
the system by design.

An explicit per-photo **"forget entirely"** remains the escape hatch for someone who
wants the record gone now and knows they have dealt with the source.

The device keeps its own local tombstone too, so a phone doesn't re-offer a file it
just deleted even before the server sees it. The server-side record is the durable
backstop that survives app reinstalls and covers other devices holding the same file.

### Purging

A scheduled sweep (EventBridge → Lambda) queries the trash partition for expired
assets and, per asset:

1. `DeleteObjects` the originals — every rendition's `s3Key`.
2. `DeleteObjects` the thumbnails — every size in `thumbs`.
3. `Query` the partition and `BatchWriteItem` delete `#META`, `R#` and `F#` items.
4. Delete the `STEM` and `PATH` pointers, and convert each `HASH` pointer to a
   tombstone — all reconstructible from step 3's results.

**Not DynamoDB TTL.** TTL would handle step 3 alone and leave the S3 objects orphaned
and billing forever. Routing TTL deletions through Streams to a cleanup Lambda would
work, but adds a moving part to gain nothing: TTL fires up to 48 hours late, and the
sweep needs to batch S3 deletes (1,000 keys per call) regardless. An explicit scheduled
job is easier to reason about, resumable, and auditable.

### Renditions

Deleting a single rendition is soft by the same rules, with `deletedAt` on the `R#`
item and its `PATH`/`HASH` pointers retained. It does not trash the asset;
`primaryRend` re-elects among the survivors and `F#REND#raw` is removed so the asset
stops matching "photos I have RAW for".

Soft is right here for the same reason as everywhere else: **deletion is about removing
unwanted images, not reclaiming space.** Retention costs 30 days of storage on files
you'd already decided you don't want, which is cheap next to discarding a RAW by
mistake. An explicit **purge now** exists for the case where space genuinely is the
motive, but it's the exception rather than the reason the feature exists.

### What trash costs

Trashed originals keep billing at full rate for the retention window, Intelligent-
Tiering monitoring included. On a 400 GB library with steady turnover that's small, but
it isn't free, and "empty trash" should be reachable rather than buried.

### Interaction with ingest

The `ConditionCheck` when attaching a rendition must also assert the asset isn't
trashed, otherwise an upload in flight can attach to something deleted a second ago:

```text
ConditionCheck  #META   cond: attribute_exists(pk) AND attribute_not_exists(deletedAt)
```

## S3 layout and storage tiering

Two buckets, not one:

| Bucket | Holds | Storage class |
| --- | --- | --- |
| `…-originals` | encrypted originals, `raw/<ownerId>/<photoId>/<renditionId>` | Intelligent-Tiering |
| `…-derived` | encrypted thumbnails, `th/<ownerId>/<photoId>/<size>` | Standard |

Your instinct to keep them separable is the right one, and buckets rather than prefixes
buy more than lifecycle scoping: independent CloudFront behaviours (thumbnails are
small, hot and cacheable for a year; originals are large and rarely re-fetched),
independent replication targets for a home-side sync, separate metrics, and the ability
to empty and rebuild the derived bucket wholesale without touching a single original.

The bucket is recorded per object in `s3Bucket` and in each `thumbs` entry rather than
resolved from config, so a future bucket move is a background item rewrite — the same
shape of job as key rotation, though this one can run server-side, since it touches no
keys — instead of a flag day.

### Tiering

Auto-tiering is fine for originals, and better than a fixed Glacier Instant Retrieval
lifecycle rule: Intelligent-Tiering's Archive Instant tier lands at the same
$0.004/GB/month as GIR, but it also promotes objects back to frequent access when you
start browsing an old year, which a one-way lifecycle rule won't. Monitoring runs about
$0.0025 per 1,000 objects per month — roughly $0.25/month at 100k photos, which is
noise against the storage saving.

**Do not put thumbnails in Intelligent-Tiering.** This is the size threshold from
the one that matters: objects under 128 KB are never transitioned to a lower tier, so a 256px
thumbnail in an Intelligent-Tiering bucket pays frequent-access rates forever and gains
nothing. Glacier classes have the same 128 KB minimum billable size, so a 15 KB
thumbnail there would be billed as 128 KB. Derivatives belong in Standard; they're
small, and they're what almost every request actually hits.

Keeping thumbnails hot is therefore not really a decision to defer — it's what the
pricing already forces. The decision you *can* revisit is the ladder itself, and the
separate bucket is what keeps that cheap.

### Thumbnail ladder: 256, 1024, 2048

Longest edge, aspect preserved — 4032×3024 becomes 256×192, portrait is the transpose.
Square grid crops come from CSS `object-fit`, so no separate square variant is needed.

I'd argue for a third rung rather than 256/1024, for two reasons:

**Device pixel ratio.** A dense phone grid at 3× DPR needs ~390 device pixels per
column, and a desktop justified grid at 2× needs ~400 for a 200px row — so 256 is a
low-bandwidth and instant-paint placeholder, not the grid image. 1024 is the grid.

**Cold originals.** This is where tiering feeds back into the ladder: if originals sleep
in Archive Instant, every full-resolution detail view pays a retrieval charge to wake
one. A desktop retina lightbox wants ~2048–3200 device pixels, which 1024 doesn't
cover, so without a 2048 rung the detail view either looks soft or hits Glacier
pricing on every photo you look at. 2048 makes the original a genuine on-demand
rarity — export, edit, download — rather than part of normal browsing.

Cost is roughly 15 KB + 150 KB + 350 KB ≈ 0.5 MB of derivatives per photo, so 100k
photos is ~50 GB in Standard alongside ~400 GB of tiered originals. Worth checking
against your Google One comparison, since derivatives in Standard are the one line item
that doesn't get cheaper with age.

### Changing the ladder later

Because thumbnails are client-generated, adding a rung is *not* a server-side reprocess
— no Lambda can read the pixels. It requires a client that holds the originals to
regenerate and re-upload. The laptop-local backup is what makes this tractable: a
one-off batch job against local plaintext files, rather than downloading 400 GB back
out of S3 and paying egress for the privilege.

Worth stating as a rule: **any future change to derivatives is a local-backup job.**
That's an argument for keeping that backup genuinely complete and current, and it's a
second reason — beyond lock-out avoidance — that it isn't optional.

## Known limitations

**No multi-facet intersection.** "Dogs AND shot on a Canon" needs two facet_gsi queries
intersected. Pragmatic answer: query the more selective facet, then filter in the
lambda against the photo's other facets. If real AND/OR/NOT search becomes a
requirement, that's the point to put an OpenSearch Serverless collection or
S3+Athena alongside DynamoDB — not to add more GSIs.

**One partition per owner on timeline_gsi.** A single timeline_gsi PK value lives on one physical
partition, capped at ~1000 WCU / 3000 RCU. Reads are fine; a bulk import of 100k
photos for one owner serialises to roughly 40 minutes. Since each deployment serves one
household, this is close to theoretical — it bounds the initial import and nothing
else. If it ever mattered, time-bucket the key — `timelinePk = O#<ownerId>#<YYYY>` — and
walk buckets descending, since reads are already time-ordered. Keeping cursors opaque
now means that change won't break clients later.

**Sorting is by UTC instant, not local wall-clock.** Photos taken at 9am in Tokyo and
9am in London are three hours apart in the timeline. This is the correct behaviour for
a continuous trip narrative and the wrong one for "my mornings", and it's baked into
`timelineSk`. `tzOffsetMin` is projected into both GSIs so the UI can still *display*
local time and group by local day within a page.

**Correcting `takenAt` rewrites the photo's facet items**, since they denormalise it
for `facetSk`. Rare, bounded to one partition, and doable in a single transaction.

## Open questions

1. **There is no ECDH key-wrapping mode, and that may cost us StrongBox.** `wrapAlg` is
   `AES-KW | RSA-OAEP-256`, so an Android device must enrol an RSA-3072 Keystore key.

   The concern is not elegance. **StrongBox** — the dedicated secure element on Pixel 3
   and later — supports a deliberately narrow algorithm set. EC P-256 is universally
   available in it; larger RSA sizes are not guaranteed. If RSA-3072 won't generate in
   StrongBox on target devices, requiring it means falling back to the TEE, trading a
   hardware isolation guarantee for an algorithm convenience — the wrong direction for
   the one key everything else hangs off. Secondary: RSA-3072 keygen in secure hardware
   is slow enough to be felt, and it lands in the first-run enrolment flow.

   It isn't a one-line addition to the enum, because **ECDH is key agreement, not key
   transport**. Wrapping to an EC public key means ECDH-ES: an ephemeral keypair, ECDH
   against the enrolled public key, HKDF into a KEK, then AES-KW as usual. The ephemeral
   public key has to be stored, so the `W#` item grows an `epk` attribute (or carries it
   prefixed inside `wrappedKey`) — a schema change, which is why this is a question
   rather than an oversight.

   Deferring is safe: `wrapAlg` is recorded per item, so adding ECDH-ES later is a new
   value rather than a break, and a mixed RSA/EC fleet is harmless.

   **What decides it is a measurement, not an argument** — whether StrongBox will take
   an RSA-3072 key on real target hardware, and how long it takes when it does. Plan
   step 2.4a runs it on a phone. If StrongBox refuses, ECDH-ES stops being optional and
   the schema question (`epk`, curve, HKDF info string) has to be settled *before*
   Android enrols its first device, because a fleet enrolled on RSA is a fleet that has
   to be re-enrolled.
