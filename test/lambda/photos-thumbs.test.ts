// POST /photos/{photoId}/thumbs — the one-photo thumbnail repair endpoint (see its own
// doc in routes/photos.ts). Same DynamoDB Local + MinIO gate as the other lambda-level
// suites.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { bootstrapUser } from "../../src/core/repo/session";
import { getAssetPartition } from "../../src/core/repo/media";
import { postUpload } from "../../src/lambda/api/routes/uploads";
import { postPhotoThumbs } from "../../src/lambda/api/routes/photos";
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
    displayName: "Thumbs Test",
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

function thumbsReq(
  ownerId: string,
  userId: string,
  photoId: string,
  body: Record<string, unknown>,
): ApiRequest {
  return {
    method: "POST",
    path: `/photos/${photoId}/thumbs`,
    params: { photoId },
    query: {},
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: JSON.stringify(body),
  };
}

async function newAsset(ownerId: string, userId: string) {
  const stem = `2026/thumbs-test/${newUlid()}`;
  const body = {
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
    // No thumbs on the initial upload — this is exactly the "thumbnail generation
    // failed on-device" case repair exists to recover from.
  };
  const response = await postUpload(uploadReq(ownerId, userId, body));
  return (response.body as { photoId: string }).photoId;
}

describe.skipIf(!RUN)("POST /photos/{photoId}/thumbs", () => {
  it("presigns fresh PUT URLs and persists them on #META.thumbs", async () => {
    const { userId, ownerId } = await newOwner();
    const photoId = await newAsset(ownerId, userId);

    const { meta: before } = await getAssetPartition(ownerId, photoId);
    expect(before?.thumbs).toEqual({});

    const response = await postPhotoThumbs(
      thumbsReq(ownerId, userId, photoId, {
        thumbs: {
          "256": { bytes: 1234, iv: "iv-256" },
          "1024": { bytes: 5678, iv: "iv-1024" },
          "2048": { bytes: 9012, iv: "iv-2048" },
        },
      }),
    );

    const uploads = (response.body as { thumbUploads: Record<string, string> }).thumbUploads;
    expect(Object.keys(uploads).sort()).toEqual(["1024", "2048", "256"]);
    for (const url of Object.values(uploads)) expect(url).toMatch(/^https?:\/\//);

    const { meta: after } = await getAssetPartition(ownerId, photoId);
    expect(after?.thumbs[256]).toMatchObject({ iv: "iv-256", bytes: 1234 });
    expect(after?.thumbs[1024]).toMatchObject({ iv: "iv-1024", bytes: 5678 });
    expect(after?.thumbs[2048]).toMatchObject({ iv: "iv-2048", bytes: 9012 });
  });

  it("overwrites only the sizes sent, keeping any others untouched", async () => {
    const { userId, ownerId } = await newOwner();
    const photoId = await newAsset(ownerId, userId);

    await postPhotoThumbs(
      thumbsReq(ownerId, userId, photoId, {
        thumbs: { "256": { bytes: 1, iv: "first" }, "1024": { bytes: 2, iv: "first" } },
      }),
    );
    await postPhotoThumbs(
      thumbsReq(ownerId, userId, photoId, { thumbs: { "256": { bytes: 3, iv: "second" } } }),
    );

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.thumbs[256]).toMatchObject({ iv: "second", bytes: 3 });
    expect(meta?.thumbs[1024]).toMatchObject({ iv: "first", bytes: 2 });
  });

  it("rejects an unknown photoId", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(
      postPhotoThumbs(
        thumbsReq(ownerId, userId, newUlid(), { thumbs: { "256": { bytes: 1, iv: "x" } } }),
      ),
    ).rejects.toThrow(/not found/i);
  });

  it("rejects an empty thumbs body", async () => {
    const { userId, ownerId } = await newOwner();
    const photoId = await newAsset(ownerId, userId);
    await expect(postPhotoThumbs(thumbsReq(ownerId, userId, photoId, {}))).rejects.toThrow(
      /thumbs/i,
    );
  });
});
