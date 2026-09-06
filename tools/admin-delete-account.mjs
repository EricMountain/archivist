#!/usr/bin/env node
// Admin-initiated account deletion, for the case DELETE /account
// (src/lambda/api/routes/account.ts) can't cover: someone requests deletion but can't
// sign into the app themselves (lost device, no recovery code). See
// docs/ops/delete-user.md for the runbook and docs/play/delete-account.md for what
// this promises end users.
//
// Deliberately NOT an HTTP route or a new JWT role -- the operator already holds the
// AWS credentials for this account (they ran `terraform apply`, and
// docs/ops/create-user.md already has them calling admin-create-user/admin-delete-user
// directly). That's the trust boundary this script relies on: whatever IAM permissions
// the operator's own AWS_PROFILE grants them, same as every other ops script here.
//
// Reimplements the key shapes from src/core/keys.ts and the purge logic from
// src/core/repo/account.ts's deleteOwnerData locally, same tradeoff
// tools/teardown-load-test.mjs already makes for the same reason (a plain-`node`-
// runnable ops script deliberately avoids the @archivist/core TS build step) -- keep
// this in sync with deleteOwnerData if that function's behaviour changes.
//
// *** THE ONE THING THIS SCRIPT MUST GET RIGHT ***
// A user's memberships (pk U#<userId> sk M#<ownerId>) are not all "their" library --
// someone can be an editor/viewer on somebody else's shared library. Only a
// role:"owner" membership gets the full Scan-and-purge treatment below. Any other
// role gets its membership row deleted (revoking access) and NOTHING ELSE -- the
// library it points at belongs to a different person entirely. Getting this
// backwards means fulfilling one person's deletion request by wiping a different
// person's entire photo library.
//
// Usage:
//   MEDIA_TABLE=... ORIGINALS_BUCKET=... DERIVED_BUCKET=... \
//     node tools/admin-delete-account.mjs \
//       --user-pool-id eu-west-1_XXXXXXXXX --email someone@example.com [--yes]
//
// Defaults to a dry run (reports what it would delete, deletes nothing) -- pass
// --yes to actually delete. Required AWS permissions: dynamodb:Scan/Query/GetItem/
// BatchWriteItem on MEDIA_TABLE, s3:DeleteObject on both buckets, cognito-idp:
// AdminGetUser/AdminDeleteUser on the pool. No Terraform-managed IAM policy grants
// these -- this relies entirely on the operator's own account access.
//
// Known limitation, carried over unchanged from the self-service route: if this
// person linked more than one identity provider, only the one resolved from --email
// gets cleaned up here -- same "no index from userId to every IDP pointer a user
// has" caveat repo/account.ts's own deleteOwnerData already documents.

import {
  AdminDeleteUserCommand,
  AdminGetUserCommand,
  CognitoIdentityProviderClient,
} from "@aws-sdk/client-cognito-identity-provider";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import {
  BatchWriteCommand,
  DynamoDBDocumentClient,
  GetCommand,
  QueryCommand,
  ScanCommand,
} from "@aws-sdk/lib-dynamodb";
import { DeleteObjectsCommand, S3Client } from "@aws-sdk/client-s3";

const SEP = "#";
const ownerPk = (ownerId) => `O${SEP}${ownerId}`;
const userPk = (userId) => `U${SEP}${userId}`;
const profileSk = () => "#PROFILE";
const membershipSk = (ownerId) => `M${SEP}${ownerId}`;
const idpPtrPk = (issuer, subject) => `IDP${SEP}${issuer}${SEP}${subject}`;
const ptrSk = () => "#PTR";
const ownerRegistryPk = () => "REGISTRY#OWNERS";
const ownerRegistrySk = (ownerId) => ownerPk(ownerId);

function parseArgs(argv) {
  const args = { yes: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--user-pool-id") args.userPoolId = argv[++i];
    else if (a === "--email") args.email = argv[++i];
    else if (a === "--yes") args.yes = true;
    else throw new Error(`unrecognized argument: ${a}`);
  }
  if (!args.userPoolId) throw new Error("--user-pool-id is required");
  if (!args.email) throw new Error("--email is required");
  return args;
}

