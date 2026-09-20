// GET /photos/by-path — resolves an exact path straight to {photoId, renditionId}
// via the PATH pointer (pattern 1b'), without paging through GET /photos/GET /trash.
// Same DynamoDB Local gate as the other lambda-level suites.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { createAsset } from "../../src/core/repo/ingest";
import { trashAsset } from "../../src/core/repo/trash";
import { getPhotoByPath } from "../../src/lambda/api/routes/photos";
import type { ApiRequest } from "../../src/lambda/api/http";
import type { MetaItem, RenditionItem } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

function req(ownerId: string, path?: string): ApiRequest {
  return {
    method: "GET",
    path: "/photos/by-path",
    params: {},
    query: path === undefined ? {} : { path },
    auth: { userId: newUlid(), ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: undefined,
  };
}

async function addOnePhoto(ownerId: string, path: string) {
  const photoId = newUlid();
  const renditionId = newUlid();
  const stem = path.replace(/\.[^.]+$/, "");
  const meta: Omit<MetaItem, "pk" | "sk" | "timelinePk" | "timelineSk"> = {
    ownerId,
    photoId,
    stem,
    renditions: 1,
    mime: "image/jpeg",
    width: 10,
    height: 10,
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
  };
  const rendition: Omit<RenditionItem, "pk" | "sk"> = {
    renditionId,
    role: "display",
    path,
    mime: "image/jpeg",
    width: 10,
    height: 10,
    contentHash: `hmac-sha256:${newUlid()}`,
    bytes: 100,
    thumbs: {},
  };
  await createAsset({ stem, path, hmac: rendition.contentHash, meta, rendition });
  return { photoId, renditionId };
}

describe.skipIf(!RUN)("GET /photos/by-path", () => {
  it("resolves an exact path to {photoId, renditionId}", async () => {
    const ownerId = `01BYPATH${newUlid().slice(-13)}`;
    const path = `-1739773001/IMG_${newUlid()}.jpg`;
    const { photoId, renditionId } = await addOnePhoto(ownerId, path);

    const res = await getPhotoByPath(req(ownerId, path));

    expect(res.statusCode).toBe(200);
    expect(res.body).toEqual({ photoId, renditionId });
  });

  it("404s for a path nothing is filed under", async () => {
    const ownerId = `01BYPATH${newUlid().slice(-13)}`;

    await expect(getPhotoByPath(req(ownerId, "no/such/path.jpg"))).rejects.toThrow(/no asset at that path/i);
  });

  it("requires the path query param", async () => {
    const ownerId = `01BYPATH${newUlid().slice(-13)}`;

    await expect(getPhotoByPath(req(ownerId))).rejects.toThrow(/path is required/i);
  });

  it("still resolves once the asset is trashed — trashing never touches PATH pointers", async () => {
    const ownerId = `01BYPATH${newUlid().slice(-13)}`;
    const path = `-1739773001/IMG_${newUlid()}.jpg`;
    const { photoId, renditionId } = await addOnePhoto(ownerId, path);

    await trashAsset(ownerId, photoId, toIsoUtc(new Date()), "test");

    const res = await getPhotoByPath(req(ownerId, path));
    expect(res.statusCode).toBe(200);
    expect(res.body).toEqual({ photoId, renditionId });
  });

  it("doesn't leak into another owner's pointer for the same path", async () => {
    const ownerA = `01BYPATH${newUlid().slice(-13)}`;
    const ownerB = `01BYPATH${newUlid().slice(-13)}`;
    const path = `-1739773001/IMG_${newUlid()}.jpg`;
    await addOnePhoto(ownerA, path);

    await expect(getPhotoByPath(req(ownerB, path))).rejects.toThrow(/no asset at that path/i);
  });
});
