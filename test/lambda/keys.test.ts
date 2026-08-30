// Exercises the /keys route handlers directly against DynamoDB Local. Same gate
// as the other DynamoDB-Local-backed suites.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { bootstrapUser } from "../../src/core/repo/session";
import { getOwnerSettings } from "../../src/core/repo/identity";
import { getKeyHashSecret, getKeys, postKey, postKeyVersion, putKeyHashSecret } from "../../src/lambda/api/routes/keys";
import type { ApiRequest } from "../../src/lambda/api/http";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

async function newOwner() {
  const { userId, ownerId } = await bootstrapUser({
    issuer: "cognito",
    subject: newUlid(),
    displayName: "Keys Test",
    homeTz: "UTC",
  });
  return { userId, ownerId };
}

function req(ownerId: string, userId: string, body?: unknown, query: Record<string, string> = {}): ApiRequest {
  return {
    method: "POST",
    path: "/keys",
    params: {},
    query,
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: body === undefined ? undefined : JSON.stringify(body),
  };
}

describe.skipIf(!RUN)("POST /keys", () => {
  it("refuses a client-supplied masterKeyVer field entirely — it's just ignored", async () => {
    const { userId, ownerId } = await newOwner();
    // No allocation yet: even a body that tries to smuggle masterKeyVer in must
    // still be refused, since the server never reads that field.
    await expect(
      postKey(
        req(ownerId, userId, {
          kind: "device",
          label: "Pixel",
          wrapAlg: "RSA-OAEP-256",
          wrappedKey: "w1",
          masterKeyVer: "mk-999", // must be silently ignored, not honoured
        }),
      ),
    ).rejects.toThrow(/master key version/i);
  });

  it("enrols against the server-allocated version once one exists", async () => {
    const { userId, ownerId } = await newOwner();
    const version = await postKeyVersion(req(ownerId, userId));
    const versionBody = version.body as { masterKeyVer: string; rotatedAt: string };
    expect(versionBody.masterKeyVer).toBe("mk-1");

    const res = await postKey(
      req(ownerId, userId, {
        kind: "device",
        label: "Pixel",
        wrapAlg: "RSA-OAEP-256",
        wrappedKey: "w1",
        masterKeyVer: "mk-999", // still ignored even when a real version exists
      }),
    );
    expect(res.statusCode).toBe(201);
    const wrapId = (res.body as { wrapId: string; masterKeyVer: string }).wrapId;
    expect((res.body as { masterKeyVer: string }).masterKeyVer).toBe("mk-1");

    const list = await getKeys(req(ownerId, userId, undefined, { wrapId }));
    const wraps = (list.body as { wraps: Array<{ wrapId: string; masterKeyVer: string; rotatedAt?: string }> }).wraps;
    const own = wraps.find((w) => w.wrapId === wrapId);
    expect(own?.masterKeyVer).toBe("mk-1");
    expect(own?.rotatedAt).toBe(versionBody.rotatedAt);
  });

  it("ECDH-ES+AES-KW wrappings require epk — key agreement, not key transport", async () => {
    const { userId, ownerId } = await newOwner();
    await postKeyVersion(req(ownerId, userId));

    await expect(
      postKey(
        req(ownerId, userId, {
          kind: "device",
          label: "Pixel",
          wrapAlg: "ECDH-ES+AES-KW",
          wrappedKey: "w1",
          // epk omitted
        }),
      ),
    ).rejects.toThrow(/epk/i);

    const res = await postKey(
      req(ownerId, userId, {
        kind: "device",
        label: "Pixel",
        wrapAlg: "ECDH-ES+AES-KW",
        wrappedKey: "w1",
        epk: "epk-bytes",
      }),
    );
    expect(res.statusCode).toBe(201);
  });
});

describe.skipIf(!RUN)("POST /keys/version", () => {
  it("two in-flight requests return different versions", async () => {
    const { userId, ownerId } = await newOwner();

    const [a, b] = await Promise.all([
      postKeyVersion(req(ownerId, userId)),
      postKeyVersion(req(ownerId, userId)),
    ]);

    const av = (a.body as { masterKeyVer: string }).masterKeyVer;
    const bv = (b.body as { masterKeyVer: string }).masterKeyVer;
    expect(av).not.toBe(bv);
    expect([av, bv].sort()).toEqual(["mk-1", "mk-2"]);
  });
});

describe.skipIf(!RUN)("PUT /keys/hash-secret", () => {
  it("stores the wrapped hash secret on #SETTINGS", async () => {
    const { userId, ownerId } = await newOwner();
    const res = await putKeyHashSecret(
      req(ownerId, userId, { encHashSecret: "wrapped", hashSecretKeyId: "mk-1" }),
    );
    expect(res.statusCode).toBe(204);

    const settings = await getOwnerSettings(ownerId);
    expect(settings?.encHashSecret).toBe("wrapped");
    expect(settings?.hashSecretKeyId).toBe("mk-1");
  });
});

describe.skipIf(!RUN)("GET /keys/hash-secret", () => {
  it("404s for an owner that has never PUT one — design.md: absent until then", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(getKeyHashSecret(req(ownerId, userId))).rejects.toThrow(/hash secret/i);
  });

  it("a second device can fetch what the first device PUT — closes design.md open question 4", async () => {
    const { userId, ownerId } = await newOwner();
    await putKeyHashSecret(req(ownerId, userId, { encHashSecret: "wrapped", hashSecretKeyId: "mk-1" }));

    const res = await getKeyHashSecret(req(ownerId, userId));
    expect(res.statusCode).toBe(200);
    expect(res.body).toEqual({ encHashSecret: "wrapped", hashSecretKeyId: "mk-1" });
  });
});
