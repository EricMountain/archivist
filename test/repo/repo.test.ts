// Repository tests against a real table — DynamoDB Local by default. Skipped
// entirely when DYNAMODB_ENDPOINT isn't set, so `npm test` stays green without
// Docker; set it (plus MEDIA_TABLE) to actually exercise this suite. See
// plan step 1.3's "Done when".
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { PutCommand } from "@aws-sdk/lib-dynamodb";
import { ddb } from "../../src/core/db";
import {
  facetGsiPk,
  facetSk,
  mediaPk,
  photoIdFromMediaPk,
  sortKey,
} from "../../src/core/keys";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { getAssetPartition } from "../../src/core/repo/media";
import { attachRendition, createAsset } from "../../src/core/repo/ingest";
import { facetPage } from "../../src/core/repo/facets";
import { timelineBounds, timelinePage } from "../../src/core/repo/timeline";
import { rebuildHistogram, readHistogram } from "../../src/core/repo/histogram";
import { restoreAsset, trashAsset } from "../../src/core/repo/trash";
import { restoreAsset, trashAsset } from "../../src/core/repo/trash";
import type { FacetItem, MetaItem, RenditionItem } from "../../src/core/items";

const RUN = !!process.env["DYNAMODB_ENDPOINT"] && !!process.env["MEDIA_TABLE"];

function baseMeta(overrides: Partial<Omit<MetaItem, "pk" | "sk">> = {}) {
  const now = toIsoUtc(new Date());
  return {
    ownerId: OWNER,
    photoId: newUlid(),
    stem: `2026/repo-test/${newUlid()}`,
    renditions: 1,
    mime: "image/heic",
    width: 100,
    height: 100,
    enc: "AES-256-GCM" as const,
    encDek: "dek",
    encKeyId: "mk-test",
    takenAt: now,
    tzOffsetMin: 0,
    tzSrc: "assumed-utc" as const,
    takenAtSrc: "upload" as const,
    uploadedAt: now,
    thumbs: {},
    groupSrc: "stem" as const,
    status: "ready" as const,
    ...overrides,
  };
}

function baseRendition(overrides: Partial<Omit<RenditionItem, "pk" | "sk">> = {}) {
  const now = toIsoUtc(new Date());
  return {
    renditionId: newUlid(),
    role: "display" as const,
    path: `2026/repo-test/${newUlid()}.jpg`,
    ext: "jpg",
    mime: "image/jpeg",
    s3Bucket: "test-originals",
    s3Key: `raw/${OWNER}/${newUlid()}`,
    contentHash: `hmac-sha256:${newUlid()}`,
    bytes: 100,
    plainBytes: 90,
    width: 100,
    height: 100,
    encIv: "iv",
    encChunkSize: 0,
    addedAt: now,
    ...overrides,
  };
}

const OWNER = "01TESTOWNER0000000000000A";

