// Daily EventBridge-triggered sweep. For each owner, finds trash past its
// retention window and purges it for real. Resumable and idempotent by
// construction: purgeCandidates only ever returns what's still in the trash
// partition, so a killed or re-run invocation just does less work next time,
// never double-deletes. See plan step 1.13.
import { photoIdFromMediaPk } from "@archivist/core/keys";
import { toIsoUtc } from "@archivist/core/time";
import { listAllOwnerIds } from "@archivist/core/repo/owners";
import { getOwnerSettings } from "@archivist/core/repo/identity";
import { purgeCandidates } from "@archivist/core/repo/timeline";
import { purgeAsset } from "@archivist/core/repo/purge";

function cutoffIso(now: Date, trashRetentionDays: number): string {
  return toIsoUtc(new Date(now.getTime() - trashRetentionDays * 24 * 60 * 60 * 1000));
}

export async function handler(): Promise<void> {
  const now = new Date();
  const purgedAt = toIsoUtc(now);
  const ownerIds = await listAllOwnerIds();

  for (const ownerId of ownerIds) {
    const settings = await getOwnerSettings(ownerId);
    const trashRetentionDays = settings?.trashRetentionDays ?? 30;
    const tombstoneRetentionDays = settings?.tombstoneRetentionDays ?? 365;
    const cutoff = cutoffIso(now, trashRetentionDays);

    let cursor: string | undefined;
    do {
      const page = await purgeCandidates(ownerId, cutoff, cursor);
      for (const entry of page.items) {
        const photoId = photoIdFromMediaPk(entry.pk);
        await purgeAsset(ownerId, photoId, purgedAt, tombstoneRetentionDays);
      }
      cursor = page.cursor;
    } while (cursor);
  }
}
