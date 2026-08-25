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
import { timelinePage } from "../../src/core/repo/timeline";
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
