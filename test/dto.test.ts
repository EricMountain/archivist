// timelineEntryDto/facetEntryDto are pure functions with no I/O — this exercises
// them directly rather than only indirectly through the DynamoDB-Local-gated
// route tests. See plan step 1.11's STATUS.md note.
import { describe, expect, it } from "vitest";
import { facetEntryDto, timelineEntryDto } from "../src/lambda/api/dto";
import type { FacetEntry, TimelineEntry } from "../src/core/items";

const OWNER = "01J7XQP4M2N8VBKD3RTYFW9GHC";
const PHOTO_ID = "01K5A2Q8ZCV1D9KXM3BQNR7T2F";
const TAKEN_AT = "2026-07-14T09:22:05.000Z";

const projection = {
  thumbs: {
    256: { bucket: "pa-derived", key: `th/${OWNER}/${PHOTO_ID}/256`, iv: "iv256", bytes: 14336 },
  },
  encDek: "b64dek",
  encKeyId: "mk-3",
  width: 4032,
  height: 3024,
  mime: "image/heic",
  tzOffsetMin: 540,
  status: "ready" as const,
};

describe("timelineEntryDto", () => {
  it("recovers photoId from pk and takenAt from timelineSk", () => {
    const entry: TimelineEntry = {
      ...projection,
      pk: `O#${OWNER}#M#${PHOTO_ID}`,
      sk: "#META",
      timelinePk: `O#${OWNER}`,
      timelineSk: `${TAKEN_AT}#${PHOTO_ID}`,
    };

    expect(timelineEntryDto(entry)).toEqual({
      photoId: PHOTO_ID,
      takenAt: TAKEN_AT,
      ...projection,
    });
  });
});

describe("facetEntryDto", () => {
  it("recovers photoId from pk, facetType/facetValue from facetPk, and takenAt from facetSk", () => {
    const entry: FacetEntry = {
      ...projection,
      pk: `O#${OWNER}#M#${PHOTO_ID}`,
      sk: "F#LABEL#temple",
      facetPk: `O#${OWNER}#F#LABEL#temple`,
      facetSk: `${TAKEN_AT}#${PHOTO_ID}`,
    };

    expect(facetEntryDto(entry)).toEqual({
      photoId: PHOTO_ID,
      facetType: "LABEL",
      facetValue: "temple",
      takenAt: TAKEN_AT,
      ...projection,
    });
  });

  it("a facet value containing '#' still resolves correctly", () => {
    const entry: FacetEntry = {
      ...projection,
      pk: `O#${OWNER}#M#${PHOTO_ID}`,
      sk: "F#LABEL#tag#with#hashes",
      facetPk: `O#${OWNER}#F#LABEL#tag#with#hashes`,
      facetSk: `${TAKEN_AT}#${PHOTO_ID}`,
    };

    const dto = facetEntryDto(entry);
    expect(dto.facetType).toBe("LABEL");
    expect(dto.facetValue).toBe("tag#with#hashes");
    expect(dto.takenAt).toBe(TAKEN_AT);
  });
});
