// Server-side stem derivation and rendition-role classification. "The client is
// the authority on bytes and metadata... deliberately not the authority on
// grouping" — see "Who decides" in design.md. Never trust a client-supplied stem.
import type { RenditionRole, TakenAtSrc } from "./items";

export interface StemAndExt {
  stem: string;
  ext: string;
}

/** Splits a path into its grouping stem and lowercased extension. Stems compare
 * case-sensitively; only the extension is normalised. */
export function stemFromPath(path: string): StemAndExt {
  const lastSlash = path.lastIndexOf("/");
  const filename = lastSlash === -1 ? path : path.slice(lastSlash + 1);
  const dot = filename.lastIndexOf(".");
  if (dot <= 0) {
    // No extension (or a dotfile): the whole path is the stem, role detection
    // falls back to "display" — see resolveRole.
    return { stem: path, ext: "" };
  }
  const dirPrefix = lastSlash === -1 ? "" : path.slice(0, lastSlash + 1);
  return {
    stem: dirPrefix + filename.slice(0, dot),
    ext: filename.slice(dot + 1).toLowerCase(),
  };
}

const RAW_EXTS = new Set([
  "cr2", "cr3", "nef", "arw", "dng", "raf", "orf", "rw2",
]);
const VIDEO_EXTS = new Set(["mov", "mp4", "m4v"]);
const SIDECAR_EXTS = new Set(["xmp"]);

/**
 * A lone video with no sibling is its own display rendition (sample-data.md's
 * A8). A video joining an existing *image* asset is a Live Photo's motion clip
 * (A3). Everything else follows the extension directly.
 */
export function resolveRole(ext: string, existingPrimaryMime: string | undefined): RenditionRole {
  const e = ext.toLowerCase();
  if (SIDECAR_EXTS.has(e)) return "sidecar";
  if (RAW_EXTS.has(e)) return "raw";
  if (VIDEO_EXTS.has(e)) {
    return existingPrimaryMime?.startsWith("image/") ? "motion" : "display";
  }
  // Ordinary display extensions (heic, jpg, png, …) and anything unrecognised
  // both fall back to the common case.
  return "display";
}

/** display beats raw; motion and sidecar never become primary. Used both to
 * decide a handover on attach and, implicitly, at creation (rank 0 vs nothing). */
const ROLE_RANK: Record<RenditionRole, number> = { display: 2, raw: 1, motion: 0, sidecar: 0 };

export function roleOutranks(candidate: RenditionRole, current: RenditionRole): boolean {
  return ROLE_RANK[candidate] > ROLE_RANK[current];
}

/** First hit wins, ranked exif > file-mtime > s3-mtime > upload — see
 * "Establishing takenAt" in design.md. */
const TAKEN_AT_SRC_RANK: Record<TakenAtSrc, number> = {
  exif: 3,
  "file-mtime": 2,
  "s3-mtime": 1,
  upload: 0,
};

export function takenAtSrcOutranks(candidate: TakenAtSrc, current: TakenAtSrc): boolean {
  return TAKEN_AT_SRC_RANK[candidate] > TAKEN_AT_SRC_RANK[current];
}
