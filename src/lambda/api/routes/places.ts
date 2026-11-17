// GET /places and PUT /photos/{photoId}/location — geolocation plan step 4. See
// design.md "Location" and "Place vocabulary (query 16)", and repo/places.ts.
import { ApiError } from "@archivist/core/errors";
import { parsePlace, placesVersion, readPlaces, setPlace } from "@archivist/core/repo/places";
import { ifNoneMatch, notModified, ok, okCacheable, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";
import { MAX_EXIF_BLOB_CHARS } from "./uploads";

/** Distinct from the timeline's `"tl-<n>"`: a different version counter, so the two
 * ETags must never be mistaken for one another. */
function placesEtag(version: number): string {
  return `"pl-${version}"`;
}

/** The owner's place vocabulary, zero rows filtered out. Revalidation mirrors
 * `GET /photos/histogram`: a matching `If-None-Match` costs one key read and gets a
 * bodiless 304. */
export const getPlaces: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const etag = placesEtag(await placesVersion(ownerId));
  if (ifNoneMatch(req) === etag) return notModified(etag);
  return okCacheable(await readPlaces(ownerId), etag);
};

interface LocationBody {
  place?: unknown;
  exifEnc?: unknown;
  exifIv?: unknown;
}

/** Replaces one photo's place (or clears it, with `place: null`) and optionally its
 * re-encrypted EXIF blob. Idempotent. Works on a trashed photo, without moving
 * counts. Responds with the stored place. */
export const putPhotoLocation: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const photoId = req.params["photoId"];
  if (!photoId) throw ApiError.validation("photoId is required");

  const body = parseJsonBody<LocationBody>(req);
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw ApiError.validation("body must be an object");
  }
  // Required, so a client can't clear a place by forgetting the field.
  if (!("place" in body)) throw ApiError.validation("place is required (null to clear)");
  const place = body.place === null ? null : parsePlace(body.place);

  const hasEnc = body.exifEnc !== undefined;
  const hasIv = body.exifIv !== undefined;
  if (hasEnc !== hasIv) throw ApiError.validation("exifEnc and exifIv must be sent together");
  if (hasEnc) {
    if (typeof body.exifEnc !== "string" || body.exifEnc.length === 0) {
      throw ApiError.validation("exifEnc must be a non-empty string");
    }
    if (body.exifEnc.length > MAX_EXIF_BLOB_CHARS) {
      throw ApiError.validation("exifEnc exceeds the maximum size");
    }
    if (typeof body.exifIv !== "string" || body.exifIv.length === 0) {
      throw ApiError.validation("exifIv must be a non-empty string");
    }
  }

  const stored = await setPlace({
    ownerId,
    photoId,
    place,
    ...(hasEnc ? { exifEnc: body.exifEnc as string, exifIv: body.exifIv as string } : {}),
  });
  return ok({ place: stored });
};
