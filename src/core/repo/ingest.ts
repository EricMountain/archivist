// The two ingest transactions from "Writing them" in design.md. Both are
// all-or-nothing: a pointer and its target must never be able to disagree, so they
// go in one TransactWriteItems rather than being written non-atomically.
import { TransactWriteCommand, type TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, tableName } from "../db";
import {
  hashPtrPk,
  mediaPk,
  metaSk,
  pathPtrPk,
  ptrSk,
  renditionSk,
  sortKey,
  stemPtrPk,
  timelineGsi1Pk,
} from "../keys";
import type { AssetStatus, GroupSrc, MetaItem, RenditionItem, TakenAtSrc, TzSrc } from "../items";

export interface CreateAssetInput {
  stem: string;
  path: string;
  hmac: string;
  meta: Omit<MetaItem, "pk" | "sk">;
  rendition: Omit<RenditionItem, "pk" | "sk">;
  /** Set when `hmac` resolved to a purge tombstone and the caller passed
   * `reAddDeleted` — see "Purge tombstones" in design.md. Allows overwriting the
   * tombstone instead of failing the conditional put. */
  overwriteHashTombstone?: boolean;
}

/** New asset: five items, all-or-nothing. Call only after a conditional put of the
 * STEM pointer (outside this function — see resolveStem) has confirmed no asset
 * already owns this stem. */
export async function createAsset(input: CreateAssetInput): Promise<void> {
  const { meta, rendition } = input;
  const pk = mediaPk(meta.ownerId, meta.photoId);

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Put: {
        TableName: tableName(),
        Item: { pk: stemPtrPk(meta.ownerId, input.stem), sk: ptrSk(), photoId: meta.photoId },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: pathPtrPk(meta.ownerId, input.path),
          sk: ptrSk(),
          photoId: meta.photoId,
          renditionId: rendition.renditionId,
        },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: hashPtrPk(meta.ownerId, input.hmac),
          sk: ptrSk(),
          kind: "live",
          photoId: meta.photoId,
          renditionId: rendition.renditionId,
        },
        ConditionExpression: input.overwriteHashTombstone
          ? "attribute_not_exists(pk) OR kind = :purged"
          : "attribute_not_exists(pk)",
        ...(input.overwriteHashTombstone
          ? { ExpressionAttributeValues: { ":purged": "purged" } }
          : {}),
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk,
          sk: metaSk(),
          ...meta,
          // timeline_gsi is sparse and written only on #META items — see
          // "timeline_gsi" in design.md. Computed here, not accepted from the
          // caller, so there's exactly one place this can go wrong.
          timelinePk: timelineGsi1Pk(meta.ownerId),
          timelineSk: sortKey(meta.takenAt, meta.photoId),
        },
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: { pk, sk: renditionSk(rendition.renditionId), ...rendition },
      },
    },
  ];

  await runTransaction(items, "stem, path or content hash already claimed");
}

export interface AttachRenditionInput {
  ownerId: string;
  photoId: string;
  path: string;
  hmac: string;
  rendition: Omit<RenditionItem, "pk" | "sk">;
  /** Set when this rendition should also become primaryRend (display beats raw). */
  newPrimaryRend?: string;
  newMime?: string;
  newWidth?: number;
  newHeight?: number;
  /** Set only when this rendition's takenAtSrc outranks the stored one — "later
   * renditions may only improve takenAt, never replace it". Rewrites timelineSk
   * too, since it's derived from takenAt. */
  takenAtImprovement?: {
    takenAt: string;
    tzOffsetMin: number;
    tzSrc: TzSrc;
    takenAtSrc: TakenAtSrc;
  };
  newGroupSrc?: GroupSrc;
  /** Set when the new rendition becomes primary — its own original/thumbs
   * haven't landed yet, so the asset goes back to processing until the S3-event
   * Lambda (step 1.10) confirms them. */
  newStatus?: AssetStatus;
  overwriteHashTombstone?: boolean;
}

/** Attach a rendition to an existing asset: four items plus a guard the create
 * path doesn't need. The ConditionCheck closes the race between reading the STEM
 * pointer and writing — the asset could have been purged, or trashed, in between. */
export async function attachRendition(input: AttachRenditionInput): Promise<void> {
  const pk = mediaPk(input.ownerId, input.photoId);

  const setParts = ["renditions = renditions + :one"];
  const values: Record<string, unknown> = { ":one": 1 };
  const names: Record<string, string> = {};
  if (input.newPrimaryRend) {
    setParts.push("primaryRend = :pr");
    values[":pr"] = input.newPrimaryRend;
  }
  if (input.newMime) {
    setParts.push("mime = :mime");
    values[":mime"] = input.newMime;
  }
  if (input.newWidth !== undefined) {
    setParts.push("width = :width");
    values[":width"] = input.newWidth;
  }
  if (input.newHeight !== undefined) {
    setParts.push("height = :height");
    values[":height"] = input.newHeight;
  }
  if (input.takenAtImprovement) {
    const imp = input.takenAtImprovement;
    setParts.push(
      "takenAt = :takenAt",
      "tzOffsetMin = :tzOffsetMin",
      "tzSrc = :tzSrc",
      "takenAtSrc = :takenAtSrc",
      "timelineSk = :timelineSk",
    );
    values[":takenAt"] = imp.takenAt;
    values[":tzOffsetMin"] = imp.tzOffsetMin;
    values[":tzSrc"] = imp.tzSrc;
    values[":takenAtSrc"] = imp.takenAtSrc;
    values[":timelineSk"] = sortKey(imp.takenAt, input.photoId);
  }
  if (input.newGroupSrc) {
    setParts.push("groupSrc = :groupSrc");
    values[":groupSrc"] = input.newGroupSrc;
  }
  if (input.newStatus) {
    setParts.push("#status = :status");
    names["#status"] = "status";
    values[":status"] = input.newStatus;
  }

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: pathPtrPk(input.ownerId, input.path),
          sk: ptrSk(),
          photoId: input.photoId,
          renditionId: input.rendition.renditionId,
        },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: hashPtrPk(input.ownerId, input.hmac),
          sk: ptrSk(),
          kind: "live",
          photoId: input.photoId,
          renditionId: input.rendition.renditionId,
        },
        ConditionExpression: input.overwriteHashTombstone
          ? "attribute_not_exists(pk) OR kind = :purged"
          : "attribute_not_exists(pk)",
        ...(input.overwriteHashTombstone
          ? { ExpressionAttributeValues: { ":purged": "purged" } }
          : {}),
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: { pk, sk: renditionSk(input.rendition.renditionId), ...input.rendition },
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression: `SET ${setParts.join(", ")}`,
        // Folded in rather than a separate ConditionCheck: DynamoDB transactions
        // reject two operations that target the same item, even a ConditionCheck
        // alongside the Update it's guarding.
        ConditionExpression: "attribute_exists(pk) AND attribute_not_exists(deletedAt)",
        ExpressionAttributeValues: values,
        ...(Object.keys(names).length > 0 ? { ExpressionAttributeNames: names } : {}),
      },
    },
  ];

  await runTransaction(
    items,
    "asset was purged or trashed, or path/content hash already claimed",
  );
}

async function runTransaction(
  items: TransactWriteCommandInput["TransactItems"],
  conflictMessage: string,
): Promise<void> {
  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    if (isTransactionCancelled(err)) {
      throw ApiError.conflict(conflictMessage);
    }
    throw err;
  }
}

function isTransactionCancelled(err: unknown): boolean {
  return (
    err instanceof Error &&
    (err.name === "TransactionCanceledException" ||
      err.name === "ConditionalCheckFailedException")
  );
}
