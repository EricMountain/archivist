// Plan step 1.8 — key wrapping items. The server never sees an unwrapped master
// key; `wrappedKey` is opaque bytes throughout. The two-wrapping / one-recovery
// invariant is enforced here, not just in a client UI — see "Key wrapping items"
// in design.md.
import { DeleteCommand, GetCommand, PutCommand, QueryCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, tableName } from "../db";
import { keyWrapSk, keysPk, ownerPk, settingsSk } from "../keys";
import { toIsoUtc } from "../time";
import type { KeyWrapItem } from "../items";

export async function listKeyWraps(ownerId: string): Promise<KeyWrapItem[]> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :p",
      ExpressionAttributeValues: { ":p": keysPk(ownerId) },
    }),
  );
  return (res.Items ?? []) as KeyWrapItem[];
}

export async function putKeyWrap(
  ownerId: string,
  item: Omit<KeyWrapItem, "pk" | "sk">,
): Promise<void> {
  await ddb().send(
    new PutCommand({
      TableName: tableName(),
      Item: { pk: keysPk(ownerId), sk: keyWrapSk(item.wrapId), ...item },
    }),
  );
}

/** Refuses a deletion that would leave fewer than two wrappings, or that would
 * remove the last recovery wrapping. Reads the whole (small — a handful of
 * items) collection first, so the check and the delete see a consistent view. */
export async function deleteKeyWrap(ownerId: string, wrapId: string): Promise<void> {
  const wraps = await listKeyWraps(ownerId);
  const target = wraps.find((w) => w.wrapId === wrapId);
  if (!target) {
    throw ApiError.notFound("key wrapping not found");
  }
  if (wraps.length < 3) {
    throw ApiError.conflict("at least two key wrappings must remain");
  }
  const recoveryCount = wraps.filter((w) => w.kind === "recovery").length;
  if (target.kind === "recovery" && recoveryCount <= 1) {
    throw ApiError.conflict("the last recovery wrapping cannot be removed");
  }

  await ddb().send(
    new DeleteCommand({
      TableName: tableName(),
      Key: { pk: keysPk(ownerId), sk: keyWrapSk(wrapId) },
    }),
  );
}

export interface CurrentMasterKeyVersion {
  masterKeyVer: string;
  rotatedAt: string;
}

/** Reads the owner's current master key version, as last allocated by
 * `allocateMasterKeyVer`. Undefined until the first allocation — a fresh owner
 * has no master key version to stamp a wrapping with yet. */
export async function getCurrentMasterKeyVersion(
  ownerId: string,
): Promise<CurrentMasterKeyVersion | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: ownerPk(ownerId), sk: settingsSk() },
      ProjectionExpression: "masterKeyVerSeq, rotatedAt",
    }),
  );
  const seq = res.Item?.["masterKeyVerSeq"] as number | undefined;
  const rotatedAt = res.Item?.["rotatedAt"] as string | undefined;
  if (seq === undefined || rotatedAt === undefined) return undefined;
  return { masterKeyVer: `mk-${seq}`, rotatedAt };
}

/** Atomically mints the next master key version — `ADD 1` on a missing
 * `masterKeyVerSeq` starts from 0, so the very first allocation naturally
 * yields `mk-1` with no bootstrap special case. Never callable by a client
 * choosing its own version: two concurrent rotations would otherwise label two
 * different keys identically. See "Master key versions" in design.md. */
export async function allocateMasterKeyVer(
  ownerId: string,
): Promise<CurrentMasterKeyVersion> {
  const rotatedAt = toIsoUtc(new Date());
  const res = await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: ownerPk(ownerId), sk: settingsSk() },
      UpdateExpression: "ADD masterKeyVerSeq :one SET rotatedAt = :rotatedAt",
      ExpressionAttributeValues: { ":one": 1, ":rotatedAt": rotatedAt },
      ReturnValues: "UPDATED_NEW",
    }),
  );
  const seq = res.Attributes?.["masterKeyVerSeq"] as number;
  return { masterKeyVer: `mk-${seq}`, rotatedAt };
}

/** Stores the owner's hash secret, wrapped by the master key — opaque to the
 * server like every other wrapped value. Written by the first client at
 * enrolment; callable again on rotation to update the wrapping (the secret
 * itself never changes, only what it's wrapped by). See "`contentHash` is
 * HMAC'd" in design.md. */
export async function putHashSecret(
  ownerId: string,
  encHashSecret: string,
  hashSecretKeyId: string,
): Promise<void> {
  await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: ownerPk(ownerId), sk: settingsSk() },
      UpdateExpression: "SET encHashSecret = :s, hashSecretKeyId = :k",
      ExpressionAttributeValues: { ":s": encHashSecret, ":k": hashSecretKeyId },
    }),
  );
}
