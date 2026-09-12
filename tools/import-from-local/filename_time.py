"""Parses a capture timestamp out of a filename -- the `filename` rung
src/core/items.ts's `TakenAtSrc` gained for this importer (see design.md's
"Establishing takenAt"). Naive local time, exactly like EXIF `DateTimeOriginal`:
neither carries its own UTC offset, which is why both feed the same offset ladder.

Patterns below were derived from, and validated against, a real ~5,600-file
Google Photos Takeout + old-phone-backup export (see tests/_real_corpus.py to
re-run that check against your own): parsing 4,864 filenames that also carried a
real EXIF `DateTimeOriginal` and comparing the two, 4,547 (93.5%) matched within
2 seconds and 231 more within 2 minutes -- the timestamp really is the capture
instant, not something else wearing its shape.
"""

from __future__ import annotations

import re
from datetime import datetime

# Ordered by specificity, not by likely frequency -- a pattern that could also
# match a *substring* of a more specific one must come after it, or it wins first
# and produces a truncated/wrong result. `search`, not `match`, so a pattern doesn't
# have to anchor at position 0 (e.g. a leading "IMG_" or "VID_" prefix, or a
# `download_` prefix, or none at all).
_PATTERNS: list[re.Pattern] = [
    # IMG_20180713_140225.jpg, VID_20190628_195032.mp4, download_20200122_190327.jpg,
    # image-20180101_120000.jpg, Screenshot_20200215-125144.jpg (hyphen before time)
    re.compile(r"(?:IMG|VID|PANO|MVIMG|TRIM|download|image|Screenshot)[-_](\d{4})(\d{2})(\d{2})[-_](\d{2})(\d{2})(\d{2})"),
    # 20150408_123456.jpg, 20150408-123456-edited.jpg
    re.compile(r"(?<!\d)(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(?!\d)"),
    # 2018_0302_20492800.jpg -- Google Photos' own export scheme: yyyy_mmdd_hhmmssff
    re.compile(r"(?<!\d)(\d{4})_(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\d{2}(?!\d)"),
    # 2019-08-01T12_00_00+02_00.JPEG -- an ISO-8601-shaped export; offset is parsed
    # separately (see `parse_offset`), this only extracts the naive local part.
    re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2})_(\d{2})_(\d{2})"),
    # IMG-20180101-WA0001.jpg -- WhatsApp's own naming; no time component, noon is
    # the least-wrong guess (matches the day at least, avoids a spurious "midnight"
    # skew across a timezone boundary more often than noon would).
    re.compile(r"IMG-(\d{4})(\d{2})(\d{2})-WA\d+"),
    # A bare date with no time at all: 2019-03-05.jpeg.
    re.compile(r"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)"),
]


def parse(filename: str) -> datetime | None:
    """Naive local `datetime` (no tzinfo), or None if nothing recognisable was
    found. Rejects a result that doesn't parse as a real calendar date/time (e.g.
    month 13) rather than raising -- a filename can coincidentally match a numeric
    pattern without being a timestamp at all."""
    for pattern in _PATTERNS:
        m = pattern.search(filename)
        if not m:
            continue
        groups = [int(g) for g in m.groups()]
        if len(groups) == 3:
            groups += [12, 0, 0]  # date-only patterns: noon, see WA/bare-date note above
        try:
            return datetime(*groups)
        except ValueError:
            continue  # e.g. "9999_9999_..." matched the shape but isn't a real date
    return None


_OFFSET_PATTERN = re.compile(r"T\d{2}_\d{2}_\d{2}([+-])(\d{2})_(\d{2})")


def parse_offset_minutes(filename: str) -> int | None:
    """The `+02_00`-shaped offset some ISO-8601-style export filenames carry
    alongside their timestamp. Separate from `parse` because most patterns above
    carry no offset at all -- this is additional signal for `tzSrc`'s
    upload-supplied-offset rung, not part of establishing `takenAt` itself."""
    m = _OFFSET_PATTERN.search(filename)
    if not m:
        return None
    sign, hours, minutes = m.groups()
    total = int(hours) * 60 + int(minutes)
    return -total if sign == "-" else total
