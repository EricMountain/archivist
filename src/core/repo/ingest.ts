// The two ingest transactions from "Writing them" in design.md. Both are
// all-or-nothing: a pointer and its target must never be able to disagree, so they
// go in one TransactWriteItems rather than being written non-atomically.
import { TransactWriteCommand, type TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError, cancelledWriteError } from "../errors";
import { ddb, tableName } from "../db";
import {
  hashPtrPk,
  mediaPk,
  metaSk,
  pathPtrPk,
  ptrSk,
  renditionSk,
  sortKey,
  stemPtrPk,
  timelineGsi1Pk,
} from "../keys";
import type { AssetStatus, GroupSrc, MetaItem, Place, RenditionItem, TakenAtSrc, TzSrc } from "../items";
import { histogramAdd, histogramMove } from "./histogram";
import { getAssetPartition } from "./media";
import { isLocationFacet, locationFacetPuts, placesAdd } from "./places";

export interface CreateAssetInput {
  stem: string;
  path: string;
  hmac: string;
  meta: Omit<MetaItem, "pk" | "sk">;
  rendition: Omit<RenditionItem, "pk" | "sk">;
  /** Set when `hmac` resolved to a purge tombstone and the caller passed
   * `reAddDeleted` — see "Purge tombstones" in design.md. Allows overwriting the
   * tombstone instead of failing the conditional put. */
  overwriteHashTombstone?: boolean;
}

/** New asset: five items, all-or-nothing. Call only after a conditional put of the
 * STEM pointer (outside this function — see resolveStem) has confirmed no asset
 * already owns this stem. */
export async function createAsset(input: CreateAssetInput): Promise<void> {
  const { meta, rendition } = input;
  const pk = mediaPk(meta.ownerId, meta.photoId);

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Put: {
        TableName: tableName(),
        Item: { pk: stemPtrPk(meta.ownerId, input.stem), sk: ptrSk(), photoId: meta.photoId },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: pathPtrPk(meta.ownerId, input.path),
          sk: ptrSk(),
          photoId: meta.photoId,
          renditionId: rendition.renditionId,
        },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: hashPtrPk(meta.ownerId, input.hmac),
          sk: ptrSk(),
          kind: "live",
          photoId: meta.photoId,
          renditionId: rendition.renditionId,
        },
        ConditionExpression: input.overwriteHashTombstone
          ? "attribute_not_exists(pk) OR kind = :purged"
          : "attribute_not_exists(pk)",
        ...(input.overwriteHashTombstone
          ? { ExpressionAttributeValues: { ":purged": "purged" } }
          : {}),
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk,
          sk: metaSk(),
          ...meta,
          // timeline_gsi is sparse and written only on #META items — see
          // "timeline_gsi" in design.md. Computed here, not accepted from the
          // caller, so there's exactly one place this can go wrong.
          timelinePk: timelineGsi1Pk(meta.ownerId),
          timelineSk: sortKey(meta.takenAt, meta.photoId),
        },
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: { pk, sk: renditionSk(rendition.renditionId), ...rendition },
      },
    },
  ];

  // Counted in the same transaction that makes the asset live, never alongside it —
  // see repo/histogram.ts for why that is the whole design. The place vocabulary
  // (repo/places.ts) rides along on the same rule, with the location facets it
  // counts.
  items.push(...histogramAdd(meta.ownerId, meta.takenAt, meta.tzOffsetMin));
  if (meta.place) {
    items.push(
      ...locationFacetPuts(meta.ownerId, meta.photoId, meta.place, meta, true),
      ...placesAdd(meta.ownerId, meta.place),
    );
  }

  await runTransaction(items, "stem, path or content hash already claimed");
}

