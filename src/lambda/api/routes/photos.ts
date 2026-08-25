// Timeline, detail, and the rename/delete/restore mutations — plan steps 1.11
// and 1.12.
import { ApiError } from "@archivist/core/errors";
import { toIsoUtc } from "@archivist/core/time";
import { getAssetPartition } from "@archivist/core/repo/media";
import { timelinePage, trashPage } from "@archivist/core/repo/timeline";
import { deleteRendition as repoDeleteRendition, renameRendition } from "@archivist/core/repo/renditions";
import { restoreAsset, trashAsset } from "@archivist/core/repo/trash";
import { timelineEntryDto } from "../dto";
import { noContent, ok, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

const MAX_LIMIT = 200;

function parseLimit(raw: string | undefined): number | undefined {
  if (!raw) return undefined;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) throw ApiError.validation("limit must be a positive number");
  return Math.min(n, MAX_LIMIT);
}

export const getPhotos: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const { cursor, from, to } = req.query;
  if ((from && !to) || (to && !from)) {
    throw ApiError.validation("from and to must be supplied together");
  }

  const page = await timelinePage(ownerId, {
    cursor,
    limit: parseLimit(req.query["limit"]),
    from,
    to,
  });

  return ok({ items: page.items.map(timelineEntryDto), cursor: page.cursor });
};

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
  return ok({ items: page.items.map(timelineEntryDto), cursor: page.cursor });
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
