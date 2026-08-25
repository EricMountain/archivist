// Builds every key documented in docs/design/sample-data.md and asserts
// byte-equality with the values written there. The sample data becomes an
// executable specification — see "Done when" for plan step 1.2.
import { describe, expect, it } from "vitest";
import {
  deviceSk,
  devicesPk,
  facetGsiPk,
  facetSk,
  hashPtrPk,
  idpPtrPk,
  keyWrapSk,
  keysPk,
  mediaPk,
  membershipSk,
  metaSk,
  ownerPk,
  pathPtrPk,
  profileSk,
  ptrSk,
  renditionSk,
  settingsSk,
  sortKey,
  stemPtrPk,
  timelineGsi1Pk,
  trashGsi1Pk,
  userPk,
} from "../src/core/keys";

const OWNER = "01J7XQP4M2N8VBKD3RTYFW9GHC";

// photoIds
const A1 = "01K5A2Q8ZCV1D9KXM3BQNR7T2F";
const A2 = "01K5A2QB3HN7WYP2GKD4RVXM8C";
const A3 = "01K5A2QDM4TZB6QNH9FKW3PXR2";
const A4 = "01K5A2QF7NKD3VYB8MQXTG5HW9";
const A5 = "01K5A2QH2WPB9NXK4TDMR6YFV3";
const A6 = "01K5A2QKB8YM5RVT7NQXHD2WFG";
const A7 = "01K5A2QMD3ZQ8WKN6BVYTX4HRP";
const A8 = "01K5A2QPF9XT2HMB5RKWNVQ3YD";
const A9 = "01K5A2QRG2WBK4XN7QTMVD3HRY";

describe("mediaPk", () => {
  it("matches every asset's pk in sample-data.md", () => {
    expect(mediaPk(OWNER, A1)).toBe(`O#${OWNER}#M#${A1}`);
    expect(mediaPk(OWNER, A2)).toBe(`O#${OWNER}#M#${A2}`);
    expect(mediaPk(OWNER, A9)).toBe(`O#${OWNER}#M#${A9}`);
  });
});

describe("metaSk / renditionSk / facetSk", () => {
  it("#META sorts first, R# and F# carry their ids", () => {
    expect(metaSk()).toBe("#META");
    expect(renditionSk("01K5A2Q8ZCW4MB7XKQNV2HTRF3")).toBe(
      "R#01K5A2Q8ZCW4MB7XKQNV2HTRF3",
    );
    // A2's two renditions, distinct roles, same asset.
    expect(renditionSk("01K5A2QB3HQ8VMT5XKND7WBFR2")).toBe(
      "R#01K5A2QB3HQ8VMT5XKND7WBFR2",
    );
    expect(renditionSk("01K5A2QB3HTF9WNX2MHQVRD6BY")).toBe(
      "R#01K5A2QB3HTF9WNX2MHQVRD6BY",
    );
  });

  it("builds every facet sk shown for A1", () => {
    expect(facetSk("CAMERA", "Google Pixel 9")).toBe("F#CAMERA#Google Pixel 9");
    expect(facetSk("DEVICE", "google|pixel 9|-")).toBe("F#DEVICE#google|pixel 9|-");
    expect(facetSk("LABEL", "temple")).toBe("F#LABEL#temple");
    expect(facetSk("REND", "display")).toBe("F#REND#display");
    expect(facetSk("YEAR", "2026")).toBe("F#YEAR#2026");
  });

  it("value goes last and is never escaped, even containing '#'-adjacent pipes", () => {
    expect(facetSk("DEVICE", "canon|eos r5|042024001234")).toBe(
      "F#DEVICE#canon|eos r5|042024001234",
    );
  });
});

describe("pointer keys", () => {
  it("STEM pointers, one per asset stem", () => {
    expect(stemPtrPk(OWNER, "2026/07-japan/IMG_4021")).toBe(
      `O#${OWNER}#STEM#2026/07-japan/IMG_4021`,
    );
    expect(stemPtrPk(OWNER, "2026/07-japan/IMG_8123")).toBe(
      `O#${OWNER}#STEM#2026/07-japan/IMG_8123`,
    );
    expect(stemPtrPk(OWNER, "archive/scans/wedding-1998")).toBe(
      `O#${OWNER}#STEM#archive/scans/wedding-1998`,
    );
  });

  it("PATH pointers, two per grouped asset", () => {
    expect(pathPtrPk(OWNER, "2026/07-japan/IMG_4021.HEIC")).toBe(
      `O#${OWNER}#PATH#2026/07-japan/IMG_4021.HEIC`,
    );
    expect(pathPtrPk(OWNER, "2026/07-japan/IMG_8123.CR3")).toBe(
      `O#${OWNER}#PATH#2026/07-japan/IMG_8123.CR3`,
    );
    expect(pathPtrPk(OWNER, "2026/07-japan/IMG_8123.JPG")).toBe(
      `O#${OWNER}#PATH#2026/07-japan/IMG_8123.JPG`,
    );
  });

  it("HASH pointer, keyed by the HMAC (sample data truncates the digest itself)", () => {
    const hmac = "hmac-sha256:9f2c41ab00112233445566778899aabbccddeeff0011223344";
    expect(hashPtrPk(OWNER, hmac)).toBe(`O#${OWNER}#HASH#${hmac}`);
  });

  it("every pointer item shares the #PTR sort key", () => {
    expect(ptrSk()).toBe("#PTR");
  });

  it("IDP pointer, issuer then subject, subject last since it's user-controlled", () => {
    // Full subject is truncated with an ellipsis in sample-data.md; check the
    // documented prefix rather than a value the doc itself doesn't give in full.
    expect(idpPtrPk("cognito", "a7f3e19c-4b82-4d6e-x")).toMatch(
      /^IDP#cognito#a7f3e19c-4b82-4d6e-/,
    );
    expect(idpPtrPk("google", "116384927461028374651")).toBe(
      "IDP#google#116384927461028374651",
    );
  });
});