export interface AttachRenditionInput {
  ownerId: string;
  photoId: string;
  path: string;
  hmac: string;
  rendition: Omit<RenditionItem, "pk" | "sk">;
  /** Set when this rendition should also become primaryRend (display beats raw). */
  newPrimaryRend?: string;
  newMime?: string;
  newWidth?: number;
  newHeight?: number;
  /** Set only when this rendition's takenAtSrc outranks the stored one — "later
   * renditions may only improve takenAt, never replace it". Rewrites timelineSk
   * too, since it's derived from takenAt. */
  takenAtImprovement?: {
    takenAt: string;
    tzOffsetMin: number;
    tzSrc: TzSrc;
    takenAtSrc: TakenAtSrc;
  };
  newGroupSrc?: GroupSrc;
  /** The uploaded rendition's `place`, when the asset has none yet — "improve, never
   * replace", as with takenAt (design.md "Location", "Ingest precedence"). Writes
   * `#META.place`, the location facets and the `#PLACES` counts. The `#META` update
   * is conditioned on `place` still being absent, so a concurrent fill aborts this
   * attach (409) rather than counting twice. */
  fillPlace?: Place;
  /** Set when the new rendition becomes primary — its own original/thumbs
   * haven't landed yet, so the asset goes back to processing until the S3-event
   * Lambda (step 1.10) confirms them. */
  newStatus?: AssetStatus;
  overwriteHashTombstone?: boolean;
}

/** Attach a rendition to an existing asset: four items plus a guard the create
 * path doesn't need. The ConditionCheck closes the race between reading the STEM
 * pointer and writing — the asset could have been purged, or trashed, in between. */
