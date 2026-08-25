// Exercises the S3-event Lambda's handler directly (no real S3 event source —
// just the shape it expects) against DynamoDB Local and MinIO. Skipped unless
// both are configured; see test/repo/repo2.test.ts for the same gate.
import { describe, expect, it } from "vitest";
import { PutObjectCommand } from "@aws-sdk/client-s3";
import type { S3Event } from "aws-lambda";
import { s3 } from "../../src/core/s3";
import { newUlid } from "../../src/core/ids";
import { toIsoUtc } from "../../src/core/time";
import { getMetaItem } from "../../src/core/repo/media";
import { createAsset } from "../../src/core/repo/ingest";
import { handler } from "../../src/lambda/s3event/index";

const RUN =
  !!process.env["DYNAMODB_ENDPOINT"] &&
  !!process.env["MEDIA_TABLE"] &&
  !!process.env["S3_ENDPOINT"] &&
  !!process.env["ORIGINALS_BUCKET"] &&
  !!process.env["DERIVED_BUCKET"];

const OWNER = "01TESTOWNER0000000000000C";

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

describe.skipIf(!RUN)("S3-event Lambda", () => {
  it("flips status to ready once the original and every thumbnail have landed with the right size", async () => {
    const photoId = newUlid();
    const renditionId = newUlid();
    const now = toIsoUtc(new Date());
    const originalsBucket = process.env["ORIGINALS_BUCKET"]!;
    const derivedBucket = process.env["DERIVED_BUCKET"]!;
    const s3Key = `raw/${OWNER}/${photoId}/${renditionId}`;
    const thumbKey256 = `th/${OWNER}/${photoId}/256`;

    await createAsset({
      stem: `2026/s3event-test/${photoId}`,
      path: `2026/s3event-test/${photoId}.jpg`,
      hmac: `hmac-sha256:${photoId}`,
      meta: {
        ownerId: OWNER,
        photoId,
        stem: `2026/s3event-test/${photoId}`,
        primaryRend: renditionId,
        renditions: 1,
        mime: "image/jpeg",
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
        thumbs: { 256: { bucket: derivedBucket, key: thumbKey256, iv: "iv", bytes: 4 } },
        groupSrc: "stem",
        status: "processing",
      },
      rendition: {
        renditionId,
        role: "display",
        path: `2026/s3event-test/${photoId}.jpg`,
        ext: "jpg",
        mime: "image/jpeg",
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

    // Original lands first — status should stay processing, thumb hasn't arrived.
    await s3().send(
      new PutObjectCommand({ Bucket: originalsBucket, Key: s3Key, Body: Buffer.from("hello") }),
    );
    await handler(s3EventFor(originalsBucket, s3Key, 5));
    expect((await getMetaItem(OWNER, photoId))?.status).toBe("processing");

    // Thumb lands — now everything declared has arrived with the right size.
    await s3().send(
      new PutObjectCommand({ Bucket: derivedBucket, Key: thumbKey256, Body: Buffer.from("thmb") }),
    );
    await handler(s3EventFor(derivedBucket, thumbKey256, 4));
    expect((await getMetaItem(OWNER, photoId))?.status).toBe("ready");
  });

  it("marks the asset failed when the arrived object's size doesn't match what was declared", async () => {
    const photoId = newUlid();
    const renditionId = newUlid();
    const now = toIsoUtc(new Date());
    const originalsBucket = process.env["ORIGINALS_BUCKET"]!;
    const s3Key = `raw/${OWNER}/${photoId}/${renditionId}`;

    await createAsset({
      stem: `2026/s3event-test/${photoId}`,
      path: `2026/s3event-test/${photoId}.jpg`,
      hmac: `hmac-sha256:${photoId}`,
      meta: {
        ownerId: OWNER,
        photoId,
        stem: `2026/s3event-test/${photoId}`,
        primaryRend: renditionId,
        renditions: 1,
        mime: "image/jpeg",
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
        thumbs: {},
        groupSrc: "stem",
        status: "processing",
      },
      rendition: {
        renditionId,
        role: "display",
        path: `2026/s3event-test/${photoId}.jpg`,
        ext: "jpg",
        mime: "image/jpeg",
        s3Bucket: originalsBucket,
        s3Key,
        contentHash: `hmac-sha256:${photoId}`,
        bytes: 999, // declared size the arriving object won't match
        plainBytes: 999,
        width: 100,
        height: 100,
        encIv: "iv",
        encChunkSize: 0,
        addedAt: now,
      },
    });

    await handler(s3EventFor(originalsBucket, s3Key, 5));
    expect((await getMetaItem(OWNER, photoId))?.status).toBe("failed");
  });
});
