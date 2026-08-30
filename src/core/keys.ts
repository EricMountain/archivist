// The one module that constructs every DynamoDB key. Nothing else in the codebase
// concatenates a `#`. See "Key encoding" in docs/design/design.md.
//
// The rule: the variable-length, user-controlled field always goes last in a
// composite key. Values are never encoded or escaped — a path or facet value may
// contain `#` freely, because parsing (where it happens at all) is split-on-known-
// prefix, never split-on-all.

const SEP = "#";

// ---------------------------------------------------------------------------
// Owner / user / identity
// ---------------------------------------------------------------------------

/** `O#<ownerId>` — the owner's root pk. Also the settings item's pk and the live
 * timeline_gsi partition. */
export function ownerPk(ownerId: string): string {
  return `O${SEP}${ownerId}`;
}

/** sk of the one `#SETTINGS` item on an owner's root partition. */
export function settingsSk(): string {
  return "#SETTINGS";
}

/** `U#<userId>` — a user's root pk. */
export function userPk(userId: string): string {
  return `U${SEP}${userId}`;
}

/** sk of the one `#PROFILE` item on a user's partition. */
export function profileSk(): string {
  return "#PROFILE";
}

/** sk of a membership item on a user's partition: `M#<ownerId>`. */
export function membershipSk(ownerId: string): string {
  return `M${SEP}${ownerId}`;
}

/** `IDP#<issuer>#<subject>` — the one pointer not scoped to an owner, since at
 * login there is no owner yet. Subject goes last: it's the user-controlled field. */
export function idpPtrPk(issuer: string, subject: string): string {
  return `IDP${SEP}${issuer}${SEP}${subject}`;
}

// ---------------------------------------------------------------------------
// Media partition: pk `O#<ownerId>#M#<photoId>`, sk `#META` | `R#<id>` | `F#<t>#<v>`
// ---------------------------------------------------------------------------

/** `O#<ownerId>#M#<photoId>` — every item belonging to one photo shares this pk. */
export function mediaPk(ownerId: string, photoId: string): string {
  return `O${SEP}${ownerId}${SEP}M${SEP}${photoId}`;
}

/** sk of the one `#META` item in a media partition. Sorts before `F#` and `R#`,
 * so a forward query on the partition always returns metadata first. */
export function metaSk(): string {
  return "#META";
}

/** sk of an `R#` rendition item: `R#<renditionId>`. */
export function renditionSk(renditionId: string): string {
  return `R${SEP}${renditionId}`;
}

/** sk of an `F#` facet item: `F#<type>#<value>`. `value` goes last — it's the
 * user/EXIF-controlled field and may contain `#`. */
export function facetSk(type: string, value: string): string {
  return `F${SEP}${type}${SEP}${value}`;
}

// ---------------------------------------------------------------------------
// Owner-scoped collections: devices, key wrappings
// ---------------------------------------------------------------------------

/** `O#<ownerId>#DEVICES` — pk of the owner's device-config collection. */
export function devicesPk(ownerId: string): string {
  return `O${SEP}${ownerId}${SEP}DEVICES`;
}

/** sk of a device item: `D#<deviceKey>`. */
export function deviceSk(deviceKey: string): string {
  return `D${SEP}${deviceKey}`;
}

/** `O#<ownerId>#KEYS` — pk of the owner's key-wrapping collection. */
export function keysPk(ownerId: string): string {
  return `O${SEP}${ownerId}${SEP}KEYS`;
}

/** sk of a key-wrapping item: `W#<wrapId>`. */
export function keyWrapSk(wrapId: string): string {
  return `W${SEP}${wrapId}`;
}

// ---------------------------------------------------------------------------
// Pointer items — sparse, scoped under an owner (except IDP, above).
// ---------------------------------------------------------------------------

/** sk of any pointer item's payload, regardless of which pointer type. */
export function ptrSk(): string {
  return "#PTR";
}

/** `O#<ownerId>#STEM#<stem>` — resolves a path stem to the asset that owns it. */
export function stemPtrPk(ownerId: string, stem: string): string {
  return `O${SEP}${ownerId}${SEP}STEM${SEP}${stem}`;
}

/** `O#<ownerId>#PATH#<path>` — resolves a full path to a rendition. */
export function pathPtrPk(ownerId: string, path: string): string {
  return `O${SEP}${ownerId}${SEP}PATH${SEP}${path}`;
}

/** `O#<ownerId>#HASH#<hmac>` — resolves a content HMAC to a rendition, or to a
 * purge tombstone. `hmac` is `HMAC-SHA256(hashSecret, plaintext)`, never a raw
 * hash — see "`contentHash` is HMAC'd" in design.md. */
