// Timeline, detail, and the rename/delete/restore mutations — plan steps 1.11
// and 1.12.
import { ApiError } from "@archivist/core/errors";
import { toIsoUtc } from "@archivist/core/time";
import { newUlid } from "@archivist/core/ids";
import { derivedBucket, presignPut } from "@archivist/core/s3";
import { getAssetPartition, getMetaItem, getRenditionItems } from "@archivist/core/repo/media";
import { timelineBounds, timelinePage, trashPage } from "@archivist/core/repo/timeline";
import { histogramVersion, readHistogram } from "@archivist/core/repo/histogram";
import { deleteRendition as repoDeleteRendition, renameRendition } from "@archivist/core/repo/renditions";
import { getHashPointer, getPathPointer } from "@archivist/core/repo/pointers";
import { restoreAsset, trashAsset } from "@archivist/core/repo/trash";
import { setThumbs, THUMB_SIZES } from "./uploads";
import type { ThumbDescriptorMap } from "./uploads";
import type { ThumbEntry } from "@archivist/core/items";
import { timelineEntryDto } from "../dto";
import { ifNoneMatch, noContent, notModified, ok, okCacheable, parseJsonBody } from "../http";
import type { ApiRequest, ApiResponse, RouteHandler } from "../http";

const MAX_LIMIT = 200;

function parseLimit(raw: string | undefined): number | undefined {
  if (!raw) return undefined;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) throw ApiError.validation("limit must be a positive number");
  return Math.min(n, MAX_LIMIT);
}

// getTrash's own per-entry enrichment fan-out — see that route's comment for
// the live incident (Lambda timeout, HTTP socket pool exhaustion) this fixes.
// 10 is conservative on purpose: it's a small multiple of what a *single*
// enriched entry needs (2-3 DynamoDB calls), not a tuned-for-throughput
// number, since the failure mode being avoided is a hard timeout, not slow
// responses — a slower GET /trash page that finishes is a fine trade for a
// fast one that doesn't. Raising this needs the same live numbers (page
// size, per-entry call count, the 15s Lambda budget in terraform/api.tf)
// checked again first, not just a larger constant.
const MAX_CONCURRENT_ENRICHMENTS = 10;

/** Runs `fn` over `items` with at most `limit` in flight at once, preserving
 * result order. Deliberately not `Promise.all(items.map(fn))` — see callers
 * for why that shape is a real production hazard once `items` gets long. */
export async function mapWithConcurrency<T, R>(
  items: T[],
  limit: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  async function worker(): Promise<void> {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i]!);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

export const getPhotos: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const { cursor, from, to, order } = req.query;
  if ((from && !to) || (to && !from)) {
    throw ApiError.validation("from and to must be supplied together");
  }
  if (order && order !== "asc" && order !== "desc") {
    throw ApiError.validation("order must be asc or desc");
  }

  const page = await timelinePage(ownerId, {
    cursor,
    limit: parseLimit(req.query["limit"]),
    from,
    to,
    ascending: order === "asc",
  });

  return ok({ items: page.items.map(timelineEntryDto), cursor: page.cursor });
};

/**
 * The histogram version, quoted as an ETag. Shared by `/photos/bounds` and
 * `/photos/histogram` because both answers are functions of the same thing — which
 * photos are live — and that is exactly what the version counts changes to. A client
 * revalidating either pays one `GetItem`.
 */
function timelineEtag(version: number): string {
  return `"tl-${version}"`;
}

/**
 * Answers a conditional request, or tells the caller to build the body.
 *
 * Both cacheable routes have the same shape: read the version, compare, and only do
 * the expensive read on a miss. Written once so the cheap path can't accidentally
 * diverge between them.
 */
async function revalidate(
  req: ApiRequest,
  build: () => Promise<unknown>,
): Promise<ApiResponse> {
  const etag = timelineEtag(await histogramVersion(req.auth!.ownerId));
  if (ifNoneMatch(req) === etag) return notModified(etag);
  return okCacheable(await build(), etag);
}

/** The fast-scroll range: oldest/newest `takenAt` in the owner's live timeline —
 * `GET /photos/bounds` (api.md), pattern 14 in design.md. */
export const getPhotosBounds: RouteHandler = async (req: ApiRequest) =>
  revalidate(req, () => timelineBounds(req.auth!.ownerId));

/** How many live photos fall on each local day — `GET /photos/histogram` (api.md),
 * pattern 15 in design.md. What lets a client weight its scrollbar by how much is
 * actually there rather than by elapsed time, and name only dates that have photos. */
export const getPhotosHistogram: RouteHandler = async (req: ApiRequest) =>
  revalidate(req, () => readHistogram(req.auth!.ownerId));

