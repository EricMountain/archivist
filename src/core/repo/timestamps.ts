// Manually correcting a photo's takenAt/tzOffsetMin after the fact — design.md's
// "Known limitations" note ("Correcting takenAt rewrites the photo's facet items...
// bounded to one partition, doable in a single transaction") describes exactly this.
// Distinct from "Retroactive correction" (device-wide, ladder-gated, via
// takenAtSrcOutranks): this is a direct, explicit per-photo edit, so it always wins —
// see paths.ts's TAKEN_AT_SRC_RANK for why `manual` outranks every automatic rung.
import { TransactWriteCommand, type TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError } from "../errors";
import { ddb, tableName } from "../db";
import { mediaPk, metaSk, sortKey } from "../keys";
import { getAssetPartition } from "./media";
import { histogramMove } from "./histogram";

export interface CorrectTakenAtInput {
  ownerId: string;
  photoId: string;
  /** toIsoUtc-formatted. */
  takenAt: string;
  tzOffsetMin: number;
}

/** Rewrites `takenAt`/`tzOffsetMin` (and, since they're evidence of neither
 * anymore, `takenAtSrc`/`tzSrc` — both set to `manual`) on `#META`, `timelineSk`
 * (live or trashed — trashing never changes which GSI partition an asset is in,
 * only trash.ts touches that), and every `F#` facet item's own denormalised
 * `takenAt`/`facetSk`. Live-only histogram bucket move: a trashed asset was
 * already removed from the histogram by trash.ts, so touching it here would
 * double-count. One partition, one transaction. */
export async function correctTakenAt(input: CorrectTakenAtInput): Promise<void> {
  const { ownerId, photoId, takenAt, tzOffsetMin } = input;
  const { meta, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) throw ApiError.notFound("asset not found");

  const pk = mediaPk(ownerId, photoId);
  const newTimelineSk = sortKey(takenAt, photoId);

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression:
          "SET takenAt = :takenAt, tzOffsetMin = :tzOffsetMin, takenAtSrc = :takenAtSrc, " +
          "tzSrc = :tzSrc, timelineSk = :timelineSk",
        ConditionExpression: "attribute_exists(pk)",
        ExpressionAttributeValues: {
          ":takenAt": takenAt,
          ":tzOffsetMin": tzOffsetMin,
          ":takenAtSrc": "manual",
          ":tzSrc": "manual",
          ":timelineSk": newTimelineSk,
        },
      },
    },
    ...facets.map((facet) => ({
      Update: {
        TableName: tableName(),
        Key: { pk, sk: facet.sk },
        UpdateExpression: "SET takenAt = :takenAt, facetSk = :facetSk",
        ExpressionAttributeValues: {
          ":takenAt": takenAt,
          // Inert (never surfaces in facet_gsi) while facetPk is absent — a
          // trashed asset's facets (trash.ts: `REMOVE facetPk, facetSk`).
          // restoreAsset recomputes facetSk from this same takenAt anyway, so
          // writing it here is belt-and-suspenders, not load-bearing.
          ":facetSk": sortKey(takenAt, photoId),
        },
      },
    })),
  ];

  if (!meta.deletedAt) {
    items.push(...histogramMove(ownerId, { takenAt: meta.takenAt, tzOffsetMin: meta.tzOffsetMin }, { takenAt, tzOffsetMin }));
  }

  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    if (
      err instanceof Error &&
      (err.name === "TransactionCanceledException" || err.name === "ConditionalCheckFailedException")
    ) {
      throw ApiError.conflict("asset was purged in the meantime");
    }
    throw err;
  }
}
