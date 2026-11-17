// Geolocation plan step 4: `place` through POST /uploads (create + attach), trash and
// restore, PUT /photos/{photoId}/location, GET /places, account deletion and
// tools/backfill-places.mjs. Same DynamoDB Local + MinIO gate as the other
// lambda-level suites.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { describe, expect, it } from "vitest";
import { PutCommand, QueryCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../../src/core/db";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { placesPk } from "../../src/core/keys";
import { bootstrapUser } from "../../src/core/repo/session";
import { getAssetPartition } from "../../src/core/repo/media";
import { deleteOwnerData } from "../../src/core/repo/account";
import { postUpload } from "../../src/lambda/api/routes/uploads";
import { deletePhoto, getPhoto, getPhotos, postRestore } from "../../src/lambda/api/routes/photos";
import { getFacetPage } from "../../src/lambda/api/routes/facets";
import { getPlaces, putPhotoLocation } from "../../src/lambda/api/routes/places";
import type { ApiRequest } from "../../src/lambda/api/http";
import type { Place } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

const SRC = "geonames-cities500@2026-11-13";
const KYOTO: Place = {
  cc: "JP",
  region: { id: "JP.22", name: "Kyoto" },
  locality: { id: "1857910", name: "Kyoto" },
  src: SRC,
};
const OSAKA: Place = {
  cc: "JP",
  region: { id: "JP.32", name: "Osaka" },
  locality: { id: "1853909", name: "Osaka" },
  src: SRC,
};
const HOKKAIDO: Place = { cc: "JP", region: { id: "JP.12", name: "Hokkaido" }, src: SRC };
/** GeoNames has no admin1 region for Stanley, so the gazetteer returns {cc, locality}. */
const STANLEY: Place = { cc: "FK", locality: { id: "3426691", name: "Stanley" }, src: SRC };
const PARIS: Place = {
  cc: "FR",
  region: { id: "FR.11", name: "Île-de-France" },
  locality: { id: "2988507", name: "Paris" },
  src: SRC,
};

async function newOwner() {
  return bootstrapUser({
    issuer: "cognito",
    subject: newUlid(),
    displayName: "Places Test",
    homeTz: "UTC",
  });
}

function req(
  ownerId: string,
  userId: string,
  opts: { method?: string; params?: Record<string, string>; body?: unknown; headers?: Record<string, string> } = {},
): ApiRequest {
  return {
    method: opts.method ?? "GET",
    path: "/",
    params: opts.params ?? {},
    query: {},
    auth: { userId, ownerId, role: "owner" },
    requestId: newUlid(),
    rawBody: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    headers: opts.headers ?? {},
  };
}

function uploadBody(overrides: Record<string, unknown> = {}) {
  const stem = `2026/places-test/${newUlid()}`;
  return {
    path: `${stem}.jpg`,
    plainBytes: 100,
    bytes: 116,
    mime: "image/jpeg",
    width: 100,
    height: 80,
    contentHash: `hmac-sha256:${newUlid()}`,
    takenAt: toIsoUtc(new Date(Date.now() - 3_600_000)),
    takenAtSrc: "exif",
    tzOffsetMin: 540,
    tzSrc: "exif-offset",
    encDek: "dek",
    encKeyId: "mk-test",
    encIv: "iv",
    encChunkSize: 0,
    thumbs: { "256": { bytes: 10, iv: "t256" }, "1024": { bytes: 20, iv: "t1024" } },
    ...overrides,
  };
}

async function upload(ownerId: string, userId: string, body: Record<string, unknown>) {
  const res = await postUpload(req(ownerId, userId, { method: "POST", body }));
  return res.body as { photoId: string; created?: boolean };
}

async function putLocation(ownerId: string, userId: string, photoId: string, body: unknown) {
  return putPhotoLocation(req(ownerId, userId, { method: "PUT", params: { photoId }, body }));
}

/** Every row of the PLACES partition, raw — zero rows included. */
async function placeRows(ownerId: string): Promise<Record<string, Record<string, unknown>>> {
  const res = await ddb().send(
    new QueryCommand({
      TableName: tableName(),
      KeyConditionExpression: "pk = :pk",
      ExpressionAttributeValues: { ":pk": placesPk(ownerId) },
      ConsistentRead: true,
    }),
  );
  const out: Record<string, Record<string, unknown>> = {};
  for (const item of res.Items ?? []) out[item["sk"] as string] = item;
  return out;
}

async function counts(ownerId: string): Promise<Record<string, number>> {
  const rows = await placeRows(ownerId);
  const out: Record<string, number> = {};
  for (const [sk, row] of Object.entries(rows)) {
    out[sk] = sk === "#META" ? (row["total"] as number) : (row["n"] as number);
  }
  return out;
}

/** Location facet sk → facetPk (undefined when trashed). */
async function locationFacets(ownerId: string, photoId: string): Promise<Record<string, string | undefined>> {
  const { facets } = await getAssetPartition(ownerId, photoId);
  const out: Record<string, string | undefined> = {};
  for (const f of facets) {
    if (["COUNTRY", "REGION", "PLACE"].includes(f.facetType)) out[f.sk] = f.facetPk;
  }
  return out;
}

const run = promisify(execFile);
async function backfillDryRun(ownerId: string, yes = false): Promise<string> {
  const { stdout } = await run(
    process.execPath,
    ["tools/backfill-places.mjs", "--owner-id", ownerId, ...(yes ? ["--yes"] : [])],
    { env: process.env },
  );
  return stdout;
}

function differences(stdout: string): number {
  const m = /^differences: (\d+)$/m.exec(stdout);
  if (!m) throw new Error(`no differences line in:\n${stdout}`);
  return Number(m[1]);
}

describe.skipIf(!RUN)("place on create (POST /uploads)", () => {
  it("writes #META.place, the three location facets and n=1 counters; GET /photos projects it", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId, created } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    expect(created).toBe(true);

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.place).toEqual(KYOTO);

    expect(await locationFacets(ownerId, photoId)).toEqual({
      "F#COUNTRY#JP": `O#${ownerId}#F#COUNTRY#JP`,
      "F#REGION#JP.22": `O#${ownerId}#F#REGION#JP.22`,
      "F#PLACE#1857910": `O#${ownerId}#F#PLACE#1857910`,
    });

    const rows = await placeRows(ownerId);
    expect(rows["C#JP"]?.["n"]).toBe(1);
    expect(rows["R#JP.22"]).toMatchObject({ n: 1, name: "Kyoto", cc: "JP" });
    expect(rows["L#1857910"]).toMatchObject({ n: 1, name: "Kyoto", regionName: "Kyoto", cc: "JP" });
    expect(rows["#META"]).toMatchObject({ total: 1 });

    // Projected through timeline_gsi, for the date bubbles.
    const page = await getPhotos(req(ownerId, userId));
    const items = (page.body as { items: Array<{ photoId: string; place?: Place }> }).items;
    expect(items.find((i) => i.photoId === photoId)?.place).toEqual(KYOTO);

    // GET /photos/{id} carries it on meta.
    const detail = await getPhoto(req(ownerId, userId, { params: { photoId } }));
    expect((detail.body as { meta: { place?: Place } }).meta.place).toEqual(KYOTO);

    // The facet is queryable, with the thumbs setThumbs filled in after the
    // create transaction (not the empty map the transaction itself wrote).
    const facetRes = await getFacetPage(req(ownerId, userId, { params: { type: "PLACE", value: "1857910" } }));
    const facetItems = (facetRes.body as { items: Array<{ photoId: string; thumbs: Record<string, unknown>; tzOffsetMin: number }> }).items;
    expect(facetItems).toHaveLength(1);
    expect(facetItems[0]!.photoId).toBe(photoId);
    expect(Object.keys(facetItems[0]!.thumbs).sort()).toEqual(["1024", "256"]);
    expect(facetItems[0]!.tzOffsetMin).toBe(540);
  });

  it("a region-only place writes COUNTRY and REGION facets, no PLACE", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: HOKKAIDO }));
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual(["F#COUNTRY#JP", "F#REGION#JP.12"]);
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#JP": 1, "R#JP.12": 1 });
  });

  it("a locality with no region writes COUNTRY and PLACE facets, no REGION, and an L# row without regionName", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: STANLEY }));

    expect((await getAssetPartition(ownerId, photoId)).meta?.place).toEqual(STANLEY);
    expect(await locationFacets(ownerId, photoId)).toEqual({
      "F#COUNTRY#FK": `O#${ownerId}#F#COUNTRY#FK`,
      "F#PLACE#3426691": `O#${ownerId}#F#PLACE#3426691`,
    });
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#FK": 1, "L#3426691": 1 });
    const rows = await placeRows(ownerId);
    expect(rows["L#3426691"]).toMatchObject({ n: 1, name: "Stanley", cc: "FK" });
    expect(rows["L#3426691"]).not.toHaveProperty("regionName");

    const vocab = (await getPlaces(req(ownerId, userId))).body as { localities: unknown[]; regions: unknown[] };
    expect(vocab.regions).toEqual([]);
    expect(vocab.localities).toEqual([{ id: "3426691", name: "Stanley", cc: "FK", n: 1 }]);
  });

  it("no place: nothing location-shaped is written", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody());
    expect(await locationFacets(ownerId, photoId)).toEqual({});
    expect(await placeRows(ownerId)).toEqual({});
  });
});

