// Plan step 1.12 (continued) — rename and single-rendition deletion. Both are
// bounded to one partition, per the "Deleting" and pointer sections of design.md.
import { TransactWriteCommand, type TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, RESERVED_ATTRS, tableName } from "../db";
import { facetSk, hashPtrPk, mediaPk, metaSk, pathPtrPk, ptrSk, renditionSk } from "../keys";
import { roleOutranks } from "../paths";
import { getAssetPartition } from "./media";
import { trashAsset } from "./trash";

/** Rename one file: delete the old PATH pointer, conditionally put the new one,
 * update the rendition's own path. Three items, atomic, no S3 traffic — facets,
 * both GSIs and every thumbnail are untouched. */
export async function renameRendition(
  ownerId: string,
  photoId: string,
  renditionId: string,
  oldPath: string,
  newPath: string,
): Promise<void> {
  const pk = mediaPk(ownerId, photoId);
  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Delete: {
        TableName: tableName(),
        Key: { pk: pathPtrPk(ownerId, oldPath), sk: ptrSk() },
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: { pk: pathPtrPk(ownerId, newPath), sk: ptrSk(), photoId, renditionId },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: renditionSk(renditionId) },
        UpdateExpression: `SET ${RESERVED_ATTRS["path"]} = :path`,
        ExpressionAttributeNames: { [RESERVED_ATTRS["path"]!]: "path" },
        ExpressionAttributeValues: { ":path": newPath },
      },
    },
  ];

  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    if (err instanceof Error && err.name === "TransactionCanceledException") {
      throw ApiError.conflict("the new path is already in use");
    }
    throw err;
  }
}

export interface DeleteRenditionResult {
  /** True when this was the asset's last live rendition, so the whole asset was
   * trashed rather than just this file. */
  assetTrashed: boolean;
}

/** Soft-deletes a single rendition: re-elects primaryRend among the survivors,
 * drops the F#REND#<role> facet if no surviving rendition shares that role, and
 * trashes the whole asset if this was the last one. See "Renditions" and
 * "Deleting" in design.md. */
export async function deleteRendition(
  ownerId: string,
  photoId: string,
  renditionId: string,
  deletedAt: string,
  deletedBy: string,
): Promise<DeleteRenditionResult> {
  const { meta, renditions, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) throw ApiError.notFound("asset not found");

  const target = renditions.find((r) => r.renditionId === renditionId);
  if (!target) throw ApiError.notFound("rendition not found");
  if (target.deletedAt) throw ApiError.conflict("rendition is already deleted");

  const survivors = renditions.filter((r) => r.renditionId !== renditionId && !r.deletedAt);
  const pk = mediaPk(ownerId, photoId);

  if (survivors.length === 0) {
    // Last rendition: mark it deleted and trash the whole asset.
    await ddb().send(
      new TransactWriteCommand({
        TransactItems: [
          {
            Update: {
              TableName: tableName(),
              Key: { pk, sk: renditionSk(renditionId) },
              UpdateExpression: "SET deletedAt = :now",
              ExpressionAttributeValues: { ":now": deletedAt },
            },
          },
        ],
      }),
    );
    await trashAsset(ownerId, photoId, deletedAt, deletedBy);
    return { assetTrashed: true };
  }

  // One Update on #META, whatever combination of "decrement renditions" and
  // "re-elect primaryRend" applies — DynamoDB transactions reject two operations
  // on the same item, so these can never be split across separate Update entries.
  const metaSetParts = ["renditions = renditions - :one"];
  const metaValues: Record<string, unknown> = { ":one": 1 };
  if (meta.primaryRend === renditionId) {
    const newPrimary = survivors.reduce((best, candidate) =>
      roleOutranks(candidate.role, best.role) ||
      (candidate.role === best.role && candidate.addedAt < best.addedAt)
        ? candidate
        : best,
    );
    metaSetParts.push("primaryRend = :pr", "mime = :mime", "width = :w", "height = :h");
    metaValues[":pr"] = newPrimary.renditionId;
    metaValues[":mime"] = newPrimary.mime;
    metaValues[":w"] = newPrimary.width;
    metaValues[":h"] = newPrimary.height;
  }

  const updateItems: TransactWriteCommandInput["TransactItems"] = [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: renditionSk(renditionId) },
        UpdateExpression: "SET deletedAt = :now",
        ConditionExpression: "attribute_not_exists(deletedAt)",
        ExpressionAttributeValues: { ":now": deletedAt },
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression: `SET ${metaSetParts.join(", ")}`,
        ExpressionAttributeValues: metaValues,
      },
    },
  ];

  const stillHasRole = survivors.some((r) => r.role === target.role);
  if (!stillHasRole) {
    const facetItem = facets.find((f) => f.facetType === "REND" && f.facetValue === target.role);
    if (facetItem) {
      updateItems.push({
        Delete: { TableName: tableName(), Key: { pk, sk: facetItem.sk } },
      });
    }
  }

  await ddb().send(new TransactWriteCommand({ TransactItems: updateItems }));
  return { assetTrashed: false };
}

