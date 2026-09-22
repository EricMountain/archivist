// POST /photos/{photoId}/renditions/{renditionId}/replace — replacing a rendition's
// stored bytes in place (design.md "Replacing a rendition's bytes"). Same DynamoDB
// Local + MinIO gate as the other lambda-level suites.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { createAsset, attachRendition } from "../../src/core/repo/ingest";
import { trashAsset } from "../../src/core/repo/trash";
import { getAssetPartition } from "../../src/core/repo/media";
import { getHashPointer } from "../../src/core/repo/pointers";
import { postRenditionReplace } from "../../src/lambda/api/routes/photos";
import type { ApiRequest } from "../../src/lambda/api/http";
import type { MetaItem, RenditionItem } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

function req(
  ownerId: string,
  photoId: string,
  renditionId: string,
  body: Record<string, unknown> | undefined,
): ApiRequest {
  return {
    method: "POST",
    path: `/photos/${photoId}/renditions/${renditionId}/replace`,
    params: { photoId, renditionId },
    query: {},
    auth: { userId: newUlid(), ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: body === undefined ? undefined : JSON.stringify(body),
  };
}

function replaceBody(overrides: Record<string, unknown> = {}) {
  return {
    contentHash: `hmac-sha256:${newUlid()}`,
    plainBytes: 200,
    bytes: 216,
    mime: "image/jpeg",
    width: 60,
    height: 100,
    encIv: "aXY=",
    encChunkSize: 0,
    ...overrides,
  };
}

async function addOnePhoto(ownerId: string, overrides: Partial<Omit<MetaItem, "pk" | "sk">> = {}) {
  const photoId = newUlid();
  const renditionId = newUlid();
  const stem = `2026/replace-test/${newUlid()}`;
  const meta: Omit<MetaItem, "pk" | "sk" | "timelinePk" | "timelineSk"> = {
    ownerId,
    photoId,
    stem,
    renditions: 1,
    mime: "image/jpeg",
    width: 100,
    height: 60,
    enc: "AES-256-GCM",
    encDek: "dek",
    encKeyId: "mk-test",
    takenAt: toIsoUtc(new Date()),
    tzOffsetMin: 0,
    takenAtSrc: "exif",
    tzSrc: "exif",
    status: "ready",
    groupSrc: "stem",
    primaryRend: renditionId,
    ...overrides,
  };
  const rendition: Omit<RenditionItem, "pk" | "sk"> = {
    renditionId,
    role: "display",
    path: `${stem}.JPG`,
    ext: "jpg",
    mime: "image/jpeg",
    s3Bucket: process.env["ORIGINALS_BUCKET"] ?? "",
    s3Key: `raw/${ownerId}/${photoId}/${renditionId}`,
    contentHash: `hmac-sha256:${newUlid()}`,
    bytes: 116,
    plainBytes: 100,
    width: 100,
    height: 60,
    encIv: "b3JpZ2l2",
    encChunkSize: 0,
    addedAt: toIsoUtc(new Date()),
  };
  await createAsset({ stem, path: rendition.path, hmac: rendition.contentHash, meta, rendition });
  return { photoId, renditionId, originalHash: rendition.contentHash, path: rendition.path };
}

describe.skipIf(!RUN)("POST /photos/{photoId}/renditions/{renditionId}/replace", () => {
  it("rewrites the rendition's content in place, keeping renditionId/s3Key/path/role", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId, path } = await addOnePhoto(ownerId);
    const body = replaceBody();

    const res = await postRenditionReplace(req(ownerId, photoId, renditionId, body));
    expect(res.statusCode).toBe(200);
    expect((res.body as { uploadUrl: string }).uploadUrl).toContain("http");

    const { renditions } = await getAssetPartition(ownerId, photoId);
    expect(renditions).toHaveLength(1);
    const r = renditions[0]!;
    expect(r.renditionId).toBe(renditionId);
    expect(r.path).toBe(path);
    expect(r.role).toBe("display");
    expect(r.contentHash).toBe(body.contentHash);
    expect(r.plainBytes).toBe(body.plainBytes);
    expect(r.bytes).toBe(body.bytes);
    expect(r.width).toBe(body.width);
    expect(r.height).toBe(body.height);
    expect(r.encIv).toBe(body.encIv);
  });

  it("swaps the HASH pointer: old gone, new points to the same rendition", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId, originalHash } = await addOnePhoto(ownerId);
    const body = replaceBody();

    await postRenditionReplace(req(ownerId, photoId, renditionId, body));

    expect(await getHashPointer(ownerId, originalHash)).toBeUndefined();
    const newPtr = await getHashPointer(ownerId, body.contentHash);
    expect(newPtr?.photoId).toBe(photoId);
    expect(newPtr?.renditionId).toBe(renditionId);
    expect(newPtr?.kind).toBe("live");
  });

  it("updates #META's mime/width/height when the rendition is primary", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId } = await addOnePhoto(ownerId);
    const body = replaceBody({ mime: "image/heic", width: 60, height: 100 });

    await postRenditionReplace(req(ownerId, photoId, renditionId, body));

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.mime).toBe("image/heic");
    expect(meta?.width).toBe(60);
    expect(meta?.height).toBe(100);
  });

  it("leaves #META untouched when the rendition is not primary", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId: primaryId } = await addOnePhoto(ownerId);
    const secondaryId = newUlid();
    await attachRendition({
      ownerId,
      photoId,
      path: `2026/replace-test/${newUlid()}.raw`,
      hmac: `hmac-sha256:${newUlid()}`,
      rendition: {
        renditionId: secondaryId,
        role: "raw",
        path: `2026/replace-test/${newUlid()}.raw`,
        ext: "raw",
        mime: "image/x-raw",
        s3Bucket: process.env["ORIGINALS_BUCKET"] ?? "",
        s3Key: `raw/${ownerId}/${photoId}/${secondaryId}`,
        contentHash: `hmac-sha256:${newUlid()}`,
        bytes: 216,
        plainBytes: 200,
        width: 100,
        height: 60,
        encIv: "cmF3aXY=",
        encChunkSize: 0,
        addedAt: toIsoUtc(new Date()),
      },
    });

    const before = await getAssetPartition(ownerId, photoId);
    const body = replaceBody({ mime: "image/x-raw-fixed", width: 999, height: 999 });

    await postRenditionReplace(req(ownerId, photoId, secondaryId, body));

    const after = await getAssetPartition(ownerId, photoId);
    expect(after.meta?.mime).toBe(before.meta?.mime);
    expect(after.meta?.width).toBe(before.meta?.width);
    expect(after.meta?.height).toBe(before.meta?.height);
    expect(after.meta?.primaryRend).toBe(primaryId);
  });

  it("removes encIv when the replacement switches to streaming mode", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId } = await addOnePhoto(ownerId);
    const body = replaceBody({ encIv: undefined, encChunkSize: 1_048_576 });

    await postRenditionReplace(req(ownerId, photoId, renditionId, body));

    const { renditions } = await getAssetPartition(ownerId, photoId);
    expect(renditions[0]?.encIv).toBeUndefined();
    expect(renditions[0]?.encChunkSize).toBe(1_048_576);
  });

  it("404s for an unknown renditionId", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId } = await addOnePhoto(ownerId);

    await expect(postRenditionReplace(req(ownerId, photoId, newUlid(), replaceBody()))).rejects.toThrow(
      /rendition not found/i,
    );
  });

  it("refuses to replace on a trashed asset", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId } = await addOnePhoto(ownerId);
    await trashAsset(ownerId, photoId, toIsoUtc(new Date()), "test");

    await expect(postRenditionReplace(req(ownerId, photoId, renditionId, replaceBody()))).rejects.toThrow(
      /trashed/i,
    );
  });

  it("refuses a content hash that collides with a different already-live asset", async () => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const other = await addOnePhoto(ownerId);
    const { photoId, renditionId } = await addOnePhoto(ownerId);

    await expect(
      postRenditionReplace(req(ownerId, photoId, renditionId, replaceBody({ contentHash: other.originalHash }))),
    ).rejects.toThrow();
  });

  it.each([
    ["contentHash", { contentHash: undefined }, /contentHash/i],
    ["mime", { mime: undefined }, /mime/i],
    ["plainBytes", { plainBytes: 0 }, /plainBytes/i],
    ["bytes", { bytes: -1 }, /bytes/i],
    ["encChunkSize", { encChunkSize: -5 }, /encChunkSize/i],
    ["encIv for whole-object mode", { encIv: undefined, encChunkSize: 0 }, /encIv/i],
  ])("rejects a request missing %s", async (_label, overrides, expected) => {
    const ownerId = `01REPLACE${newUlid().slice(-11)}`;
    const { photoId, renditionId } = await addOnePhoto(ownerId);

    await expect(
      postRenditionReplace(req(ownerId, photoId, renditionId, replaceBody(overrides))),
    ).rejects.toThrow(expected);
  });
});
