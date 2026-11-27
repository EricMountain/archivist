import { describe, expect, it } from "vitest";

// Throwaway (plan 03 step 3.17 Done-when): a PR with a failing check must not be
// mergeable. This PR is closed without merging.
describe("required checks", () => {
  it("fails on purpose", () => {
    expect(1).toBe(2);
  });
});
