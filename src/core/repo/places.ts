// Pattern 16 — the owner's place vocabulary, plus everything that writes location:
// `#META.place`, the `COUNTRY`/`REGION`/`PLACE` facet items, and the `#PLACES`
// counters. See "Location" and "Place vocabulary (query 16)" in design.md.
//
// Modelled on repo/histogram.ts, for the same reason: DynamoDB has no aggregation,
// so the vocabulary is a set of counters, and they are moved *inside the
// transactions that already move an asset in and out of the live timeline* (create
// and attach in repo/ingest.ts, trash and restore in repo/trash.ts), plus
// [setPlace] here. A photo can't be live and located without having been counted,
// because the same TransactWriteItems did both.
import { QueryCommand, TransactWriteCommand } from "@aws-sdk/lib-dynamodb";
import type { TransactWriteCommandInput } from "@aws-sdk/lib-dynamodb";
import { ApiError, cancelledWriteError } from "../errors";
import { ddb, tableName } from "../db";
import {
  facetGsiPk,
  facetSk,
  mediaPk,
  metaSk,
  placeCountrySk,
  placeLocalitySk,
  placeRegionSk,
  placesMetaSk,
  placesPk,
  sortKey,
} from "../keys";
import { LOCATION_FACET_TYPES } from "../items";
import type { FacetItem, LocationFacetType, MetaItem, Place, ThumbMap } from "../items";
import { getAssetPartition } from "./media";

type TransactItems = NonNullable<TransactWriteCommandInput["TransactItems"]>;

// ---------------------------------------------------------------------------
// Validation — design.md "Location", "Validation". `place` is client-asserted input
// crossing the trust boundary; the server can't check it against coordinates it
// never sees, so it checks shape and nothing else.
// ---------------------------------------------------------------------------

const CC_RE = /^[A-Z]{2}$/;
const REGION_ID_RE = /^[A-Z]{2}\.[0-9A-Z]{1,20}$/;
const LOCALITY_ID_RE = /^[0-9]{1,12}$/;
const CONTROL_RE = /\p{Cc}/u;
const MAX_NAME_CHARS = 200;
const MAX_SRC_CHARS = 64;

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function validName(v: unknown): v is string {
  if (typeof v !== "string") return false;
  const chars = [...v].length;
  return chars >= 1 && chars <= MAX_NAME_CHARS && !CONTROL_RE.test(v);
}

/**
 * Validates a client-supplied `place` and returns a fresh object holding only the
 * known fields — anything else the client sent is dropped rather than stored.
 * Throws a 400 naming the first offending field.
 */
export function parsePlace(raw: unknown, field = "place"): Place {
  if (!isObject(raw)) throw ApiError.validation(`${field} must be an object`);
  const { cc, region, locality, src } = raw;
  if (typeof cc !== "string" || !CC_RE.test(cc)) {
    throw ApiError.validation(`${field}.cc must be an ISO-3166 alpha-2 code`);
  }
  if (typeof src !== "string" || src.length === 0 || [...src].length > MAX_SRC_CHARS) {
    throw ApiError.validation(`${field}.src must be 1-${MAX_SRC_CHARS} characters`);
  }
  const place: Place = { cc, src };

  if (region !== undefined && region !== null) {
    if (!isObject(region)) throw ApiError.validation(`${field}.region must be an object`);
    if (typeof region["id"] !== "string" || !REGION_ID_RE.test(region["id"])) {
      throw ApiError.validation(`${field}.region.id must be a GeoNames admin1 key like JP.22`);
    }
    if (region["id"].slice(0, 2) !== cc) {
      throw ApiError.validation(`${field}.region.id must start with ${field}.cc`);
    }
    if (!validName(region["name"])) {
      throw ApiError.validation(`${field}.region.name must be 1-${MAX_NAME_CHARS} characters, no control characters`);
    }
    place.region = { id: region["id"], name: region["name"] };
  }

  // No `region` requirement: GeoNames has towns with no admin1 (Stanley, most of
  // Lesotho), so `{cc, locality}` is a legitimate lookup result. See "Validation".
  if (locality !== undefined && locality !== null) {
    if (!isObject(locality)) throw ApiError.validation(`${field}.locality must be an object`);
    if (typeof locality["id"] !== "string" || !LOCALITY_ID_RE.test(locality["id"])) {
      throw ApiError.validation(`${field}.locality.id must be a GeoNames geonameid`);
    }
    if (!validName(locality["name"])) {
      throw ApiError.validation(`${field}.locality.name must be 1-${MAX_NAME_CHARS} characters, no control characters`);
    }
    place.locality = { id: locality["id"], name: locality["name"] };
  }

  return place;
}

