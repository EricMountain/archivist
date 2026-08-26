// Timestamps are stored UTC, ISO-8601, fixed width, always millisecond-precision and
// Z-suffixed: 2026-07-14T09:22:05.000Z. One formatter, used everywhere — see
// "Design conventions" in CLAUDE.md.

export function toIsoUtc(date: Date | number): string {
  const d = typeof date === "number" ? new Date(date) : date;
  if (Number.isNaN(d.getTime())) {
    throw new RangeError("toIsoUtc: invalid date");
  }
  // Date#toISOString already produces exactly this shape (fixed-width ms, Z suffix)
  // for any finite Date, since a Date's internal representation is a UTC instant.
  return d.toISOString();
}

export function isIsoUtc(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value);
}

/** DynamoDB TTL attributes must be epoch **seconds**, not milliseconds and not
 * ISO-8601 — a value it can't parse that way is silently never expired, which
 * surfaces as "nothing ever expired" months later. Used only for purge
 * tombstones' `expiresAt`; nothing else in this design carries a TTL. */
export function epochSecondsAfterDays(fromIso: string, days: number): number {
  return Math.floor(new Date(fromIso).getTime() / 1000) + days * 86400;
}
