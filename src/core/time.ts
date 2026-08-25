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
