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
}

export interface ApiResponse {
  statusCode: number;
  body?: unknown;
}

export type RouteHandler = (req: ApiRequest) => Promise<ApiResponse>;

export function ok(body?: unknown): ApiResponse {
  return { statusCode: 200, body };
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
    rawBody:
      event.body === undefined
        ? undefined
        : event.isBase64Encoded
          ? Buffer.from(event.body, "base64").toString("utf8")
          : event.body,
  };
}
