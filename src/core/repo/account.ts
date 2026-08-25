// Plan step 1.15 — full account erasure: every DynamoDB item and S3 object under
// an owner's namespace, plus the user's profile/membership if this was their only
// library. The privacy policy's deletion promise is only honoured if this reaches
// purge tombstones too — see "The tombstone deletion matters" in the plan.
//
// This Scans the base table filtered by owner prefix rather than Querying an
// index, for the same reason as listFacetVocabulary in repo/facets.ts: no index
// enumerates "every item an owner has", by design (pointers deliberately carry no
// index keys). Account deletion is rare and deliberate, which is what makes a
// Scan acceptable here even though it wouldn't be for a hot path.
import { BatchWriteCommand, GetCommand, ScanCommand } from "@aws-sdk/lib-dynamodb";
import { DeleteObjectsCommand } from "@aws-sdk/client-s3";
import { ddb, tableName } from "../db";
import { s3 } from "../s3";
import { membershipSk, ownerPk, ownerRegistryPk, ownerRegistrySk, profileSk, userPk } from "../keys";
import { listMemberships } from "./identity";
import type { ThumbEntry } from "../items";

export interface TableKey {
  pk: string;
  sk: string;
}

export interface AccountDeletionResult {
  itemsDeleted: number;
  objectsDeleted: number;
}

function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/**
 * Deletes every DynamoDB item and S3 object the owner's namespace holds, plus the
 * user's profile if this library was their only membership. `extraKeys` lets the
 * caller include the specific IDP pointer it authenticated with — there's no
 * index from userId to every IDP pointer a user has (the same sparse-pointer
 * limitation as everywhere else pointers appear), so a user with more than one
 * linked identity provider needs each pointer deleted by whoever knows it.
 */
export async function deleteOwnerData(
  ownerId: string,
  userId: string,
  extraKeys: TableKey[] = [],
): Promise<AccountDeletionResult> {
  // The registry entry lives at a fixed pk ("REGISTRY#OWNERS") rather than one
  // prefixed by this owner, so the Scan below can never find it — it has to be
  // deleted explicitly, or the owner leaves a ghost row the purge sweep keeps
  // (harmlessly, but pointlessly) revisiting forever.
  const tableItems: TableKey[] = [
    ...extraKeys,
    { pk: ownerRegistryPk(), sk: ownerRegistrySk(ownerId) },
  ];
  const s3ObjectsByBucket = new Map<string, Set<string>>();

  function addS3Object(bucket: string, key: string): void {
    let keys = s3ObjectsByBucket.get(bucket);
    if (!keys) {
      keys = new Set();
      s3ObjectsByBucket.set(bucket, keys);
    }
    keys.add(key);
  }

  let exclusiveStartKey: Record<string, unknown> | undefined;
  do {
    const res = await ddb().send(
      new ScanCommand({
        TableName: tableName(),
        FilterExpression: "begins_with(pk, :prefix)",
        ExpressionAttributeValues: { ":prefix": ownerPk(ownerId) },
        ExclusiveStartKey: exclusiveStartKey,
      }),
    );

    for (const item of res.Items ?? []) {
      const pk = item["pk"] as string;
      const sk = item["sk"] as string;
      tableItems.push({ pk, sk });

      if (typeof sk === "string" && sk.startsWith("R#")) {
        const bucket = item["s3Bucket"] as string | undefined;
        const key = item["s3Key"] as string | undefined;
        if (bucket && key) addS3Object(bucket, key);
      }
      if (sk === "#META") {
        const thumbs = item["thumbs"] as Record<number, ThumbEntry> | undefined;
        for (const thumb of Object.values(thumbs ?? {})) {
          addS3Object(thumb.bucket, thumb.key);
        }
      }
    }

    exclusiveStartKey = res.LastEvaluatedKey;
  } while (exclusiveStartKey);

  // The user's membership in this library, and their profile too if this was
  // their only one.
  const membershipKey: TableKey = { pk: userPk(userId), sk: membershipSk(ownerId) };
  const membershipExists = await ddb().send(
    new GetCommand({ TableName: tableName(), Key: membershipKey }),
  );
  if (membershipExists.Item) tableItems.push(membershipKey);

  const allMemberships = await listMemberships(userId);
  if (allMemberships.length <= 1) {
    tableItems.push({ pk: userPk(userId), sk: profileSk() });
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

  let itemsDeleted = 0;
  for (const batch of chunk(tableItems, 25)) {
    await ddb().send(
      new BatchWriteCommand({
        RequestItems: {
          [tableName()]: batch.map((key) => ({ DeleteRequest: { Key: key } })),
        },
      }),
    );
    itemsDeleted += batch.length;
  }

  return { itemsDeleted, objectsDeleted };
}
