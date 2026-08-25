// Patterns 6, 6b, 9, 10 — users, owners, devices. Pattern 9 (IDP -> userId) lives in
// repo/pointers.ts alongside the other pointer reads.
import { GetCommand, PutCommand, QueryCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import {
  deviceSk,
  devicesPk,
  membershipSk,
  ownerPk,
  profileSk,
  settingsSk,
  userPk,
} from "../keys";
import type { DeviceItem, MembershipItem, OwnerSettingsItem, UserItem } from "../items";

/** Pattern 10: which libraries can this user see — one Query at session start. */
export async function listMemberships(userId: string): Promise<MembershipItem[]> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :u AND begins_with(sk, :m)",
      ExpressionAttributeValues: { ":u": userPk(userId), ":m": "M#" },
    }),
  );
  return (res.Items ?? []) as MembershipItem[];
}

export async function getUserProfile(userId: string): Promise<UserItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: userPk(userId), sk: profileSk() },
    }),
  );
  return res.Item as UserItem | undefined;
}

export async function getOwnerSettings(
  ownerId: string,
): Promise<OwnerSettingsItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: ownerPk(ownerId), sk: settingsSk() },
    }),
  );
  return res.Item as OwnerSettingsItem | undefined;
}

/** Pattern 6: the settings screen's device list, one Query per owner. */
export async function listDevices(ownerId: string): Promise<DeviceItem[]> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :d",
      ExpressionAttributeValues: { ":d": devicesPk(ownerId) },
    }),
  );
  return (res.Items ?? []) as DeviceItem[];
}

/** Pattern 6b: one device's default offset, during ingest. */
export async function getDevice(
  ownerId: string,
  deviceKey: string,
): Promise<DeviceItem | undefined> {
  const res = await ddb().send(
    new GetCommand({
      TableName: tableName(),
      Key: { pk: devicesPk(ownerId), sk: deviceSk(deviceKey) },
    }),
  );
  return res.Item as DeviceItem | undefined;
}

/** Auto-registers a device on first sight, with no offset set, so it appears in
 * the settings UI ready to configure. Never overwrites an existing device. */
export async function ensureDeviceRegistered(
  ownerId: string,
  deviceKey: string,
  firstSeenAt: string,
): Promise<void> {
  await ddb().send(
    new PutCommand({
      TableName: tableName(),
      Item: {
        pk: devicesPk(ownerId),
        sk: deviceSk(deviceKey),
        deviceKey,
        label: deviceKey,
        firstSeenAt,
        photoCount: 0,
      },
      ConditionExpression: "attribute_not_exists(pk)",
    }),
  ).catch((err: unknown) => {
    if (err instanceof Error && err.name === "ConditionalCheckFailedException") return;
    throw err;
  });
}
