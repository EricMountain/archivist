// Plan step 1.13 — the daily sweep that actually deletes expired trash. Not
// DynamoDB TTL: that would orphan the S3 objects and can't batch the S3 deletes.
// See "Purging" in design.md for the four-step shape this follows.
import { BatchWriteCommand, DeleteCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { DeleteObjectsCommand } from "@aws-sdk/client-s3";
import { ddb, tableName } from "../db";
import { s3 } from "../s3";
import { hashPtrPk, mediaPk, pathPtrPk, ptrSk, stemPtrPk } from "../keys";
import { epochSecondsAfterDays } from "../time";
import { getAssetPartition } from "./media";

function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

export interface PurgeAssetResult {
  itemsDeleted: number;
  objectsDeleted: number;
}

/**
 * Purges one trashed asset: deletes its S3 objects (originals + thumbnails),
 * deletes every item in its partition, deletes its STEM and PATH pointers, and
 * converts each HASH pointer to a tombstone rather than deleting it — see
 * "Purge tombstones" in design.md. No index is needed to find the pointers;
 * they're all reconstructible from the partition's own contents.
 */
export async function purgeAsset(
  ownerId: string,
  photoId: string,
  purgedAt: string,
  tombstoneRetentionDays: number,
): Promise<PurgeAssetResult> {
  const { meta, renditions, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) return { itemsDeleted: 0, objectsDeleted: 0 };

  const s3ObjectsByBucket = new Map<string, Set<string>>();
  function addObject(bucket: string, key: string): void {
    let keys = s3ObjectsByBucket.get(bucket);
    if (!keys) {
      keys = new Set();
      s3ObjectsByBucket.set(bucket, keys);
    }
    keys.add(key);
  }
  for (const rendition of renditions) {
    addObject(rendition.s3Bucket, rendition.s3Key);
  }
  for (const thumb of Object.values(meta.thumbs)) {
    addObject(thumb.bucket, thumb.key);
  }

  let objectsDeleted = 0;
  for (const [bucket, keys] of s3ObjectsByBucket) {
    for (const batch of chunk([...keys], 1000)) {
      await s3().send(
        new DeleteObjectsCommand({
          Bucket: bucket,
          Delete: { Objects: batch.map((Key) => ({ Key })) },
        }),
      );
      objectsDeleted += batch.length;
    }
  }

  const pk = mediaPk(ownerId, photoId);
  const partitionKeys = [meta, ...renditions, ...facets].map((item) => ({
    pk,
    sk: item.sk,
  }));

  let itemsDeleted = 0;
  for (const batch of chunk(partitionKeys, 25)) {
    await ddb().send(
      new BatchWriteCommand({
        RequestItems: {
          [tableName()]: batch.map((key) => ({ DeleteRequest: { Key: key } })),
        },
      }),
    );
    itemsDeleted += batch.length;
  }

  await ddb().send(
    new DeleteCommand({
      TableName: tableName(),
      Key: { pk: stemPtrPk(ownerId, meta.stem), sk: ptrSk() },
    }),
  );

  for (const rendition of renditions) {
    await ddb().send(
      new DeleteCommand({
        TableName: tableName(),
        Key: { pk: pathPtrPk(ownerId, rendition.path), sk: ptrSk() },
      }),
    );
    await ddb().send(
      new UpdateCommand({
        TableName: tableName(),
        Key: { pk: hashPtrPk(ownerId, rendition.contentHash), sk: ptrSk() },
        // blockedAttempts/lastAttemptAt/lastAttemptBy are deliberately untouched:
        // they carry over from any blocked re-upload attempts during the trash
        // window — "re-uploads refused, across trash and purge" in design.md.
        UpdateExpression:
          "SET kind = :purged, purgedAt = :now, expiresAt = :exp REMOVE photoId, renditionId",
        ExpressionAttributeValues: {
          ":purged": "purged",
          ":now": purgedAt,
          ":exp": epochSecondsAfterDays(purgedAt, tombstoneRetentionDays),
        },
      }),
    );
  }

  return { itemsDeleted, objectsDeleted };
}
