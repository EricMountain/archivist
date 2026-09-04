// Plan step 2.14 — device config items ("Device config items" in design.md). Nothing
// wrote these before this step: `deviceKey`/`devicesPk`/`deviceSk`/`DeviceItem` all
// existed already (plan steps 1.x), but no repo function or route ever read or wrote
// the `O#<ownerId>#DEVICES` partition, so "auto-registered on first sight" was
// undocumented dead text until `upsertDeviceSighting` below was wired into
// `postUpload`'s create-asset path.
import { DeleteCommand, QueryCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, tableName } from "../db";
import { deviceSk, devicesPk } from "../keys";
import { toIsoUtc } from "../time";
import type { DeviceItem } from "../items";

export async function listDevices(ownerId: string): Promise<DeviceItem[]> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :p",
      ExpressionAttributeValues: { ":p": devicesPk(ownerId) },
    }),
  );
  return (res.Items ?? []) as DeviceItem[];
}

/** Called once per newly-created asset that carries a `deviceKey` (see `postUpload`'s
 * create-asset branch in `uploads.ts`) — never on an attach, so a RAW+JPEG pair from
 * the same shutter release counts as one photo, not two. A single conditional-free
 * `UpdateCommand` creates the item on first sight (`if_not_exists` on `label`/
 * `firstSeenAt`) or just bumps `photoCount` on every sighting after — no read before
 * write, so two concurrent first uploads from a brand-new camera can't race each other
 * into two different items the way a naive check-then-put would. `label` defaults to
 * `deviceKey` itself (the raw `make|model|serial` string) since there's no human-
 * friendly name to derive one from until the owner renames it in Settings.
 * `tzOffsetMin` is deliberately never touched here — "auto-registered ... with no
 * offset set" (design.md) is exactly the absent-attribute state, and this update
 * doesn't mention it either way, so it stays absent until `updateDevice` sets it. */
export async function upsertDeviceSighting(ownerId: string, deviceKey: string): Promise<void> {
  const now = toIsoUtc(new Date());
  await ddb().send(
    new UpdateCommand({
      TableName: tableName(),
      Key: { pk: devicesPk(ownerId), sk: deviceSk(deviceKey) },
      UpdateExpression:
        "SET deviceKey = if_not_exists(deviceKey, :deviceKey), " +
        "label = if_not_exists(label, :deviceKey), " +
        "firstSeenAt = if_not_exists(firstSeenAt, :now) " +
        "ADD photoCount :one",
      ExpressionAttributeValues: { ":deviceKey": deviceKey, ":now": now, ":one": 1 },
    }),
  );
}

export interface UpdateDeviceInput {
  label?: string;
  /** `null` clears a previously-set default (design.md: "absent means 'no default
   * set'") — distinct from `undefined`, which leaves it untouched. */
  tzOffsetMin?: number | null;
}

/** Requires the device to already exist (`attribute_exists(pk)`) — there's no
 * legitimate way for a client to invent a `deviceKey` that ingest hasn't seen yet,
 * and a 404 here is a clearer signal than silently creating a phantom device. */
export async function updateDevice(
  ownerId: string,
  deviceKey: string,
  input: UpdateDeviceInput,
): Promise<void> {
  const sets: string[] = [];
  const removes: string[] = [];
  const values: Record<string, unknown> = {};

  if (input.label !== undefined) {
    sets.push("label = :label");
    values[":label"] = input.label.slice(0, 200);
  }
  if (input.tzOffsetMin === null) {
    removes.push("tzOffsetMin");
  } else if (input.tzOffsetMin !== undefined) {
    sets.push("tzOffsetMin = :tzOffsetMin");
    values[":tzOffsetMin"] = input.tzOffsetMin;
  }
  if (sets.length === 0 && removes.length === 0) return;

  const clauses = [
    sets.length > 0 ? `SET ${sets.join(", ")}` : undefined,
    removes.length > 0 ? `REMOVE ${removes.join(", ")}` : undefined,
  ].filter((c): c is string => c !== undefined);

  try {
    await ddb().send(
      new UpdateCommand({
        TableName: tableName(),
        Key: { pk: devicesPk(ownerId), sk: deviceSk(deviceKey) },
        UpdateExpression: clauses.join(" "),
        ConditionExpression: "attribute_exists(pk)",
        ExpressionAttributeValues: Object.keys(values).length > 0 ? values : undefined,
      }),
    );
  } catch (err) {
    if (err instanceof Error && err.name === "ConditionalCheckFailedException") {
      throw ApiError.notFound("device not found");
    }
    throw err;
  }
}

export async function deleteDevice(ownerId: string, deviceKey: string): Promise<void> {
  await ddb().send(
    new DeleteCommand({
      TableName: tableName(),
      Key: { pk: devicesPk(ownerId), sk: deviceSk(deviceKey) },
    }),
  );
}
