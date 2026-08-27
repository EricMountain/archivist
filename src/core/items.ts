// Item shapes for the `archivist-media` table, mirroring docs/design/design.md and
// docs/design/sample-data.md byte-for-byte. If you're changing an attribute name or
// adding an item type, the design doc and the sample data change in the same commit
// — see "Keep the sample data in sync" in CLAUDE.md.

/** Closed facet vocabulary. No ISO or FSTOP — deliberately removed. */
export type FacetType =
  | "LABEL"
  | "CAMERA"
  | "DEVICE"
  | "REND"
  | "LENS"
  | "YEAR"
  | "ALBUM"
  | "FAVOURITE"
  | "PERSON";

export type RenditionRole = "display" | "raw" | "motion" | "sidecar";

export type TakenAtSrc = "exif" | "file-mtime" | "s3-mtime" | "upload";

export type TzSrc =
  | "upload-forced"
  | "exif-offset"
  | "gps"
  | "upload"
  | "device"
  | "owner-default"
  | "assumed-utc";

export type AssetStatus = "processing" | "ready" | "failed";

export type GroupSrc = "stem" | "manual";

export type LabelSrc = "rekognition" | "client" | "manual";

export type WrapKind = "device" | "passkey" | "recovery";

export type MembershipRole = "owner" | "editor" | "viewer";

/** A thumbnail entry, keyed by longest-edge size (256 | 1024 | 2048). */
export interface ThumbEntry {
  bucket: string;
  key: string;
  iv: string;
  bytes: number;
}

export type ThumbMap = Record<number, ThumbEntry>;

// ---------------------------------------------------------------------------
// Media partition items
// ---------------------------------------------------------------------------

export interface MetaItem {
  pk: string;
  sk: "#META";
  ownerId: string;
  photoId: string;
  stem: string;
  primaryRend?: string;
  renditions: number;
  mime: string;
  width: number;
  height: number;
  enc: "AES-256-GCM";
  encDek: string;
  encKeyId: string;
  takenAt: string;
  tzOffsetMin: number;
  tzSrc: TzSrc;
  deviceKey?: string;
  takenAtSrc: TakenAtSrc;
  uploadedAt: string;
  thumbs: ThumbMap;
  exifEnc?: string;
  exifIv?: string;
  groupSrc: GroupSrc;
  status: AssetStatus;
  deletedAt?: string;
  deletedBy?: string;
  // Present only via timeline_gsi's projection, or once written by a mutation —
  // see "Trashing reuses timeline_gsi" in design.md.
  timelinePk?: string;
  timelineSk?: string;
}

export interface RenditionItem {
  pk: string;
  sk: `R#${string}`;
  renditionId: string;
  role: RenditionRole;
  path: string;
  ext: string;
  mime: string;
  s3Bucket: string;
  s3Key: string;
  contentHash: string;
  bytes: number;
  plainBytes: number;
  width: number;
  height: number;
  /** Whole-object mode only. Absent when `encChunkSize > 0` — the streaming format
   * carries its salt and nonce prefix in the ciphertext header. See
   * `docs/design/crypto-format.md`. */
  encIv?: string;
  /** `0` = whole-object AES-256-GCM; `1048576` = 1 MiB streaming segments. */
  encChunkSize: number;
  addedAt: string;
  deletedAt?: string;
}

export interface FacetItem {
  pk: string;
  sk: `F#${string}`;
  facetType: FacetType;
  facetValue: string;
  confidence?: number;
  labelSrc?: LabelSrc;
  takenAt: string;
  tzOffsetMin: number;
  thumbs: ThumbMap;
  encDek: string;
  encKeyId: string;
  width: number;
  height: number;
  // Removed on trash, restored on un-trash — see "Trashing reuses timeline_gsi".
  facetPk?: string;
  facetSk?: string;
}

export type MediaItem = MetaItem | RenditionItem | FacetItem;

/** The `INCLUDE` projection shared by timeline_gsi and facet_gsi — see
 * `local.grid_projection` in terraform/locals.tf. Enough to paint a grid cell
 * without a second read; deliberately excludes `path`, `photoId`, `facetType` and
 * `facetValue`, none of which are index or projected attributes. */
export interface GridProjectionFields {
  thumbs: ThumbMap;
  encDek: string;
  encKeyId: string;
  width: number;
  height: number;
  mime: string;
  tzOffsetMin: number;
  status: AssetStatus;
}

/** A timeline_gsi query result. `photoId` isn't projected — recover it from `pk`
 * with `photoIdFromMediaPk`. */
export interface TimelineEntry extends GridProjectionFields {
  pk: string;
  sk: "#META";
  timelinePk: string;
  timelineSk: string;
}

/** A facet_gsi query result. `facetType`/`facetValue` aren't projected — recover
 * them from `facetPk` with `parseFacetGsiPk`; `photoId` from `pk` as above. */
