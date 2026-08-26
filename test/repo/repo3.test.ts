// Third batch: master key version allocation (concurrency-sensitive per plan
// step 1.8's "Done when"), hash-secret storage, and the blocked-attempts
// tracking plan steps 1.9/1.12/1.13 added. Same DynamoDB Local + MinIO gate as
// repo2.test.ts.
import { describe, expect, it } from "vitest";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc, epochSecondsAfterDays } from "../../src/core/time";
import {
  allocateMasterKeyVer,
  getCurrentMasterKeyVersion,
  putHashSecret,
} from "../../src/core/repo/keys";
import { bootstrapUser } from "../../src/core/repo/session";
import { createAsset } from "../../src/core/repo/ingest";
import { trashAsset } from "../../src/core/repo/trash";
import { purgeAsset } from "../../src/core/repo/purge";
import { getHashPointer, recordBlockedHashAttempt } from "../../src/core/repo/pointers";
import { getOwnerSettings } from "../../src/core/repo/identity";
import type { MetaItem, RenditionItem } from "../../src/core/items";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

async function newOwner() {
  const { ownerId } = await bootstrapUser({
    issuer: "cognito",
    subject: newUlid(),
    displayName: "Repo3 Test",
    homeTz: "UTC",
  });
  return ownerId;
}

function baseMeta(ownerId: string, overrides: Partial<Omit<MetaItem, "pk" | "sk">> = {}) {
  const now = toIsoUtc(new Date());
  return {
    ownerId,
    photoId: newUlid(),
    stem: `2026/repo3-test/${newUlid()}`,
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

function baseRendition(ownerId: string, overrides: Partial<Omit<RenditionItem, "pk" | "sk">> = {}) {
  const now = toIsoUtc(new Date());
  return {
    renditionId: newUlid(),
    role: "display" as const,
    path: `2026/repo3-test/${newUlid()}.jpg`,
    ext: "jpg",
    mime: "image/jpeg",
    s3Bucket: process.env["ORIGINALS_BUCKET"] ?? "",
    s3Key: `raw/${ownerId}/${newUlid()}`,
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

describe.skipIf(!RUN)("master key version allocation", () => {
  it("the first allocation yields mk-1 with no bootstrap special case", async () => {
    const ownerId = await newOwner();
    expect(await getCurrentMasterKeyVersion(ownerId)).toBeUndefined();

    const result = await allocateMasterKeyVer(ownerId);
    expect(result.masterKeyVer).toBe("mk-1");
    expect(result.rotatedAt).toBeTruthy();

    const current = await getCurrentMasterKeyVersion(ownerId);
    expect(current).toEqual(result);
  });

  it("two concurrent allocations return different versions", async () => {
    const ownerId = await newOwner();

    const [a, b] = await Promise.all([
      allocateMasterKeyVer(ownerId),
      allocateMasterKeyVer(ownerId),
    ]);

    expect(a.masterKeyVer).not.toBe(b.masterKeyVer);
    expect([a.masterKeyVer, b.masterKeyVer].sort()).toEqual(["mk-1", "mk-2"]);
  });
});

describe.skipIf(!RUN)("hash secret", () => {
  it("stores encHashSecret and hashSecretKeyId on #SETTINGS", async () => {
    const ownerId = await newOwner();
    expect((await getOwnerSettings(ownerId))?.encHashSecret).toBeUndefined();

    await putHashSecret(ownerId, "wrapped-secret-b64", "mk-1");
    const settings = await getOwnerSettings(ownerId);
    expect(settings?.encHashSecret).toBe("wrapped-secret-b64");
    expect(settings?.hashSecretKeyId).toBe("mk-1");

    // Callable again on rotation — the wrapping changes, the secret doesn't.
    await putHashSecret(ownerId, "re-wrapped-secret-b64", "mk-2");
    const rotated = await getOwnerSettings(ownerId);
    expect(rotated?.encHashSecret).toBe("re-wrapped-secret-b64");
    expect(rotated?.hashSecretKeyId).toBe("mk-2");
  });
});

describe.skipIf(!RUN)("blocked re-upload attempts", () => {
  it("accumulates blockedAttempts and pushes expiresAt forward on a tombstone", async () => {
    const ownerId = await newOwner();
    const meta = baseMeta(ownerId);
    const rend = baseRendition(ownerId);
    await createAsset({
      stem: meta.stem,
      path: rend.path,
      hmac: rend.contentHash,
      meta,
      rendition: rend,
    });
    await trashAsset(ownerId, meta.photoId, toIsoUtc(new Date()), "test-device");
    await purgeAsset(ownerId, meta.photoId, toIsoUtc(new Date()), 365);

    const tombstoneAfterPurge = await getHashPointer(ownerId, rend.contentHash);
    const firstExpiry = (tombstoneAfterPurge as { expiresAt?: number })?.expiresAt;
    expect(firstExpiry).toBeGreaterThan(0);

    // A blocked attempt some time later pushes expiresAt further out than the
    // purge-time value, and bumps the counter.
    const laterAttempt = toIsoUtc(new Date(Date.now() + 10_000));
    await recordBlockedHashAttempt(
      ownerId,
      rend.contentHash,
      "home-server",
      laterAttempt,
      epochSecondsAfterDays(laterAttempt, 365),
    );

    const afterBlock = await getHashPointer(ownerId, rend.contentHash);
    const record = afterBlock as {
      blockedAttempts?: number;
      lastAttemptAt?: string;
      lastAttemptBy?: string;
      expiresAt?: number;
    };
    expect(record.blockedAttempts).toBe(1);
    expect(record.lastAttemptBy).toBe("home-server");
    expect(record.lastAttemptAt).toBe(laterAttempt);
    expect(record.expiresAt).toBeGreaterThan(firstExpiry!);

    // A second blocked attempt accumulates rather than resetting.
    await recordBlockedHashAttempt(
      ownerId,
      rend.contentHash,
      "home-server",
      toIsoUtc(new Date()),
    );
    const afterSecondBlock = await getHashPointer(ownerId, rend.contentHash);
    expect((afterSecondBlock as { blockedAttempts?: number }).blockedAttempts).toBe(2);
  });
});
