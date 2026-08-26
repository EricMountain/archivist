// Reads of the three owner-scoped pointer types, plus the IDP pointer. Most
// writes live in repo/ingest.ts, since a pointer is normally only written as part
// of the transaction that creates or updates the thing it resolves to. Recording
// a blocked re-upload attempt (below) is the one standalone exception: it's a
// plain update to a pointer that already exists, not part of any ingest
// transaction.
import { GetCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import { hashPtrPk, idpPtrPk, pathPtrPk, ptrSk, stemPtrPk } from "../keys";
import type { HashPointerItem, IdpPointerItem, LivePointerItem } from "../items";

export async function getStemPointer(
  ownerId: string,
  stem: string,
): Promise<LivePointerItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: stemPtrPk(ownerId, stem), sk: ptrSk() },
      ConsistentRead: true,
    }),
  );
  return res.Item as LivePointerItem | undefined;
}

export async function getPathPointer(
  ownerId: string,
  path: string,
): Promise<LivePointerItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: pathPtrPk(ownerId, path), sk: ptrSk() },
      ConsistentRead: true,
    }),
  );
  return res.Item as LivePointerItem | undefined;
}

/** Three outcomes: no item (new bytes), `kind: live` (already held), `kind: purged`
 * (deliberately deleted — see "Purge tombstones" in design.md). */
export async function getHashPointer(
  ownerId: string,
  hmac: string,
): Promise<HashPointerItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: hashPtrPk(ownerId, hmac), sk: ptrSk() },
      ConsistentRead: true,
    }),
  );
  return res.Item as HashPointerItem | undefined;
}

/**
 * Records a refused re-upload against a HASH pointer: `ADD blockedAttempts 1`,
 * `SET lastAttemptAt`/`lastAttemptBy`, and — only for a tombstone — pushes
 * `expiresAt` out to `attemptAt + tombstoneRetentionDays`. Silent *on the wire*
 * (an unattended sync has nowhere useful to put an error) but never silent in
 * the library: this is what the trash/purge UI surfaces. See "Purge tombstones"
 * in design.md.
 */
export async function recordBlockedHashAttempt(
  ownerId: string,
  hmac: string,
  attemptBy: string,
  attemptAt: string,
  tombstoneExpiresAt?: number,
): Promise<void> {
  const setParts = ["lastAttemptAt = :at", "lastAttemptBy = :by"];
  const values: Record<string, unknown> = { ":one": 1, ":at": attemptAt, ":by": attemptBy };
  if (tombstoneExpiresAt !== undefined) {
    setParts.push("expiresAt = :exp");
    values[":exp"] = tombstoneExpiresAt;
  }
  await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: hashPtrPk(ownerId, hmac), sk: ptrSk() },
      UpdateExpression: `ADD blockedAttempts :one SET ${setParts.join(", ")}`,
      ExpressionAttributeValues: values,
    }),
  );
}

export async function resolveIdpPointer(
  issuer: string,
  subject: string,
): Promise<IdpPointerItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: idpPtrPk(issuer, subject), sk: ptrSk() },
      ConsistentRead: true,
    }),
  );
  return res.Item as IdpPointerItem | undefined;
}