describe.skipIf(!RUN)("place on attach — improve, never replace", () => {
  it("an attaching rendition fills an absent place", async () => {
    const { userId, ownerId } = await newOwner();
    const first = uploadBody();
    const { photoId } = await upload(ownerId, userId, first);
    const stem = (first.path as string).replace(/\.jpg$/, "");

    const second = await upload(
      ownerId,
      userId,
      uploadBody({ path: `${stem}.dng`, mime: "image/x-adobe-dng", place: KYOTO }),
    );
    expect(second).toMatchObject({ photoId, created: false });

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.place).toEqual(KYOTO);
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual([
      "F#COUNTRY#JP",
      "F#PLACE#1857910",
      "F#REGION#JP.22",
    ]);
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#JP": 1, "R#JP.22": 1, "L#1857910": 1 });
  });

  it("an attaching rendition with a different place doesn't replace the existing one", async () => {
    const { userId, ownerId } = await newOwner();
    const first = uploadBody({ place: KYOTO });
    const { photoId } = await upload(ownerId, userId, first);
    const stem = (first.path as string).replace(/\.jpg$/, "");
    const before = await counts(ownerId);

    await upload(ownerId, userId, uploadBody({ path: `${stem}.dng`, mime: "image/x-adobe-dng", place: PARIS }));

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.place).toEqual(KYOTO);
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual([
      "F#COUNTRY#JP",
      "F#PLACE#1857910",
      "F#REGION#JP.22",
    ]);
    expect(await counts(ownerId)).toEqual(before);
  });
});

