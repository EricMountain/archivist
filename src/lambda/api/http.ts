// Shared request/response shapes for the router and every route handler. One
// place, so a handler never touches the raw API Gateway event.
import type { APIGatewayProxyEventV2WithJWTAuthorizer } from "aws-lambda";
import { ApiError } from "@archivist/core/errors";
import type { AuthContext, JwtIdentity } from "./auth";

export interface ApiRequest {
  method: string;
  path: string;
  params: Record<string, string>;
  query: Record<string, string>;
  /** Present only for `authMode: "owner"` routes. */
  auth?: AuthContext;
  /** Present for `authMode: "identity"` and `"owner"` routes. */
  identity?: JwtIdentity;
  requestId: string;
  rawBody: string | undefined;
  /** Lower-cased by API Gateway v2. Only conditional-request headers are read so
   * far — see [ifNoneMatch]. */
  headers?: Record<string, string>;
}

export interface ApiResponse {
  statusCode: number;
  body?: unknown;
  /** Merged over the default `content-type` in `index.ts`. Only caching headers use
   * this so far — see [okCacheable]. */
  headers?: Record<string, string>;
}

export type RouteHandler = (req: ApiRequest) => Promise<ApiResponse>;

export function ok(body?: unknown): ApiResponse {
  return { statusCode: 200, body };
}

/**
 * A response a client may cache and later revalidate.
 *
 * `must-revalidate` with `max-age=0` rather than a duration: the answer is derived
 * from DynamoDB and can change the moment a photo is uploaded or trashed, so there is
 * no interval over which serving it blind is safe. What this buys is not skipped
 * requests but cheap ones — a revalidation that matches costs a single `GetItem` on
 * the version item and returns 304 with no body, instead of reading a whole partition
 * and serialising it.
 *
 * `private` because every byte of it is one owner's data; no shared cache may hold it.
 */
export function okCacheable(body: unknown, etag: string): ApiResponse {
  return {
    statusCode: 200,
    body,
    headers: { etag, "cache-control": "private, max-age=0, must-revalidate" },
  };
}

/** The other half of [okCacheable]: what a matching `if-none-match` gets. Carries no
 * body by definition, and repeats the etag so a client can keep revalidating. */
export function notModified(etag: string): ApiResponse {
  return {
    statusCode: 304,
    headers: { etag, "cache-control": "private, max-age=0, must-revalidate" },
  };
}

/**
 * The client's `if-none-match`, if any. Case-insensitively looked up because API
 * Gateway v2 lower-cases header names but nothing in the type says so, and compared
 * loosely: a cache may return a weak validator (`W/"3"`) for a strong one, and both
 * mean the same thing here since the version either matches or it doesn't.
 */
export function ifNoneMatch(req: ApiRequest): string | undefined {
  const raw = req.headers?.["if-none-match"] ?? req.headers?.["If-None-Match"];
  return raw?.replace(/^W\//, "").trim();
}

export function created(body?: unknown): ApiResponse {
  return { statusCode: 201, body };
}

export function noContent(): ApiResponse {
  return { statusCode: 204 };
}

/** Parses the JSON body, rejecting anything malformed as 400 rather than letting
 * a SyntaxError escape as an internal error. */
export function parseJsonBody<T = unknown>(req: ApiRequest): T {
  if (!req.rawBody) {
    throw ApiError.validation("request body is required");
  }
  try {
    return JSON.parse(req.rawBody) as T;
  } catch {
    throw ApiError.validation("request body is not valid JSON");
  }
}

function stripUndefined(
  obj: Record<string, string | undefined> | undefined,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(obj ?? {})) {
    if (v !== undefined) out[k] = v;
  }
  return out;
}

export function toApiRequest(
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
  requestId: string,
  auth: AuthContext | undefined,
  identity: JwtIdentity | undefined,
): ApiRequest {
  return {
    method: event.requestContext.http.method,
    path: event.rawPath,
    params: stripUndefined(event.pathParameters),
    query: stripUndefined(event.queryStringParameters),
    auth,
    identity,
    requestId,
    headers: stripUndefined(event.headers),
    rawBody:
      event.body === undefined
        ? undefined
        : event.isBase64Encoded
          ? Buffer.from(event.body, "base64").toString("utf8")
          : event.body,
  };
}