export const getPhoto: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  if (!photoId) throw ApiError.validation("photoId is required");

  const { meta, renditions, facets } = await getAssetPartition(ownerId, photoId);
  if (!meta) throw ApiError.notFound("photo not found");

  return ok({ meta, renditions, facets });
};

/** Pattern 1b, exposed: resolve one exact path straight to the rendition it
 * names — a single consistent GetItem against the PATH pointer, nothing else.
 * Works for a trashed asset too: trashing (trash.ts) never touches PATH
 * pointers. Deliberately doesn't also return detail — a caller wanting that
 * chains to the existing `GET /photos/{photoId}`, which already answers the
 * same for live or trashed. Added so a caller who already knows an exact path
 * (a rename target, a local import tool's own computed path) never has to
 * page through `GET /photos`/`GET /trash` just to turn it into a photoId —
 * see the `GET /trash` timeout writeup in STATUS.md 1.12 for why that walk is
 * expensive at any real trash size. */
export const getPhotoByPath: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const path = req.query["path"];
  if (!path) throw ApiError.validation("path is required");

  const ptr = await getPathPointer(ownerId, path);
  if (!ptr) throw ApiError.notFound("no asset at that path");

  return ok({ photoId: ptr.photoId, renditionId: ptr.renditionId });
};

export const getTrash: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const page = await trashPage(ownerId, {
    cursor: req.query["cursor"],
    limit: parseLimit(req.query["limit"]),
  });

  // Each entry carries its primary rendition's HASH pointer blockedAttempts /
  // lastAttemptAt / lastAttemptBy when non-zero, so the client can warn that a
  // source still holds the file — see plan step 1.12.
  //
  // **Bounded concurrency, not Promise.all** — found live, not by inspection: a
  // full page (MAX_LIMIT 200) firing 200 of these unbounded meant up to 400
  // concurrent DynamoDB calls (getMetaItem/getRenditionItems below, plus
  // getHashPointer), which blew straight through the SDK's default 50-socket
  // HTTP pool ("socket usage at capacity=50 and 150 additional requests are
  // enqueued", CloudWatch) and the Lambda's own 15s timeout (`terraform/api.tf`)
  // — confirmed live against a real trash partition of ~2,200 items, a single
  // GET /trash took the full 15000ms and API Gateway surfaced the timeout as a
  // bare 500. mapWithConcurrency below caps it at MAX_CONCURRENT_ENRICHMENTS.
  const items = await mapWithConcurrency(page.items, MAX_CONCURRENT_ENRICHMENTS, async (entry) => {
    const dto = timelineEntryDto(entry);
    // getMetaItem + getRenditionItems, not getAssetPartition: this only ever
    // needs the primary rendition's contentHash, never the facet items
    // getAssetPartition's full-partition Query would also pull back (up to
    // ~20 more per photo, design.md's own cost estimate) — real waste at this
    // volume, on top of the concurrency fix above.
    const meta = await getMetaItem(ownerId, dto.photoId);
    if (!meta?.primaryRend) return dto;
    const renditions = await getRenditionItems(ownerId, dto.photoId);
    const primary = renditions.find((r) => r.renditionId === meta.primaryRend);
    if (!primary) return dto;

    const ptr = await getHashPointer(ownerId, primary.contentHash);
    if (!ptr?.blockedAttempts) return dto;

    return {
      ...dto,
      blockedAttempts: ptr.blockedAttempts,
      lastAttemptAt: ptr.lastAttemptAt,
      lastAttemptBy: ptr.lastAttemptBy,
    };
  });

  return ok({ items, cursor: page.cursor });
};

interface RenameBody {
  path: string;
}

export const patchRendition: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  const renditionId = req.params["renditionId"];
  if (!photoId || !renditionId) throw ApiError.validation("photoId and renditionId are required");

  const body = parseJsonBody<RenameBody>(req);
  if (!body.path) throw ApiError.validation("path is required");

  const { renditions } = await getAssetPartition(ownerId, photoId);
  const rendition = renditions.find((r) => r.renditionId === renditionId);
  if (!rendition) throw ApiError.notFound("rendition not found");

  await renameRendition(ownerId, photoId, renditionId, rendition.path, body.path);
  return noContent();
};

interface DeleteBody {
  deletedBy?: string;
}

export const deletePhoto: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  if (!photoId) throw ApiError.validation("photoId is required");

  const body = req.rawBody ? parseJsonBody<DeleteBody>(req) : {};
  await trashAsset(ownerId, photoId, toIsoUtc(new Date()), body.deletedBy ?? "unknown device");
  return noContent();
};