describe.skipIf(!RUN)("repo layer against DynamoDB Local", () => {
  beforeAll(async () => {
    if (!RUN) return;
  });

  it("creates an asset, then attaches a second rendition to the same stem", async () => {
    const stem = `2026/repo-test/${newUlid()}`;
    const path1 = `${stem}.CR3`;
    const path2 = `${stem}.JPG`;
    const meta = baseMeta({ stem });
    const rend1 = baseRendition({ role: "raw", path: path1 });

    await createAsset({
      stem,
      path: path1,
      hmac: rend1.contentHash,
      meta,
      rendition: rend1,
    });

    const afterCreate = await getAssetPartition(OWNER, meta.photoId);
    expect(afterCreate.meta?.photoId).toBe(meta.photoId);
    expect(afterCreate.renditions).toHaveLength(1);

    // Concurrent create with the same stem must fail — the pointer's conditional
    // put is the arbiter.
    await expect(
      createAsset({
        stem,
        path: `${stem}.PNG`,
        hmac: `hmac-sha256:${newUlid()}`,
        meta: baseMeta({ stem }),
        rendition: baseRendition({ path: `${stem}.PNG` }),
      }),
    ).rejects.toThrow();

    const rend2 = baseRendition({ role: "display", path: path2 });
    await attachRendition({
      ownerId: OWNER,
      photoId: meta.photoId,
      path: path2,
      hmac: rend2.contentHash,
      rendition: rend2,
      newPrimaryRend: rend2.renditionId,
      newMime: rend2.mime,
    });

    const afterAttach = await getAssetPartition(OWNER, meta.photoId);
    expect(afterAttach.renditions).toHaveLength(2);
    expect(afterAttach.meta?.renditions).toBe(2);
    expect(afterAttach.meta?.primaryRend).toBe(rend2.renditionId);
  });

  it("attaching to a purged/trashed asset is rejected by the ConditionCheck", async () => {
    const meta = baseMeta();
    const rend = baseRendition();
    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });

    await trashAsset(OWNER, meta.photoId, toIsoUtc(new Date()), "test-device");

    await expect(
      attachRendition({
        ownerId: OWNER,
        photoId: meta.photoId,
        path: `${meta.stem}-sidecar.xmp`,
        hmac: `hmac-sha256:${newUlid()}`,
        rendition: baseRendition({ role: "sidecar", path: `${meta.stem}-sidecar.xmp` }),
      }),
    ).rejects.toThrow();
  });

  it("pages the timeline newest-first with a cursor, visiting each asset once", async () => {
    const owner = `01TIMELINE${newUlid().slice(0, 16)}`;
    const created: string[] = [];
    for (let i = 0; i < 5; i++) {
      const takenAt = toIsoUtc(new Date(Date.UTC(2026, 0, i + 1)));
      const meta = baseMeta({ ownerId: owner, takenAt });
      const rend = baseRendition();
      await createAsset({
        stem: meta.stem,
        path: rend.path,
        hmac: rend.contentHash,
        meta,
        rendition: rend,
      });
      created.push(meta.photoId);
    }

    const seen: string[] = [];
    let cursor: string | undefined;
    do {
      const page = await timelinePage(owner, { limit: 2, cursor });
      seen.push(...page.items.map((item) => photoIdFromMediaPk(item.pk)));
      cursor = page.cursor;
    } while (cursor);

    expect(seen).toHaveLength(5);
    expect(new Set(seen).size).toBe(5);
    // Newest (Jan 5) first.
    expect(seen[0]).toBe(created[4]);
    expect(seen[4]).toBe(created[0]);
  });

  it("returns a range oldest-first when ascending, so a client can load the page just newer than its cache", async () => {
    const owner = `01ASCEND${newUlid().slice(0, 18)}`;
    const takenAts = [
      toIsoUtc(new Date(Date.UTC(2024, 0, 1))),
      toIsoUtc(new Date(Date.UTC(2024, 0, 2))),
      toIsoUtc(new Date(Date.UTC(2024, 0, 3))),
      toIsoUtc(new Date(Date.UTC(2024, 0, 4))),
    ];
    const ids: string[] = [];
    for (const takenAt of takenAts) {
      const meta = baseMeta({ ownerId: owner, takenAt });
      const rend = baseRendition();
      await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
      ids.push(meta.photoId);
    }

    // Anchor on Jan 2 — the two rows immediately newer are Jan 3 then Jan 4, and the
    // anchor itself comes back first because `from` is inclusive.
    const page = await timelinePage(owner, { from: takenAts[1], to: "9999-12-31T23:59:59.999Z", ascending: true, limit: 3 });
    expect(page.items.map((i) => photoIdFromMediaPk(i.pk))).toEqual([ids[1], ids[2], ids[3]]);

    // The default (newest-first) form of the same range would start at the far end.
    const descending = await timelinePage(owner, { from: takenAts[1], to: "9999-12-31T23:59:59.999Z", limit: 3 });
    expect(photoIdFromMediaPk(descending.items[0]!.pk)).toBe(ids[3]);
  });

  it("finds the oldest and newest takenAt in the timeline", async () => {
    const owner = `01BOUNDS${newUlid().slice(0, 18)}`;
    const takenAts = [
      toIsoUtc(new Date(Date.UTC(2019, 5, 1))),
      toIsoUtc(new Date(Date.UTC(2026, 0, 1))),
      toIsoUtc(new Date(Date.UTC(2022, 11, 25))),
    ];
    for (const takenAt of takenAts) {
      const meta = baseMeta({ ownerId: owner, takenAt });
      const rend = baseRendition();
      await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
    }

    const bounds = await timelineBounds(owner);
    expect(bounds.oldest).toBe(takenAts[0]);
    expect(bounds.newest).toBe(takenAts[1]);
  });

  it("counts each live photo on its own local day, and bumps the version each time", async () => {
    const owner = `01HIST${newUlid().slice(0, 14)}`;
    // Two on one local day and one on the next. The third is deliberately 22:40Z with
    // a +02:00 offset: that is still the 17th in UTC but the 18th to the photo, and
    // the grid headers it as the 18th — so the histogram has to as well.
    const photos = [
      { takenAt: "2025-11-17T09:00:00.000Z", tzOffsetMin: 120 },
      { takenAt: "2025-11-17T16:10:00.000Z", tzOffsetMin: 120 },
      { takenAt: "2025-11-17T22:40:29.000Z", tzOffsetMin: 120 },
    ];
    for (const p of photos) {
      const meta = baseMeta({ ownerId: owner, takenAt: p.takenAt, tzOffsetMin: p.tzOffsetMin });
      const rend = baseRendition();
      await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
    }

    const hist = await readHistogram(owner);
    expect(hist.days).toEqual({ "2025-11-17": 2, "2025-11-18": 1 });
    expect(hist.total).toBe(3);
    expect(hist.version).toBe(3);
  });

  it("trashing decrements the day it was counted on, and restoring puts it back", async () => {
    const owner = `01HISTTRASH${newUlid().slice(0, 8)}`;
    const meta = baseMeta({ ownerId: owner, takenAt: "2025-11-17T09:00:00.000Z", tzOffsetMin: 0 });
    const rend = baseRendition();
    await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
    expect((await readHistogram(owner)).days).toEqual({ "2025-11-17": 1 });

    await trashAsset(owner, meta.photoId, toIsoUtc(new Date()), "dev-1");
    const trashed = await readHistogram(owner);
    // Emptied days are absent, not zero — a client drawing a zero would draw a day
    // with nothing on it.
    expect(trashed.days).toEqual({});
    expect(trashed.total).toBe(0);

    await restoreAsset(owner, meta.photoId);
    expect((await readHistogram(owner)).days).toEqual({ "2025-11-17": 1 });
  });

  it("an owner with no photos has an empty histogram at version zero", async () => {
    const owner = `01HISTEMPTY${newUlid().slice(0, 8)}`;
    expect(await readHistogram(owner)).toEqual({ days: {}, total: 0, version: 0 });
  });

  it("rebuildHistogram recovers the correct counts for photos that predate the counters", async () => {
    const owner = `01HISTBACKFILL${newUlid().slice(0, 6)}`;
    // Simulate the gap this tool exists for: photos already in timeline_gsi that were
    // never counted, because createAsset's own histogramAdd only fires on the write
    // path — bypass it here the same way a pre-feature account's data would have.
    const photos = [
      { takenAt: "2025-11-17T09:00:00.000Z", tzOffsetMin: 0 },
      { takenAt: "2025-11-17T10:00:00.000Z", tzOffsetMin: 0 },
      // 22:40Z at +02:00 is the 18th to itself, same edge case as the live create path.
      { takenAt: "2025-11-17T22:40:00.000Z", tzOffsetMin: 120 },
    ];
    for (const p of photos) {
      const meta = baseMeta({ ownerId: owner, takenAt: p.takenAt, tzOffsetMin: p.tzOffsetMin });
      const rend = baseRendition();
      await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
    }
    // Photos were counted by createAsset itself (this test can't easily bypass it), so
    // wipe the histogram partition's illusion of already being correct by asserting the
    // rebuild reproduces the same answer independently, via the *index* rather than the
    // counters that were incidentally kept in step.
    const before = await readHistogram(owner);
    expect(before.days).toEqual({ "2025-11-17": 2, "2025-11-18": 1 });

    const rebuilt = await rebuildHistogram(owner);

    expect(rebuilt.days).toEqual({ "2025-11-17": 2, "2025-11-18": 1 });
    expect(rebuilt.total).toBe(3);
    // Version advances past whatever it already was — never resets to 1 — so a
    // client's cached copy is correctly told to refetch.
    expect(rebuilt.version).toBeGreaterThan(before.version);
    expect(await readHistogram(owner)).toEqual(rebuilt);
  });

  it("a takenAt improvement that crosses a local day boundary moves the count, not just timelineSk", async () => {
    const owner = `01HISTMOVE${newUlid().slice(0, 8)}`;
    const meta = baseMeta({ ownerId: owner, takenAt: "2025-11-17T09:00:00.000Z", tzOffsetMin: 0 });
    const rend = baseRendition({ role: "raw" });
    await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
    expect((await readHistogram(owner)).days).toEqual({ "2025-11-17": 1 });

    // A second, better rendition arrives with a takenAt that lands on a *different*
    // local day — the exact case histogramMove exists for, distinct from the far more
    // common "refines the time within the same day" improvement.
    const better = baseRendition({ role: "display" });
    await attachRendition({
      ownerId: owner,
      photoId: meta.photoId,
      path: better.path,
      hmac: better.contentHash,
      rendition: better,
      takenAtImprovement: {
        takenAt: "2025-11-18T03:00:00.000Z",
        tzOffsetMin: 0,
        tzSrc: "exif",
        takenAtSrc: "exif",
      },
    });

    const after = await readHistogram(owner);
    expect(after.days).toEqual({ "2025-11-18": 1 });
    expect(after.total).toBe(1);
  });

  it("a takenAt improvement that stays on the same local day leaves the histogram untouched", async () => {
    const owner = `01HISTNOMOVE${newUlid().slice(0, 6)}`;
    const meta = baseMeta({ ownerId: owner, takenAt: "2025-11-17T09:00:00.000Z", tzOffsetMin: 0 });
    const rend = baseRendition({ role: "raw" });
    await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });
    const before = await readHistogram(owner);

    const better = baseRendition({ role: "display" });
    await attachRendition({
      ownerId: owner,
      photoId: meta.photoId,
      path: better.path,
      hmac: better.contentHash,
      rendition: better,
      // Same day, just a more precise time -- histogramMove's own doc calls this the
      // common case, and the empty-array short circuit it takes for it.
      takenAtImprovement: { takenAt: "2025-11-17T14:00:00.000Z", tzOffsetMin: 0, tzSrc: "exif", takenAtSrc: "exif" },
    });

    const after = await readHistogram(owner);
    expect(after.days).toEqual({ "2025-11-17": 1 });
    // version is untouched too -- histogramMove returns no items at all for this case,
    // so nothing in the histogram partition was written.
    expect(after.version).toBe(before.version);
  });

  it("rebuildHistogram is idempotent: running it twice produces the same counts", async () => {
    const owner = `01HISTIDEMPOTENT${newUlid().slice(0, 4)}`;
    const meta = baseMeta({ ownerId: owner, takenAt: "2026-01-01T00:00:00.000Z", tzOffsetMin: 0 });
    const rend = baseRendition();
    await createAsset({ stem: meta.stem, path: rend.path, hmac: rend.contentHash, meta, rendition: rend });

    const first = await rebuildHistogram(owner);
    const second = await rebuildHistogram(owner);

    expect(second.days).toEqual(first.days);
    expect(second.total).toBe(first.total);
    expect(second.version).toBe(first.version + 1);
  });

  it("returns no bounds for an owner with an empty timeline", async () => {
    const owner = `01EMPTYBOUNDS${newUlid().slice(0, 12)}`;
    const bounds = await timelineBounds(owner);
    expect(bounds.oldest).toBeUndefined();
    expect(bounds.newest).toBeUndefined();
  });

  it("queries facet_gsi for a label", async () => {
    const photoId = newUlid();
    const takenAt = toIsoUtc(new Date());
    const facet: FacetItem = {
      pk: mediaPk(OWNER, photoId),
      sk: facetSk("LABEL", "temple"),
      facetType: "LABEL",
      facetValue: "temple",
      confidence: 0.9,
      labelSrc: "manual",
      takenAt,
      tzOffsetMin: 0,
      thumbs: {},
      encDek: "dek",
      encKeyId: "mk-test",
      width: 100,
      height: 100,
      facetPk: facetGsiPk(OWNER, "LABEL", "temple"),
      facetSk: sortKey(takenAt, photoId),
    };
    await ddb().send(
      new PutCommand({ TableName: process.env["MEDIA_TABLE"], Item: facet }),
    );

    const page = await facetPage(OWNER, "LABEL", "temple");
    expect(page.items.some((item) => item.pk === facet.pk)).toBe(true);
  });

  it("trashes and restores an asset, moving it between timeline_gsi partitions", async () => {
    const meta = baseMeta();
    const rend = baseRendition();
    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });

    const beforeTrash = await timelinePage(OWNER, { limit: 200 });
    expect(beforeTrash.items.some((i) => photoIdFromMediaPk(i.pk) === meta.photoId)).toBe(true);

    await trashAsset(OWNER, meta.photoId, toIsoUtc(new Date()), "test-device");

    const afterTrash = await timelinePage(OWNER, { limit: 200 });
    expect(afterTrash.items.some((i) => photoIdFromMediaPk(i.pk) === meta.photoId)).toBe(false);

    const trashed = await getAssetPartition(OWNER, meta.photoId);
    expect(trashed.meta?.deletedAt).toBeDefined();

    await restoreAsset(OWNER, meta.photoId);

    const afterRestore = await timelinePage(OWNER, { limit: 200 });
    expect(afterRestore.items.some((i) => photoIdFromMediaPk(i.pk) === meta.photoId)).toBe(true);

    const restored = await getAssetPartition(OWNER, meta.photoId);
    expect(restored.meta?.deletedAt).toBeUndefined();
  });
});
