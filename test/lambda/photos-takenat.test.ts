// PATCH /photos/{photoId} — manually correcting takenAt/tzOffsetMin (design.md
// "Manually correcting takenAt"). Same DynamoDB Local gate as the other
// lambda-level suites.
import { describe, expect, it } from "vitest";
import { PutCommand } from "@aws-sdk/lib-dynamodb";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { ddb, tableName } from "../../src/core/db";
import { facetGsiPk, facetSk, mediaPk, sortKey } from "../../src/core/keys";
import { createAsset } from "../../src/core/repo/ingest";
import { trashAsset } from "../../src/core/repo/trash";
import { getAssetPartition } from "../../src/core/repo/media";
import { readHistogram } from "../../src/core/repo/histogram";
import { patchTakenAt } from "../../src/lambda/api/routes/photos";
import type { ApiRequest } from "../../src/lambda/api/http";
import type { FacetItem, MetaItem, RenditionItem } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

function req(ownerId: string, photoId: string, body: unknown): ApiRequest {
  return {
    method: "PATCH",
    path: `/photos/${photoId}`,
    params: { photoId },
    query: {},
    auth: { userId: newUlid(), ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: JSON.stringify(body),
  };
}

async function addOnePhoto(ownerId: string, takenAt: string, tzOffsetMin = 0) {
  const photoId = newUlid();
  const stem = `2026/takenat-test/${newUlid()}`;
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
    takenAt,
    tzOffsetMin,
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

async function addFacet(ownerId: string, photoId: string, takenAt: string) {
  const facet: FacetItem = {
    pk: mediaPk(ownerId, photoId),
    sk: facetSk("YEAR", "2026"),
    facetType: "YEAR",
    facetValue: "2026",
    takenAt,
    tzOffsetMin: 0,
    thumbs: {},
    encDek: "dek",
    encKeyId: "mk-test",
    width: 10,
    height: 10,
    facetPk: facetGsiPk(ownerId, "YEAR", "2026"),
    facetSk: sortKey(takenAt, photoId),
  };
  await ddb().send(new PutCommand({ TableName: tableName(), Item: facet }));
  return facet;
}

describe.skipIf(!RUN)("PATCH /photos/{photoId} — manual takenAt correction", () => {
  it("rewrites takenAt, tzOffsetMin, timelineSk, and sets both srcs to manual", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    const original = "2020-01-01T00:00:00.000Z";
    const photoId = await addOnePhoto(ownerId, original);

    const corrected = "2018-06-15T12:00:00.000Z";
    const res = await patchTakenAt(req(ownerId, photoId, { takenAt: corrected, tzOffsetMin: 120 }));
    expect(res.statusCode).toBe(204);

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.takenAt).toBe(corrected);
    expect(meta?.tzOffsetMin).toBe(120);
    expect(meta?.takenAtSrc).toBe("manual");
    expect(meta?.tzSrc).toBe("manual");
    expect(meta?.timelineSk).toBe(sortKey(corrected, photoId));
  });

  it("rewrites every facet item's own takenAt and facetSk", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    const original = "2020-01-01T00:00:00.000Z";
    const photoId = await addOnePhoto(ownerId, original);
    await addFacet(ownerId, photoId, original);

    const corrected = "2018-06-15T12:00:00.000Z";
    await patchTakenAt(req(ownerId, photoId, { takenAt: corrected, tzOffsetMin: 0 }));

    const { facets } = await getAssetPartition(ownerId, photoId);
    expect(facets).toHaveLength(1);
    expect(facets[0]?.takenAt).toBe(corrected);
    expect(facets[0]?.facetSk).toBe(sortKey(corrected, photoId));
  });

  it("moves the histogram bucket for a live asset", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    const original = "2020-01-01T12:00:00.000Z";
    const photoId = await addOnePhoto(ownerId, original);

    const corrected = "2018-06-15T12:00:00.000Z";
    await patchTakenAt(req(ownerId, photoId, { takenAt: corrected, tzOffsetMin: 0 }));

    const histogram = await readHistogram(ownerId);
    expect(histogram.days["2020-01-01"]).toBeUndefined();
    expect(histogram.days["2018-06-15"]).toBe(1);
    expect(histogram.total).toBe(1);
  });

  it("still corrects a trashed asset, without touching the histogram", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    const original = "2020-01-01T12:00:00.000Z";
    const photoId = await addOnePhoto(ownerId, original);
    await trashAsset(ownerId, photoId, toIsoUtc(new Date()), "test");

    const corrected = "2018-06-15T12:00:00.000Z";
    const res = await patchTakenAt(req(ownerId, photoId, { takenAt: corrected, tzOffsetMin: 0 }));
    expect(res.statusCode).toBe(204);

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.takenAt).toBe(corrected);

    const histogram = await readHistogram(ownerId);
    expect(histogram.total).toBe(0); // never counted live, still isn't
  });

  it("404s for an unknown photoId", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    await expect(
      patchTakenAt(req(ownerId, newUlid(), { takenAt: "2018-06-15T12:00:00.000Z", tzOffsetMin: 0 })),
    ).rejects.toThrow(/not found/i);
  });

  it("rejects a non-ISO takenAt", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    const photoId = await addOnePhoto(ownerId, "2020-01-01T00:00:00.000Z");
    await expect(patchTakenAt(req(ownerId, photoId, { takenAt: "2020-01-01", tzOffsetMin: 0 }))).rejects.toThrow(
      /ISO-8601/i,
    );
  });

  it("rejects a non-integer tzOffsetMin", async () => {
    const ownerId = `01TAKENAT${newUlid().slice(-12)}`;
    const photoId = await addOnePhoto(ownerId, "2020-01-01T00:00:00.000Z");
    await expect(
      patchTakenAt(req(ownerId, photoId, { takenAt: "2020-01-01T00:00:00.000Z", tzOffsetMin: 90.5 })),
    ).rejects.toThrow(/tzOffsetMin/i);
  });
});
