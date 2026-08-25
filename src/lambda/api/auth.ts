// Turns a JWT into an authorised { userId, ownerId, role }. API Gateway's JWT
// authorizer (cognito.tf + api.tf) has already verified the token's signature and
// audience by the time this runs — this module only resolves *identity*, never
// re-verifies the token.
//
// JWT claims -> IDP#<issuer>#<sub> pointer -> userId -> membership -> ownerId.
// Every handler gets the resolved context and must never accept an ownerId from
// the request body or path — that's the whole authorisation model. See
// "Authorisation middleware" in plan step 1.6.
import type { APIGatewayProxyEventV2WithJWTAuthorizer } from "aws-lambda";
import { ApiError } from "@archivist/core/errors";
import { resolveIdpPointer } from "@archivist/core/repo/pointers";
import { listMemberships } from "@archivist/core/repo/identity";
import type { MembershipRole } from "@archivist/core/items";

export interface JwtIdentity {
  issuer: string;
  subject: string;
}

export interface AuthContext {
  userId: string;
  ownerId: string;
  role: MembershipRole;
}

/** Short labels, matching the `IDP#<issuer>#<sub>` shape in sample-data.md —
 * never the full issuer URL, which would make the pointer key unwieldy and
 * couples it to a hostname that can change. */
function shortIssuer(iss: string): string {
  if (iss.includes("cognito-idp.")) return "cognito";
  if (iss === "https://accounts.google.com") return "google";
  throw ApiError.unauthorized("unrecognised token issuer");
}

export function extractJwtIdentity(
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): JwtIdentity {
  const claims = event.requestContext.authorizer?.jwt?.claims;
  const sub = claims?.["sub"];
  const iss = claims?.["iss"];
  if (typeof sub !== "string" || typeof iss !== "string") {
    throw ApiError.unauthorized("missing token claims");
  }
  return { issuer: shortIssuer(iss), subject: sub };
}

interface CacheEntry {
  userId: string;
  expiresAt: number;
}

// Per-warm-container cache of issuer#subject -> userId. The membership check
// below is deliberately *not* cached — it's a key lookup, and staying per-request
// means a revoked membership takes effect on the very next call.
const identityCache = new Map<string, CacheEntry>();
const IDENTITY_CACHE_TTL_MS = 5 * 60 * 1000;

function cacheKey(identity: JwtIdentity): string {
  return `${identity.issuer}#${identity.subject}`;
}

export function cacheUserId(identity: JwtIdentity, userId: string): void {
  identityCache.set(cacheKey(identity), {
    userId,
    expiresAt: Date.now() + IDENTITY_CACHE_TTL_MS,
  });
}

/** Resolves the IDP pointer to a userId, or undefined if this identity has never
 * signed in — the case /session/bootstrap exists to handle. */
export async function resolveUserId(identity: JwtIdentity): Promise<string | undefined> {
  const cached = identityCache.get(cacheKey(identity));
  if (cached && cached.expiresAt > Date.now()) {
    return cached.userId;
  }
  const ptr = await resolveIdpPointer(identity.issuer, identity.subject);
  if (!ptr) return undefined;
  cacheUserId(identity, ptr.userId);
  return ptr.userId;
}

/** Full resolution for every route except /health and /session/bootstrap, which
 * has to handle "no userId yet" itself. */
export async function resolveAuth(
  event: APIGatewayProxyEventV2WithJWTAuthorizer,
): Promise<AuthContext> {
  const identity = extractJwtIdentity(event);
  const userId = await resolveUserId(identity);
  if (!userId) {
    throw ApiError.unauthorized("unknown identity — call POST /session/bootstrap first");
  }
  const memberships = await listMemberships(userId);
  const membership = memberships[0];
  if (!membership) {
    throw ApiError.forbidden("no library membership");
  }
  return { userId, ownerId: membership.ownerId, role: membership.role };
}
