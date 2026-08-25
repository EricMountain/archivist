// Patterns 4, 5, 4b, 5b, 7, 8 — facet_gsi. One query shape serves all of them; only
// the partition key (type + value) and an optional date range differ.
import { QueryCommand, ScanCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../db";
import { decodeCursor, encodeCursor } from "../cursor";
import { facetGsiPk, ownerPk, parseFacetGsiPk, photoIdFromMediaPk } from "../keys";
import type { FacetEntry, FacetType } from "../items";
import type { Page } from "./timeline";

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

export interface FacetPageOptions {
  cursor?: string;
  limit?: number;
  from?: string;
  to?: string;
}

export async function facetPage(
  ownerId: string,
  type: FacetType,
  value: string,
  opts: FacetPageOptions = {},
): Promise<Page<FacetEntry>> {
  const values: Record<string, unknown> = { ":pk": facetGsiPk(ownerId, type, value) };
  let keyCondition = "facetPk = :pk";
  if (opts.from && opts.to) {
    keyCondition += " AND facetSk BETWEEN :from AND :to";
    values[":from"] = opts.from;
    values[":to"] = opts.to;
  }

  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      IndexName: "facet_gsi",
      KeyConditionExpression: keyCondition,
      ExpressionAttributeValues: values,
      ScanIndexForward: false,
      Limit: opts.limit ? Math.min(Math.max(1, opts.limit), MAX_LIMIT) : DEFAULT_LIMIT,
      ExclusiveStartKey: opts.cursor ? decodeCursor(opts.cursor) : undefined,
    }),
  );

  return {
    items: (res.Items ?? []) as FacetEntry[],
    cursor: res.LastEvaluatedKey ? encodeCursor(res.LastEvaluatedKey) : undefined,
  };
}

/** "What you cannot do": intersecting two facets. Run the more selective side
 * (`primary`), then filter each result's own F# items for the second facet. Fine
 * at a handful of candidates; a normal-use pattern is the signal to add
 * OpenSearch, not a third GSI. */
export function intersectionCandidateIds(items: FacetEntry[]): string[] {
  return items.map((item) => photoIdFromMediaPk(item.pk));
}

export interface FacetVocabularyEntry {
  type: string;
  value: string;
}

/**
 * The owner's distinct facet values, for the search UI's vocabulary. Not one of
 * the access patterns in design.md's table — there's no index that lists
 * distinct facetPk values for an owner, since a GSI Query needs an exact
 * partition-key match, not a prefix. This Scan-filters facet_gsi instead, which
 * is only acceptable at this project's explicit single-tenant, one-household
 * scale (see docs/design/deployment.md) and for a screen that isn't a hot path.
 * Worth raising in design.md as an open question if it ever needs to be cheap.
 */
export async function listFacetVocabulary(ownerId: string): Promise<FacetVocabularyEntry[]> {
  const seen = new Set<string>();
  const results: FacetVocabularyEntry[] = [];
  let exclusiveStartKey: Record<string, unknown> | undefined;

  do {
    const res = await ddb().send(
      new ScanCommand({
        TableName: tableName(),
        IndexName: "facet_gsi",
        FilterExpression: "begins_with(facetPk, :prefix)",
        ExpressionAttributeValues: { ":prefix": `${ownerPk(ownerId)}#F#` },
        ProjectionExpression: "facetPk",
        ExclusiveStartKey: exclusiveStartKey,
      }),
    );
    for (const item of res.Items ?? []) {
      const facetPk = item["facetPk"] as string;
      if (seen.has(facetPk)) continue;
      seen.add(facetPk);
      results.push(parseFacetGsiPk(facetPk));
    }
    exclusiveStartKey = res.LastEvaluatedKey;
  } while (exclusiveStartKey);

  return results;
}
