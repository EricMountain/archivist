// mapWithConcurrency (routes/photos.ts) -- the fix for a real production
// incident: GET /trash's per-entry enrichment used to be Promise.all over a
// full page (up to MAX_LIMIT 200 items, 2-3 DynamoDB calls each), which blew
// through the AWS SDK's 50-socket HTTP pool and the Lambda's own 15s timeout
// once a real trash partition grew to ~2,200 items -- confirmed live via
// CloudWatch ("socket usage at capacity=50 and 150 additional requests are
// enqueued", then a bare Lambda timeout on the next call). Pure logic, no
// DynamoDB involved, so this runs unconditionally rather than behind the
// DYNAMODB_ENDPOINT-gated RUN flag every other test/lambda/* file uses.
import { describe, expect, it } from "vitest";
import { mapWithConcurrency } from "../../src/lambda/api/routes/photos";

describe("mapWithConcurrency", () => {
  it("never exceeds the concurrency limit at any instant", async () => {
    const items = Array.from({ length: 37 }, (_, i) => i);
    let inFlight = 0;
    let maxInFlight = 0;
    const limit = 5;

    await mapWithConcurrency(items, limit, async (i) => {
      inFlight++;
      maxInFlight = Math.max(maxInFlight, inFlight);
      // Yield a few times so overlapping calls actually have a chance to
      // race each other, rather than resolving synchronously in submission
      // order (which would pass this assertion for the wrong reason).
      await new Promise((r) => setTimeout(r, 1));
      inFlight--;
      return i;
    });

    expect(maxInFlight).toBeLessThanOrEqual(limit);
    expect(maxInFlight).toBe(limit); // and it actually *used* the full budget, not less
  });

  it("preserves result order regardless of completion order", async () => {
    const items = [30, 10, 20, 5, 25];
    const results = await mapWithConcurrency(items, 3, async (ms) => {
      await new Promise((r) => setTimeout(r, ms));
      return ms;
    });
    expect(results).toEqual(items); // same order as input, not completion order
  });

  it("works when the item count is below the concurrency limit", async () => {
    const results = await mapWithConcurrency([1, 2, 3], 10, async (i) => i * 2);
    expect(results).toEqual([2, 4, 6]);
  });

  it("works on an empty list", async () => {
    const results = await mapWithConcurrency([], 5, async (i) => i);
    expect(results).toEqual([]);
  });

  it("propagates a rejection rather than hanging or swallowing it", async () => {
    await expect(
      mapWithConcurrency([1, 2, 3], 2, async (i) => {
        if (i === 2) throw new Error("boom");
        return i;
      }),
    ).rejects.toThrow("boom");
  });
});
