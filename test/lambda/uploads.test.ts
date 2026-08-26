// Exercises the hash-check decision tree in routes/uploads.ts directly — the
// trashed-live restore-or-record branch and the tombstone block-and-skip branch
// that plan steps 1.9/1.12/1.13 added. Same DynamoDB Local + MinIO gate as the
// other lambda-level suites.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { bootstrapUser } from "../../src/core/repo/session";
import { getAssetPartition } from "../../src/core/repo/media";
import { getHashPointer } from "../../src/core/repo/pointers";
import { postUpload } from "../../src/lambda/api/routes/uploads";
import { getTrash } from "../../src/lambda/api/routes/photos";
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
    displayName: "Uploads Test",
    homeTz: "UTC",
  });
  return { userId, ownerId };
}

function uploadReq(ownerId: string, userId: string, body: Record<string, unknown>): ApiRequest {
  return {
    method: "POST",
    path: "/uploads",
    params: {},
    query: {},
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: JSON.stringify(body),
  };
}

function baseUploadBody(overrides: Record<string, unknown> = {}) {
  const stem = `2026/uploads-test/${newUlid()}`;
  return {
    path: `${stem}.jpg`,
    plainBytes: 100,
    bytes: 116,
    mime: "image/jpeg",
    width: 100,
    height: 100,
    contentHash: `hmac-sha256:${newUlid()}`,
    takenAt: toIsoUtc(new Date()),
    takenAtSrc: "upload",
    tzOffsetMin: 0,
    tzSrc: "assumed-utc",
    encDek: "dek",
    encKeyId: "mk-test",
    encIv: "iv",
    encChunkSize: 0,
    deviceKey: "test-device",
    ...overrides,
  };
}

describe.skipIf(!RUN)("POST /uploads — hash hit on a trashed asset", () => {
  it("without reAddDeleted: records a blocked attempt and reports it, without restoring", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody();

    const first = await postUpload(uploadReq(ownerId, userId, body));
    const photoId = (first.body as { photoId: string }).photoId;

    // Trash it directly (bypassing the API — same effect as DELETE /photos/{id}).
    const { trashAsset } = await import("../../src/core/repo/trash");
    await trashAsset(ownerId, photoId, toIsoUtc(new Date()), "test-device");

    const second = await postUpload(uploadReq(ownerId, userId, body));
    expect(second.body).toMatchObject({ photoId, duplicate: true, trashed: true });

    const ptr = await getHashPointer(ownerId, body.contentHash as string);
    expect((ptr as { blockedAttempts?: number }).blockedAttempts).toBe(1);
    expect((ptr as { lastAttemptBy?: string }).lastAttemptBy).toBe("test-device");

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.deletedAt).toBeDefined(); // still trashed — not restored

    // GET /trash surfaces exactly this warning — plan step 1.12.
    const trash = await getTrash({
      method: "GET",
      path: "/trash",
      params: {},
      query: {},
      auth: { userId, ownerId, role: "owner" },
      requestId: newUlid(),
      rawBody: undefined,
    });
    const trashItems = (trash.body as { items: Array<{ photoId: string; blockedAttempts?: number; lastAttemptBy?: string }> }).items;
    const entry = trashItems.find((i) => i.photoId === photoId);
    expect(entry?.blockedAttempts).toBe(1);
    expect(entry?.lastAttemptBy).toBe("test-device");
  });

  it("with reAddDeleted: restores the asset instead of recording a block", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody();

    const first = await postUpload(uploadReq(ownerId, userId, body));
    const photoId = (first.body as { photoId: string }).photoId;

    const { trashAsset } = await import("../../src/core/repo/trash");
    await trashAsset(ownerId, photoId, toIsoUtc(new Date()), "test-device");

    const second = await postUpload(
      uploadReq(ownerId, userId, { ...body, reAddDeleted: true }),
    );
    expect(second.body).toMatchObject({ photoId, restored: true });

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.deletedAt).toBeUndefined();
  });
});

describe.skipIf(!RUN)("POST /uploads — hash hit on a purge tombstone", () => {
  it("without reAddDeleted: skips silently and records the block with an expiry", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody();

    const first = await postUpload(uploadReq(ownerId, userId, body));
    const photoId = (first.body as { photoId: string }).photoId;

    const { trashAsset } = await import("../../src/core/repo/trash");
    const { purgeAsset } = await import("../../src/core/repo/purge");
    await trashAsset(ownerId, photoId, toIsoUtc(new Date()), "test-device");
    await purgeAsset(ownerId, photoId, toIsoUtc(new Date()), 365);

    const third = await postUpload(uploadReq(ownerId, userId, body));
    expect(third.body).toEqual({ skipped: true });

    const ptr = await getHashPointer(ownerId, body.contentHash as string);
    const record = ptr as { blockedAttempts?: number; expiresAt?: number; kind?: string };
    expect(record.kind).toBe("purged");
    expect(record.blockedAttempts).toBe(1);
    expect(record.expiresAt).toBeGreaterThan(Math.floor(Date.now() / 1000));
  });
});
