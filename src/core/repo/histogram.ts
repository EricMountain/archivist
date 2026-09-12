// Pattern 15 — how many live photos fall on each local day, and a version to ETag
// that answer with.
//
// DynamoDB has no aggregation, so the alternative to counters is walking timeline_gsi
// and counting on every request: tens of thousands of items read to produce a few
// kilobytes of answer, for a client that asks each time it opens the scrollbar. These
// counters are maintained incrementally instead, and — crucially — *inside the
// transactions that already move an asset in and out of timeline_gsi*
// (`repo/ingest.ts`, `repo/trash.ts`). Folding them in rather than writing them
// alongside is what makes drift impossible: a photo cannot be in the live partition
// without having been counted, because the same TransactWriteItems did both.
//
// Days are keyed by the photo's own recorded offset (`localDateOf`), matching the
// date headers the Android grid draws. A histogram keyed on UTC would disagree with
// the grid for every photo near a day boundary whose offset isn't the viewer's.
import { BatchWriteCommand, QueryCommand } from "@aws-sdk/lib-dynamodb";
import type { TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import { histogramDaySk, histogramMetaSk, histogramPk, timelineGsi1Pk } from "../keys";
import { localDateOf } from "../time";

type TransactItems = NonNullable<TransactWriteCommandInput["TransactItems"]>;

// `total` and `version` are both DynamoDB reserved words, and `n` is short enough to
// be worth aliasing for the same reason rather than discovering it later.
const COUNT_NAMES = { "#n": "n" };
const META_NAMES = { "#version": "version", "#total": "total" };

export interface Histogram {
  /** Local day (`yyyy-mm-dd`) to the number of live photos on it. Days with no
   * photos are absent rather than zero. */
  days: Record<string, number>;
  /** Live photos across every day — the same number the days sum to, kept
   * separately so a client can size a scrollbar without summing the map. */
  total: number;
  /** Bumped by every change. The ETag, and the whole point of the `#META` item:
   * revalidating costs one `GetItem` instead of reading the entire partition. */
  version: number;
}

/**
 * The transaction items that record [delta] photos on [localDate].
 *
 * Returned rather than executed so callers can fold them into the transaction they
 * are already running — see this module's own doc for why that matters. Two items:
 * the day's counter, and the partition's version/total.
 *
 * `ADD` creates the attribute (and the item) when absent, so there is no
 * initialisation path and no read-before-write. A day whose count reaches zero keeps
 * a zero-valued item rather than being deleted; [readHistogram] filters those out, and
 * deleting would need a read to know when, which is the thing this avoids.
 */
export function histogramDelta(
  ownerId: string,
  localDate: string,
  delta: number,
): TransactItems {
  const pk = histogramPk(ownerId);
  return [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: histogramDaySk(localDate) },
        UpdateExpression: "ADD #n :d",
        ExpressionAttributeNames: COUNT_NAMES,
        ExpressionAttributeValues: { ":d": delta },
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: histogramMetaSk() },
        UpdateExpression: "ADD #version :one, #total :d",
        ExpressionAttributeNames: META_NAMES,
        ExpressionAttributeValues: { ":one": 1, ":d": delta },
      },
    },
  ];
}

/** [histogramDelta] for a photo entering the live timeline. */
export function histogramAdd(
  ownerId: string,
  takenAt: string,
  tzOffsetMin: number,
): TransactItems {
  return histogramDelta(ownerId, localDateOf(takenAt, tzOffsetMin), 1);
}

/** [histogramDelta] for a photo leaving it — trashed, never purged: a purge acts on
 * an asset already out of the live partition, so it was uncounted at trash time. */
export function histogramRemove(
  ownerId: string,
  takenAt: string,
  tzOffsetMin: number,
): TransactItems {
  return histogramDelta(ownerId, localDateOf(takenAt, tzOffsetMin), -1);
}

/**
 * A photo staying live but changing day, which is what a later rendition improving
 * `takenAt` does ("renditions may only improve takenAt, never replace it").
 *
 * Empty when the day is unchanged — the common case, since an improvement usually
 * refines the time within the same day, and DynamoDB rejects a transaction that
 * touches the same item twice, which a naive -1/+1 on one day would do.
 */
export function histogramMove(
  ownerId: string,
  from: { takenAt: string; tzOffsetMin: number },
  to: { takenAt: string; tzOffsetMin: number },
): TransactItems {
  const fromDay = localDateOf(from.takenAt, from.tzOffsetMin);
  const toDay = localDateOf(to.takenAt, to.tzOffsetMin);
  if (fromDay === toDay) return [];
  // Both deltas plus one combined version bump: the two `histogramDelta` calls would
  // otherwise each target `#META`, which is the same double-touch DynamoDB rejects.
  const pk = histogramPk(ownerId);
  return [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: histogramDaySk(fromDay) },
        UpdateExpression: "ADD #n :minus",
        ExpressionAttributeNames: COUNT_NAMES,
        ExpressionAttributeValues: { ":minus": -1 },
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: histogramDaySk(toDay) },
        UpdateExpression: "ADD #n :plus",
        ExpressionAttributeNames: COUNT_NAMES,
        ExpressionAttributeValues: { ":plus": 1 },
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: histogramMetaSk() },
        UpdateExpression: "ADD #version :one",
        ExpressionAttributeNames: { "#version": "version" },
        ExpressionAttributeValues: { ":one": 1 },
      },
    },
  ];
}

