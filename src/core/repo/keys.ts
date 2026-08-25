// Plan step 1.8 — key wrapping items. The server never sees an unwrapped master
// key; `wrappedKey` is opaque bytes throughout. The two-wrapping / one-recovery
// invariant is enforced here, not just in a client UI — see "Key wrapping items"
// in design.md.
import { DeleteCommand, PutCommand, QueryCommand } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, tableName } from "../db";
import { keyWrapSk, keysPk } from "../keys";
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
