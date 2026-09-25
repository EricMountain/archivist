// POST /uploads — the stem handshake and presigned URLs. Plan step 1.9, "the
// most intricate step here". Client metadata is untrusted throughout; every
// server-controlled field (ownerId, photoId, uploadedAt) is derived here, never
// accepted from the body.
import { UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "@archivist/core/errors";
import { ddb, tableName } from "@archivist/core/db";
import { mediaPk, metaSk } from "@archivist/core/keys";
import { isUlid, newUlid } from "@archivist/core/ids";
import { toIsoUtc } from "@archivist/core/time";
import { resolveRole, roleOutranks, stemFromPath, takenAtSrcOutranks } from "@archivist/core/paths";
import {
  derivedBucket,
  originalKey,
  originalsBucket,
  presignPut,
  previewKey,
  thumbKey,
} from "@archivist/core/s3";
import { attachRendition, createAsset } from "@archivist/core/repo/ingest";
import { upsertDeviceSighting } from "@archivist/core/repo/devices";
import { getMetaItem, getRenditionItems } from "@archivist/core/repo/media";
import {
  getHashPointer,
  getStemPointer,
  recordBlockedHashAttempt,
} from "@archivist/core/repo/pointers";
import { getOwnerSettings } from "@archivist/core/repo/identity";
import { restoreAsset } from "@archivist/core/repo/trash";
import { epochSecondsAfterDays } from "@archivist/core/time";
import { isPurgedPointer } from "@archivist/core/items";
import type {
  MetaItem,
  PreviewEntry,
  RenditionItem,
  TakenAtSrc,
  ThumbEntry,
  TzSrc,
} from "@archivist/core/items";
import { ok, parseJsonBody } from "../http";
import type { ApiRequest, ApiResponse, RouteHandler } from "../http";

export const THUMB_SIZES = [256, 1024, 2048] as const;
export type ThumbSize = (typeof THUMB_SIZES)[number];

export interface ThumbDescriptor {
  bytes: number;
  iv: string;
}

export type ThumbDescriptorMap = Partial<Record<`${ThumbSize}`, ThumbDescriptor>>;

/** Same shape as a still's descriptor — the ciphertext size and IV — for the video
 * preview clip. Upper bound is generous headroom over the design's ~2 MB target (a
 * 60 s clip at 200-300 kbps), so a client bug can't presign an unbounded object. */
export type PreviewDescriptor = ThumbDescriptor;
export const MAX_PREVIEW_BYTES = 16 * 1024 * 1024;

export function validatePreviewDescriptor(preview: PreviewDescriptor | undefined): void {
  if (preview === undefined) return;
  if (!preview || typeof preview.iv !== "string" || preview.iv.length === 0) {
    throw ApiError.validation("preview.iv is required");
  }
  if (!Number.isFinite(preview.bytes) || preview.bytes <= 0 || preview.bytes > MAX_PREVIEW_BYTES) {
    throw ApiError.validation(`preview.bytes must be between 1 and ${MAX_PREVIEW_BYTES}`);
  }
}

interface UploadBody {
  path: string;
  plainBytes: number;
  bytes: number;
  mime: string;
  width: number;
  height: number;
  contentHash: string;
  takenAt: string;
  takenAtSrc: TakenAtSrc;
  tzOffsetMin: number;
  tzSrc: TzSrc;
  deviceKey?: string;
  exifEnc?: string;
  exifIv?: string;
  encDek: string;
  encKeyId: string;
  encIv: string;
  encChunkSize: number;
  thumbs?: ThumbDescriptorMap;
  /** Video preview clip descriptor — optional, and only ever sent for a video. Handled
   * exactly like `thumbs` (persisted on create, on an attach that becomes primary, and
   * on resume), except it never gates readiness. */
  preview?: PreviewDescriptor;
  reAddDeleted?: boolean;
  groupWith?: string;
  noGroup?: boolean;
  /** Client-minted candidate photoId (a ULID). Every object that isn't the bare
   * original rendition — thumbnails, the EXIF blob, and the original itself once it's
   * streamed — is encrypted client-side under an AAD that embeds `photoId`
   * (`crypto-format.md`), and that encryption has to happen before this call returns
   * whatever ID the server would otherwise mint. So the client picks one up front and
   * this call uses it *when creating a new asset* — see the response's `created` flag.
   * When this upload instead attaches to an existing asset, the candidate is discarded
   * in favour of the existing photoId/DEK (echoed back as `encDek`/`encKeyId`), and
   * whatever the client pre-encrypted against its candidate is simply never persisted
   * — except thumbnails when the attach also becomes primary; see `attachAndRespond`'s
   * `becomesPrimary` handling below. */
  photoId?: string;
}

const MIN_TAKEN_AT = Date.UTC(1990, 0, 1);
const FUTURE_SKEW_MS = 5 * 60 * 1000;
const MAX_EXIF_BLOB_CHARS = 300_000;
const MAX_DIMENSION = 20_000;

function clamp(n: number, min: number, max: number): number {
  return Math.min(Math.max(n, min), max);
}

function validate(body: UploadBody): void {
  if (!body.path || typeof body.path !== "string") {
    throw ApiError.validation("path is required");
  }
  if (!body.contentHash || typeof body.contentHash !== "string") {
    throw ApiError.validation("contentHash is required");
  }
  if (!body.mime || typeof body.mime !== "string") {
    throw ApiError.validation("mime is required");
  }
  if (!Number.isFinite(body.plainBytes) || body.plainBytes <= 0) {
    throw ApiError.validation("plainBytes must be a positive number");
  }
  if (!Number.isFinite(body.bytes) || body.bytes <= 0) {
    throw ApiError.validation("bytes must be a positive number");
  }
  if (!body.encDek || !body.encKeyId) {
    throw ApiError.validation("encDek and encKeyId are required");
  }
  if (body.encChunkSize === undefined || body.encChunkSize < 0) {
    throw ApiError.validation("encChunkSize must be 0 or a positive chunk size");
  }
  // encIv is whole-object-mode only (crypto-format.md: the streaming format carries
  // its salt/nonce prefix in the ciphertext header instead) — required exactly when
  // encChunkSize says whole-object, never required otherwise.
  if (body.encChunkSize === 0 && !body.encIv) {
    throw ApiError.validation("encIv is required for whole-object mode (encChunkSize: 0)");
  }
  const takenAtMs = Date.parse(body.takenAt);
  if (!Number.isFinite(takenAtMs)) {
    throw ApiError.validation("takenAt is not a valid timestamp");
  }
  if (takenAtMs < MIN_TAKEN_AT || takenAtMs > Date.now() + FUTURE_SKEW_MS) {
    throw ApiError.validation("takenAt is outside the plausible range");
  }
  if (body.exifEnc && body.exifEnc.length > MAX_EXIF_BLOB_CHARS) {
    throw ApiError.validation("exifEnc exceeds the maximum size");
  }
  if (body.photoId !== undefined && !isUlid(body.photoId)) {
    throw ApiError.validation("photoId must be a ULID");
  }
  validatePreviewDescriptor(body.preview);
}

interface BuildRenditionArgs {
  renditionId: string;
  role: RenditionItem["role"];
  ownerId: string;
  photoId: string;
  path: string;
  ext: string;
  mime: string;
  width: number;
  height: number;
  body: UploadBody;
  addedAt: string;
}

function buildRendition(args: BuildRenditionArgs): Omit<RenditionItem, "pk" | "sk"> {
  return {
    renditionId: args.renditionId,
    role: args.role,
    path: args.path,
    ext: args.ext,
    mime: args.mime,
    s3Bucket: originalsBucket(),
    s3Key: originalKey(args.ownerId, args.photoId, args.renditionId),
    contentHash: args.body.contentHash,
    bytes: args.body.bytes,
    plainBytes: args.body.plainBytes,
    width: args.width,
    height: args.height,
    encIv: args.body.encIv,
    encChunkSize: args.body.encChunkSize,
    addedAt: args.addedAt,
  };
}

/** Presigns a fresh PUT URL for each descriptor entry against this asset's
 * deterministic thumbnail keys ([thumbKey] depends only on ownerId/photoId/size, so
 * repairing or replacing a thumbnail never orphans the old object — it's overwritten
 * in place) and builds the [ThumbEntry] map [setThumbs] persists. Shared by the
 * create/attach/resume branches above and by `postPhotoThumbs` (`routes/photos.ts`) —
 * the latter is the same "presign, then persist once the client confirms" shape, just
 * without a rendition upload alongside it. */
export async function presignedThumbs(
  ownerId: string,
  photoId: string,
  descriptors: ThumbDescriptorMap | undefined,
): Promise<{ thumbs: Record<number, ThumbEntry>; uploads: Record<number, string> }> {
  const thumbs: Record<number, ThumbEntry> = {};
  const uploads: Record<number, string> = {};
  for (const size of THUMB_SIZES) {
    const descriptor = descriptors?.[`${size}`];
    if (!descriptor) continue;
    const key = thumbKey(ownerId, photoId, size);
    thumbs[size] = { bucket: derivedBucket(), key, iv: descriptor.iv, bytes: descriptor.bytes };
    uploads[size] = await presignPut(derivedBucket(), key);
  }
  return { thumbs, uploads };
}

/** The preview counterpart of [presignedThumbs]: a deterministic key
 * ([previewKey]) plus the [PreviewEntry] [setPreview] persists. `undefined` for both
 * when the client sent no descriptor. */
export async function presignedPreview(
  ownerId: string,
  photoId: string,
  descriptor: PreviewDescriptor | undefined,
): Promise<{ preview?: PreviewEntry; upload?: string }> {
  if (!descriptor) return {};
  const key = previewKey(ownerId, photoId);
  return {
    preview: { bucket: derivedBucket(), key, iv: descriptor.iv, bytes: descriptor.bytes },
    upload: await presignPut(derivedBucket(), key),
  };
}

async function primaryRoleOf(
  ownerId: string,
  existing: MetaItem,
): Promise<RenditionItem["role"] | undefined> {
  if (!existing.primaryRend) return undefined;
  const renditions = await getRenditionItems(ownerId, existing.photoId);
  return renditions.find((r) => r.renditionId === existing.primaryRend)?.role;
}

export const postUpload: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const body = parseJsonBody<UploadBody>(req);
  validate(body);

  const width = clamp(body.width, 1, MAX_DIMENSION);
  const height = clamp(body.height, 1, MAX_DIMENSION);
  const takenAt = toIsoUtc(new Date(body.takenAt));
  const uploadedAt = toIsoUtc(new Date());
  const { stem, ext } = stemFromPath(body.path);

  // 2. Hash check. The nominal "three outcomes" (absent / live / purged) hide a
  // fourth in the live case: a live pointer whose asset is currently trashed is
  // a conflict, not an ordinary duplicate — see "Pointers stay put" in
  // design.md. That's also the one place besides a tombstone where a refused
  // re-upload gets recorded, since the trash window is when the warning is
  // most useful to the user.
  const attemptBy = body.deviceKey ?? "unknown device";
  const hashPtr = await getHashPointer(ownerId, body.contentHash);
  const isTombstone = !!hashPtr && isPurgedPointer(hashPtr);

  if (hashPtr && !isTombstone) {
    const target = await getMetaItem(ownerId, hashPtr.photoId);
    if (target?.deletedAt) {
      if (body.reAddDeleted) {
        await restoreAsset(ownerId, hashPtr.photoId);
        return ok({ photoId: hashPtr.photoId, renditionId: hashPtr.renditionId, restored: true });
      }
      await recordBlockedHashAttempt(ownerId, body.contentHash, attemptBy, uploadedAt);
      return ok({ photoId: hashPtr.photoId, renditionId: hashPtr.renditionId, duplicate: true, trashed: true });
    }
    // The hash/stem pointers and the #META/#R# items commit in one transaction
    // *before* any ciphertext is sent (see "Ingest" in design.md) — so a rendition
    // can be a live hash-duplicate while its bytes never actually arrived (the
    // client died mid-PUT: a process kill or a lost connection, exactly what plan
    // step 2.10's "Done when" tests). A bare `duplicate: true` would leave that
    // asset stuck in `processing` forever, since it carries no presigned URL to
    // retry with. Re-presigning instead — same deterministic S3 keys, cheap — lets
    // whoever holds these exact bytes (almost certainly the same device, resuming)
    // finish the job. Harmless even for a genuine coincidental dedup from a
    // different caller: same plaintext, so identical ciphertext once encrypted
    // under the returned encDek, and thumbs get re-recorded from *this* call's
    // descriptors same as a fresh create would.
    //
    // `status !== "ready"` (not `=== "processing"`): the S3-event Lambda (step
    // 1.10) also sets `failed` on a declared/actual size mismatch, and a failed
    // asset is exactly as stuck as a processing one — nothing else ever moves it
    // off `failed`, so treating it as a bare duplicate here would strand it
    // permanently (the DDB record exists, the object doesn't, GET /media/raw
    // 403s forever). Found live: a corrupted/incomplete PUT flips status to
    // failed, and every subsequent upload attempt with the same content hash
    // then short-circuited as a duplicate instead of retrying.
    if (target && target.status !== "ready" && hashPtr.renditionId) {
      return resumeUpload(ownerId, hashPtr.photoId, hashPtr.renditionId, body, target);
    }
    return ok({ photoId: hashPtr.photoId, renditionId: hashPtr.renditionId, duplicate: true });
  }

  if (isTombstone && !body.reAddDeleted) {
    const settings = await getOwnerSettings(ownerId);
    const tombstoneRetentionDays = settings?.tombstoneRetentionDays ?? 365;
    await recordBlockedHashAttempt(
      ownerId,
      body.contentHash,
      attemptBy,
      uploadedAt,
      epochSecondsAfterDays(uploadedAt, tombstoneRetentionDays),
    );
    // Deliberately quiet on the wire — see "Purge tombstones" in design.md.
    return ok({ skipped: true });
  }

  const renditionId = newUlid();

  // Explicit override: attach to a named asset regardless of stem.
  if (body.groupWith) {
    const existing = await getMetaItem(ownerId, body.groupWith);
    if (!existing) throw ApiError.validation("groupWith does not name an existing asset");
    return attachAndRespond({
      ownerId,
      existing,
      renditionId,
      ext,
      body,
      width,
      height,
      takenAt,
      uploadedAt,
      isTombstone,
      newGroupSrc: "manual",
    });
  }

  // 3. Stem resolution: try creating a new asset first. noGroup forces a unique
  // effective stem so this branch always wins.
  const effectiveStem = body.noGroup ? `${stem} ${renditionId}` : stem;
  const candidatePhotoId = body.photoId ?? newUlid();
  const role = resolveRole(ext, undefined);

  try {
    await createAsset({
      stem: effectiveStem,
      path: body.path,
      hmac: body.contentHash,
      overwriteHashTombstone: isTombstone,
      meta: {
        ownerId,
        photoId: candidatePhotoId,
        stem: effectiveStem,
        primaryRend: renditionId,
        renditions: 1,
        mime: body.mime,
        width,
        height,
        enc: "AES-256-GCM",
        encDek: body.encDek,
        encKeyId: body.encKeyId,
        takenAt,
        tzOffsetMin: body.tzOffsetMin,
        tzSrc: body.tzSrc,
        takenAtSrc: body.takenAtSrc,
        uploadedAt,
        thumbs: {},
        groupSrc: body.noGroup ? "manual" : "stem",
        status: "processing",
        ...(body.deviceKey ? { deviceKey: body.deviceKey } : {}),
        ...(body.exifEnc ? { exifEnc: body.exifEnc } : {}),
        ...(body.exifIv ? { exifIv: body.exifIv } : {}),
      },
      rendition: buildRendition({
        renditionId,
        role,
        ownerId,
        photoId: candidatePhotoId,
        path: body.path,
        ext,
        mime: body.mime,
        width,
        height,
        body,
        addedAt: uploadedAt,
      }),
    });

    const { thumbs, uploads } = await presignedThumbs(ownerId, candidatePhotoId, body.thumbs);
    // The #META item was written with thumbs: {} above; fill it in with a plain
    // update once presigning has produced real keys. Cheap and idempotent.
    if (Object.keys(thumbs).length > 0) {
      await setThumbs(ownerId, candidatePhotoId, thumbs);
    }
    const previewResult = await presignedPreview(ownerId, candidatePhotoId, body.preview);
    if (previewResult.preview) await setPreview(ownerId, candidatePhotoId, previewResult.preview);
    // Plan step 2.14: "devices are auto-registered on first sight" (design.md) — once
    // per new asset, not per rendition attach, so a RAW+JPEG pair counts as one photo.
    // Best-effort, same as setThumbs above: outside the create transaction, non-fatal
    // to the upload if it somehow failed.
    if (body.deviceKey) {
      await upsertDeviceSighting(ownerId, body.deviceKey);
    }

    return ok({
      photoId: candidatePhotoId,
      renditionId,
      // created: true tells the client its candidate photoId/DEK (see UploadBody.photoId)
      // was actually used, so whatever it pre-encrypted against them is valid to upload
      // as-is. encDek/encKeyId are echoed back for symmetry with the attach branch below
      // rather than because the client doesn't already know them here.
      created: true,
      encDek: body.encDek,
      encKeyId: body.encKeyId,
      originalUpload: {
        url: await presignPut(
          originalsBucket(),
          originalKey(ownerId, candidatePhotoId, renditionId),
          { storageClass: "INTELLIGENT_TIERING" },
        ),
      },
      thumbUploads: uploads,
      ...(previewResult.upload ? { previewUpload: previewResult.upload } : {}),
    });
  } catch (err) {
    if (!(err instanceof ApiError && err.code === "CONFLICT") || body.noGroup) throw err;
    // Fell through: another asset already owns this stem — attach instead.
  }

  const stemPtr = await getStemPointer(ownerId, stem);
  if (!stemPtr) {
    throw ApiError.conflict("upload conflicted but no existing asset was found for its stem");
  }
  const existing = await getMetaItem(ownerId, stemPtr.photoId);
  if (!existing) {
    throw ApiError.conflict("asset referenced by its stem pointer is missing");
  }

  return attachAndRespond({
    ownerId,
    existing,
    renditionId,
    ext,
    body,
    width,
    height,
    takenAt,
    uploadedAt,
    isTombstone,
  });
};