/**
 * Just the version, for revalidating a cached histogram. One `GetItem`-shaped query
 * against a single known key, which is the cheap path a conditional request takes
 * when nothing has changed — the reason `#META` is a separate item at all.
 *
 * Zero for an owner who has never had a photo, which is a real version: a client
 * holding `"0"` is correctly told nothing changed.
 */
export async function histogramVersion(ownerId: string): Promise<number> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :pk AND sk = :sk",
      ExpressionAttributeValues: {
        ":pk": histogramPk(ownerId),
        ":sk": histogramMetaSk(),
      },
    }),
  );
  return (res.Items?.[0]?.["version"] as number | undefined) ?? 0;
}

/**
 * Rebuilds an owner's histogram from scratch by walking `timeline_gsi`'s live
 * partition — the tool for two situations: an owner whose photos predate this
 * feature (the counters only ever move on a write, so nothing already in the table
 * was ever counted), and recovering from a suspected drift.
 *
 * `timeline_gsi`'s own projection already carries `tzOffsetMin`
 * (design.md's "timeline_gsi" section), so this reads one index instead of every
 * `#META` item.
 *
 * Overwrites every day count and `#META` outright — `PutItem`, not `ADD` — rather
 * than trying to reconcile against whatever is already there, which is what makes
 * this idempotent: running it twice in a row produces the same state, not double
 * counts. Callers that only want to *add* a delta already have [histogramDelta].
 *
 * Returns what it wrote rather than nothing, so a caller (the CLI wrapper below, or
 * a test) can report or assert on it without a second read.
 */
export async function rebuildHistogram(ownerId: string): Promise<Histogram> {
  const days: Record<string, number> = {};
  let cursor: Record<string, unknown> | undefined;

  do {
    const res = await ddb().send(
      new QueryCommand({
        TableName: tableName(),
        IndexName: "timeline_gsi",
        KeyConditionExpression: "timelinePk = :pk",
        ExpressionAttributeValues: { ":pk": timelineGsi1Pk(ownerId) },
        ExclusiveStartKey: cursor,
      }),
    );
    for (const item of res.Items ?? []) {
      const takenAt = item["timelineSk"] as string | undefined;
      const tzOffsetMin = (item["tzOffsetMin"] as number | undefined) ?? 0;
      if (!takenAt) continue;
      const day = localDateOf(takenAt.split("#")[0]!, tzOffsetMin);
      days[day] = (days[day] ?? 0) + 1;
    }
    cursor = res.LastEvaluatedKey;
  } while (cursor);

  const total = Object.values(days).reduce((sum, n) => sum + n, 0);
  // A rebuild changes the answer, even when the counts come out the same as before
  // (e.g. re-run with nothing changed in between) — a client's cached copy is stale
  // relative to *this* computation and has to be told to refetch. Reading the
  // current version first keeps it monotonic rather than resetting to 1.
  const version = (await histogramVersion(ownerId)) + 1;

  const pk = histogramPk(ownerId);
  const writes = [
    ...Object.entries(days).map(([day, n]) => ({
      PutRequest: { Item: { pk, sk: histogramDaySk(day), n } },
    })),
    { PutRequest: { Item: { pk, sk: histogramMetaSk(), version, total } } },
  ];
  for (let i = 0; i < writes.length; i += DYNAMODB_BATCH_LIMIT) {
    await ddb().send(
      new BatchWriteCommand({ RequestItems: { [tableName()]: writes.slice(i, i + DYNAMODB_BATCH_LIMIT) } }),
    );
  }

  return { days, total, version };
}

/** `BatchWriteItem`'s own cap on items per call. */
const DYNAMODB_BATCH_LIMIT = 25;

/** The whole histogram: one query over the owner's `HIST` partition, paginated
 * because a long-lived library has one item per day and eventually outgrows a single
 * 1MB page. */
export async function readHistogram(ownerId: string): Promise<Histogram> {
  const pk = histogramPk(ownerId);
  const days: Record<string, number> = {};
  let total = 0;
  let version = 0;
  let cursor: Record<string, unknown> | undefined;

  do {
    const res = await ddb().send(
      new QueryCommand({
        TableName: tableName(),
        KeyConditionExpression: "pk = :pk",
        ExpressionAttributeValues: { ":pk": pk },
        ExclusiveStartKey: cursor,
      }),
    );
    for (const item of res.Items ?? []) {
      const sk = item["sk"] as string;
      if (sk === histogramMetaSk()) {
        version = (item["version"] as number | undefined) ?? 0;
        total = (item["total"] as number | undefined) ?? 0;
        continue;
      }
      const n = (item["n"] as number | undefined) ?? 0;
      // A day emptied by trashing everything on it keeps a zero-valued item — see
      // [histogramDelta]. Reporting it would make a client draw a day that has
      // nothing on it.
      if (n > 0) days[sk.slice("D#".length)] = n;
    }
    cursor = res.LastEvaluatedKey;
  } while (cursor);

  return { days, total, version };
}
