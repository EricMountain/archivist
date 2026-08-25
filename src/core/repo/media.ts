// Access patterns 1, 1b, 1c, 1d — reading one asset, by id or by path.
import { GetCommand, QueryCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, RESERVED_ATTRS, tableName } from "../db";
import { mediaPk, metaSk, pathPtrPk, ptrSk } from "../keys";
import { isFacetItem, isMetaItem, isRenditionItem } from "../items";
import type { AssetStatus, FacetItem, MediaItem, MetaItem, RenditionItem } from "../items";

export interface AssetPartition {
  meta: MetaItem | undefined;
  renditions: RenditionItem[];
  facets: FacetItem[];
}

/** Pattern 1c: everything about an asset in one Query. */
export async function getAssetPartition(
  ownerId: string,
  photoId: string,
): Promise<AssetPartition> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :p",
      ExpressionAttributeValues: { ":p": mediaPk(ownerId, photoId) },
    }),
  );
  const items = (res.Items ?? []) as MediaItem[];
  return {
    meta: items.find(isMetaItem),
    renditions: items.filter(isRenditionItem),
    facets: items.filter(isFacetItem),
  };
}

/** Pattern 1: just the #META item. */
export async function getMetaItem(
  ownerId: string,
  photoId: string,
): Promise<MetaItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: mediaPk(ownerId, photoId), sk: metaSk() },
    }),
  );
  return res.Item as MetaItem | undefined;
}

/** Just the renditions, e.g. for a "download RAW" detail-view offer. */
export async function getRenditionItems(
  ownerId: string,
  photoId: string,
): Promise<RenditionItem[]> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :p AND begins_with(sk, :r)",
      ExpressionAttributeValues: { ":p": mediaPk(ownerId, photoId), ":r": "R#" },
    }),
  );
  return (res.Items ?? []) as RenditionItem[];
}

/** Pattern 1b: resolve a full path to the asset that owns it. Two reads — the
 * pointer, then the metadata — never a GSI, since the pointer is also the
 * uniqueness constraint. */
export async function getPhotoByPath(
  ownerId: string,
  path: string,
): Promise<MetaItem | undefined> {
  const ptr = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: pathPtrPk(ownerId, path), sk: ptrSk() },
      ConsistentRead: true,
    }),
  );
  if (!ptr.Item) return undefined;
  const photoId = ptr.Item["photoId"] as string;

  const meta = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: mediaPk(ownerId, photoId), sk: metaSk() },
      ProjectionExpression: `photoId, stem, takenAt, thumbs, ${RESERVED_ATTRS["status"]}`,
      ExpressionAttributeNames: { [RESERVED_ATTRS["status"]!]: "status" },
    }),
  );
  return meta.Item as MetaItem | undefined;
}

/** Used by the S3-event Lambda (step 1.10) once it's confirmed arrival, and by
 * the purge sweep's failure paths. Deliberately doesn't touch a trashed asset. */
export async function setAssetStatus(
  ownerId: string,
  photoId: string,
  status: AssetStatus,
): Promise<void> {
  await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: mediaPk(ownerId, photoId), sk: metaSk() },
      UpdateExpression: `SET ${RESERVED_ATTRS["status"]} = :status`,
      ConditionExpression: "attribute_not_exists(deletedAt)",
      ExpressionAttributeNames: { [RESERVED_ATTRS["status"]!]: "status" },
      ExpressionAttributeValues: { ":status": status },
    }),
  ).catch((err: unknown) => {
    if (err instanceof Error && err.name === "ConditionalCheckFailedException") return;
    throw err;
  });
}