export async function attachRendition(input: AttachRenditionInput): Promise<void> {
  const pk = mediaPk(input.ownerId, input.photoId);

  const setParts = ["renditions = renditions + :one"];
  const values: Record<string, unknown> = { ":one": 1 };
  const names: Record<string, string> = {};
  if (input.newPrimaryRend) {
    setParts.push("primaryRend = :pr");
    values[":pr"] = input.newPrimaryRend;
  }
  if (input.newMime) {
    setParts.push("mime = :mime");
    values[":mime"] = input.newMime;
  }
  if (input.newWidth !== undefined) {
    setParts.push("width = :width");
    values[":width"] = input.newWidth;
  }
  if (input.newHeight !== undefined) {
    setParts.push("height = :height");
    values[":height"] = input.newHeight;
  }
  if (input.takenAtImprovement) {
    const imp = input.takenAtImprovement;
    setParts.push(
      "takenAt = :takenAt",
      "tzOffsetMin = :tzOffsetMin",
      "tzSrc = :tzSrc",
      "takenAtSrc = :takenAtSrc",
      "timelineSk = :timelineSk",
    );
    values[":takenAt"] = imp.takenAt;
    values[":tzOffsetMin"] = imp.tzOffsetMin;
    values[":tzSrc"] = imp.tzSrc;
    values[":takenAtSrc"] = imp.takenAtSrc;
    values[":timelineSk"] = sortKey(imp.takenAt, input.photoId);
  }
  if (input.newGroupSrc) {
    setParts.push("groupSrc = :groupSrc");
    values[":groupSrc"] = input.newGroupSrc;
  }
  if (input.newStatus) {
    setParts.push("#status = :status");
    names["#status"] = "status";
    values[":status"] = input.newStatus;
  }
  if (input.fillPlace) {
    setParts.push("place = :place");
    values[":place"] = input.fillPlace;
  }

  const items: TransactWriteCommandInput["TransactItems"] = [
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: pathPtrPk(input.ownerId, input.path),
          sk: ptrSk(),
          photoId: input.photoId,
          renditionId: input.rendition.renditionId,
        },
        ConditionExpression: "attribute_not_exists(pk)",
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: {
          pk: hashPtrPk(input.ownerId, input.hmac),
          sk: ptrSk(),
          kind: "live",
          photoId: input.photoId,
          renditionId: input.rendition.renditionId,
        },
        ConditionExpression: input.overwriteHashTombstone
          ? "attribute_not_exists(pk) OR kind = :purged"
          : "attribute_not_exists(pk)",
        ...(input.overwriteHashTombstone
          ? { ExpressionAttributeValues: { ":purged": "purged" } }
          : {}),
      },
    },
    {
      Put: {
        TableName: tableName(),
        Item: { pk, sk: renditionSk(input.rendition.renditionId), ...input.rendition },
      },
    },
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression: `SET ${setParts.join(", ")}`,
        // Folded in rather than a separate ConditionCheck: DynamoDB transactions
        // reject two operations that target the same item, even a ConditionCheck
        // alongside the Update it's guarding.
        ConditionExpression:
          "attribute_exists(pk) AND attribute_not_exists(deletedAt)" +
          (input.fillPlace ? " AND attribute_not_exists(place)" : ""),
        ExpressionAttributeValues: values,
        ...(Object.keys(names).length > 0 ? { ExpressionAttributeNames: names } : {}),
      },
    },
  ];

  // A takenAt improvement rewrites the same things a manual correction does
  // (repo/timestamps.ts's correctTakenAt) — every facet item denormalises
  // takenAt for its own facetSk, so an improvement that doesn't touch them
  // leaves facet_gsi's ordering stale for this asset until something else
  // corrects it. histogramMove: a takenAt improvement usually refines the
  // time within the same day, in which case this is empty; when it does cross
  // a day boundary the counter has to follow, because timelineSk just did.
  // Always live here (never trashed): the #META Update above already
  // conditions on `attribute_not_exists(deletedAt)`, so if the asset was
  // trashed between this read and the transaction, the whole thing aborts.
  const partition =
    input.takenAtImprovement || input.fillPlace
      ? await getAssetPartition(input.ownerId, input.photoId)
      : undefined;
  if (input.takenAtImprovement && partition) {
    const { meta, facets } = partition;
    if (meta) {
      const { takenAt, tzOffsetMin } = input.takenAtImprovement;
      items.push(
        // A place fill below Puts the location facets afresh with the improved
        // takenAt; updating them here too would touch one item twice in one
        // transaction, which DynamoDB rejects.
        ...facets.filter((facet) => !(input.fillPlace && isLocationFacet(facet))).map((facet) => ({
          Update: {
            TableName: tableName(),
            Key: { pk, sk: facet.sk },
            UpdateExpression: "SET takenAt = :takenAt, facetSk = :facetSk",
            ExpressionAttributeValues: {
              ":takenAt": takenAt,
              ":facetSk": sortKey(takenAt, input.photoId),
            },
          },
        })),
        ...histogramMove(
          input.ownerId,
          { takenAt: meta.takenAt, tzOffsetMin: meta.tzOffsetMin },
          { takenAt, tzOffsetMin },
        ),
      );
    }
  }

  // Filling an absent place: the asset is live (the #META condition above), so the
  // facets get their facet_gsi keys and the counts go up. Facets denormalise the
  // post-attach values — the improved takenAt, the new primary's dimensions — since
  // they're written in the same transaction that changes them. Thumbs follow later
  // with setThumbs, as #META's own do.
  if (input.fillPlace && partition?.meta) {
    const meta = partition.meta;
    const denorm = {
      takenAt: input.takenAtImprovement?.takenAt ?? meta.takenAt,
      tzOffsetMin: input.takenAtImprovement?.tzOffsetMin ?? meta.tzOffsetMin,
      thumbs: meta.thumbs,
      encDek: meta.encDek,
      encKeyId: meta.encKeyId,
      width: input.newWidth ?? meta.width,
      height: input.newHeight ?? meta.height,
    };
    items.push(
      ...locationFacetPuts(input.ownerId, input.photoId, input.fillPlace, denorm, true),
      ...placesAdd(input.ownerId, input.fillPlace),
    );
  }

  await runTransaction(
    items,
    "asset was purged or trashed, or path/content hash already claimed",
  );
}

async function runTransaction(
  items: TransactWriteCommandInput["TransactItems"],
  conflictMessage: string,
): Promise<void> {
  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    throw cancelledWriteError(err, conflictMessage) ?? err;
  }
}
