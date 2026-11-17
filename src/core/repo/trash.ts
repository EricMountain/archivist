// "Trashing reuses timeline_gsi" — soft delete/restore of a whole asset. Both
// directions are one TransactWriteItems: the #META item moves between partitions
// of timeline_gsi, and every F# item has its facet_gsi keys removed or restored.
// Bounded to one partition (a few dozen items at most), so a single transaction is
// both correct and cheap.
import { TransactWriteCommand, type TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError, cancelledWriteError } from "../errors";
import { ddb, tableName } from "../db";
import { facetGsiPk, mediaPk, metaSk, sortKey, timelineGsi1Pk, trashGsi1Pk } from "../keys";
import { getAssetPartition } from "./media";
import { histogramAdd, histogramRemove } from "./histogram";
import { placesAdd, placesRemove } from "./places";
import type { Place } from "../items";

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
        ConditionExpression: `attribute_not_exists(deletedAt) AND ${placeGuard(meta.place)}`,
        ExpressionAttributeValues: {
          ":trash": trashGsi1Pk(ownerId),
          ":dsk": sortKey(deletedAt, photoId),
          ":now": deletedAt,
          ":dev": deletedBy,
          ...placeGuardValues(meta.place),
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

  // Leaving the live partition is leaving the histogram, in the same transaction —
  // and the place vocabulary (empty when the asset has no place).
  items.push(...histogramRemove(ownerId, meta.takenAt, meta.tzOffsetMin));
  items.push(...placesRemove(ownerId, meta.place));

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
        ConditionExpression: `attribute_exists(deletedAt) AND ${placeGuard(meta.place)}`,
        ExpressionAttributeValues: {
          ":live": timelineGsi1Pk(ownerId),
          ":tsk": sortKey(meta.takenAt, photoId),
          ...placeGuardValues(meta.place),
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

  // Re-entering the live partition is re-entering the histogram, and the place
  // vocabulary.
  items.push(...histogramAdd(ownerId, meta.takenAt, meta.tzOffsetMin));
  items.push(...placesAdd(ownerId, meta.place));

  await runOrConflict(items, "asset was already restored");
}

// The place counters move by what was *read*; a concurrent `PUT …/location` landing
// between that read and this transaction would make them move the wrong place. So
// the #META update also asserts `place` is unchanged, and a lost race is a 409.
function placeGuard(place: Place | undefined): string {
  return place ? "place = :guardPlace" : "attribute_not_exists(place)";
}

function placeGuardValues(place: Place | undefined): Record<string, unknown> {
  return place ? { ":guardPlace": place } : {};
}

async function runOrConflict(
  items: TransactWriteCommandInput["TransactItems"],
  message: string,
): Promise<void> {
  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    throw cancelledWriteError(err, message) ?? err;
  }
}