describe.skipIf(!RUN)("trash and restore move the place counters", () => {
  it("1 → 0 → 1, facet keys removed then restored", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#JP": 1, "R#JP.22": 1, "L#1857910": 1 });

    await deletePhoto(req(ownerId, userId, { method: "DELETE", params: { photoId } }));
    expect(await counts(ownerId)).toEqual({ "#META": 0, "C#JP": 0, "R#JP.22": 0, "L#1857910": 0 });
    expect(Object.values(await locationFacets(ownerId, photoId))).toEqual([undefined, undefined, undefined]);

    await postRestore(req(ownerId, userId, { method: "POST", params: { photoId } }));
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#JP": 1, "R#JP.22": 1, "L#1857910": 1 });
    expect(Object.values(await locationFacets(ownerId, photoId)).every((pk) => !!pk)).toBe(true);
  });
});

describe.skipIf(!RUN)("trash and restore of a locality with no region", () => {
  it("1 → 0 → 1 on C# and L#, never an R# row", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: STANLEY }));

    await deletePhoto(req(ownerId, userId, { method: "DELETE", params: { photoId } }));
    expect(await counts(ownerId)).toEqual({ "#META": 0, "C#FK": 0, "L#3426691": 0 });
    expect(await locationFacets(ownerId, photoId)).toEqual({ "F#COUNTRY#FK": undefined, "F#PLACE#3426691": undefined });

    await postRestore(req(ownerId, userId, { method: "POST", params: { photoId } }));
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#FK": 1, "L#3426691": 1 });
    expect(await locationFacets(ownerId, photoId)).toEqual({
      "F#COUNTRY#FK": `O#${ownerId}#F#COUNTRY#FK`,
      "F#PLACE#3426691": `O#${ownerId}#F#PLACE#3426691`,
    });
    expect((await placeRows(ownerId))["L#3426691"]).not.toHaveProperty("regionName");
  });
});

