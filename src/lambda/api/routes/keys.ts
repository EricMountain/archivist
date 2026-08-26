// GET/POST /keys, DELETE /keys/{wrapId}, POST /keys/version, PUT /keys/hash-secret
// — plan step 1.8.
import { ApiError } from "@archivist/core/errors";
import {
  allocateMasterKeyVer,
  deleteKeyWrap,
  getCurrentMasterKeyVersion,
  listKeyWraps,
  putHashSecret,
  putKeyWrap,
} from "@archivist/core/repo/keys";
import { newUlid } from "@archivist/core/ids";
import { toIsoUtc } from "@archivist/core/time";
import type { KeyWrapItem, WrapKind } from "@archivist/core/items";
import { created, noContent, ok, parseJsonBody } from "../http";
import type { ApiRequest, RouteHandler } from "../http";

// Metadata-only view: never returns another device's unwrapping material.
type KeyWrapMeta = Pick<
  KeyWrapItem,
  "wrapId" | "kind" | "label" | "masterKeyVer" | "rotatedAt" | "createdAt" | "lastUsedAt"
>;

function toMeta(item: KeyWrapItem): KeyWrapMeta {
  const meta: KeyWrapMeta = {
    wrapId: item.wrapId,
    kind: item.kind,
    label: item.label,
    masterKeyVer: item.masterKeyVer,
    createdAt: item.createdAt,
  };
  if (item.rotatedAt) meta.rotatedAt = item.rotatedAt;
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
  if (!body.label || !body.wrapAlg || !body.wrappedKey) {
    throw ApiError.validation("label, wrapAlg and wrappedKey are required");
  }
  if (body.kind === "passkey" && (!body.credentialId || !body.prfSalt)) {
    throw ApiError.validation("passkey wrappings require credentialId and prfSalt");
  }
  if (body.kind === "recovery" && (!body.kdfSalt || !body.kdfParams)) {
    throw ApiError.validation("recovery wrappings require kdfSalt and kdfParams");
  }

  // masterKeyVer is never a request field — see "Master key versions" in
  // design.md. A client that hasn't called POST /keys/version yet (a fresh
  // owner, before its first enrolment) has nothing to wrap against.
  const current = await getCurrentMasterKeyVersion(ownerId);
  if (!current) {
    throw ApiError.conflict("no master key version allocated — call POST /keys/version first");
  }

  const wrapId = newUlid();
  const item: Omit<KeyWrapItem, "pk" | "sk"> = {
    wrapId,
    kind: body.kind,
    label: body.label.slice(0, 200),
    masterKeyVer: current.masterKeyVer,
    rotatedAt: current.rotatedAt,
    wrapAlg: body.wrapAlg,
    wrappedKey: body.wrappedKey,
    createdAt: toIsoUtc(new Date()),
    ...(body.credentialId ? { credentialId: body.credentialId } : {}),
    ...(body.prfSalt ? { prfSalt: body.prfSalt } : {}),
    ...(body.kdfSalt ? { kdfSalt: body.kdfSalt } : {}),
    ...(body.kdfParams ? { kdfParams: body.kdfParams } : {}),
  };

  await putKeyWrap(ownerId, item);
  return created({ wrapId, masterKeyVer: current.masterKeyVer });
};

export const deleteKey: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const wrapId = req.params["wrapId"];
  if (!wrapId) throw ApiError.validation("wrapId is required");

  await deleteKeyWrap(ownerId, wrapId);
  return noContent();
};

/** Mints the next master key version. Called once at the start of enrolment or
 * rotation — never per-device — and the result is what POST /keys stamps on
 * every wrapping written afterward. See "Master key versions" in design.md. */
export const postKeyVersion: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const result = await allocateMasterKeyVer(ownerId);
  return created(result);
};

interface PutHashSecretBody {
  encHashSecret: string;
  hashSecretKeyId: string;
}

export const putKeyHashSecret: RouteHandler = async (req: ApiRequest) => {
  const ownerId = req.auth!.ownerId;
  const body = parseJsonBody<PutHashSecretBody>(req);
  if (!body.encHashSecret || !body.hashSecretKeyId) {
    throw ApiError.validation("encHashSecret and hashSecretKeyId are required");
  }
  await putHashSecret(ownerId, body.encHashSecret, body.hashSecretKeyId);
  return noContent();
};
