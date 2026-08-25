// "Trashing reuses timeline_gsi" — soft delete/restore of a whole asset. Both
// directions are one TransactWriteItems: the #META item moves between partitions
// of timeline_gsi, and every F# item has its facet_gsi keys removed or restored.
// Bounded to one partition (a few dozen items at most), so a single transaction is
// both correct and cheap.
import { TransactWriteCommand, type TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, tableName } from "../db";
import { facetGsiPk, mediaPk, metaSk, sortKey, timelineGsi1Pk, trashGsi1Pk } from "../keys";
import { getAssetPartition } from "./media";

export async function trashAsset(
  ownerId: string,
  photoId: string,
  deletedAt: string,
  deletedBy: string,
): Promise<void> {
  const { meta, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) throw ApiError.notFound("asset not found");
  const pk = mediaPk(ownerId, photoId);

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression:
          "SET timelinePk = :trash, timelineSk = :dsk, deletedAt = :now, deletedBy = :dev",
        ConditionExpression: "attribute_not_exists(deletedAt)",
        ExpressionAttributeValues: {
          ":trash": trashGsi1Pk(ownerId),
          ":dsk": sortKey(deletedAt, photoId),
          ":now": deletedAt,
          ":dev": deletedBy,
        },
      },
    },
    ...facets.map((facet) => ({
      Update: {
        TableName: tableName(),
        Key: { pk, sk: facet.sk },
        UpdateExpression: "REMOVE facetPk, facetSk",
      },
    })),
  ];

  await runOrConflict(items, "asset is already trashed");
}

export async function restoreAsset(ownerId: string, photoId: string): Promise<void> {
  const { meta, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) throw ApiError.notFound("asset not found");
  if (!meta.deletedAt) throw ApiError.conflict("asset is not trashed");
  const pk = mediaPk(ownerId, photoId);

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression:
          "SET timelinePk = :live, timelineSk = :tsk REMOVE deletedAt, deletedBy",
        ConditionExpression: "attribute_exists(deletedAt)",
        ExpressionAttributeValues: {
          ":live": timelineGsi1Pk(ownerId),
          ":tsk": sortKey(meta.takenAt, photoId),
        },
      },
    },
    ...facets.map((facet) => ({
      Update: {
        TableName: tableName(),
        Key: { pk, sk: facet.sk },
        UpdateExpression: "SET facetPk = :fpk, facetSk = :fsk",
        ExpressionAttributeValues: {
          ":fpk": facetGsiPk(ownerId, facet.facetType, facet.facetValue),
          ":fsk": sortKey(facet.takenAt, photoId),
        },
      },
    })),
  ];

  await runOrConflict(items, "asset was already restored");
}

async function runOrConflict(
  items: TransactWriteCommandInput["TransactItems"],
  message: string,
): Promise<void> {
  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    if (
      err instanceof Error &&
      (err.name === "TransactionCanceledException" ||
        err.name === "ConditionalCheckFailedException")
    ) {
      throw ApiError.conflict(message);
    }
    throw err;
  }
}