/** Field-by-field equality; `undefined` means "no place". */
export function placesEqual(a: Place | undefined, b: Place | undefined): boolean {
  if (!a || !b) return !a && !b;
  return (
    a.cc === b.cc &&
    a.src === b.src &&
    a.region?.id === b.region?.id &&
    a.region?.name === b.region?.name &&
    a.locality?.id === b.locality?.id &&
    a.locality?.name === b.locality?.name
  );
}

// ---------------------------------------------------------------------------
// Facet items
// ---------------------------------------------------------------------------

/** The `F#COUNTRY#`/`F#REGION#`/`F#PLACE#` facets a place implies — ids, not names. */
export function locationFacets(place: Place | undefined): Array<{ type: LocationFacetType; value: string }> {
  if (!place) return [];
  const out: Array<{ type: LocationFacetType; value: string }> = [{ type: "COUNTRY", value: place.cc }];
  if (place.region) out.push({ type: "REGION", value: place.region.id });
  if (place.locality) out.push({ type: "PLACE", value: place.locality.id });
  return out;
}

export function isLocationFacet(facet: Pick<FacetItem, "facetType">): boolean {
  return (LOCATION_FACET_TYPES as readonly string[]).includes(facet.facetType);
}

/** What every facet item denormalises from `#META` — see "`F#` facet items". */
export type FacetDenorm = Pick<
  MetaItem,
  "takenAt" | "tzOffsetMin" | "thumbs" | "encDek" | "encKeyId" | "width" | "height"
>;

export function facetDenormOf(meta: FacetDenorm): FacetDenorm {
  return {
    takenAt: meta.takenAt,
    tzOffsetMin: meta.tzOffsetMin,
    thumbs: meta.thumbs as ThumbMap,
    encDek: meta.encDek,
    encKeyId: meta.encKeyId,
    width: meta.width,
    height: meta.height,
  };
}

/**
 * `Put`s for the location facet items of [place]. `live: false` writes them without
 * `facetPk`/`facetSk` — exactly the state trash.ts leaves a trashed asset's facets
 * in, so a later restore puts them back into facet_gsi like any other facet.
 */
export function locationFacetPuts(
  ownerId: string,
  photoId: string,
  place: Place | undefined,
  denorm: FacetDenorm,
  live: boolean,
): TransactItems {
  const pk = mediaPk(ownerId, photoId);
  return locationFacets(place).map(({ type, value }) => ({
    Put: {
      TableName: tableName(),
      Item: {
        pk,
        sk: facetSk(type, value),
        facetType: type,
        facetValue: value,
        ...facetDenormOf(denorm),
        ...(live
          ? { facetPk: facetGsiPk(ownerId, type, value), facetSk: sortKey(denorm.takenAt, photoId) }
          : {}),
      },
    },
  }));
}

// ---------------------------------------------------------------------------
// Counters
// ---------------------------------------------------------------------------

// `name`, `total` and `version` are DynamoDB reserved words; `n` aliased for symmetry
// with repo/histogram.ts.
interface CounterRow {
  sk: string;
  /** Display attributes, refreshed whenever the place is counted *in*. */
  attrs: Record<string, string>;
  /** Display attributes this place doesn't have, removed when it is counted *in* —
   * `regionName` for a locality with no region, so the row never keeps one left
   * over from an earlier writer. */
  absent?: string[];
}

function counterRows(place: Place | undefined): CounterRow[] {
  if (!place) return [];
  const rows: CounterRow[] = [{ sk: placeCountrySk(place.cc), attrs: {} }];
  if (place.region) {
    rows.push({ sk: placeRegionSk(place.region.id), attrs: { name: place.region.name, cc: place.cc } });
  }
  if (place.locality) {
    rows.push(
      place.region
        ? {
            sk: placeLocalitySk(place.locality.id),
            attrs: { name: place.locality.name, regionName: place.region.name, cc: place.cc },
          }
        : {
            // A town with no admin1 region: `regionName` absent, not "".
            sk: placeLocalitySk(place.locality.id),
            attrs: { name: place.locality.name, cc: place.cc },
            absent: ["regionName"],
          },
    );
  }
  return rows;
}

/**
 * The transaction items that move one live photo's count from [from] to [to].
 * Either side may be `undefined` (no place), which makes this also the add and
 * remove primitive ([placesAdd], [placesRemove]).
 *
 * Returned rather than executed, so callers fold them into the transaction they are
 * already running. **Each counter key appears at most once**: DynamoDB rejects a
 * transaction that touches the same item twice, and two places in the same country
 * (or region) share a `C#` (or `R#`) row. Shared rows get the net delta, and are
 * skipped entirely when that's zero and the display names didn't change. Empty when
 * the two places are equal — which is what makes `PUT /photos/{id}/location`
 * idempotent on the counts.
 *
 * `ADD` creates the item when absent, so there is no initialisation path. A row
 * reaching zero stays as a zero row; [readPlaces] filters it out.
 */