export interface ReplaceRenditionInput {
  ownerId: string;
  photoId: string;
  renditionId: string;
  contentHash: string;
  plainBytes: number;
  bytes: number;
  mime: string;
  width: number;
  height: number;
  encIv?: string;
  encChunkSize: number;
}

/**
 * Replaces one rendition's stored bytes in place — same `renditionId`, same S3 key,
 * same `role`/`path` (no PATH pointer to touch). Only `contentHash`/`bytes`/
 * `plainBytes`/`mime`/`width`/`height`/`encIv`/`encChunkSize` change, plus the HASH
 * pointer, which has to move with it: the old one is deleted (it would otherwise
 * keep asserting this owner already holds content that, after this call, they no
 * longer do) and a new one is put for the new content, conditioned on not already
 * existing — a genuine collision with different, already-live content is a real
 * conflict this refuses rather than silently reassigns.
 *
 * For "the bytes themselves need fixing" — an orientation baked into the original
 * wrong, say — as opposed to `postPhotoThumbs`'s repair, which is deliberately the
 * mirror image: that one never touches `renditions` or any original, only derived
 * thumbnails (see its own doc). Doesn't touch thumbnails either, for the same
 * reason in reverse — a caller wanting those regenerated from the corrected bytes
 * makes its own separate `POST .../thumbs` call after this one succeeds.
 *
 * No CloudFront cache to invalidate by moving to a fresh key the way
 * `postPhotoThumbs`'s `presignedRepairThumbs` has to: `/media/*` is `caching_disabled`
 * at the edge (`terraform/cloudfront.tf` — "Originals are large and rarely
 * re-fetched"), unlike `/thumbs/*`'s year-long immutable policy.
 */
export async function replaceRenditionBytes(input: ReplaceRenditionInput): Promise<void> {
  const { meta, renditions } = await getAssetPartition(input.ownerId, input.photoId);
  if (!meta) throw ApiError.notFound("asset not found");
  if (meta.deletedAt) throw ApiError.conflict("asset is trashed");

  const target = renditions.find((r) => r.renditionId === input.renditionId);
  if (!target || target.deletedAt) throw ApiError.notFound("rendition not found");

  const pk = mediaPk(input.ownerId, input.photoId);

  const renditionSetParts = [
    "contentHash = :hash",
    "plainBytes = :plainBytes",
    `${RESERVED_ATTRS["bytes"]} = :bytes`,
    "mime = :mime",
    "width = :w",
    "height = :h",
    "encChunkSize = :chunk",
  ];
  const renditionValues: Record<string, unknown> = {
    ":hash": input.contentHash,
    ":plainBytes": input.plainBytes,
    ":bytes": input.bytes,
    ":mime": input.mime,
    ":w": input.width,
    ":h": input.height,
    ":chunk": input.encChunkSize,
  };
  // Whole-object mode carries its own IV as a stored attribute; streaming mode
  // carries it in the ciphertext header instead (crypto-format.md) — an old
  // whole-object encIv left behind after a switch to streaming would be actively
  // misleading, not just unused, so it's REMOVEd rather than left stale.
  const renditionRemoveParts: string[] = [];
  if (input.encIv) {
    renditionSetParts.push("encIv = :iv");
    renditionValues[":iv"] = input.encIv;
  } else {
    renditionRemoveParts.push("encIv");
  }

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Delete: {
        TableName: tableName(),
        Key: { pk: hashPtrPk(input.ownerId, target.contentHash), sk: ptrSk() },
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: hashPtrPk(input.ownerId, input.contentHash),
          sk: ptrSk(),
          kind: "live",
          photoId: input.photoId,
          renditionId: input.renditionId,
        },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: renditionSk(input.renditionId) },
        UpdateExpression:
          `SET ${renditionSetParts.join(", ")}` +
          (renditionRemoveParts.length > 0 ? ` REMOVE ${renditionRemoveParts.join(", ")}` : ""),
        ExpressionAttributeNames: { [RESERVED_ATTRS["bytes"]!]: "bytes" },
        ExpressionAttributeValues: renditionValues,
      },
    },
  ];

  if (meta.primaryRend === target.renditionId) {
    items.push({
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression: "SET mime = :mime, width = :w, height = :h",
        ExpressionAttributeValues: { ":mime": input.mime, ":w": input.width, ":h": input.height },
      },
    });
  }

  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    if (err instanceof Error && err.name === "TransactionCanceledException") {
      throw ApiError.conflict("that content already exists elsewhere, or the rendition changed underneath this request");
    }
    throw err;
  }
}