export const deleteRendition: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  const renditionId = req.params["renditionId"];
  if (!photoId || !renditionId) throw ApiError.validation("photoId and renditionId are required");

  const body = req.rawBody ? parseJsonBody<DeleteBody>(req) : {};
  const result = await repoDeleteRendition(
    ownerId,
    photoId,
    renditionId,
    toIsoUtc(new Date()),
    body.deletedBy ?? "unknown device",
  );
  return ok(result);
};

export const postRestore: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  if (!photoId) throw ApiError.validation("photoId is required");

  await restoreAsset(ownerId, photoId);
  return noContent();
};

interface PhotoThumbsBody {
  thumbs?: ThumbDescriptorMap;
}

/**
 * Presigns against a **fresh** key per repair call, deliberately *not* the plain
 * `thumbKey(ownerId, photoId, size)` [presignedThumbs] (`uploads.ts`) uses for a
 * first-time upload. `thumbKey`'s own doc calls those keys "ULID-derived and
 * therefore immutable" — that's exactly what lets `terraform/cloudfront.tf`'s
 * `thumbnails_immutable` cache policy cache `/thumbs/*` for a full year
 * (`default_ttl = max_ttl = 31536000`) with no `Cache-Control` from S3 needed. A
 * repair that overwrote that same key in place would still work at the S3 layer, but
 * every CloudFront edge that had already cached the broken response — the whole
 * reason a repair was needed — would keep serving it for up to a year, and so would
 * any client-side image cache keyed on the same URL. **Found live**: a repair that did
 * exactly this reported success (the S3 object and `#META.thumbs` were both genuinely
 * fixed) but the app's own timeline kept showing a blank square, since nothing ever
 * told CloudFront the cached response at that URL was stale. Minting a new key per
 * repair — `th/<ownerId>/<photoId>/<generation>/<size>` — sidesteps the problem rather
 * than solving cache invalidation: the URL has simply never been cached anywhere, by
 * construction, so there's nothing stale to serve. The original object at the old key
 * is left in place (orphaned, never deleted) rather than cleaned up here — S3 storage
 * cost for one broken thumbnail's few hundred KB isn't worth the complexity of also
 * deleting it in the same request.
 */
async function presignedRepairThumbs(
  ownerId: string,
  photoId: string,
  descriptors: ThumbDescriptorMap,
): Promise<{ thumbs: Record<number, ThumbEntry>; uploads: Record<number, string> }> {
  const generation = newUlid();
  const thumbs: Record<number, ThumbEntry> = {};
  const uploads: Record<number, string> = {};
  for (const size of THUMB_SIZES) {
    const descriptor = descriptors[`${size}`];
    if (!descriptor) continue;
    const key = `th/${ownerId}/${photoId}/${generation}/${size}`;
    thumbs[size] = { bucket: derivedBucket(), key, iv: descriptor.iv, bytes: descriptor.bytes };
    uploads[size] = await presignPut(derivedBucket(), key);
  }
  return { thumbs, uploads };
}

/**
 * `POST /photos/{photoId}/thumbs` — regenerating a broken thumbnail. design.md's
 * "Changing the ladder later" already establishes the model this reuses: thumbnails
 * are client-generated, so fixing one requires a client that holds the original to
 * regenerate and re-upload, not a server-side reprocess. This is that flow's one-photo
 * counterpart — same "presign, persist once the client confirms" shape [setThumbs]
 * already implements for `POST /uploads`, reused here — except the keys presigned
 * against are fresh per call, not the deterministic ones a first-time upload uses; see
 * [presignedRepairThumbs]'s own doc for why. Deliberately does **not** touch
 * `renditions` or any other `#META` field — a repair only ever replaces derived
 * thumbnails, never the original bytes they were derived from.
 */
export const postPhotoThumbs: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  if (!photoId) throw ApiError.validation("photoId is required");

  const meta = await getMetaItem(ownerId, photoId);
  if (!meta) throw ApiError.notFound("photo not found");

  const body = parseJsonBody<PhotoThumbsBody>(req);
  if (!body.thumbs || Object.keys(body.thumbs).length === 0) {
    throw ApiError.validation("thumbs is required");
  }

  // Merged with the existing map, not a bare replace: unlike POST /uploads (whose
  // callers always send the full ladder together — see Thumbnailer's own "all three
  // sizes are always produced together"), a repair might reasonably resend only the
  // sizes that were actually missing/corrupt. setThumbs itself does a plain attribute
  // SET, so a caller here that omitted a still-good size would otherwise silently
  // erase it.
  const { thumbs, uploads } = await presignedRepairThumbs(ownerId, photoId, body.thumbs);
  await setThumbs(ownerId, photoId, { ...meta.thumbs, ...thumbs });

  return ok({ thumbUploads: uploads });
};