function chunk(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

function requireEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} environment variable is not set`);
  return value;
}

function keyId(key) {
  return `${key.pk} ${key.sk}`;
}

/** The exact inverse of routes/account.ts's cognitoUsername(): a federated sign-in's
 * Cognito username is "<ProviderName>_<sub>"; a native one is the sub itself. This
 * is the same {issuer, subject} pair auth.ts's shortIssuer/extractJwtIdentity would
 * produce from that person's own JWT -- computed here from Cognito's user record
 * instead of from a token. */
function issuerAndSubjectFrom(cognitoUser) {
  const sub = cognitoUser.UserAttributes?.find((a) => a.Name === "sub")?.Value;
  if (!sub) throw new Error("Cognito user has no sub attribute");
  const username = cognitoUser.Username ?? "";
  const issuer = username.startsWith("Google_") ? "google" : "cognito";
  return { issuer, subject: sub };
}

/** Full Scan-and-purge of one owner's entire library -- the role:"owner" branch only.
 * Mirrors src/core/repo/account.ts's deleteOwnerData exactly (same Scan-by-prefix
 * tradeoff: no index enumerates "every item an owner has", fine for a rare,
 * deliberate, manually-invoked op like this one). */
async function purgeOwnerLibrary(ddb, s3, ownerId) {
  const tableItems = [{ pk: ownerRegistryPk(), sk: ownerRegistrySk(ownerId) }];
  const s3ObjectsByBucket = new Map();
  const addS3 = (bucket, key) => {
    if (!s3ObjectsByBucket.has(bucket)) s3ObjectsByBucket.set(bucket, new Set());
    s3ObjectsByBucket.get(bucket).add(key);
  };

  let exclusiveStartKey;
  do {
    const res = await ddb.send(
      new ScanCommand({
        TableName: requireEnv("MEDIA_TABLE"),
        FilterExpression: "begins_with(pk, :prefix)",
        ExpressionAttributeValues: { ":prefix": ownerPk(ownerId) },
        ExclusiveStartKey: exclusiveStartKey,
      }),
    );

    for (const item of res.Items ?? []) {
      tableItems.push({ pk: item.pk, sk: item.sk });
      if (typeof item.sk === "string" && item.sk.startsWith(`R${SEP}`)) {
        if (item.s3Bucket && item.s3Key) addS3(item.s3Bucket, item.s3Key);
      }
      if (item.sk === "#META") {
        for (const thumb of Object.values(item.thumbs ?? {})) addS3(thumb.bucket, thumb.key);
      }
    }
    exclusiveStartKey = res.LastEvaluatedKey;
  } while (exclusiveStartKey);

  return { tableItems, s3ObjectsByBucket };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const tableName = requireEnv("MEDIA_TABLE");
  requireEnv("ORIGINALS_BUCKET");
  requireEnv("DERIVED_BUCKET");

  const cognito = new CognitoIdentityProviderClient({});
  const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}), {
    marshallOptions: { removeUndefinedValues: true },
  });
  const s3 = new S3Client({});

  const cognitoUser = await cognito.send(
    new AdminGetUserCommand({ UserPoolId: args.userPoolId, Username: args.email }),
  );
  const { issuer, subject } = issuerAndSubjectFrom(cognitoUser);
  console.log(`Resolved ${args.email} -> issuer=${issuer} subject=${subject}`);

  const ptr = await ddb.send(
    new GetCommand({
      TableName: tableName,
      Key: { pk: idpPtrPk(issuer, subject), sk: ptrSk() },
      ConsistentRead: true,
    }),
  );
  if (!ptr.Item) {
    console.log(
      "No IDP# pointer for this identity -- they've never signed in, so there's no library or membership to delete. Only the Cognito user itself remains (see docs/ops/create-user.md's 'invited the wrong person' case).",
    );
    return;
  }
  const userId = ptr.Item.userId;
  console.log(`IDP pointer -> userId=${userId}`);

  const memberships = await ddb.send(
    new QueryCommand({
      TableName: tableName,
      KeyConditionExpression: "pk = :u AND begins_with(sk, :m)",
      ExpressionAttributeValues: { ":u": userPk(userId), ":m": "M#" },
    }),
  );
  const memberItems = memberships.Items ?? [];

  const tableKeysToDelete = new Map();
  const addKey = (key) => tableKeysToDelete.set(keyId(key), key);
  const s3ObjectsByBucket = new Map();
  const addS3All = (byBucket) => {
    for (const [bucket, keys] of byBucket) {
      if (!s3ObjectsByBucket.has(bucket)) s3ObjectsByBucket.set(bucket, new Set());
      for (const k of keys) s3ObjectsByBucket.get(bucket).add(k);
    }
  };

  const ownedLibraries = [];
  const revokedMemberships = [];

  for (const m of memberItems) {
    if (m.role === "owner") {
      const { tableItems, s3ObjectsByBucket: ownerS3 } = await purgeOwnerLibrary(
        ddb,
        s3,
        m.ownerId,
      );
      for (const key of tableItems) addKey(key);
      addS3All(ownerS3);
      ownedLibraries.push({
        ownerId: m.ownerId,
        items: tableItems.length,
        objects: [...ownerS3.values()].reduce((n, s) => n + s.size, 0),
      });
    } else {
      addKey({ pk: userPk(userId), sk: membershipSk(m.ownerId) });
      revokedMemberships.push({ ownerId: m.ownerId, role: m.role });
    }
  }

  addKey({ pk: idpPtrPk(issuer, subject), sk: ptrSk() });
  // Unlike deleteOwnerData's self-service "only if this was their only membership"
  // check, this script resolves every membership the Query above found -- owned
  // libraries purged, shared ones revoked -- so the user has zero memberships left
  // either way, and the profile row is always safe to delete.
  addKey({ pk: userPk(userId), sk: profileSk() });

  console.log("");
  console.log("Would purge these owned libraries in full:");
  if (ownedLibraries.length === 0) console.log("  (none -- this identity owns no library)");
  for (const lib of ownedLibraries) {
    console.log(`  ownerId=${lib.ownerId}: ${lib.items} DynamoDB item(s), ${lib.objects} S3 object(s)`);
  }
  console.log("Would revoke these shared-library memberships only (library untouched):");
  if (revokedMemberships.length === 0) console.log("  (none)");
  for (const rev of revokedMemberships) {
    console.log(`  ownerId=${rev.ownerId} role=${rev.role}`);
  }
  console.log(`Would also delete: the IDP# pointer, and this user's #PROFILE row.`);
  console.log(`Would then delete the Cognito user ${args.email} (username: ${cognitoUser.Username}).`);

  if (!args.yes) {
    console.log("");
    console.log("Dry run only -- pass --yes to actually delete.");
    return;
  }

  let objectsDeleted = 0;
  for (const [bucket, keys] of s3ObjectsByBucket) {
    for (const batch of chunk([...keys], 1000)) {
      await s3.send(
        new DeleteObjectsCommand({ Bucket: bucket, Delete: { Objects: batch.map((Key) => ({ Key })) } }),
      );
      objectsDeleted += batch.length;
    }
  }

  let itemsDeleted = 0;
  for (const batch of chunk([...tableKeysToDelete.values()], 25)) {
    await ddb.send(
      new BatchWriteCommand({
        RequestItems: { [tableName]: batch.map((key) => ({ DeleteRequest: { Key: key } })) },
      }),
    );
    itemsDeleted += batch.length;
  }

  // Cognito last, matching routes/account.ts's own ordering: a failed/partial
  // DynamoDB batch above is safely re-runnable as long as the person can still be
  // looked up by username.
  await cognito.send(
    new AdminDeleteUserCommand({ UserPoolId: args.userPoolId, Username: cognitoUser.Username }),
  );

  console.log(
    `Deleted ${itemsDeleted} DynamoDB item(s), ${objectsDeleted} S3 object(s), and the Cognito user.`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
