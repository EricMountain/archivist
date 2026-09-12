#!/usr/bin/env node
// One-time (or re-run-as-needed) rebuild of an owner's per-day photo histogram
// (design.md pattern 15, src/core/repo/histogram.ts's rebuildHistogram) from
// timeline_gsi's live partition.
//
// Why this has to exist at all: the histogram's counters only ever move inside the
// transactions that create, trash or restore an asset (repo/ingest.ts, repo/trash.ts)
// -- by design, so they can never drift from what's actually live. But that also means
// nothing already in the table when this feature shipped was ever counted. Without a
// backfill, every account that had photos before this feature exists sits at
// total: 0 forever, and the Android client's density-weighted scrollbar silently falls
// back to a plain time-based one (see TimelineScale.kt's railScale) rather than doing
// anything visibly wrong -- which is exactly the kind of gap easy to miss without
// running this. Confirmed live against the dev account: histogram total was 0 despite
// a fully populated timeline.
//
// Reimplements the key shapes from src/core/keys.ts and the aggregation from
// src/core/repo/histogram.ts's rebuildHistogram/localDateOf locally, same tradeoff
// tools/teardown-load-test.mjs and tools/admin-delete-account.mjs already make for the
// same reason (a plain-`node`-runnable ops script deliberately avoids the
// @archivist/core TS build step) -- keep this in sync with those if their behaviour
// changes.
//
// Overwrites every day count and the #META item outright (PutItem, not ADD), so
// running this twice in a row is safe and produces the same state, not double counts.
//
// Usage:
//   MEDIA_TABLE=... node tools/backfill-histogram.mjs --owner-id <ownerId> [--yes]
//   MEDIA_TABLE=... node tools/backfill-histogram.mjs --all-owners [--yes]
//
// Defaults to a dry run (reports the per-day counts it would write, writes nothing)
// -- pass --yes to actually write. See `make backfill-histogram-dev OWNER_ID=...
// [ALL=1] [EXECUTE=1]` (or `make backfill-histogram` for prod's "default" workspace)
// for the wrapper that fills in MEDIA_TABLE from Terraform's own output.

import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { BatchWriteCommand, DynamoDBDocumentClient, GetCommand, QueryCommand } from "@aws-sdk/lib-dynamodb";

const SEP = "#";
const ownerPk = (ownerId) => `O${SEP}${ownerId}`;
const timelineGsi1Pk = (ownerId) => ownerPk(ownerId);
const histogramPk = (ownerId) => `${ownerPk(ownerId)}${SEP}HIST`;
const histogramDaySk = (day) => `D${SEP}${day}`;
const histogramMetaSk = () => "#META";
const ownerRegistryPk = () => "REGISTRY#OWNERS";

const BATCH_LIMIT = 25;

/** Mirrors src/core/time.ts's localDateOf: the calendar day a photo belongs to in its
 * own recorded offset, not the operator's machine's timezone or UTC. */
function localDateOf(takenAtIso, tzOffsetMin) {
  const shifted = new Date(Date.parse(takenAtIso) + tzOffsetMin * 60_000);
  return shifted.toISOString().slice(0, 10);
}

function parseArgs(argv) {
  const args = { yes: false, allOwners: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--owner-id") args.ownerId = argv[++i];
    else if (a === "--all-owners") args.allOwners = true;
    else if (a === "--yes") args.yes = true;
    else throw new Error(`unrecognized argument: ${a}`);
  }
  if (!args.ownerId && !args.allOwners) throw new Error("--owner-id or --all-owners is required");
  if (args.ownerId && args.allOwners) throw new Error("pass --owner-id or --all-owners, not both");
  return args;
}

function requireEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} environment variable is not set`);
  return value;
}

function chunk(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

async function listAllOwnerIds(ddb, tableName) {
  const ownerIds = [];
  let exclusiveStartKey;
  do {
    const res = await ddb.send(
      new QueryCommand({
        TableName: tableName,
        KeyConditionExpression: "pk = :p",
        ExpressionAttributeValues: { ":p": ownerRegistryPk() },
        ExclusiveStartKey: exclusiveStartKey,
      }),
    );
    for (const item of res.Items ?? []) ownerIds.push(item.ownerId);
    exclusiveStartKey = res.LastEvaluatedKey;
  } while (exclusiveStartKey);
  return ownerIds;
}

async function currentVersion(ddb, tableName, ownerId) {
  const res = await ddb.send(
    new GetCommand({ TableName: tableName, Key: { pk: histogramPk(ownerId), sk: histogramMetaSk() } }),
  );
  return res.Item?.version ?? 0;
}

/** Walks timeline_gsi's live partition and groups by local day -- mirrors
 * rebuildHistogram in src/core/repo/histogram.ts exactly, including relying on
 * tzOffsetMin being in the index's own projection (design.md's "timeline_gsi"
 * section) rather than reading every #META item separately. */
async function computeHistogram(ddb, tableName, ownerId) {
  const days = {};
  let cursor;
  do {
    const res = await ddb.send(
      new QueryCommand({
        TableName: tableName,
        IndexName: "timeline_gsi",
        KeyConditionExpression: "timelinePk = :pk",
        ExpressionAttributeValues: { ":pk": timelineGsi1Pk(ownerId) },
        ExclusiveStartKey: cursor,
      }),
    );
    for (const item of res.Items ?? []) {
      const takenAt = item.timelineSk?.split(SEP)[0];
      if (!takenAt) continue;
      const day = localDateOf(takenAt, item.tzOffsetMin ?? 0);
      days[day] = (days[day] ?? 0) + 1;
    }
    cursor = res.LastEvaluatedKey;
  } while (cursor);
  return days;
}

async function backfillOwner(ddb, tableName, ownerId, yes) {
  const days = await computeHistogram(ddb, tableName, ownerId);
  const total = Object.values(days).reduce((sum, n) => sum + n, 0);
  const dayCount = Object.keys(days).length;

  console.log(`${ownerId}: ${total} live photo(s) across ${dayCount} day(s)`);
  if (!yes) return;

  const pk = histogramPk(ownerId);
  const version = (await currentVersion(ddb, tableName, ownerId)) + 1;
  const writes = [
    ...Object.entries(days).map(([day, n]) => ({ PutRequest: { Item: { pk, sk: histogramDaySk(day), n } } })),
    { PutRequest: { Item: { pk, sk: histogramMetaSk(), version, total } } },
  ];
  for (const batch of chunk(writes, BATCH_LIMIT)) {
    await ddb.send(new BatchWriteCommand({ RequestItems: { [tableName]: batch } }));
  }
  console.log(`  written at version ${version}`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const tableName = requireEnv("MEDIA_TABLE");
  const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}), {
    marshallOptions: { removeUndefinedValues: true },
  });

  const ownerIds = args.allOwners ? await listAllOwnerIds(ddb, tableName) : [args.ownerId];
  if (args.allOwners) console.log(`${ownerIds.length} owner(s) in the registry`);
  if (!args.yes) console.log("DRY RUN -- pass --yes to write. Nothing will be changed.\n");

  for (const ownerId of ownerIds) {
    await backfillOwner(ddb, tableName, ownerId, args.yes);
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