export function placesMove(
  ownerId: string,
  from: Place | undefined,
  to: Place | undefined,
): TransactItems {
  if (placesEqual(from, to)) return [];
  const pk = placesPk(ownerId);

  const merged = new Map<
    string,
    { delta: number; fromAttrs?: Record<string, string>; toAttrs?: Record<string, string>; toAbsent?: string[] }
  >();
  for (const row of counterRows(from)) {
    merged.set(row.sk, { delta: -1, fromAttrs: row.attrs });
  }
  for (const row of counterRows(to)) {
    const existing = merged.get(row.sk);
    if (existing) {
      existing.delta += 1;
      existing.toAttrs = row.attrs;
      existing.toAbsent = row.absent;
    } else {
      merged.set(row.sk, { delta: 1, toAttrs: row.attrs, toAbsent: row.absent });
    }
  }

  const items: TransactItems = [];
  for (const [sk, { delta, fromAttrs, toAttrs, toAbsent }] of merged) {
    const namesChanged =
      !!toAttrs && JSON.stringify(toAttrs) !== JSON.stringify(fromAttrs ?? {});
    if (delta === 0 && !namesChanged) continue;

    const names: Record<string, string> = { "#n": "n" };
    const values: Record<string, unknown> = { ":d": delta };
    const sets: string[] = [];
    for (const [attr, value] of Object.entries(toAttrs ?? {})) {
      names[`#${attr}`] = attr;
      values[`:${attr}`] = value;
      sets.push(`#${attr} = :${attr}`);
    }
    const removes: string[] = [];
    for (const attr of toAbsent ?? []) {
      names[`#${attr}`] = attr;
      removes.push(`#${attr}`);
    }
    items.push({
      Update: {
        TableName: tableName(),
        Key: { pk, sk },
        UpdateExpression:
          `${sets.length > 0 ? `SET ${sets.join(", ")} ` : ""}` +
          `${removes.length > 0 ? `REMOVE ${removes.join(", ")} ` : ""}ADD #n :d`,
        ExpressionAttributeNames: names,
        ExpressionAttributeValues: values,
      },
    });
  }

  const totalDelta = (to ? 1 : 0) - (from ? 1 : 0);
  if (items.length === 0 && totalDelta === 0) return [];
  items.push({
    Update: {
      TableName: tableName(),
      Key: { pk, sk: placesMetaSk() },
      UpdateExpression: "ADD #version :one, #total :d",
      ExpressionAttributeNames: { "#version": "version", "#total": "total" },
      ExpressionAttributeValues: { ":one": 1, ":d": totalDelta },
    },
  });
  return items;
}

/** [placesMove] for a photo entering the live timeline. Empty for no place. */
export function placesAdd(ownerId: string, place: Place | undefined): TransactItems {
  return placesMove(ownerId, undefined, place);
}

/** [placesMove] for a photo leaving it (trash). Empty for no place. */
export function placesRemove(ownerId: string, place: Place | undefined): TransactItems {
  return placesMove(ownerId, place, undefined);
}

// ---------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------

export interface PlaceVocabulary {
  countries: Array<{ cc: string; n: number }>;
  regions: Array<{ id: string; name: string; cc: string; n: number }>;
  /** `regionName` is absent for a town with no admin1 region. */
  localities: Array<{ id: string; name: string; regionName?: string; cc: string; n: number }>;
  /** Live located photos. */
  total: number;
  /** Bumped by every change; the ETag. */
  version: number;
}

/** Just the version, for revalidating — one key read, as [histogramVersion]. Zero
 * for an owner who has never had a located photo. */
export async function placesVersion(ownerId: string): Promise<number> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :pk AND sk = :sk",
      ExpressionAttributeValues: { ":pk": placesPk(ownerId), ":sk": placesMetaSk() },
    }),
  );
  return (res.Items?.[0]?.["version"] as number | undefined) ?? 0;
}

/** The whole vocabulary: one paginated query over `O#<ownerId>#PLACES`, zero rows
 * filtered out. */
