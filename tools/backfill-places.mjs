#!/usr/bin/env node
// Rebuilds an owner's place vocabulary (design.md pattern 16, the
// O#<ownerId>#PLACES partition maintained by src/core/repo/places.ts) from the
// live assets' own #META.place.
//
// The counters only move inside the transactions that create, attach, trash, restore
// or re-geocode an asset, so they can't drift in normal operation. This exists for
// the other cases: recovering from a suspected drift, and checking that nothing has
// drifted (the dry run's diff). Same model as tools/backfill-histogram.mjs.
//
// Source of truth: timeline_gsi's live partition enumerates the live assets, and a
// BatchGetItem reads each one's #META.place from the base table. That works whether
// or not `place` is projected into timeline_gsi yet (terraform/locals.tf's
// timeline_projection), and it is where the display names live — facet_gsi carries
// only ids.
//
// Reimplements key shapes from src/core/keys.ts locally, the same tradeoff
// backfill-histogram.mjs makes (a plain-`node` ops script, no TS build step) — keep
// in sync with src/core/repo/places.ts.
//
// Overwrites rather than adds: every computed row is Put outright, and any existing
// row the recount doesn't produce is Put back with n 0 (zero rows are kept and
// filtered on read, as everywhere else). Running it twice gives the same state.
//
// Usage:
//   MEDIA_TABLE=... node tools/backfill-places.mjs --owner-id <ownerId> [--yes]
//   MEDIA_TABLE=... node tools/backfill-places.mjs --all-owners [--yes]
//
// Defaults to a dry run: prints each row whose stored count differs from the recount
// and a final "differences: <n>" line, and writes nothing. Pass --yes to write.
// DYNAMODB_ENDPOINT, when set, points it at DynamoDB Local (the test suites use it).
// See `make backfill-places[-dev] OWNER_ID=... [ALL=1] [EXECUTE=1]`.

import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import {
  BatchGetCommand,
  BatchWriteCommand,
  DynamoDBDocumentClient,
  GetCommand,
  QueryCommand,
} from "@aws-sdk/lib-dynamodb";

const SEP = "#";
const ownerPk = (ownerId) => `O${SEP}${ownerId}`;
const timelineGsi1Pk = (ownerId) => ownerPk(ownerId);
const placesPk = (ownerId) => `${ownerPk(ownerId)}${SEP}PLACES`;
const placesMetaSk = () => "#META";
const ownerRegistryPk = () => "REGISTRY#OWNERS";

const BATCH_WRITE_LIMIT = 25;
const BATCH_GET_LIMIT = 100;

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

/** pks of every live asset, from timeline_gsi's live partition. */
async function liveMediaPks(ddb, tableName, ownerId) {
  const pks = [];
  let cursor;
  do {
    const res = await ddb.send(
      new QueryCommand({
        TableName: tableName,
        IndexName: "timeline_gsi",
        KeyConditionExpression: "timelinePk = :pk",
        ExpressionAttributeValues: { ":pk": timelineGsi1Pk(ownerId) },
        ProjectionExpression: "pk",
        ExclusiveStartKey: cursor,
      }),
    );
    for (const item of res.Items ?? []) pks.push(item.pk);
    cursor = res.LastEvaluatedKey;
  } while (cursor);
  return pks;
}

async function livePlaces(ddb, tableName, ownerId) {
  const places = [];
  for (const batch of chunk(await liveMediaPks(ddb, tableName, ownerId), BATCH_GET_LIMIT)) {
    let request = {
      [tableName]: {
        Keys: batch.map((pk) => ({ pk, sk: "#META" })),
        ProjectionExpression: "place, deletedAt",
      },
    };
    while (request && Object.keys(request).length > 0) {
      const res = await ddb.send(new BatchGetCommand({ RequestItems: request }));
      for (const item of res.Responses?.[tableName] ?? []) {
        // timeline_gsi is eventually consistent; trust #META over the index.
        if (item.place && !item.deletedAt) places.push(item.place);
      }
      request = res.UnprocessedKeys;
    }
  }
  return places;
}

