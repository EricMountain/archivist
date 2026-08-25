// Reads of the three owner-scoped pointer types, plus the IDP pointer. Writes live
// in repo/ingest.ts, since a pointer is never written except as part of the
// transaction that creates or updates the thing it resolves to.
import { GetCommand } from "@aws-sdk/lib-dynamodb";
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
