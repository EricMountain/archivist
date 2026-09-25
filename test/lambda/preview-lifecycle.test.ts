// The video preview clip's server-side lifecycle (design.md, "Video preview clip"):
// it must never gate an asset's readiness, and it must be deleted along with the
// asset — on purge and on account deletion — like every other derived object. Same
// DynamoDB Local + MinIO gate as the other lambda-level suites.
import { describe, expect, it } from "vitest";
import { HeadObjectCommand, PutObjectCommand } from "@aws-sdk/client-s3";
import type { S3Event } from "aws-lambda";
import { s3 } from "../../src/core/s3";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { bootstrapUser } from "../../src/core/repo/session";
import { getMetaItem } from "../../src/core/repo/media";
import { createAsset } from "../../src/core/repo/ingest";
import { purgeAsset } from "../../src/core/repo/purge";
import { trashAsset } from "../../src/core/repo/trash";
import { deleteOwnerData } from "../../src/core/repo/account";
import { handler } from "../../src/lambda/s3event/index";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

function s3EventFor(bucket: string, key: string, size: number): S3Event {
  return {
    Records: [
      {
        eventVersion: "2.1",
        eventSource: "aws:s3",
        awsRegion: "us-east-1",
        eventTime: new Date().toISOString(),
        eventName: "ObjectCreated:Put",
        s3: {
          s3SchemaVersion: "1.0",
          configurationId: "test",
          bucket: { name: bucket, ownerIdentity: { principalId: "test" }, arn: `arn:aws:s3:::${bucket}` },
          object: { key, size, eTag: "x", sequencer: "x" },
        },
      } as S3Event["Records"][number],
    ],
  };
}

async function exists(bucket: string, key: string): Promise<boolean> {
  try {
    await s3().send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    return true;
  } catch (err) {
    if (err instanceof Error && err.name === "NotFound") return false;
    throw err;
  }
}

/** A video asset with one 256 still and a preview, both declared; nothing uploaded. */
async function newVideoAsset(ownerId: string) {
  const photoId = newUlid();
  const renditionId = newUlid();
  const now = toIsoUtc(new Date());
  const originalsBucket = process.env["ORIGINALS_BUCKET"]!;
  const derivedBucket = process.env["DERIVED_BUCKET"]!;
  const s3Key = `raw/${ownerId}/${photoId}/${renditionId}`;
  const thumbKey = `th/${ownerId}/${photoId}/256`;
  const previewKey = `th/${ownerId}/${photoId}/preview`;
  const path = `2026/preview-test/${photoId}.mp4`;

  await createAsset({
    stem: `2026/preview-test/${photoId}`,
    path,
    hmac: `hmac-sha256:${photoId}`,
    meta: {
      ownerId,
      photoId,
      stem: `2026/preview-test/${photoId}`,
      primaryRend: renditionId,
      renditions: 1,
      mime: "video/mp4",
      width: 100,
      height: 100,
      enc: "AES-256-GCM",
      encDek: "dek",
      encKeyId: "mk-test",
      takenAt: now,
      tzOffsetMin: 0,
      tzSrc: "assumed-utc",
      takenAtSrc: "upload",
      uploadedAt: now,
      thumbs: { 256: { bucket: derivedBucket, key: thumbKey, iv: "iv", bytes: 4 } },
      preview: { bucket: derivedBucket, key: previewKey, iv: "piv", bytes: 7 },
      groupSrc: "stem",
      status: "processing",
    },
    rendition: {
      renditionId,
      role: "display",
      path,
      ext: "mp4",
      mime: "video/mp4",
      s3Bucket: originalsBucket,
      s3Key,
      contentHash: `hmac-sha256:${photoId}`,
      bytes: 5,
      plainBytes: 5,
      width: 100,
      height: 100,
      encIv: "iv",
      encChunkSize: 0,
      addedAt: now,
    },
  });
  return { photoId, originalsBucket, derivedBucket, s3Key, thumbKey, previewKey };
}

describe.skipIf(!RUN)("video preview lifecycle", () => {
  it("never gates readiness: ready once original and stills land, preview or not", async () => {
    const ownerId = `01PREVIEWOWNER${newUlid().slice(-12)}`;
    const a = await newVideoAsset(ownerId);

    await s3().send(new PutObjectCommand({ Bucket: a.originalsBucket, Key: a.s3Key, Body: Buffer.from("hello") }));
    await handler(s3EventFor(a.originalsBucket, a.s3Key, 5));
    await s3().send(new PutObjectCommand({ Bucket: a.derivedBucket, Key: a.thumbKey, Body: Buffer.from("thmb") }));
    await handler(s3EventFor(a.derivedBucket, a.thumbKey, 4));

    // The preview was declared on #META but never uploaded (say, the client died
    // between the still PUTs and the preview PUT) -- the asset is still ready.
    expect((await getMetaItem(ownerId, a.photoId))?.status).toBe("ready");
  });

  it("ignores the preview object's own S3 event, even with a mismatching size", async () => {
    const ownerId = `01PREVIEWOWNER${newUlid().slice(-12)}`;
    const a = await newVideoAsset(ownerId);

    await s3().send(new PutObjectCommand({ Bucket: a.derivedBucket, Key: a.previewKey, Body: Buffer.from("x") }));
    // Declared 7 bytes; 1 arrived. For an original or still that would flip the asset
    // to failed -- a best-effort preview must not.
    await handler(s3EventFor(a.derivedBucket, a.previewKey, 1));
    expect((await getMetaItem(ownerId, a.photoId))?.status).toBe("processing");
  });

  it("purging an asset deletes its preview object along with everything else", async () => {
    const ownerId = `01PREVIEWOWNER${newUlid().slice(-12)}`;
    const a = await newVideoAsset(ownerId);
    for (const [bucket, key] of [
      [a.originalsBucket, a.s3Key],
      [a.derivedBucket, a.thumbKey],
      [a.derivedBucket, a.previewKey],
    ] as const) {
      await s3().send(new PutObjectCommand({ Bucket: bucket, Key: key, Body: Buffer.from("x") }));
    }

    await trashAsset(ownerId, a.photoId, toIsoUtc(new Date()), "test-device");
    const result = await purgeAsset(ownerId, a.photoId, toIsoUtc(new Date()), 365);

    expect(result.objectsDeleted).toBe(3);
    expect(await exists(a.derivedBucket, a.previewKey)).toBe(false);
    expect(await exists(a.derivedBucket, a.thumbKey)).toBe(false);
    expect(await exists(a.originalsBucket, a.s3Key)).toBe(false);
  });

  it("deleting an account deletes its preview objects", async () => {
    const { userId, ownerId } = await bootstrapUser({
      issuer: "cognito",
      subject: newUlid(),
      displayName: "Preview Deletion Test",
      homeTz: "UTC",
    });
    const a = await newVideoAsset(ownerId);
    await s3().send(new PutObjectCommand({ Bucket: a.derivedBucket, Key: a.previewKey, Body: Buffer.from("x") }));
    expect(await exists(a.derivedBucket, a.previewKey)).toBe(true);

    await deleteOwnerData(ownerId, userId);

    expect(await exists(a.derivedBucket, a.previewKey)).toBe(false);
  });
});
