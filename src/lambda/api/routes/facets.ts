// GET /facets, GET /facets/{type}/{value} — plan step 1.11.
import { ApiError } from "@archivist/core/errors";
import { facetPage, listFacetVocabulary } from "@archivist/core/repo/facets";
import type { FacetType } from "@archivist/core/items";
import { facetEntryDto } from "../dto";
import { ok } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

const MAX_LIMIT = 200;

function parseLimit(raw: string | undefined): number | undefined {
  if (!raw) return undefined;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) throw ApiError.validation("limit must be a positive number");
  return Math.min(n, MAX_LIMIT);
}

export const getFacets: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const vocabulary = await listFacetVocabulary(ownerId);
  return ok({ facets: vocabulary });
};

export const getFacetPage: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const type = req.params["type"] as FacetType | undefined;
  const value = req.params["value"];
  if (!type || !value) throw ApiError.validation("type and value are required");

  const { from, to, cursor } = req.query;
  if ((from && !to) || (to && !from)) {
    throw ApiError.validation("from and to must be supplied together");
  }

  const page = await facetPage(ownerId, type, decodeURIComponent(value), {
    cursor,
    limit: parseLimit(req.query["limit"]),
    from,
    to,
  });

  return ok({ items: page.items.map(facetEntryDto), cursor: page.cursor });
};