describe.skipIf(!RUN)("PUT /photos/{photoId}/location", () => {
  it("moves between a locality with no region and a regioned place, both ways", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: STANLEY }));

    await putLocation(ownerId, userId, photoId, { place: PARIS });
    expect(await counts(ownerId)).toEqual({
      "#META": 1,
      "C#FK": 0,
      "L#3426691": 0,
      "C#FR": 1,
      "R#FR.11": 1,
      "L#2988507": 1,
    });
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual([
      "F#COUNTRY#FR",
      "F#PLACE#2988507",
      "F#REGION#FR.11",
    ]);

    await putLocation(ownerId, userId, photoId, { place: STANLEY });
    expect(await counts(ownerId)).toEqual({
      "#META": 1,
      "C#FK": 1,
      "L#3426691": 1,
      "C#FR": 0,
      "R#FR.11": 0,
      "L#2988507": 0,
    });
    expect(await locationFacets(ownerId, photoId)).toEqual({
      "F#COUNTRY#FK": `O#${ownerId}#F#COUNTRY#FK`,
      "F#PLACE#3426691": `O#${ownerId}#F#PLACE#3426691`,
    });
    expect((await getAssetPartition(ownerId, photoId)).meta?.place).toEqual(STANLEY);
  });

  it("the same locality gaining or losing a region moves only the R# row and refreshes regionName", async () => {
    const { userId, ownerId } = await newOwner();
    // Same geonameid, as if a later gazetteer snapshot assigned (or dropped) an admin1.
    const withRegion: Place = { ...STANLEY, region: { id: "FK.01", name: "Somewhere" } };
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: STANLEY }));

    await putLocation(ownerId, userId, photoId, { place: withRegion });
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#FK": 1, "R#FK.01": 1, "L#3426691": 1 });
    expect((await placeRows(ownerId))["L#3426691"]).toMatchObject({ regionName: "Somewhere" });
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual([
      "F#COUNTRY#FK",
      "F#PLACE#3426691",
      "F#REGION#FK.01",
    ]);

    await putLocation(ownerId, userId, photoId, { place: STANLEY });
    expect(await counts(ownerId)).toEqual({ "#META": 1, "C#FK": 1, "R#FK.01": 0, "L#3426691": 1 });
    expect((await placeRows(ownerId))["L#3426691"]).not.toHaveProperty("regionName");
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual(["F#COUNTRY#FK", "F#PLACE#3426691"]);
  });

  it("moves a place to another country: old n down, new up, facets swapped", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));

    const res = await putLocation(ownerId, userId, photoId, { place: PARIS });
    expect(res.body).toEqual({ place: PARIS });

    expect(await counts(ownerId)).toEqual({
      "#META": 1,
      "C#JP": 0,
      "R#JP.22": 0,
      "L#1857910": 0,
      "C#FR": 1,
      "R#FR.11": 1,
      "L#2988507": 1,
    });
    expect(await locationFacets(ownerId, photoId)).toEqual({
      "F#COUNTRY#FR": `O#${ownerId}#F#COUNTRY#FR`,
      "F#REGION#FR.11": `O#${ownerId}#F#REGION#FR.11`,
      "F#PLACE#2988507": `O#${ownerId}#F#PLACE#2988507`,
    });
    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.place).toEqual(PARIS);
  });

  it("within one country: the shared C# row is touched once and stays put", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    await upload(ownerId, userId, uploadBody({ place: HOKKAIDO }));

    await putLocation(ownerId, userId, photoId, { place: OSAKA });
    expect(await counts(ownerId)).toEqual({
      "#META": 2,
      "C#JP": 2,
      "R#JP.12": 1,
      "R#JP.22": 0,
      "L#1857910": 0,
      "R#JP.32": 1,
      "L#1853909": 1,
    });
    expect(Object.keys(await locationFacets(ownerId, photoId)).sort()).toEqual([
      "F#COUNTRY#JP",
      "F#PLACE#1853909",
      "F#REGION#JP.32",
    ]);

    // Same region, locality dropped (town → region-only): the R# row is shared too.
    await putLocation(ownerId, userId, photoId, {
      place: { cc: "JP", region: { id: "JP.32", name: "Osaka" }, src: SRC },
    });
    const after = await counts(ownerId);
    expect(after["R#JP.32"]).toBe(1);
    expect(after["L#1853909"]).toBe(0);
    expect(after["C#JP"]).toBe(2);
  });

  it("place: null clears the place, its facets and its counts", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));

    const res = await putLocation(ownerId, userId, photoId, { place: null });
    expect(res.body).toEqual({ place: null });

    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta?.place).toBeUndefined();
    expect(await locationFacets(ownerId, photoId)).toEqual({});
    expect(await counts(ownerId)).toEqual({ "#META": 0, "C#JP": 0, "R#JP.22": 0, "L#1857910": 0 });

    // Clearing an already-absent place is fine too.
    expect((await putLocation(ownerId, userId, photoId, { place: null })).body).toEqual({ place: null });
  });

  it("is idempotent: the same body twice leaves counts and version unchanged", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));

    await putLocation(ownerId, userId, photoId, { place: PARIS });
    const once = await placeRows(ownerId);
    await putLocation(ownerId, userId, photoId, { place: PARIS });
    expect(await placeRows(ownerId)).toEqual(once);

    // Re-sending the *current* place is equally a no-op on the counts.
    await putLocation(ownerId, userId, photoId, { place: PARIS });
    expect(await placeRows(ownerId)).toEqual(once);
    expect(Object.keys(await locationFacets(ownerId, photoId))).toHaveLength(3);
  });

  it("on a trashed asset: replaces the place and facets without facet keys, moves no counts; restore counts the new place", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    await deletePhoto(req(ownerId, userId, { method: "DELETE", params: { photoId } }));
    const trashedRows = await placeRows(ownerId);

    await putLocation(ownerId, userId, photoId, { place: PARIS });
    expect(await placeRows(ownerId)).toEqual(trashedRows);
    expect(await locationFacets(ownerId, photoId)).toEqual({
      "F#COUNTRY#FR": undefined,
      "F#REGION#FR.11": undefined,
      "F#PLACE#2988507": undefined,
    });
    expect((await getAssetPartition(ownerId, photoId)).meta?.place).toEqual(PARIS);

    await postRestore(req(ownerId, userId, { method: "POST", params: { photoId } }));
    expect(await counts(ownerId)).toEqual({
      "#META": 1,
      "C#JP": 0,
      "R#JP.22": 0,
      "L#1857910": 0,
      "C#FR": 1,
      "R#FR.11": 1,
      "L#2988507": 1,
    });
    expect(Object.values(await locationFacets(ownerId, photoId)).every((pk) => !!pk)).toBe(true);
  });

  it("swaps in a re-encrypted exifEnc/exifIv when sent, and requires them together", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ exifEnc: "old-blob", exifIv: "old-iv" }));

    await putLocation(ownerId, userId, photoId, { place: KYOTO, exifEnc: "new-blob", exifIv: "new-iv" });
    const { meta } = await getAssetPartition(ownerId, photoId);
    expect(meta).toMatchObject({ exifEnc: "new-blob", exifIv: "new-iv", place: KYOTO });

    await expect(putLocation(ownerId, userId, photoId, { place: KYOTO, exifEnc: "x" })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(putLocation(ownerId, userId, photoId, { place: KYOTO, exifIv: "x" })).rejects.toMatchObject({
      code: "VALIDATION",
    });
  });

  it("404s an unknown photo, and 400s a body without a place field", async () => {
    const { userId, ownerId } = await newOwner();
    await expect(putLocation(ownerId, userId, newUlid(), { place: KYOTO })).rejects.toMatchObject({ code: "NOT_FOUND" });
    const { photoId } = await upload(ownerId, userId, uploadBody());
    await expect(putLocation(ownerId, userId, photoId, {})).rejects.toMatchObject({ code: "VALIDATION" });
  });
});

