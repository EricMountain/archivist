// A small hand-written router over API Gateway v2's routeKey. No framework — one
// Lambda, one dispatch table. See "One Lambda with an internal router" in plan
// step 1.5.
import type { RouteHandler } from "./http";
import { getHealth } from "./routes/health";
import { postSessionBootstrap } from "./routes/session";
import { deleteKey, getKeyHashSecret, getKeys, postKey, postKeyVersion, putKeyHashSecret } from "./routes/keys";
import { postUpload } from "./routes/uploads";
import {
  deletePhoto,
  deleteRendition,
  getPhoto,
  getPhotos,
  getTrash,
  patchRendition,
  postRestore,
} from "./routes/photos";
import { getFacetPage, getFacets } from "./routes/facets";
import { deleteAccount } from "./routes/account";

export interface RouteEntry {
  handler: RouteHandler;
  /**
   * "none" — /health only, no JWT involved at all.
   * "identity" — /session/bootstrap only: API Gateway's JWT authorizer has
   *   already verified the token, but there's no ownerId to resolve yet since
   *   that's what this route creates. The handler gets `req.identity`.
   * "owner" — everything else: the handler gets a full `req.auth`, resolved
   *   through the IDP pointer and the membership check. See auth.ts.
   */
  authMode: "none" | "identity" | "owner";
}

export const routes: Record<string, RouteEntry> = {
  "GET /health": { handler: getHealth, authMode: "none" },

  "POST /session/bootstrap": { handler: postSessionBootstrap, authMode: "identity" },

  "GET /keys": { handler: getKeys, authMode: "owner" },
  "POST /keys": { handler: postKey, authMode: "owner" },
  "DELETE /keys/{wrapId}": { handler: deleteKey, authMode: "owner" },
  "POST /keys/version": { handler: postKeyVersion, authMode: "owner" },
  "GET /keys/hash-secret": { handler: getKeyHashSecret, authMode: "owner" },
  "PUT /keys/hash-secret": { handler: putKeyHashSecret, authMode: "owner" },

  "POST /uploads": { handler: postUpload, authMode: "owner" },

  "GET /photos": { handler: getPhotos, authMode: "owner" },
  "GET /photos/{photoId}": { handler: getPhoto, authMode: "owner" },
  "PATCH /photos/{photoId}/renditions/{renditionId}": {
    handler: patchRendition,
    authMode: "owner",
  },
  "DELETE /photos/{photoId}": { handler: deletePhoto, authMode: "owner" },
  "DELETE /photos/{photoId}/renditions/{renditionId}": {
    handler: deleteRendition,
    authMode: "owner",
  },
  "POST /photos/{photoId}/restore": { handler: postRestore, authMode: "owner" },

  "GET /trash": { handler: getTrash, authMode: "owner" },

  "GET /facets": { handler: getFacets, authMode: "owner" },
  "GET /facets/{type}/{value}": { handler: getFacetPage, authMode: "owner" },

  "DELETE /account": { handler: deleteAccount, authMode: "owner" },
};
