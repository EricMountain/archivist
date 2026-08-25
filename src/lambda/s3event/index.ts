// Confirms arrival and flips status. Never opens a file — the bytes are
// ciphertext, and this Lambda checks existence and size only. Idempotent: S3 can
// deliver an event twice. See plan step 1.10.
import type { S3Event } from "aws-lambda";
import { HeadObjectCommand } from "@aws-sdk/client-s3";
import { s3 } from "@archivist/core/s3";
import { getAssetPartition, setAssetStatus } from "@archivist/core/repo/media";

interface ParsedOriginalKey {
  kind: "original";
  ownerId: string;
  photoId: string;
  renditionId: string;
}

interface ParsedThumbKey {
  kind: "thumb";
  ownerId: string;
  photoId: string;
  size: number;
}

function parseKey(key: string): ParsedOriginalKey | ParsedThumbKey | undefined {
  const original = key.match(/^raw\/([^/]+)\/([^/]+)\/([^/]+)$/);
  if (original) {
    const [, ownerId, photoId, renditionId] = original;
    return { kind: "original", ownerId: ownerId!, photoId: photoId!, renditionId: renditionId! };
  }
  const thumb = key.match(/^th\/([^/]+)\/([^/]+)\/(\d+)$/);
  if (thumb) {
    const [, ownerId, photoId, size] = thumb;
    return { kind: "thumb", ownerId: ownerId!, photoId: photoId!, size: Number(size) };
  }
  return undefined;
}

async function objectSize(bucket: string, key: string): Promise<number | undefined> {
  try {
    const head = await s3().send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    return head.ContentLength;
  } catch (err) {
    if (err instanceof Error && err.name === "NotFound") return undefined;
    throw err;
  }
}

/** True once the primary rendition's original and every declared thumbnail exist
 * in S3 with the declared size. Re-verifies everything on every call rather than
 * tracking per-object confirmation state — simpler, and idempotent by
 * construction against duplicate or out-of-order S3 events. */
async function isFullyLanded(ownerId: string, photoId: string): Promise<boolean> {
  const { meta, renditions } = await getAssetPartition(ownerId, photoId);
  if (!meta || !meta.primaryRend) return false;

  const primary = renditions.find((r) => r.renditionId === meta.primaryRend);
  if (!primary || primary.deletedAt) return false;

  const originalSize = await objectSize(primary.s3Bucket, primary.s3Key);
  if (originalSize !== primary.bytes) return false;

  for (const thumb of Object.values(meta.thumbs)) {
    const size = await objectSize(thumb.bucket, thumb.key);
    if (size !== thumb.bytes) return false;
  }

  return true;
}

export async function handler(event: S3Event): Promise<void> {
  for (const record of event.Records) {
    const key = decodeURIComponent(record.s3.object.key.replace(/\+/g, " "));
    const size = record.s3.object.size;

    const parsed = parseKey(key);
    if (!parsed) continue; // not one of ours — ignore

    const { meta, renditions } = await getAssetPartition(parsed.ownerId, parsed.photoId);
    if (!meta) continue; // asset was deleted or purged after the upload began

    if (parsed.kind === "original") {
      const rendition = renditions.find((r) => r.renditionId === parsed.renditionId);
      if (!rendition || rendition.bytes !== size) {
        await setAssetStatus(parsed.ownerId, parsed.photoId, "failed");
        continue;
      }
    } else {
      const declared = meta.thumbs[parsed.size];
      if (!declared || declared.bytes !== size) {
        await setAssetStatus(parsed.ownerId, parsed.photoId, "failed");
        continue;
      }
    }

    if (meta.status !== "ready" && (await isFullyLanded(parsed.ownerId, parsed.photoId))) {
      await setAssetStatus(parsed.ownerId, parsed.photoId, "ready");
    }
  }
}