describe.skipIf(!RUN)("place validation (design.md \"Location\")", () => {
  const bad: Array<[string, unknown]> = [
    ["not an object", "Kyoto"],
    ["an array", [KYOTO]],
    ["lower-case cc", { ...KYOTO, cc: "jp" }],
    ["three-letter cc", { ...KYOTO, cc: "JPN" }],
    ["missing src", { cc: "JP" }],
    ["empty src", { cc: "JP", src: "" }],
    ["src over 64 chars", { cc: "JP", src: "x".repeat(65) }],
    ["malformed region id", { ...KYOTO, region: { id: "JP-22", name: "Kyoto" } }],
    ["region id from another country", { ...KYOTO, region: { id: "FR.22", name: "Kyoto" } }],
    ["region id from another country, no locality", { cc: "FK", region: { id: "GB.ENG", name: "England" }, src: SRC }],
    ["region id too long", { ...KYOTO, region: { id: `JP.${"1".repeat(21)}`, name: "Kyoto" } }],
    ["empty region name", { ...KYOTO, region: { id: "JP.22", name: "" } }],
    ["region name with a control character", { ...KYOTO, region: { id: "JP.22", name: "Kyo\u0000to" } }],
    ["non-numeric locality id", { ...KYOTO, locality: { id: "kyoto", name: "Kyoto" } }],
    ["locality id over 12 digits", { ...KYOTO, locality: { id: "1".repeat(13), name: "Kyoto" } }],
    ["locality name over 200 chars", { ...KYOTO, locality: { id: "1857910", name: "k".repeat(201) } }],
    ["locality name with a newline", { ...KYOTO, locality: { id: "1857910", name: "Kyo\nto" } }],
  ];

  it.each(bad)("POST /uploads rejects %s", async (_label, place) => {
    const { userId, ownerId } = await newOwner();
    await expect(upload(ownerId, userId, uploadBody({ place }))).rejects.toMatchObject({ code: "VALIDATION" });
  });

  it.each(bad)("PUT …/location rejects %s", async (_label, place) => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody());
    await expect(putLocation(ownerId, userId, photoId, { place })).rejects.toMatchObject({ code: "VALIDATION" });
  });

  it("accepts a locality without a region (Stanley, FK has no admin1)", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody());
    expect((await putLocation(ownerId, userId, photoId, { place: STANLEY })).body).toEqual({ place: STANLEY });
  });

  it("accepts boundary values, and stores only the known fields", async () => {
    const { userId, ownerId } = await newOwner();
    const place = {
      cc: "GB",
      region: { id: `GB.${"A".repeat(20)}`, name: "r".repeat(200) },
      locality: { id: "1".repeat(12), name: "Ærøskøbing" },
      src: "s".repeat(64),
      extra: "dropped",
    };
    const { photoId } = await upload(ownerId, userId, uploadBody({ place }));
    const { extra: _extra, ...stored } = place;
    expect((await getAssetPartition(ownerId, photoId)).meta?.place).toEqual(stored);
  });
});

