// Second batch of repo tests: session bootstrap, key-wrap invariants, rename,
// rendition deletion/primary re-election, and the purge sweep (including its S3
// side, against a local MinIO). Skipped unless DYNAMODB_ENDPOINT, MEDIA_TABLE,
// S3_ENDPOINT, ORIGINALS_BUCKET and DERIVED_BUCKET are all set.
import { describe, expect, it } from "vitest";
import { PutObjectCommand } from "@aws-sdk/client-s3";
import { s3 } from "../../src/core/s3";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { GetCommand } from "@aws-sdk/lib-dynamodb";
import { ddb, tableName } from "../../src/core/db";
import { ownerRegistryPk, ownerRegistrySk } from "../../src/core/keys";
import { getAssetPartition, getPhotoByPath } from "../../src/core/repo/media";
import { createAsset } from "../../src/core/repo/ingest";
import { deleteKeyWrap, listKeyWraps, putKeyWrap } from "../../src/core/repo/keys";
import { bootstrapUser } from "../../src/core/repo/session";
import { deleteRendition, renameRendition } from "../../src/core/repo/renditions";
import { purgeAsset } from "../../src/core/repo/purge";
import { deleteOwnerData } from "../../src/core/repo/account";
import { trashAsset } from "../../src/core/repo/trash";
import { getHashPointer, getPathPointer, getStemPointer } from "../../src/core/repo/pointers";
import { listAllOwnerIds } from "../../src/core/repo/owners";
import type { KeyWrapItem, MetaItem, RenditionItem } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

const OWNER = "01TESTOWNER0000000000000B";

function baseMeta(overrides: Partial<Omit<MetaItem, "pk" | "sk">> = {}) {
  const now = toIsoUtc(new Date());
  return {
    ownerId: OWNER,
    photoId: newUlid(),
    stem: `2026/repo2-test/${newUlid()}`,
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
    path: `2026/repo2-test/${newUlid()}.jpg`,
    ext: "jpg",
    mime: "image/jpeg",
    s3Bucket: process.env["ORIGINALS_BUCKET"] ?? "",
    s3Key: `raw/${OWNER}/${newUlid()}`,
    contentHash: `hmac-sha256:${newUlid()}`,
    bytes: 11,
    plainBytes: 5,
    width: 100,
    height: 100,
    encIv: "iv",
    encChunkSize: 0,
    addedAt: now,
    ...overrides,
  };
}

describe.skipIf(!RUN)("session bootstrap", () => {
  it("mints exactly one owner across two concurrent-ish calls", async () => {
    const subject = newUlid();
    const first = await bootstrapUser({
      issuer: "cognito",
      subject,
      displayName: "Test User",
      homeTz: "UTC",
    });
    expect(first.created).toBe(true);

    const second = await bootstrapUser({
      issuer: "cognito",
      subject,
      displayName: "Test User",
      homeTz: "UTC",
    });
    expect(second.created).toBe(false);
    expect(second.userId).toBe(first.userId);
    expect(second.ownerId).toBe(first.ownerId);

    const owners = await listAllOwnerIds();
    expect(owners).toContain(first.ownerId);
  });
});

describe.skipIf(!RUN)("key-wrap invariants", () => {
  it("refuses to drop below two wrappings, and refuses to remove the last recovery", async () => {
    const owner = `01KEYS${newUlid().slice(0, 20)}`;
    const now = toIsoUtc(new Date());
    const device: Omit<KeyWrapItem, "pk" | "sk"> = {
      wrapId: newUlid(),
      kind: "device",
      label: "Pixel",
      masterKeyVer: "mk-1",
      wrapAlg: "RSA-OAEP-256",
      wrappedKey: "w1",
      createdAt: now,
    };
    const recovery: Omit<KeyWrapItem, "pk" | "sk"> = {
      wrapId: newUlid(),
      kind: "recovery",
      label: "Recovery code",
      masterKeyVer: "mk-1",
      wrapAlg: "AES-KW",
      wrappedKey: "w2",
      kdfSalt: "salt",
      kdfParams: { alg: "argon2id", m: "64MiB", t: 3, p: 1 },
      createdAt: now,
    };
    await putKeyWrap(owner, device);
    await putKeyWrap(owner, recovery);

    expect((await listKeyWraps(owner)).length).toBe(2);
    await expect(deleteKeyWrap(owner, device.wrapId)).rejects.toThrow();

    const passkey: Omit<KeyWrapItem, "pk" | "sk"> = {
      wrapId: newUlid(),
      kind: "passkey",
      label: "Firefox",
      masterKeyVer: "mk-1",
      wrapAlg: "RSA-OAEP-256",
      wrappedKey: "w3",
      credentialId: "cred",
      prfSalt: "salt2",
      createdAt: now,
    };
    await putKeyWrap(owner, passkey);
    expect((await listKeyWraps(owner)).length).toBe(3);

    // Now down to 3: deleting the device wrapping is fine (2 remain, recovery intact).
    await deleteKeyWrap(owner, device.wrapId);
    expect((await listKeyWraps(owner)).length).toBe(2);

    // But the recovery wrapping can't go — it's the last one, and we're at the floor.
    await expect(deleteKeyWrap(owner, recovery.wrapId)).rejects.toThrow();
  });
});

