// GET/POST /keys, DELETE /keys/{wrapId} — plan step 1.8.
import { ApiError } from "@archivist/core/errors";
import { deleteKeyWrap, listKeyWraps, putKeyWrap } from "@archivist/core/repo/keys";
import { newUlid } from "@archivist/core/ids";
import { toIsoUtc } from "@archivist/core/time";
import type { KeyWrapItem, WrapKind } from "@archivist/core/items";
import { created, noContent, ok, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

// Metadata-only view: never returns another device's unwrapping material.
type KeyWrapMeta = Pick<
  KeyWrapItem,
  "wrapId" | "kind" | "label" | "masterKeyVer" | "createdAt" | "lastUsedAt"
>;

function toMeta(item: KeyWrapItem): KeyWrapMeta {
  const meta: KeyWrapMeta = {
    wrapId: item.wrapId,
    kind: item.kind,
    label: item.label,
    masterKeyVer: item.masterKeyVer,
    createdAt: item.createdAt,
  };
  if (item.lastUsedAt) meta.lastUsedAt = item.lastUsedAt;
  return meta;
}

export const getKeys: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const wraps = await listKeyWraps(ownerId);
  // The caller identifies its own wrapping so it (and only it) gets the full
  // unwrapping material back — everyone else's is metadata only.
  const ownWrapId = req.query["wrapId"];

  return ok({
    wraps: wraps.map((w) => (w.wrapId === ownWrapId ? w : toMeta(w))),
  });
};

interface PostKeyBody {
  kind: WrapKind;
  label: string;
  wrapAlg: "AES-KW" | "RSA-OAEP-256";
  wrappedKey: string;
  masterKeyVer: string;
  credentialId?: string;
  prfSalt?: string;
  kdfSalt?: string;
  kdfParams?: { alg: "argon2id"; m: string; t: number; p: number };
}

const VALID_KINDS: WrapKind[] = ["device", "passkey", "recovery"];

export const postKey: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const body = parseJsonBody<PostKeyBody>(req);

  if (!VALID_KINDS.includes(body.kind)) {
    throw ApiError.validation("kind must be device, passkey or recovery");
  }
  if (!body.label || !body.wrapAlg || !body.wrappedKey || !body.masterKeyVer) {
    throw ApiError.validation("label, wrapAlg, wrappedKey and masterKeyVer are required");
  }
  if (body.kind === "passkey" && (!body.credentialId || !body.prfSalt)) {
    throw ApiError.validation("passkey wrappings require credentialId and prfSalt");
  }
  if (body.kind === "recovery" && (!body.kdfSalt || !body.kdfParams)) {
    throw ApiError.validation("recovery wrappings require kdfSalt and kdfParams");
  }

  const wrapId = newUlid();
  const item: Omit<KeyWrapItem, "pk" | "sk"> = {
    wrapId,
    kind: body.kind,
    label: body.label.slice(0, 200),
    masterKeyVer: body.masterKeyVer,
    wrapAlg: body.wrapAlg,
    wrappedKey: body.wrappedKey,
    createdAt: toIsoUtc(new Date()),
    ...(body.credentialId ? { credentialId: body.credentialId } : {}),
    ...(body.prfSalt ? { prfSalt: body.prfSalt } : {}),
    ...(body.kdfSalt ? { kdfSalt: body.kdfSalt } : {}),
    ...(body.kdfParams ? { kdfParams: body.kdfParams } : {}),
  };

  await putKeyWrap(ownerId, item);
  return created({ wrapId });
};

export const deleteKey: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const wrapId = req.params["wrapId"];
  if (!wrapId) throw ApiError.validation("wrapId is required");

  await deleteKeyWrap(ownerId, wrapId);
  return noContent();
};