describe.skipIf(!RUN)("GET /places", () => {
  it("filters zero rows, and answers a matching If-None-Match with 304", async () => {
    const { userId, ownerId } = await newOwner();
    const { photoId } = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    await upload(ownerId, userId, uploadBody({ place: HOKKAIDO }));
    await putLocation(ownerId, userId, photoId, { place: PARIS }); // leaves L#1857910, R#JP.22 at 0

    const first = await getPlaces(req(ownerId, userId));
    expect(first.statusCode).toBe(200);
    const body = first.body as {
      countries: unknown[];
      regions: Array<{ id: string }>;
      localities: Array<{ id: string }>;
      total: number;
      version: number;
    };
    expect(body.total).toBe(2);
    expect(body.countries).toEqual(
      expect.arrayContaining([
        { cc: "JP", n: 1 },
        { cc: "FR", n: 1 },
      ]),
    );
    expect(body.countries).toHaveLength(2);
    expect(body.regions.map((r) => r.id).sort()).toEqual(["FR.11", "JP.12"]);
    expect(body.localities).toEqual([{ id: "2988507", name: "Paris", regionName: "Île-de-France", cc: "FR", n: 1 }]);
    expect(body.regions).toContainEqual({ id: "JP.12", name: "Hokkaido", cc: "JP", n: 1 });

    const etag = first.headers!["etag"]!;
    expect(etag).toBe(`"pl-${body.version}"`);
    const revalidated = await getPlaces(req(ownerId, userId, { headers: { "if-none-match": etag } }));
    expect(revalidated.statusCode).toBe(304);
    expect(revalidated.body).toBeUndefined();
    expect(revalidated.headers?.["etag"]).toBe(etag);

    await upload(ownerId, userId, uploadBody({ place: OSAKA }));
    const fresh = await getPlaces(req(ownerId, userId, { headers: { "if-none-match": etag } }));
    expect(fresh.statusCode).toBe(200);
    expect(fresh.headers?.["etag"]).not.toBe(etag);
  });

  it("an owner with no located photos gets an empty vocabulary at version 0", async () => {
    const { userId, ownerId } = await newOwner();
    const res = await getPlaces(req(ownerId, userId));
    expect(res.headers?.["etag"]).toBe('"pl-0"');
    expect(res.body).toEqual({ countries: [], regions: [], localities: [], total: 0, version: 0 });
  });
});

