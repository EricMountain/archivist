// Lambda entry point. Resolves auth per the route's authMode, dispatches through
// router.ts, and maps ApiError -> status code. Never lets an SDK exception message
// reach the client, and never logs a path, filename or stem — see plan step 1.5.
import type {
  APIGatewayProxyEventV2WithJWTAuthorizer,
  APIGatewayProxyStructuredResultV2,
  Context,
} from "aws-lambda";
import { randomUUID } from "node:crypto";
import { ApiError, HTTP_STATUS_BY_CODE } from "@archivist/core/errors";
import { resolveAuth, extractJwtIdentity } from "./auth";
import { routes } from "./router";
import { toApiRequest } from "./http";

interface LogFields {
  requestId: string;
  routeKey: string;
  status: number;
  durationMs: number;
  errorCode?: string;
}

function log(fields: LogFields): void {
  // Structured, one line per request. Deliberately excludes path/query — those
  // can carry a filename or stem, which is user content the privacy policy
  // promises never to log.
  console.log(JSON.stringify(fields));
}

export async function handler(
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
  context: Context,
): Promise<APIGatewayProxyStructuredResultV2> {
  const requestId = event.requestContext.requestId ?? context.awsRequestId ?? randomUUID();
  const routeKey = event.routeKey ?? `${event.requestContext.http.method} ${event.rawPath}`;
  const start = Date.now();

  const entry = routes[routeKey];
  if (!entry) {
    log({ requestId, routeKey, status: 404, durationMs: Date.now() - start });
    return respond(404, { error: "not found" });
  }

  try {
    let auth;
    let identity;
    if (entry.authMode === "owner") {
      auth = await resolveAuth(event);
      identity = extractJwtIdentity(event);
    } else if (entry.authMode === "identity") {
      identity = extractJwtIdentity(event);
    }

    const req = toApiRequest(event, requestId, auth, identity);
    const res = await entry.handler(req);
    log({ requestId, routeKey, status: res.statusCode, durationMs: Date.now() - start });
    return respond(res.statusCode, res.body);
  } catch (err) {
    if (err instanceof ApiError) {
      const status = HTTP_STATUS_BY_CODE[err.code];
      log({
        requestId,
        routeKey,
        status,
        durationMs: Date.now() - start,
        errorCode: err.code,
      });
      return respond(status, { error: err.message });
    }

    // Never leak an SDK exception message to the client.
    log({
      requestId,
      routeKey,
      status: 500,
      durationMs: Date.now() - start,
      errorCode: "INTERNAL",
    });
    console.error(JSON.stringify({ requestId, err: err instanceof Error ? err.stack : err }));
    return respond(500, { error: "internal error", requestId });
  }
}

function respond(
  statusCode: number,
  body?: unknown,
): APIGatewayProxyStructuredResultV2 {
  return {
    statusCode,
    headers: { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  };
}
