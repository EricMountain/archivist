// GET /settings, PATCH /settings — plan step 1.17. The non-secret subset of the
// owner's #SETTINGS item, read by every client before it acts on policy (uploads
// already need this the same way they need homeTz). Deliberately narrow: PATCH only
// ever accepts stripLocationOnUpload for now — see routes/keys.ts for the routes that
// already own encHashSecret/masterKeyVerSeq, and design.md's "Owner settings endpoint"
// for why this isn't a generic attribute patcher.
import { ApiError } from "@archivist/core/errors";
import { getOwnerSettings, setStripLocationOnUpload } from "@archivist/core/repo/identity";
import { ok, noContent, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

export const getSettings: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const settings = await getOwnerSettings(ownerId);
  return ok({
    homeTz: settings?.homeTz ?? "UTC",
    displayName: settings?.displayName,
    stripLocationOnUpload: settings?.stripLocationOnUpload ?? false,
  });
};

interface PatchSettingsBody {
  stripLocationOnUpload?: boolean;
}

const PATCHABLE_FIELDS = new Set(["stripLocationOnUpload"]);

export const patchSettings: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const body = parseJsonBody<PatchSettingsBody>(req);

  const unknown = Object.keys(body).filter((k) => !PATCHABLE_FIELDS.has(k));
  if (unknown.length > 0) {
    throw ApiError.validation(`unsupported field(s): ${unknown.join(", ")}`);
  }

  if (body.stripLocationOnUpload !== undefined) {
    if (typeof body.stripLocationOnUpload !== "boolean") {
      throw ApiError.validation("stripLocationOnUpload must be a boolean");
    }
    await setStripLocationOnUpload(ownerId, body.stripLocationOnUpload);
  }

  return noContent();
};