describe.skipIf(!RUN)("account deletion", () => {
  it("leaves no #PLACES rows", async () => {
    const { userId, ownerId } = await newOwner();
    await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    expect(Object.keys(await placeRows(ownerId)).length).toBeGreaterThan(0);

    await deleteOwnerData(ownerId, userId);
    expect(await placeRows(ownerId)).toEqual({});
  });
});

describe.skipIf(!RUN)("tools/backfill-places.mjs", () => {
  it("reports 0 differences after normal operations, finds drift, and repairs it", async () => {
    const { userId, ownerId } = await newOwner();
    const a = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    const b = await upload(ownerId, userId, uploadBody({ place: KYOTO }));
    const c = await upload(ownerId, userId, uploadBody({ place: HOKKAIDO }));
    const d = await upload(ownerId, userId, uploadBody());
    await upload(ownerId, userId, uploadBody({ place: STANLEY }));
    await deletePhoto(req(ownerId, userId, { method: "DELETE", params: { photoId: b.photoId } }));
    await putLocation(ownerId, userId, c.photoId, { place: PARIS });
    await putLocation(ownerId, userId, d.photoId, { place: OSAKA });
    await putLocation(ownerId, userId, a.photoId, { place: null });
    await postRestore(req(ownerId, userId, { method: "POST", params: { photoId: b.photoId } }));

    expect(differences(await backfillDryRun(ownerId))).toBe(0);

    // Introduce drift by hand.
    await ddb().send(
      new PutCommand({
        TableName: tableName(),
        Item: { pk: placesPk(ownerId), sk: "L#1857910", name: "Kyoto", regionName: "Kyoto", cc: "JP", n: 7 },
      }),
    );
    await ddb().send(
      new PutCommand({
        TableName: tableName(),
        Item: { pk: placesPk(ownerId), sk: "L#999", name: "Nowhere", regionName: "X", cc: "JP", n: 2 },
      }),
    );
    const before = await placeRows(ownerId);
    const dry = await backfillDryRun(ownerId);
    expect(differences(dry)).toBe(2);
    expect(dry).toContain("L#1857910: stored 7 -> recount 1");
    expect(await placeRows(ownerId)).toEqual(before); // dry run wrote nothing

    await backfillDryRun(ownerId, true);
    expect(differences(await backfillDryRun(ownerId))).toBe(0);
    const after = await placeRows(ownerId);
    expect(after["L#999"]?.["n"]).toBe(0);
    expect(after["L#1857910"]?.["n"]).toBe(1);
    expect(after["L#3426691"]).toMatchObject({ n: 1, name: "Stanley", cc: "FK" });
    expect(after["L#3426691"]).not.toHaveProperty("regionName");
    expect(after["#META"]?.["version"]).toBe((before["#META"]?.["version"] as number) + 1);
  });
});
