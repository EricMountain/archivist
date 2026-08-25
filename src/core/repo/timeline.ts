// Patterns 2, 3, 11, 12 — timeline_gsi, live partition and trash partition. Same
// query shape throughout; only the partition key and the sort-key bound differ.
import { QueryCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import { decodeCursor, encodeCursor } from "../cursor";
import { timelineGsi1Pk, trashGsi1Pk } from "../keys";
import type { TimelineEntry } from "../items";

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

export interface Page<T> {
  items: T[];
  cursor?: string;
}

export interface TimelinePageOptions {
  cursor?: string;
  limit?: number;
  /** Both inclusive, toIsoUtc-formatted. */
  from?: string;
  to?: string;
}

function clampLimit(limit: number | undefined): number {
  if (!limit) return DEFAULT_LIMIT;
  return Math.min(Math.max(1, limit), MAX_LIMIT);
}

async function queryTimelinePartition(
  timelinePk: string,
  opts: TimelinePageOptions,
): Promise<Page<TimelineEntry>> {
  const values: Record<string, unknown> = { ":pk": timelinePk };
  let keyCondition = "timelinePk = :pk";
  if (opts.from && opts.to) {
    keyCondition += " AND timelineSk BETWEEN :from AND :to";
    values[":from"] = opts.from;
    values[":to"] = opts.to;
  }

  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      IndexName: "timeline_gsi",
      KeyConditionExpression: keyCondition,
      ExpressionAttributeValues: values,
      ScanIndexForward: false,
      Limit: clampLimit(opts.limit),
      ExclusiveStartKey: opts.cursor ? decodeCursor(opts.cursor) : undefined,
    }),
  );

  return {
    items: (res.Items ?? []) as TimelineEntry[],
    cursor: res.LastEvaluatedKey ? encodeCursor(res.LastEvaluatedKey) : undefined,
  };
}

/** Patterns 2 and 3: the live timeline, newest first, optionally range-bound. */
export function timelinePage(
  ownerId: string,
  opts: TimelinePageOptions = {},
): Promise<Page<TimelineEntry>> {
  return queryTimelinePartition(timelineGsi1Pk(ownerId), opts);
}

/** Pattern 11: the trash, most recently deleted first. */
export function trashPage(
  ownerId: string,
  opts: TimelinePageOptions = {},
): Promise<Page<TimelineEntry>> {
  return queryTimelinePartition(trashGsi1Pk(ownerId), opts);
}

/** Pattern 12: assets whose retention has expired — the purge sweep's input. */
export async function purgeCandidates(
  ownerId: string,
  cutoffIso: string,
  cursor?: string,
): Promise<Page<TimelineEntry>> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      IndexName: "timeline_gsi",
      KeyConditionExpression: "timelinePk = :pk AND timelineSk < :cutoff",
      ExpressionAttributeValues: { ":pk": trashGsi1Pk(ownerId), ":cutoff": cutoffIso },
      Limit: 1000,
      ExclusiveStartKey: cursor ? decodeCursor(cursor) : undefined,
    }),
  );
  return {
    items: (res.Items ?? []) as TimelineEntry[],
    cursor: res.LastEvaluatedKey ? encodeCursor(res.LastEvaluatedKey) : undefined,
  };
}
