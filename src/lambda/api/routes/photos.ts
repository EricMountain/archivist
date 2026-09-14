// Timeline, detail, and the rename/delete/restore mutations — plan steps 1.11
// and 1.12.
import { ApiError } from "@archivist/core/errors";
import { toIsoUtc } from "@archivist/core/time";
import { getAssetPartition, getMetaItem } from "@archivist/core/repo/media";
import { timelineBounds, timelinePage, trashPage } from "@archivist/core/repo/timeline";
import { histogramVersion, readHistogram } from "@archivist/core/repo/histogram";
import { deleteRendition as repoDeleteRendition, renameRendition } from "@archivist/core/repo/renditions";
import { getHashPointer } from "@archivist/core/repo/pointers";
import { restoreAsset, trashAsset } from "@archivist/core/repo/trash";
import { presignedThumbs, setThumbs } from "./uploads";
import type { ThumbDescriptorMap } from "./uploads";
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

export const getTrash: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const page = await trashPage(ownerId, {
    cursor: req.query["cursor"],
    limit: parseLimit(req.query["limit"]),
  });

  // Each entry carries its primary rendition's HASH pointer blockedAttempts /
  // lastAttemptAt / lastAttemptBy when non-zero, so the client can warn that a
  // source still holds the file — see plan step 1.12. Bounded by page size (max
  // 200) and this is not a hot path, so the extra reads per entry are cheap
  // relative to the warning being reachable at all.
  const items = await Promise.all(
    page.items.map(async (entry) => {
      const dto = timelineEntryDto(entry);
      const { meta, renditions } = await getAssetPartition(ownerId, dto.photoId);
      const primary = renditions.find((r) => r.renditionId === meta?.primaryRend);
      if (!primary) return dto;

      const ptr = await getHashPointer(ownerId, primary.contentHash);
      if (!ptr?.blockedAttempts) return dto;

      return {
        ...dto,
        blockedAttempts: ptr.blockedAttempts,
        lastAttemptAt: ptr.lastAttemptAt,
        lastAttemptBy: ptr.lastAttemptBy,
      };
    }),
  );

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
 * `POST /photos/{photoId}/thumbs` — regenerating a broken thumbnail. design.md's
 * "Changing the ladder later" already establishes the model this reuses: thumbnails
 * are client-generated, so fixing one requires a client that holds the original to
 * regenerate and re-upload, not a server-side reprocess. This is that flow's one-photo
 * counterpart — same "presign against deterministic keys, persist once the client
 * confirms" shape [presignedThumbs]/[setThumbs] already implement for `POST /uploads`,
 * reused here verbatim rather than duplicated. Deliberately does **not** touch
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
  const { thumbs, uploads } = await presignedThumbs(ownerId, photoId, body.thumbs);
  await setThumbs(ownerId, photoId, { ...meta.thumbs, ...thumbs });

  return ok({ thumbUploads: uploads });
};
