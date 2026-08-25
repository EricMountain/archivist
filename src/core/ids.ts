// IDs are ULIDs — see "Design conventions" in CLAUDE.md. Minted here, never taken
// from an external IdP subject, so the identity provider stays swappable.
import { ulid, decodeTime } from "ulid";

const ULID_RE = /^[0-7][0-9A-HJKMNP-TV-Z]{25}$/;

export function newUlid(): string {
  return ulid();
}

export function isUlid(value: string): boolean {
  return ULID_RE.test(value);
}

export function ulidTimestamp(id: string): Date {
  return new Date(decodeTime(id));
}