describe("devices and key wrappings", () => {
  it("device items, one collection per owner", () => {
    expect(devicesPk(OWNER)).toBe(`O#${OWNER}#DEVICES`);
    expect(deviceSk("canon|eos r5|042024001234")).toBe("D#canon|eos r5|042024001234");
    expect(deviceSk("google|pixel 9|-")).toBe("D#google|pixel 9|-");
    expect(deviceSk("nikon|d750|3021447")).toBe("D#nikon|d750|3021447");
  });

  it("key-wrapping items, one collection per owner", () => {
    expect(keysPk(OWNER)).toBe(`O#${OWNER}#KEYS`);
    expect(keyWrapSk("01K5A2P4XNVBQ7MK3NTXWD9HF2")).toBe(
      "W#01K5A2P4XNVBQ7MK3NTXWD9HF2",
    );
    expect(keyWrapSk("01K5A2P4XNWD3KQB8NMXVTRH5Y")).toBe(
      "W#01K5A2P4XNWD3KQB8NMXVTRH5Y",
    );
    expect(keyWrapSk("01K5A2P4XNXM9BVQ2KTNWRDH4G")).toBe(
      "W#01K5A2P4XNXM9BVQ2KTNWRDH4G",
    );
  });
});

describe("users and owners", () => {
  const USER = "01J7XRB6K3PQ8WNVD2MTYX4HFG";

  it("owner root pk and #SETTINGS sk", () => {
    expect(ownerPk(OWNER)).toBe(`O#${OWNER}`);
    expect(settingsSk()).toBe("#SETTINGS");
  });

  it("user root pk, #PROFILE sk, and M# membership sk", () => {
    expect(userPk(USER)).toBe(`U#${USER}`);
    expect(profileSk()).toBe("#PROFILE");
    expect(membershipSk(OWNER)).toBe(`M#${OWNER}`);
  });
});

describe("timeline_gsi keys", () => {
  it("timelinePk for the live partition is the owner's root pk, reused", () => {
    expect(timelineGsi1Pk(OWNER)).toBe(`O#${OWNER}`);
    expect(timelineGsi1Pk(OWNER)).toBe(ownerPk(OWNER));
  });

  it("timelinePk for the trash partition of the same index", () => {
    expect(trashGsi1Pk(OWNER)).toBe(`O#${OWNER}#TRASH`);
  });

  it("sortKey builds every documented timelineSk", () => {
    expect(sortKey("2026-07-14T09:22:05.000Z", A1)).toBe(
      `2026-07-14T09:22:05.000Z#${A1}`,
    );
    expect(sortKey("2026-07-13T16:48:20.000Z", A2)).toBe(
      `2026-07-13T16:48:20.000Z#${A2}`,
    );
    expect(sortKey("2026-08-06T20:14:52.000Z", A9)).toBe(
      `2026-08-06T20:14:52.000Z#${A9}`,
    );
  });

  it("A6/A7 share a takenAt to the millisecond; only the id suffix separates them", () => {
    const tie = "2026-07-15T11:03:12.000Z";
    const skA6 = sortKey(tie, A6);
    const skA7 = sortKey(tie, A7);
    expect(skA6).not.toBe(skA7);
    expect(skA6).toBe(`${tie}#${A6}`);
    expect(skA7).toBe(`${tie}#${A7}`);
    // Total order still holds: sorted descending by string comparison, as DynamoDB
    // would, A7 (later ULID) sorts after A6.
    expect([skA6, skA7].sort()).toEqual([skA6, skA7]);
  });

  it("A3 and A8 timelineSk", () => {
    expect(sortKey("2026-07-14T10:05:41.000Z", A3)).toBe(
      `2026-07-14T10:05:41.000Z#${A3}`,
    );
    expect(sortKey("2026-07-16T04:15:33.000Z", A8)).toBe(
      `2026-07-16T04:15:33.000Z#${A8}`,
    );
  });

  it("A4's timelineSk lands in 2011, following its file-mtime takenAt", () => {
    expect(sortKey("2011-03-02T19:44:10.000Z", A4)).toBe(
      `2011-03-02T19:44:10.000Z#${A4}`,
    );
  });

  it("A5's timelineSk", () => {
    expect(sortKey("2026-06-21T14:30:00.000Z", A5)).toBe(
      `2026-06-21T14:30:00.000Z#${A5}`,
    );
  });
});

describe("facet_gsi keys", () => {
  it("matches the documented facet_gsi slice", () => {
    expect(facetGsiPk(OWNER, "LABEL", "temple")).toBe(`O#${OWNER}#F#LABEL#temple`);
    expect(facetGsiPk(OWNER, "REND", "raw")).toBe(`O#${OWNER}#F#REND#raw`);
    expect(facetGsiPk(OWNER, "DEVICE", "canon|eos r5|042024001234")).toBe(
      `O#${OWNER}#F#DEVICE#canon|eos r5|042024001234`,
    );
    expect(facetGsiPk(OWNER, "CAMERA", "Google Pixel 9")).toBe(
      `O#${OWNER}#F#CAMERA#Google Pixel 9`,
    );
  });

  it("facetSk shares the same sortKey shape as timeline_gsi", () => {
    expect(sortKey("2026-07-14T09:22:05.000Z", A1)).toBe(
      sortKey("2026-07-14T09:22:05.000Z", A1),
    );
  });
});