interface AttachAndRespondArgs {
  ownerId: string;
  existing: MetaItem;
  renditionId: string;
  ext: string;
  body: UploadBody;
  width: number;
  height: number;
  takenAt: string;
  uploadedAt: string;
  isTombstone: boolean;
  newGroupSrc?: "manual";
}

async function attachAndRespond(args: AttachAndRespondArgs): Promise<ApiResponse> {
  const { ownerId, existing, renditionId, ext, body, width, height, takenAt, uploadedAt } = args;

  const role = resolveRole(ext, existing.mime);
  const currentPrimaryRole = await primaryRoleOf(ownerId, existing);
  const becomesPrimary =
    currentPrimaryRole === undefined || roleOutranks(role, currentPrimaryRole);

  const improvesTakenAt = takenAtSrcOutranks(body.takenAtSrc, existing.takenAtSrc);

  await attachRendition({
    ownerId,
    photoId: existing.photoId,
    path: body.path,
    hmac: body.contentHash,
    overwriteHashTombstone: args.isTombstone,
    rendition: buildRendition({
      renditionId,
      role,
      ownerId,
      photoId: existing.photoId,
      path: body.path,
      ext,
      mime: body.mime,
      width,
      height,
      body,
      addedAt: uploadedAt,
    }),
    ...(becomesPrimary
      ? {
          newPrimaryRend: renditionId,
          newMime: body.mime,
          newWidth: width,
          newHeight: height,
          newStatus: "processing" as const,
        }
      : {}),
    ...(improvesTakenAt
      ? {
          takenAtImprovement: {
            takenAt,
            tzOffsetMin: body.tzOffsetMin,
            tzSrc: body.tzSrc,
            takenAtSrc: body.takenAtSrc,
          },
        }
      : {}),
    ...(args.newGroupSrc ? { newGroupSrc: args.newGroupSrc } : {}),
  });

  const { thumbs, uploads } = await presignedThumbs(ownerId, existing.photoId, body.thumbs);
  // Closes the gap flagged in plan step 1.9/2.10's STATUS.md notes: an attach
  // that becomes primary used to leave #META.thumbs pointing at the *previous*
  // primary rendition's thumbnails (or, if that rendition never had any —
  // e.g. a RAW file the client can't decode — at nothing at all), forever,
  // since nothing here ever wrote fresh ones. Only safe to persist when this
  // rendition actually becomes primary: otherwise these thumbs are encrypted
  // under a different (correct, still-referenced) rendition's descriptors and
  // must not overwrite what #META.thumbs already points at.
  if (becomesPrimary && Object.keys(thumbs).length > 0) {
    await setThumbs(ownerId, existing.photoId, thumbs);
  }
  // Same rule for the preview: only a primary rendition's preview may replace what
  // #META.preview points at, and a non-primary attach must not PUT one at all.
  const previewResult = becomesPrimary
    ? await presignedPreview(ownerId, existing.photoId, body.preview)
    : {};
  if (previewResult.preview) await setPreview(ownerId, existing.photoId, previewResult.preview);

  return ok({
    photoId: existing.photoId,
    renditionId,
    // created: false tells the client its candidate photoId/DEK were discarded in
    // favour of the existing asset's — whatever it pre-encrypted against its own
    // candidate (thumbnails, exifEnc) is bound to the wrong AAD/key and must not be
    // uploaded. encDek/encKeyId are the *existing* asset's, wrapped under the same
    // master key version the client already holds — unwrap and re-encrypt the original
    // rendition against these before streaming it to originalUpload.url. becomesPrimary
    // tells the client it's now both safe and necessary to also PUT to thumbUploads —
    // #META.thumbs was just persisted to match, above.
    created: false,
    becomesPrimary,
    encDek: existing.encDek,
    encKeyId: existing.encKeyId,
    originalUpload: {
      url: await presignPut(
        originalsBucket(),
        originalKey(ownerId, existing.photoId, renditionId),
        { storageClass: "INTELLIGENT_TIERING" },
      ),
    },
    thumbUploads: uploads,
    ...(previewResult.upload ? { previewUpload: previewResult.upload } : {}),
  });
}