export async function readPlaces(ownerId: string): Promise<PlaceVocabulary> {
  const out: PlaceVocabulary = { countries: [], regions: [], localities: [], total: 0, version: 0 };
  let cursor: Record<string, unknown> | undefined;
  do {
    const res = await ddb().send(
      new QueryCommand({
        TableName: tableName(),
        KeyConditionExpression: "pk = :pk",
        ExpressionAttributeValues: { ":pk": placesPk(ownerId) },
        ExclusiveStartKey: cursor,
      }),
    );
    for (const item of res.Items ?? []) {
      const sk = item["sk"] as string;
      if (sk === placesMetaSk()) {
        out.version = (item["version"] as number | undefined) ?? 0;
        out.total = (item["total"] as number | undefined) ?? 0;
        continue;
      }
      const n = (item["n"] as number | undefined) ?? 0;
      // A place emptied by trashing or re-geocoding keeps a zero row — see
      // [placesMove]. Reporting it would offer a search suggestion with no photos.
      if (n <= 0) continue;
      const id = sk.slice(2);
      const name = (item["name"] as string | undefined) ?? "";
      const cc = (item["cc"] as string | undefined) ?? "";
      if (sk.startsWith("C#")) out.countries.push({ cc: id, n });
      else if (sk.startsWith("R#")) out.regions.push({ id, name, cc, n });
      else if (sk.startsWith("L#")) {
        const regionName = item["regionName"] as string | undefined;
        out.localities.push({ id, name, ...(regionName ? { regionName } : {}), cc, n });
      }
    }
    cursor = res.LastEvaluatedKey;
  } while (cursor);
  return out;
}

// ---------------------------------------------------------------------------
// PUT /photos/{photoId}/location
// ---------------------------------------------------------------------------

export interface SetPlaceInput {
  ownerId: string;
  photoId: string;
  /** `null` clears the place. */
  place: Place | null;
  /** Re-encrypted EXIF blob carrying the coordinates; both or neither. */
  exifEnc?: string;
  exifIv?: string;
}

/**
 * Replaces `#META.place`, swaps the location facet items and — for a live asset only
 * — moves the `#PLACES` counts, all in one transaction. A trashed asset was
 * uncounted when it was trashed (same rule as the histogram), so its counts don't
 * move here and its new facets are written without facet_gsi keys, the way trash
 * leaves them; restore then counts and indexes the *new* place.
 *
 * Guarded optimistically: the `#META` update only commits if `place` and the
 * trashed/live state are still what was read, so a concurrent change can't make the
 * counters disagree with the facets. A lost race is a 409; retrying is safe.
 */
export async function setPlace(input: SetPlaceInput): Promise<Place | null> {
  const { ownerId, photoId } = input;
  const { meta, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) throw ApiError.notFound("photo not found");

  const live = !meta.deletedAt;
  const oldPlace = meta.place;
  const newPlace = input.place ?? undefined;
  const pk = mediaPk(ownerId, photoId);

  const sets: string[] = [];
  const removes: string[] = [];
  const values: Record<string, unknown> = {};
  if (newPlace) {
    sets.push("place = :place");
    values[":place"] = newPlace;
  } else {
    removes.push("place");
  }
  if (input.exifEnc !== undefined) {
    sets.push("exifEnc = :exifEnc", "exifIv = :exifIv");
    values[":exifEnc"] = input.exifEnc;
    values[":exifIv"] = input.exifIv;
  }
  const conditions = [
    "attribute_exists(pk)",
    live ? "attribute_not_exists(deletedAt)" : "attribute_exists(deletedAt)",
  ];
  if (oldPlace) {
    conditions.push("place = :oldPlace");
    values[":oldPlace"] = oldPlace;
  } else {
    conditions.push("attribute_not_exists(place)");
  }

  const newFacetSks = new Set(locationFacets(newPlace).map(({ type, value }) => facetSk(type, value)));
  const items: TransactItems = [
    {
      Update: {
        TableName: tableName(),
        Key: { pk, sk: metaSk() },
        UpdateExpression: [
          sets.length > 0 ? `SET ${sets.join(", ")}` : "",
          removes.length > 0 ? `REMOVE ${removes.join(", ")}` : "",
        ]
          .filter(Boolean)
          .join(" "),
        ConditionExpression: conditions.join(" AND "),
        ...(Object.keys(values).length > 0 ? { ExpressionAttributeValues: values } : {}),
      },
    },
    // Every location facet the new place doesn't imply goes, including any strays
    // from an earlier inconsistency — not just the old place's three.
    ...facets
      .filter((f) => isLocationFacet(f) && !newFacetSks.has(f.sk))
      .map((f) => ({ Delete: { TableName: tableName(), Key: { pk, sk: f.sk } } })),
    ...locationFacetPuts(ownerId, photoId, newPlace, meta, live),
  ];
  if (live) items.push(...placesMove(ownerId, oldPlace, newPlace));

  try {
    await ddb().send(new TransactWriteCommand({ TransactItems: items }));
  } catch (err) {
    throw cancelledWriteError(err, "photo changed in the meantime; retry") ?? err;
  }
  return newPlace ?? null;
}
