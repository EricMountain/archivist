// POST /session/bootstrap — plan step 1.7.
import { ApiError } from "@archivist/core/errors";
import { bootstrapUser } from "@archivist/core/repo/session";
import { ok } from "../http";
import type { ApiRequest, RouteHandler } from "../http";
import { parseJsonBody } from "../http";

interface BootstrapBody {
  displayName?: string;
  email?: string;
  /** IANA zone name, e.g. "Europe/Paris" — never a raw offset. Defaults to UTC. */
  homeTz?: string;
}

function isPlausibleIanaZone(zone: string): boolean {
  // A real validity check needs Intl.supportedValuesOf("timeZone") or a try/catch
  // through Intl.DateTimeFormat — cheap enough to just attempt it.
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

export const postSessionBootstrap: RouteHandler = async (req: ApiRequest) => {
  if (!req.identity) {
    throw ApiError.unauthorized();
  }

  const body = req.rawBody ? parseJsonBody<BootstrapBody>(req) : {};
  const homeTz = body.homeTz ?? "UTC";
  if (!isPlausibleIanaZone(homeTz)) {
    throw ApiError.validation("homeTz must be a valid IANA zone name");
  }

  const result = await bootstrapUser({
    issuer: req.identity.issuer,
    subject: req.identity.subject,
    displayName: body.displayName?.slice(0, 100) || "New user",
    email: body.email?.slice(0, 320),
    homeTz,
  });

  return ok(result);
};