export function hashPtrPk(ownerId: string, hmac: string): string {
  return `O${SEP}${ownerId}${SEP}HASH${SEP}${hmac}`;
}

// ---------------------------------------------------------------------------
// timeline_gsi (queries 2, 3, 11, 12) — sparse, written only on #META items.
// ---------------------------------------------------------------------------

/** `timelinePk` for a live (non-trashed) asset: the owner's root pk, reused. */
export function timelineGsi1Pk(ownerId: string): string {
  return ownerPk(ownerId);
}

/** `timelinePk` for a trashed asset — a distinct partition of the same index, so
 * an asset is in the timeline or the trash, never both. */
export function trashGsi1Pk(ownerId: string): string {
  return `${ownerPk(ownerId)}${SEP}TRASH`;
}

/** The sort key shared by timeline_gsi and facet_gsi: `<timestamp>#<id>`. Fixed
 * width and immutable, so pagination stays total even across a timestamp tie —
 * see A6/A7 in sample-data.md. `timestamp` must already be `toIsoUtc`-formatted. */
export function sortKey(timestamp: string, id: string): string {
  return `${timestamp}${SEP}${id}`;
}

// ---------------------------------------------------------------------------
// facet_gsi (queries 4, 5, 7, 8) — sparse, written only on F# items.
// ---------------------------------------------------------------------------

/** `facetPk`: `O#<ownerId>#F#<type>#<value>`. */
export function facetGsiPk(ownerId: string, type: string, value: string): string {
  return `O${SEP}${ownerId}${SEP}F${SEP}${type}${SEP}${value}`;
}

// ---------------------------------------------------------------------------
// Owner registry — one sparse partition, `REGISTRY#OWNERS`, holding one small
// item per owner. Every other partition in this design is only reachable if you
// already know its ownerId; the purge sweep (plan step 1.13) is the one job that
// has to visit *every* owner, so it needs a way to enumerate them without a full
// table Scan. Written once, in the same transaction as bootstrapUser.
// ---------------------------------------------------------------------------

export function ownerRegistryPk(): string {
  return "REGISTRY#OWNERS";
}

export function ownerRegistrySk(ownerId: string): string {
  return ownerPk(ownerId);
}

// ---------------------------------------------------------------------------
// Parsing — needed because both GSIs are INCLUDE-projected (see grid_projection
// in terraform/locals.tf) and therefore don't carry photoId, facetType or
// facetValue as their own attributes. Every GSI result still carries pk and
// facetPk, which already encode them.
// ---------------------------------------------------------------------------

/** Recovers `photoId` from a media partition's own pk: `O#<owner>#M#<photoId>`. */
export function photoIdFromMediaPk(pk: string): string {
  const marker = `${SEP}M${SEP}`;
  const i = pk.lastIndexOf(marker);
  if (i === -1) {
    throw new Error(`not a media pk: ${pk}`);
  }
  return pk.slice(i + marker.length);
}

/** Recovers `{ type, value }` from a facet_gsi `facetPk`:
 * `O#<owner>#F#<type>#<value>`. `value` may itself contain `#`, so only the first
 * two segments after `F#` are split off. */
export function parseFacetGsiPk(facetPk: string): { type: string; value: string } {
  const marker = `${SEP}F${SEP}`;
  const i = facetPk.indexOf(marker);
  if (i === -1) {
    throw new Error(`not a facet_gsi pk: ${facetPk}`);
  }
  const rest = facetPk.slice(i + marker.length);
  const sep = rest.indexOf(SEP);
  if (sep === -1) {
    throw new Error(`malformed facet_gsi pk: ${facetPk}`);
  }
  return { type: rest.slice(0, sep), value: rest.slice(sep + 1) };
}

/** Inverse of `sortKey`: recovers `{ timestamp, id }` from a `timelineSk` or
 * `facetSk` value. `timestamp` is fixed-width `toIsoUtc` output and never
 * contains `#`, so the first separator is always the boundary — unlike
 * `parseFacetGsiPk`'s `value`, `id` (a ULID) can't contain `#` either, but even
 * if a caller passed something stranger, splitting on the *first* `#` is still
 * correct here since `timestamp` is always exactly one field. */
export function parseSortKey(sk: string): { timestamp: string; id: string } {
  const i = sk.indexOf(SEP);
  if (i === -1) {
    throw new Error(`malformed sort key: ${sk}`);
  }
  return { timestamp: sk.slice(0, i), id: sk.slice(i + 1) };
}
