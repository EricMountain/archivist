// S3 client, bucket-name env lookups, key builders and presigning. Mirrors db.ts's
// pattern: one lazy client, env-driven configuration, nothing hardcoded.
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

let client: S3Client | undefined;

export function s3(): S3Client {
  if (!client) {
    // S3_ENDPOINT is unset in every deployed environment; it exists only so
    // tests can point at a local S3-compatible server instead of real AWS.
    const endpoint = process.env["S3_ENDPOINT"];
    client = new S3Client(
      endpoint ? { endpoint, forcePathStyle: true } : {},
    );
  }
  return client;
}

export function originalsBucket(): string {
  const name = process.env["ORIGINALS_BUCKET"];
  if (!name) throw new Error("ORIGINALS_BUCKET environment variable is not set");
  return name;
}

export function derivedBucket(): string {
  const name = process.env["DERIVED_BUCKET"];
  if (!name) throw new Error("DERIVED_BUCKET environment variable is not set");
  return name;
}

/** `raw/<ownerId>/<photoId>/<renditionId>` — keyed by ids, not path, so a rename
 * never touches S3. photoId is included (not just renditionId) so the S3-event
 * Lambda (plan step 1.10) can resolve which asset an object belongs to from the
 * key alone. See "R# rendition items" in design.md. */
export function originalKey(ownerId: string, photoId: string, renditionId: string): string {
  return `raw/${ownerId}/${photoId}/${renditionId}`;
}

/** `th/<ownerId>/<photoId>/<size>` — ULID-derived and therefore immutable. */
export function thumbKey(ownerId: string, photoId: string, size: number): string {
  return `th/${ownerId}/${photoId}/${size}`;
}

const PRESIGN_EXPIRY_SECONDS = 15 * 60;

/** Originals get INTELLIGENT_TIERING at PUT time — that's the storage class the
 * object lands in, avoiding a separate per-object transition request.
 * Thumbnails don't; see "Tiering" in design.md. */
export async function presignPut(
  bucket: string,
  key: string,
  opts: { storageClass?: "INTELLIGENT_TIERING" } = {},
): Promise<string> {
  const command = new PutObjectCommand({
    Bucket: bucket,
    Key: key,
    ...(opts.storageClass ? { StorageClass: opts.storageClass } : {}),
  });
  return getSignedUrl(s3(), command, { expiresIn: PRESIGN_EXPIRY_SECONDS });
}