/** Mirrors counterRows in src/core/repo/places.ts. Map of sk -> row attributes. */
function computeRows(places) {
  const rows = new Map();
  const bump = (sk, attrs) => {
    const row = rows.get(sk) ?? { n: 0 };
    // Last writer's display attributes win outright (not merged), so a locality with
    // no region doesn't inherit a regionName from an earlier place.
    rows.set(sk, { ...attrs, n: row.n + 1 });
  };
  for (const place of places) {
    bump(`C${SEP}${place.cc}`, {});
    if (place.region) bump(`R${SEP}${place.region.id}`, { name: place.region.name, cc: place.cc });
    for (const { kind, ref } of namedLevels(place)) {
      // A place with no admin1 region has no regionName (absent, not ""); `within`
      // names the locality for sub/spot/feature rows only.
      bump(`L${SEP}${ref.id}`, {
        name: ref.name,
        cc: place.cc,
        kind,
        ...(place.region ? { regionName: place.region.name } : {}),
        ...((kind === "sub" || kind === "spot" || kind === "feature") && place.locality
          ? { within: place.locality.name }
          : {}),
      });
    }
  }
  return rows;
}

/** Mirrors namedLevels in src/core/repo/places.ts. */
function namedLevels(place) {
  const out = [];
  if (place.locality) out.push({ kind: "locality", ref: place.locality });
  if (place.sub) out.push({ kind: "sub", ref: place.sub });
  if (place.spot) out.push({ kind: "spot", ref: place.spot });
  for (const area of place.areas ?? []) out.push({ kind: "area", ref: area });
  if (place.feature) out.push({ kind: "feature", ref: place.feature });
  return out;
}

async function storedRows(ddb, tableName, ownerId) {
  const rows = new Map();
  let meta;
  let cursor;
  do {
    const res = await ddb.send(
      new QueryCommand({
        TableName: tableName,
        KeyConditionExpression: "pk = :pk",
        ExpressionAttributeValues: { ":pk": placesPk(ownerId) },
        ExclusiveStartKey: cursor,
      }),
    );
    for (const item of res.Items ?? []) {
      const { pk: _pk, sk, ...attrs } = item;
      if (sk === placesMetaSk()) meta = attrs;
      else rows.set(sk, attrs);
    }
    cursor = res.LastEvaluatedKey;
  } while (cursor);
  return { rows, meta };
}

async function backfillOwner(ddb, tableName, ownerId, yes) {
  const places = await livePlaces(ddb, tableName, ownerId);
  const computed = computeRows(places);
  const total = places.length;
  const stored = await storedRows(ddb, tableName, ownerId);

  // A missing row and a zero row mean the same thing ("no live photos there").
  let differences = 0;
  for (const sk of new Set([...computed.keys(), ...stored.rows.keys()])) {
    const want = computed.get(sk)?.n ?? 0;
    const have = stored.rows.get(sk)?.n ?? 0;
    if (want !== have) {
      differences++;
      console.log(`  ${sk}: stored ${have} -> recount ${want}`);
    }
  }
  const storedTotal = stored.meta?.total ?? 0;
  if (storedTotal !== total) {
    differences++;
    console.log(`  #META total: stored ${storedTotal} -> recount ${total}`);
  }

  console.log(`${ownerId}: ${total} live located photo(s), ${computed.size} place row(s)`);
  console.log(`differences: ${differences}`);
  if (!yes) return differences;

  const pk = placesPk(ownerId);
  const version = (stored.meta?.version ?? 0) + 1;
  const writes = [...computed.entries()].map(([sk, attrs]) => ({ PutRequest: { Item: { pk, sk, ...attrs } } }));
  for (const [sk, attrs] of stored.rows) {
    if (!computed.has(sk)) writes.push({ PutRequest: { Item: { pk, sk, ...attrs, n: 0 } } });
  }
  writes.push({ PutRequest: { Item: { pk, sk: placesMetaSk(), version, total } } });
  for (const batch of chunk(writes, BATCH_WRITE_LIMIT)) {
    let request = { [tableName]: batch };
    while (request && Object.keys(request).length > 0) {
      const res = await ddb.send(new BatchWriteCommand({ RequestItems: request }));
      request = res.UnprocessedItems;
    }
  }
  console.log(`  written at version ${version}`);
  return differences;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const tableName = requireEnv("MEDIA_TABLE");
  const endpoint = process.env.DYNAMODB_ENDPOINT;
  const ddb = DynamoDBDocumentClient.from(new DynamoDBClient(endpoint ? { endpoint } : {}), {
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