export interface FacetEntry extends GridProjectionFields {
  pk: string;
  sk: `F#${string}`;
  facetPk: string;
  facetSk: string;
}

export function isMetaItem(item: MediaItem): item is MetaItem {
  return item.sk === "#META";
}

export function isRenditionItem(item: MediaItem): item is RenditionItem {
  return item.sk.startsWith("R#");
}

export function isFacetItem(item: MediaItem): item is FacetItem {
  return item.sk.startsWith("F#");
}

// ---------------------------------------------------------------------------
// Pointer items
// ---------------------------------------------------------------------------

export interface LivePointerItem {
  pk: string;
  sk: "#PTR";
  kind?: "live";
  photoId: string;
  renditionId?: string;
  /** Re-uploads this pointer has refused. Recorded so the trash UI can tell the user
   * a source still holds the file — they are the only one who can delete it. */
  blockedAttempts?: number;
  lastAttemptAt?: string;
  lastAttemptBy?: string;
}

export interface PurgedHashPointerItem {
  pk: string;
  sk: "#PTR";
  kind: "purged";
  purgedAt: string;
  /** DynamoDB TTL, epoch **seconds**. Set to `tombstoneRetentionDays` past the last
   * blocked attempt, not past `purgedAt` — every refusal pushes it out, so a tombstone
   * lives exactly as long as something keeps offering the file back. */
  expiresAt: number;
  blockedAttempts?: number;
  lastAttemptAt?: string;
  lastAttemptBy?: string;
}

export type HashPointerItem = LivePointerItem | PurgedHashPointerItem;
export type PointerItem = LivePointerItem | HashPointerItem;

export function isPurgedPointer(item: PointerItem): item is PurgedHashPointerItem {
  return "kind" in item && item.kind === "purged";
}

// ---------------------------------------------------------------------------
// Device config
// ---------------------------------------------------------------------------

export interface DeviceItem {
  pk: string;
  sk: `D#${string}`;
  deviceKey: string;
  label: string;
  tzOffsetMin?: number;
  firstSeenAt: string;
  photoCount: number;
}

// ---------------------------------------------------------------------------
// Key wrapping
// ---------------------------------------------------------------------------

export interface KeyWrapItem {
  pk: string;
  sk: `W#${string}`;
  wrapId: string;
  kind: WrapKind;
  label: string;
  /** `mk-<n>`, allocated by the server from `masterKeyVerSeq`. */
  masterKeyVer: string;
  /** When this master key version was minted. */
  rotatedAt?: string;
  wrapAlg: "AES-KW" | "RSA-OAEP-256" | "ECDH-ES+AES-KW";
  wrappedKey: string;
  /** `wrapAlg: ECDH-ES+AES-KW` only — the ephemeral public key ECDH is key
   * *agreement*, not key *transport*, so unlike the other wrapAlgs the recipient's
   * static key alone isn't enough to unwrap; see "Master key wrapping" in
   * crypto-format.md. */
  epk?: string;
  credentialId?: string;
  prfSalt?: string;
  kdfSalt?: string;
  kdfParams?: { alg: "argon2id"; m: string; t: number; p: number };
  createdAt: string;
  lastUsedAt?: string;
}

// ---------------------------------------------------------------------------
// Users and owners
// ---------------------------------------------------------------------------

export interface UserItem {
  pk: string;
  sk: "#PROFILE";
  userId: string;
  email?: string;
  displayName: string;
  createdAt: string;
}

export interface MembershipItem {
  pk: string;
  sk: `M#${string}`;
  ownerId: string;
  role: MembershipRole;
}

export interface OwnerSettingsItem {
  pk: string;
  sk: "#SETTINGS";
  ownerId: string;
  displayName: string;
  homeTz: string;
  trashRetentionDays: number;
  /** TTL window for purge tombstones, in days. Default 365. */
  tombstoneRetentionDays: number;
  /** The owner's `contentHash` HMAC key, wrapped by the master key. Written by the
   * first client at enrolment, so absent on a freshly bootstrapped owner; the server
   * never unwraps it. Deliberately not derived from the master key — see
   * "`contentHash` is HMAC'd" in design.md. */
  encHashSecret?: string;
  /** Which master key version wrapped `encHashSecret`; the rotation sweep's cursor. */
  hashSecretKeyId?: string;
  /** Allocator for master key versions. An atomic `ADD 1` yields the `n` in `mk-<n>`.
   * Server-owned: a client must never mint a version itself, or two concurrent
   * rotations label two different keys the same. Absent until first enrolment. */
  masterKeyVerSeq?: number;
  /** When the current `masterKeyVerSeq` was minted, set atomically alongside it by
   * `POST /keys/version`. Read back by `POST /keys` and stamped onto every `W#`
   * item of that version — like `masterKeyVer` itself, never client-supplied. */
  rotatedAt?: string;
  createdAt: string;
}

export interface IdpPointerItem {
  pk: string;
  sk: "#PTR";
  userId: string;
}
