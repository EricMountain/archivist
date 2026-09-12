// Exercises the conditional-request wiring on the two cacheable /photos routes —
// getPhotosBounds and getPhotosHistogram share the same `revalidate` helper and the
// same ETag, so one suite covers both. Same DynamoDB Local gate as the other
// lambda-level suites.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { createAsset } from "../../src/core/repo/ingest";
import { getPhotosBounds, getPhotosHistogram } from "../../src/lambda/api/routes/photos";
import type { ApiRequest } from "../../src/lambda/api/http";
import type { MetaItem, RenditionItem } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

function req(ownerId: string, ifNoneMatch?: string): ApiRequest {
  return {
    method: "GET",
    path: "/photos/bounds",
    params: {},
    query: {},
    auth: { userId: newUlid(), ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: undefined,
    headers: ifNoneMatch ? { "if-none-match": ifNoneMatch } : {},
  };
}

async function addOnePhoto(ownerId: string) {
  const photoId = newUlid();
  const stem = `2026/photos-cache-test/${newUlid()}`;
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
  };
  const rendition: Omit<RenditionItem, "pk" | "sk"> = {
    renditionId: newUlid(),
    role: "display",
    path: `${stem}.JPG`,
    mime: "image/jpeg",
    width: 10,
    height: 10,
    contentHash: `hmac-sha256:${newUlid()}`,
    bytes: 100,
    thumbs: {},
  };
  await createAsset({ stem, path: rendition.path, hmac: rendition.contentHash, meta, rendition });
  return photoId;
}

describe.skipIf(!RUN)("GET /photos/bounds and /photos/histogram — conditional requests", () => {
  it("both cacheable routes carry the same ETag, since both describe the same version", async () => {
    const ownerId = `01CACHE${newUlid().slice(0, 14)}`;
    await addOnePhoto(ownerId);

    const boundsRes = await getPhotosBounds(req(ownerId));
    const histogramRes = await getPhotosHistogram(req(ownerId));

    expect(boundsRes.statusCode).toBe(200);
    expect(boundsRes.headers?.["etag"]).toBeDefined();
    expect(histogramRes.headers?.["etag"]).toBe(boundsRes.headers?.["etag"]);
  });

  it("a matching If-None-Match gets a bodiless 304 with the cache-control header repeated", async () => {
    const ownerId = `01CACHE${newUlid().slice(0, 14)}`;
    await addOnePhoto(ownerId);

    const first = await getPhotosHistogram(req(ownerId));
    const etag = first.headers!["etag"]!;

    const revalidated = await getPhotosHistogram(req(ownerId, etag));

    expect(revalidated.statusCode).toBe(304);
    expect(revalidated.body).toBeUndefined();
    expect(revalidated.headers?.["etag"]).toBe(etag);
    expect(revalidated.headers?.["cache-control"]).toContain("must-revalidate");
  });

  it("a stale If-None-Match (a photo was added since) gets a fresh 200 with a new ETag", async () => {
    const ownerId = `01CACHE${newUlid().slice(0, 14)}`;
    await addOnePhoto(ownerId);
    const stale = await getPhotosHistogram(req(ownerId));
    const staleEtag = stale.headers!["etag"]!;

    await addOnePhoto(ownerId);
    const fresh = await getPhotosHistogram(req(ownerId, staleEtag));

    expect(fresh.statusCode).toBe(200);
    expect(fresh.headers?.["etag"]).not.toBe(staleEtag);
    expect((fresh.body as { total: number }).total).toBe(2);
  });

  it("an owner with no photos still gets a valid ETag (version zero) rather than an error", async () => {
    const ownerId = `01CACHEEMPTY${newUlid().slice(0, 8)}`;

    const res = await getPhotosHistogram(req(ownerId));

    expect(res.statusCode).toBe(200);
    expect(res.headers?.["etag"]).toBe('"tl-0"');
    expect(res.body).toEqual({ days: {}, total: 0, version: 0 });
  });
});
