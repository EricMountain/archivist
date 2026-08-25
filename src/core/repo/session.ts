// Plan step 1.7: a new Cognito (or federated) identity gets a library. Idempotent
// — calling this twice for the same issuer/subject must produce exactly one owner.
import { TransactWriteCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import {
  idpPtrPk,
  membershipSk,
  ownerPk,
  ownerRegistryPk,
  ownerRegistrySk,
  profileSk,
  ptrSk,
  settingsSk,
  userPk,
} from "../keys";
import { newUlid } from "../ids";
import { toIsoUtc } from "../time";
import { resolveIdpPointer } from "./pointers";
import { listMemberships } from "./identity";

export interface BootstrapResult {
  userId: string;
  ownerId: string;
  created: boolean;
}

export interface BootstrapInput {
  issuer: string;
  subject: string;
  displayName: string;
  email?: string;
  /** IANA zone name, not an offset — see "homeTz is a zone, not an offset". */
  homeTz: string;
}

export async function bootstrapUser(input: BootstrapInput): Promise<BootstrapResult> {
  const existingPtr = await resolveIdpPointer(input.issuer, input.subject);
  if (existingPtr) {
    const memberships = await listMemberships(existingPtr.userId);
    const owner = memberships[0];
    if (!owner) {
      // Shouldn't happen — every user is created with a membership — but fail
      // loudly rather than returning a userId with no library.
      throw new Error(`user ${existingPtr.userId} has an IDP pointer but no membership`);
    }
    return { userId: existingPtr.userId, ownerId: owner.ownerId, created: false };
  }

  const userId = newUlid();
  const ownerId = newUlid();
  const now = toIsoUtc(new Date());

  try {
    await ddb().send(
      new TransactWriteCommand({
        TransactItems: [
          {
            Put: {
              TableName: tableName(),
              Item: { pk: idpPtrPk(input.issuer, input.subject), sk: ptrSk(), userId },
              ConditionExpression: "attribute_not_exists(pk)",
            },
          },
          {
            Put: {
              TableName: tableName(),
              Item: {
                pk: userPk(userId),
                sk: profileSk(),
                userId,
                displayName: input.displayName,
                ...(input.email ? { email: input.email } : {}),
                createdAt: now,
              },
            },
          },
          {
            Put: {
              TableName: tableName(),
              Item: {
                pk: userPk(userId),
                sk: membershipSk(ownerId),
                ownerId,
                role: "owner",
              },
            },
          },
          {
            Put: {
              TableName: tableName(),
              Item: {
                pk: ownerPk(ownerId),
                sk: settingsSk(),
                ownerId,
                displayName: input.displayName,
                homeTz: input.homeTz,
                trashRetentionDays: 30,
                tombstoneRetentionDays: 365,
                createdAt: now,
              },
            },
          },
          {
            Put: {
              TableName: tableName(),
              Item: {
                pk: ownerRegistryPk(),
                sk: ownerRegistrySk(ownerId),
                ownerId,
                createdAt: now,
              },
            },
          },
        ],
      }),
    );
  } catch (err) {
    if (err instanceof Error && err.name === "TransactionCanceledException") {
      // Lost a concurrent bootstrap race for the same identity — re-resolve
      // rather than erroring, which is what makes this idempotent.
      const ptr = await resolveIdpPointer(input.issuer, input.subject);
      if (ptr) {
        const memberships = await listMemberships(ptr.userId);
        const owner = memberships[0];
        if (owner) return { userId: ptr.userId, ownerId: owner.ownerId, created: false };
      }
    }
    throw err;
  }

  return { userId, ownerId, created: true };
}