// Re-presigns an already-committed-but-still-processing rendition's upload URLs —
// see the `target.status === "processing"` branch in postUpload above. Nothing
// about identity (photoId/renditionId/hash+stem pointers/encDek) changes here;
// only thumbs are re-recorded, from this call's fresh descriptors, same as the
// create path does on a first attempt.
async function resumeUpload(
  ownerId: string,
  photoId: string,
  renditionId: string,
  body: UploadBody,
  target: MetaItem,
): Promise<ApiResponse> {
  const { thumbs, uploads } = await presignedThumbs(ownerId, photoId, body.thumbs);
  if (Object.keys(thumbs).length > 0) {
    await setThumbs(ownerId, photoId, thumbs);
  }
  const previewResult = await presignedPreview(ownerId, photoId, body.preview);
  if (previewResult.preview) await setPreview(ownerId, photoId, previewResult.preview);

  // The R# item's own encIv/encChunkSize were fixed by whichever attempt's
  // createAsset/attachRendition transaction actually committed, and are never
  // rewritten afterwards (unlike #META.thumbs above) — a resuming client MUST
  // reuse them bit-for-bit rather than generating a fresh IV, or the ciphertext it
  // PUTs won't match what this item already (and permanently) claims decrypts it.
  const existingRendition = (await getRenditionItems(ownerId, photoId)).find(
    (r) => r.renditionId === renditionId,
  );

  return ok({
    photoId,
    renditionId,
    resumed: true,
    created: false,
    encDek: target.encDek,
    encKeyId: target.encKeyId,
    encIv: existingRendition?.encIv,
    encChunkSize: existingRendition?.encChunkSize,
    originalUpload: {
      url: await presignPut(originalsBucket(), originalKey(ownerId, photoId, renditionId), {
        storageClass: "INTELLIGENT_TIERING",
      }),
    },
    thumbUploads: uploads,
    ...(previewResult.upload ? { previewUpload: previewResult.upload } : {}),
  });
}

// Fills in thumbs on the just-created #META item. Kept out of the create
// transaction because the thumbnail S3 keys depend on photoId, which the
// transaction itself mints.
export async function setThumbs(
  ownerId: string,
  photoId: string,
  thumbs: Record<number, ThumbEntry>,
): Promise<void> {
  await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: mediaPk(ownerId, photoId), sk: metaSk() },
      UpdateExpression: "SET thumbs = :thumbs",
      ExpressionAttributeValues: { ":thumbs": thumbs },
    }),
  );
}

/** Persists the video preview's descriptor on `#META`, the counterpart of
 * [setThumbs] — same "keys depend on photoId, so it's a follow-up update rather than
 * part of the create transaction" reasoning. */
export async function setPreview(
  ownerId: string,
  photoId: string,
  preview: PreviewEntry,
): Promise<void> {
  await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: mediaPk(ownerId, photoId), sk: metaSk() },
      UpdateExpression: "SET preview = :preview",
      ExpressionAttributeValues: { ":preview": preview },
    }),
  );
}