describe.skipIf(!RUN)("rename and rendition deletion", () => {
  it("renames a rendition, moving the PATH pointer", async () => {
    const meta = baseMeta();
    const rend = baseRendition();
    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });

    const newPath = `${meta.stem}-renamed.jpg`;
    await renameRendition(OWNER, meta.photoId, rend.renditionId, rend.path, newPath);

    expect(await getPathPointer(OWNER, rend.path)).toBeUndefined();
    const newPtr = await getPathPointer(OWNER, newPath);
    expect(newPtr?.photoId).toBe(meta.photoId);

    const found = await getPhotoByPath(OWNER, newPath);
    expect(found?.photoId).toBe(meta.photoId);
  });

  it("re-elects primaryRend and drops the F#REND facet when the primary is deleted", async () => {
    const meta = baseMeta();
    const display = baseRendition({ role: "display" });
    await createAsset({
      stem: meta.stem,
      path: display.path,
      hmac: display.contentHash,
      meta: { ...meta, primaryRend: display.renditionId, renditions: 1 },
      rendition: display,
    });

    // Attach a raw rendition by hand (bypassing the API layer) so both survive.
    const raw = baseRendition({ role: "raw", path: `${meta.stem}.raw` });
    const { attachRendition } = await import("../../src/core/repo/ingest");
    await attachRendition({
      ownerId: OWNER,
      photoId: meta.photoId,
      path: raw.path,
      hmac: raw.contentHash,
      rendition: raw,
    });

    const result = await deleteRendition(
      OWNER,
      meta.photoId,
      display.renditionId,
      toIsoUtc(new Date()),
      "test-device",
    );
    expect(result.assetTrashed).toBe(false);

    const after = await getAssetPartition(OWNER, meta.photoId);
    expect(after.meta?.primaryRend).toBe(raw.renditionId);
    expect(after.renditions.find((r) => r.renditionId === display.renditionId)?.deletedAt).toBeDefined();
  });

  it("trashes the whole asset when the last rendition is deleted", async () => {
    const meta = baseMeta();
    const rend = baseRendition();
    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });

    const result = await deleteRendition(
      OWNER,
      meta.photoId,
      rend.renditionId,
      toIsoUtc(new Date()),
      "test-device",
    );
    expect(result.assetTrashed).toBe(true);

    const after = await getAssetPartition(OWNER, meta.photoId);
    expect(after.meta?.deletedAt).toBeDefined();
  });
});

describe.skipIf(!RUN)("purge sweep", () => {
  it("deletes S3 objects and table items, and tombstones the HASH pointer", async () => {
    const meta = baseMeta();
    const rend = baseRendition({ s3Key: `raw/${OWNER}/${newUlid()}` });
    await s3().send(
      new PutObjectCommand({
        Bucket: rend.s3Bucket,
        Key: rend.s3Key,
        Body: Buffer.from("hello"),
      }),
    );

    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });
    await trashAsset(OWNER, meta.photoId, toIsoUtc(new Date()), "test-device");

    const result = await purgeAsset(OWNER, meta.photoId, toIsoUtc(new Date()), 365);
    expect(result.objectsDeleted).toBe(1);
    expect(result.itemsDeleted).toBeGreaterThan(0);

    const after = await getAssetPartition(OWNER, meta.photoId);
    expect(after.meta).toBeUndefined();

    expect(await getStemPointer(OWNER, meta.stem)).toBeUndefined();
    expect(await getPathPointer(OWNER, rend.path)).toBeUndefined();

    const tombstone = await getHashPointer(OWNER, rend.contentHash);
    expect(tombstone).toBeDefined();
    expect((tombstone as { kind?: string })?.kind).toBe("purged");
  });
});

describe.skipIf(!RUN)("account deletion", () => {
  it("removes media, tombstones, S3 objects and the owner-registry entry", async () => {
    const { userId, ownerId } = await bootstrapUser({
      issuer: "cognito",
      subject: newUlid(),
      displayName: "Deletion Test",
      homeTz: "UTC",
    });

    const meta = baseMeta({ ownerId });
    const rend = baseRendition({ s3Key: `raw/${ownerId}/${newUlid()}` });
    await s3().send(
      new PutObjectCommand({ Bucket: rend.s3Bucket, Key: rend.s3Key, Body: Buffer.from("x") }),
    );
    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });

    // A pre-existing tombstone from an earlier, unrelated purge — deletion must
    // reach this too, not just live data.
    await trashAsset(ownerId, meta.photoId, toIsoUtc(new Date()), "test-device");
    await purgeAsset(ownerId, meta.photoId, toIsoUtc(new Date()), 365);
    expect((await getHashPointer(ownerId, rend.contentHash))).toBeDefined();

    const result = await deleteOwnerData(ownerId, userId);
    expect(result.itemsDeleted).toBeGreaterThan(0);

    expect(await getHashPointer(ownerId, rend.contentHash)).toBeUndefined();
    expect(await listAllOwnerIds()).not.toContain(ownerId);

    const registryRow = await ddb().send(
      new GetCommand({
        TableName: tableName(),
        Key: { pk: ownerRegistryPk(), sk: ownerRegistrySk(ownerId) },
      }),
    );
    expect(registryRow.Item).toBeUndefined();
  });
});
