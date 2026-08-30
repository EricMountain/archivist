// Shapes GSI-projected entries into API response bodies, recovering the fields
// the projection itself doesn't carry (photoId, facetType/facetValue, takenAt)
// from the key. See "GridProjectionFields" in core/items.ts.
import { photoIdFromMediaPk, parseFacetGsiPk, parseSortKey } from "@archivist/core/keys";
import type { FacetEntry, TimelineEntry } from "@archivist/core/items";

export function timelineEntryDto(item: TimelineEntry) {
  return {
    photoId: photoIdFromMediaPk(item.pk),
    takenAt: parseSortKey(item.timelineSk).timestamp,
    thumbs: item.thumbs,
    encDek: item.encDek,
    encKeyId: item.encKeyId,
    width: item.width,
    height: item.height,
    mime: item.mime,
    tzOffsetMin: item.tzOffsetMin,
    status: item.status,
  };
}

export function facetEntryDto(item: FacetEntry) {
  const { type, value } = parseFacetGsiPk(item.facetPk);
  return {
    photoId: photoIdFromMediaPk(item.pk),
    facetType: type,
    facetValue: value,
    takenAt: parseSortKey(item.facetSk).timestamp,
    thumbs: item.thumbs,
    encDek: item.encDek,
    encKeyId: item.encKeyId,
    width: item.width,
    height: item.height,
    mime: item.mime,
    tzOffsetMin: item.tzOffsetMin,
    status: item.status,
  };
}
