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

describe.skipIf(!RUN)("POST /uploads — client-supplied photoId (plan step 2.10)", () => {
  it("creating a new asset uses the client's candidate photoId and echoes created: true plus its own encDek/encKeyId", async () => {
    const { userId, ownerId } = await newOwner();
    const candidatePhotoId = newUlid();
    const body = baseUploadBody({ photoId: candidatePhotoId, encDek: "dek-candidate", encKeyId: "mk-candidate" });

    const res = await postUpload(uploadReq(ownerId, userId, body));
    expect(res.body).toMatchObject({
      photoId: candidatePhotoId,
      created: true,
      encDek: "dek-candidate",
      encKeyId: "mk-candidate",
    });

    const { meta } = await getAssetPartition(ownerId, candidatePhotoId);
    expect(meta?.photoId).toBe(candidatePhotoId);
  });

  it("attaching to an existing asset discards the candidate and reports created: false with the existing encDek/encKeyId", async () => {
    const { userId, ownerId } = await newOwner();
    const stem = `2026/uploads-test/${newUlid()}`;

    const firstCandidate = newUlid();
    const first = await postUpload(
      uploadReq(
        ownerId,
        userId,
        baseUploadBody({
          path: `${stem}.cr3`,
          photoId: firstCandidate,
          encDek: "dek-original",
          encKeyId: "mk-original",
        }),
      ),
    );
    expect(first.body).toMatchObject({ photoId: firstCandidate, created: true });

    const secondCandidate = newUlid();
    const second = await postUpload(
      uploadReq(
        ownerId,
        userId,
        baseUploadBody({
          path: `${stem}.jpg`,
          photoId: secondCandidate,
          encDek: "dek-second-candidate",
          encKeyId: "mk-second-candidate",
        }),
      ),
    );
    // Attached to the first asset: photoId is the *existing* one, not the second
    // upload's own candidate, and the DEK returned is the existing asset's — the
    // second candidate's encDek/encKeyId never make it into the response or #META.
    expect(second.body).toMatchObject({
      photoId: firstCandidate,
      created: false,
      encDek: "dek-original",
      encKeyId: "mk-original",
    });
    expect((second.body as { photoId: string }).photoId).not.toBe(secondCandidate);

    const { meta } = await getAssetPartition(ownerId, firstCandidate);
    expect(meta?.encDek).toBe("dek-original"); // unchanged by the attach
  });

  it("rejects a photoId that isn't a ULID", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody({ photoId: "not-a-ulid" });
    await expect(postUpload(uploadReq(ownerId, userId, body))).rejects.toThrow();
  });

  it("streaming mode (encChunkSize > 0) is accepted with no encIv at all", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody({ encChunkSize: 1_048_576 });
    delete (body as { encIv?: unknown }).encIv;
    const res = await postUpload(uploadReq(ownerId, userId, body));
    expect(res.body).toMatchObject({ created: true });
  });

  it("whole-object mode (encChunkSize: 0) still requires encIv", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody({ encChunkSize: 0 });
    delete (body as { encIv?: unknown }).encIv;
    await expect(postUpload(uploadReq(ownerId, userId, body))).rejects.toThrow();
  });
});

describe.skipIf(!RUN)("POST /uploads — resuming an interrupted upload (plan step 2.10)", () => {
  it("re-uploading the same content while the asset is still processing re-presigns instead of a bare duplicate", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody({ encDek: "dek-original", encKeyId: "mk-original" });

    const first = await postUpload(uploadReq(ownerId, userId, body));
    const { photoId, renditionId } = first.body as { photoId: string; renditionId: string };

    // Nothing marked it "ready" (that's the S3-event lambda, plan step 1.10) --
    // simulating a client that died mid-PUT, before any bytes arrived.
    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.status).toBe("processing");

    const retry = await postUpload(uploadReq(ownerId, userId, body));
    expect(retry.body).toMatchObject({
      photoId,
      renditionId,
      resumed: true,
      created: false,
      encDek: "dek-original",
      encKeyId: "mk-original",
      // The rendition's own encIv/encChunkSize were fixed by the first attempt's
      // transaction and must come back unchanged, not regenerated — a resuming
      // client has to reuse them bit-for-bit or its ciphertext won't match what
      // this item already (permanently) claims decrypts it.
      encIv: "iv",
      encChunkSize: 0,
    });
    expect((retry.body as { originalUpload?: { url: string } }).originalUpload?.url).toBeTruthy();
  });

  it("re-uploading the same content once the asset is ready reports a bare duplicate, no presigned URLs", async () => {
    const { userId, ownerId } = await newOwner();
    const body = baseUploadBody();

    const first = await postUpload(uploadReq(ownerId, userId, body));
    const { photoId } = first.body as { photoId: string };

    const { setAssetStatus } = await import("../../src/core/repo/media");
    await setAssetStatus(ownerId, photoId, "ready");

    const retry = await postUpload(uploadReq(ownerId, userId, body));
    expect(retry.body).toEqual({ photoId, renditionId: (first.body as { renditionId: string }).renditionId, duplicate: true });
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
